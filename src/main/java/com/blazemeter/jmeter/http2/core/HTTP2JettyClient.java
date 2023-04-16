package com.blazemeter.jmeter.http2.core;

import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.client.GZIPContentDecoder;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.Origin.Address;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request.Content;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.client.util.AbstractAuthentication;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.DigestAuthentication;
import org.eclipse.jetty.client.util.FormRequestContent;
import org.eclipse.jetty.client.util.MultiPartRequestContent;
import org.eclipse.jetty.client.util.PathRequestContent;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpFields.Mutable;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2JettyClient {
  public static final MappedByteBufferPool BUFFER_POOL = new MappedByteBufferPool(
      JMeterUtils.getPropDefault("httpJettyClient.byteBufferPoolFactor", 4));
  private static final Logger LOG = LoggerFactory.getLogger(HTTP2JettyClient.class);
  private static final Set<String> SUPPORTED_METHODS = new HashSet<>(Arrays
      .asList(HTTPConstants.GET, HTTPConstants.POST, HTTPConstants.PUT, HTTPConstants.PATCH,
          HTTPConstants.OPTIONS, HTTPConstants.DELETE));
  private static final Set<String> METHODS_WITH_BODY = new HashSet<>(Arrays
      .asList(HTTPConstants.POST, HTTPConstants.PUT, HTTPConstants.PATCH));
  private static final boolean ADD_CONTENT_TYPE_TO_POST_IF_MISSING = JMeterUtils.getPropDefault(
      "http.post_add_content_type_if_missing", false);
  private static final Pattern PORT_PATTERN = Pattern.compile("\\d+");
  private static final String MULTI_PART_SEPARATOR = "--";
  private static final String LINE_SEPARATOR = "\r\n";
  private static final String DEFAULT_FILE_MIME_TYPE = "application/octet-stream";
  private int requestTimeout = 0;
  private int maxBufferSize = 2 * 1024 * 1024;
  private int maxThreads = 5;
  private int minThreads = 1;
  private int maxRequestsQueuedPerDestination = Short.MAX_VALUE;
  private int maxConnectionsPerDestination = 1;

  private int maxConcurrentPushedStreams = 100;
  private int maxRequestsPerConnection = 100;

  private boolean strictEventOrdering = false;
  private boolean removeIdleDestinations = true;
  private int idleTimeout = 30000;
  private final HttpClient httpClient;
  private boolean http1UpgradeRequired;

  public HTTP2JettyClient(boolean http1UpgradeRequired, String name) {
    loadProperties();
    ClientConnector clientConnector = new ClientConnector();
    clientConnector.setSelectors(1);

    SslContextFactory.Client sslContextFactory = new JMeterJettySslContextFactory();
    clientConnector.setSslContextFactory(sslContextFactory);

    QueuedThreadPool queuedThreadPool = new QueuedThreadPool(maxThreads);
    queuedThreadPool.setMinThreads(minThreads);
    queuedThreadPool.setName(name);

    clientConnector.setExecutor(queuedThreadPool);

    ClientConnectionFactory.Info http11 = HttpClientConnectionFactory.HTTP11;

    HTTP2Client http2Client = new HTTP2Client(clientConnector);
    ClientConnectionFactoryOverHTTP2.HTTP2 http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(
        http2Client);
    http2Client.setMaxConcurrentPushedStreams(maxConcurrentPushedStreams);
    http2Client.setUseALPN(true);
    http2Client.setProtocols(List.of("h2", "h2c", "http/1.1"));

    // If ALPN could not negotiate HTTP2, it tries in the order of protocols indicated
    HttpClientTransport transport = new HttpClientTransportDynamic(
        clientConnector, http11, http2);

    transport.setConnectionPoolFactory((destination) -> {
      MultiplexConnectionPool mcp = new MultiplexConnectionPool(destination,
          destination.getHttpClient().getMaxConnectionsPerDestination(), destination, 1);
      mcp.setMaxUsageCount(maxRequestsPerConnection);
      mcp.setMaxMultiplex(maxRequestsPerConnection);
      return mcp;
    });

    this.httpClient = new HttpClient(transport);
    this.httpClient.setUserAgentField(null); // No set UA header
    this.httpClient.setByteBufferPool(HTTP2JettyClient.BUFFER_POOL);
    this.httpClient.setMaxRequestsQueuedPerDestination(maxRequestsQueuedPerDestination);
    this.httpClient.setMaxConnectionsPerDestination(maxConnectionsPerDestination);
    this.httpClient.setStrictEventOrdering(strictEventOrdering);
    this.httpClient.setRemoveIdleDestinations(removeIdleDestinations);
    this.httpClient.setIdleTimeout(idleTimeout);
    this.http1UpgradeRequired = http1UpgradeRequired;
    this.httpClient.setName(name);

    // TODO: Research
    //this.httpClient.setRequestBufferSize();
    //clientConnector.setByteBufferPool();
  }

  public HTTP2JettyClient() {
    this(false, "HttpClient");
  }

  public static void clearBufferPool() {
    HTTP2JettyClient.BUFFER_POOL.clear();
  }

  public int getMaxBufferSize() {
    return maxBufferSize;
  }

  public int getRequestTimeout() {
    return requestTimeout;
  }

  public void loadProperties() {
    requestTimeout = JMeterUtils.getPropDefault("HTTPSampler.response_timeout", 0);
    maxBufferSize =
        Integer.parseInt(JMeterUtils.getPropDefault("httpJettyClient.maxBufferSize",
            String.valueOf(2 * 1024 * 1024)));
    minThreads = Integer
        .parseInt(JMeterUtils.getPropDefault("httpJettyClient.minThreads",
            String.valueOf(minThreads)));
    maxThreads = Integer
        .parseInt(JMeterUtils.getPropDefault("httpJettyClient.maxThreads",
            String.valueOf(maxThreads)));
    maxRequestsQueuedPerDestination = Integer
        .parseInt(JMeterUtils.getPropDefault("httpJettyClient.maxRequestsQueuedPerDestination",
            String.valueOf(maxRequestsQueuedPerDestination)));
    maxRequestsPerConnection = Integer
        .parseInt(JMeterUtils.getPropDefault("httpJettyClient.maxRequestsPerConnection",
            String.valueOf(maxRequestsPerConnection)));
    maxConcurrentPushedStreams = Integer
        .parseInt(JMeterUtils.getPropDefault("httpJettyClient.maxConcurrentPushedStreams",
            String.valueOf(maxConcurrentPushedStreams)));
    maxConnectionsPerDestination =
        Integer.parseInt(JMeterUtils.getPropDefault("httpJettyClient.maxConnectionsPerDestination",
            String.valueOf(maxConnectionsPerDestination)));
    strictEventOrdering =
        Boolean.parseBoolean(JMeterUtils.getPropDefault("httpJettyClient.strictEventOrdering",
            String.valueOf(strictEventOrdering)));
    removeIdleDestinations =
        Boolean.parseBoolean(JMeterUtils.getPropDefault("httpJettyClient.removeIdleDestinations",
            String.valueOf(removeIdleDestinations)));
    idleTimeout =
        Integer.parseInt(JMeterUtils.getPropDefault("httpJettyClient.idleTimeout",
            String.valueOf(idleTimeout)));
  }

  public void start() throws Exception {
    if (!httpClient.isStarted()) {
      httpClient.start();
      httpClient.getContentDecoderFactories().clear(); // Clear default headers
    }
  }

  public void stop() throws Exception {
    httpClient.stop();
  }

  private void samplePrepareRequest(HttpRequest request,
                                    HTTP2Sampler sampler,
                                    HTTPSampleResult result) throws IOException {

    URL url = result.getURL();
    setTimeouts(sampler, request);
    request.followRedirects(sampler.getAutoRedirects());
    String method = result.getHTTPMethod();
    request.method(method);
    setHeaders(request, url, sampler.getHeaderManager());

    CookieManager cookieManager = sampler.getCookieManager();
    if (cookieManager != null) {
      result.setCookies(buildCookies(request, url, cookieManager));
    }

    if (!sampler.getProxyHost().isEmpty()) {
      setProxy(sampler.getProxyHost(), sampler.getProxyPortInt(), sampler.getProxyScheme());
    }
    result.sampleStart();

    setBody(request, sampler, result);

  }

  private boolean requestInCache(JettyCacheManager cacheManager,
                                 HttpRequest request)
      throws URISyntaxException, MalformedURLException {
    URL url = request.getURI().toURL();
    String method = request.getMethod();
    if (cacheManager != null) {
      cacheManager.setHeaders(url, request);
      if (HTTPConstants.GET.equalsIgnoreCase(method) && cacheManager.inCache(url,
          request.getHeaders())) {
        return true;
      }
    }
    return false;
  }

  private void postContentResponse(HTTP2Sampler sampler, HttpRequest request,
                                   HTTPSampleResult result,
                                   ContentResponse contentResponse, JettyCacheManager cacheManager)
      throws IOException {
    http1UpgradeRequired = contentResponse.getVersion() != HttpVersion.HTTP_2;
    result.setRequestHeaders(buildHeadersString(request.getHeaders()));
    setResultContentResponse(result, contentResponse, sampler);
    saveCookiesInCookieManager(contentResponse, request.getURI().toURL(),
        sampler.getCookieManager());

    if (cacheManager != null) {
      cacheManager.saveDetails(contentResponse, result);
    }
  }

  public HttpRequest sampleAsync(HTTP2Sampler sampler,
                                 HTTPSampleResult result,
                                 HTTP2FutureResponseListener listener)
      throws Exception {
    errorWhenNotSupportedMethod(result.getHTTPMethod());
    setAuthManager(sampler);
    HttpRequest request = buildRequest(result);
    samplePrepareRequest(request, sampler, result);
    listener.setRequest(request);
    return request;

  }

  private void errorWhenNotSupportedMethod(String method) throws UnsupportedOperationException {
    if (!isSupportedMethod(method)) {
      LOG.error(String.format("Method %s is not supported",
          method));
      throw new UnsupportedOperationException(String.format("Method %s is not supported",
          method));
    }
  }

  public HTTPSampleResult sample(HTTP2Sampler sampler, HTTPSampleResult result,
                                 boolean areFollowingRedirect, int depth) throws Exception {

    errorWhenNotSupportedMethod(result.getHTTPMethod());
    setAuthManager(sampler);
    HttpRequest request = buildRequest(result);

    samplePrepareRequest(request, sampler, result);

    JettyCacheManager cacheManager = JettyCacheManager.fromCacheManager(sampler.getCacheManager());
    if (requestInCache(cacheManager, request)) {
      return cacheManager.buildCachedSampleResult(result);
    }
    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener(maxBufferSize);
    ContentResponse contentResponse = send(request, listener);

    postContentResponse(sampler, request, result, contentResponse, cacheManager);
    result.setEndTime(listener.getResponseEnd());

    return sampler.resultProcessing(areFollowingRedirect, depth, result);
  }

  public HTTPSampleResult sampleFromListener(HTTP2Sampler sampler, HTTPSampleResult result,
                                             boolean areFollowingRedirect, int depth,
                                             HTTP2FutureResponseListener listener
  ) throws Exception {

    ContentResponse contentResponse = getContent(listener);
    JettyCacheManager cacheManager = JettyCacheManager.fromCacheManager(sampler.getCacheManager());
    HttpRequest request = listener.getRequest();
    postContentResponse(sampler, request, result, contentResponse, cacheManager);
    result.setEndTime(listener.getResponseEnd());

    return sampler.resultProcessing(areFollowingRedirect, depth, result);
  }

  public ContentResponse send(HttpRequest request, HTTP2FutureResponseListener listener)
      throws InterruptedException,
      TimeoutException, ExecutionException {
    if (request.getHeaders().contains("Accept-Encoding") &&
        request.getHeaders().get("Accept-Encoding").contains("gzip")) {
      httpClient.getContentDecoderFactories()
          .add(new GZIPContentDecoder.Factory(httpClient.getByteBufferPool()));
    } else {
      httpClient.getContentDecoderFactories().clear();
    }
    if (request.getHeaders() != null) {
      HttpFields hm = request.getHeaders();
      String ae = hm.get("Accept-Encoding");
      if (ae != null && ae.contains("br")) {
        Mutable headers = ((Mutable) request.getHeaders());
        headers.remove("Accept-Encoding");
        String acceptEncoding = ae;
        acceptEncoding = acceptEncoding.replace(", br", "").replace("br", "");
        HttpField aef = new HttpField("Accept-Encoding", acceptEncoding);
        request.addHeader(aef);
      }
    }
    request.send(listener);
    return getContent(listener);
  }

  public ContentResponse getContent(HTTP2FutureResponseListener listener)
      throws InterruptedException, TimeoutException, ExecutionException {
    long getStart = System.currentTimeMillis();
    try {
      if (requestTimeout > 0) {
        int extraTime = 2000;
        return listener.get(requestTimeout + extraTime, TimeUnit.MILLISECONDS);
      } else {
        return listener.get();
      }
    } catch (TimeoutException e) {
      long endGet = System.currentTimeMillis();
      throw new TimeoutException("The request took more than " + (endGet - getStart)
          + " milliseconds to complete");
    } catch (ExecutionException e) {
      if (e.getCause() != null && e.getCause() instanceof TimeoutException) {
        throw (TimeoutException) e.getCause();
      } else if (e.getCause() != null && e.getCause() instanceof IllegalArgumentException) {
        throw (IllegalArgumentException) e.getCause();
      }
      throw e;
    }
  }

  private void setAuthManager(HTTP2Sampler sampler) {
    AuthManager authManager = sampler.getAuthManager();
    if (authManager != null) {
      StreamSupport.stream(authManager.getAuthObjects().spliterator(), false)
          .map(j -> (Authorization) j.getObjectValue())
          .filter(auth -> isSupportedMechanism(auth) && !StringUtils.isEmpty(auth.getURL()))
          .forEach(this::addAuthenticationToJettyClient);
    }
  }

  private boolean isSupportedMechanism(Authorization auth) {
    String authName = auth.getMechanism().name();
    return authName.equals(AuthManager.Mechanism.BASIC.name())
        || authName.equals(AuthManager.Mechanism.DIGEST.name());
  }

  private void addAuthenticationToJettyClient(Authorization auth) {
    AuthenticationStore authenticationStore = httpClient.getAuthenticationStore();
    String authName = auth.getMechanism().name();
    if (authName.equals(AuthManager.Mechanism.BASIC.name()) && JMeterUtils.getPropDefault(
        "httpJettyClient.auth.preemptive", false)) {
      authenticationStore.addAuthenticationResult(
          new BasicAuthentication.BasicResult(URI.create(auth.getURL()), auth.getUser(),
              auth.getPass()));
    } else {
      AbstractAuthentication authentication =
          authName.equals(AuthManager.Mechanism.BASIC.name()) ? new BasicAuthentication(
              URI.create(auth.getURL()), auth.getRealm(), auth.getUser(), auth.getPass())
              : new DigestAuthentication(URI.create(auth.getURL()), auth.getRealm(), auth.getUser(),
              auth.getPass());
      authenticationStore.addAuthentication(authentication);
    }
  }

  private HttpRequest buildRequest(HTTPSampleResult result) throws URISyntaxException,
      IllegalArgumentException {
    URL url = result.getURL();
    HttpRequest request = (HttpRequest) httpClient.newRequest(url.toURI());
    request.onRequestBegin(r -> result.connectEnd());
    request.onRequestContent(
        (r, c) -> result.setSentBytes(result.getSentBytes() + c.limit()));
    request.onResponseBegin(r -> result.latencyEnd());
    return request;
  }

  private void setTimeouts(HTTP2Sampler sampler, HttpRequest request) {
    if (sampler.getConnectTimeout() > 0) {
      httpClient.setConnectTimeout(sampler.getConnectTimeout());
    }
    if (sampler.getResponseTimeout() > 0) {
      request.timeout(sampler.getResponseTimeout(), TimeUnit.MILLISECONDS);
    } else if (requestTimeout > 0) {
      request.timeout(requestTimeout, TimeUnit.MILLISECONDS);
    }
  }

  private void setHeaders(HttpRequest request, URL url, HeaderManager headerManager) {
    if (headerManager != null) {
      StreamSupport.stream(headerManager.getHeaders().spliterator(), false)
          .map(prop -> (Header) prop.getObjectValue())
          .filter(header -> (!header.getName().isEmpty()) && (!HTTPConstants.HEADER_CONTENT_LENGTH
              .equalsIgnoreCase(header.getName())))
          .forEach(header -> request.addHeader(createJettyHeader(header, url)));
    }
    if (http1UpgradeRequired) {
      Mutable headers = ((Mutable) request.getHeaders());
      addHeaderIfMissing(HttpHeader.UPGRADE, "h2c", headers);
      addHeaderIfMissing(HttpHeader.HTTP2_SETTINGS, "", headers);
      addHeaderIfMissing(HttpHeader.CONNECTION, "Upgrade, HTTP2-Settings", headers);
    }
  }

  private void addHeaderIfMissing(HttpHeader header, String value, Mutable headers) {
    if (!headers.contains(header)) {
      headers.put(header, value);
    }
  }

  private HttpField createJettyHeader(Header header, URL url) {
    String headerName = header.getName();
    String headerValue = header.getValue();
    if (HTTPConstants.HEADER_HOST.equalsIgnoreCase(headerName)) {
      int port = getPortFromHostHeader(headerValue, url.getPort());
      // remove any port specification
      headerValue = headerValue.replaceFirst(":\\d+$", "");
      if (port != -1 && port == url.getDefaultPort()) {
        // no need to specify the port if it is the default
        port = -1;
      }
      return port == -1 ? new HttpField(HTTPConstants.HEADER_HOST, headerValue)
          : new HttpField(HTTPConstants.HEADER_HOST, headerValue + ":" + port);
    } else {
      return new HttpField(headerName, headerValue);
    }
  }

  private int getPortFromHostHeader(String hostHeaderValue, int defaultValue) {
    String[] hostParts = hostHeaderValue.split(":");
    if (hostParts.length > 1) {
      String portString = hostParts[hostParts.length - 1];
      if (PORT_PATTERN.matcher(portString).matches()) {
        return Integer.parseInt(portString);
      }
    }
    return defaultValue;
  }

  private String buildCookies(HttpRequest request, URL url, CookieManager cookieManager) {
    if (cookieManager == null) {
      return null;
    }
    String cookieString = cookieManager.getCookieHeaderForURL(url);
    if (cookieString != null) {
      HttpField cookieHeader = new HttpField(HTTPConstants.HEADER_COOKIE, cookieString);
      request.addHeader(cookieHeader);
    }
    return cookieString;
  }

  private void setProxy(String host, int port, String protocol) {
    HttpProxy proxy =
        new HttpProxy(new Address(host, port), HTTPConstants.PROTOCOL_HTTPS.equals(protocol));
    httpClient.getProxyConfiguration().getProxies().add(proxy);
  }

  private void setBody(HttpRequest request, HTTP2Sampler sampler, HTTPSampleResult result)
      throws IOException {
    String contentEncoding = sampler.getContentEncoding();
    String contentTypeHeader =
        request.getHeaders() != null ? request.getHeaders().get(HTTPConstants.HEADER_CONTENT_TYPE)
            : null;
    boolean hasContentTypeHeader = contentTypeHeader != null && contentTypeHeader.isEmpty();
    StringBuilder postBody = new StringBuilder();
    if (sampler.getUseMultipart()) {
      MultiPartRequestContent multipartEntityBuilder = new MultiPartRequestContent();
      String boundary = extractMultipartBoundary(multipartEntityBuilder);
      Charset contentCharset =
          buildCharsetOrDefault(contentEncoding, StandardCharsets.US_ASCII);
      for (JMeterProperty jMeterProperty : sampler.getArguments()) {
        HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
        String parameterName = arg.getName();
        if (!arg.isSkippable(parameterName)) {
          postBody.append(
              buildArgumentPartRequestBody(arg, contentCharset, contentEncoding, boundary));
          multipartEntityBuilder.addFieldPart(parameterName,
              new StringRequestContent(contentTypeHeader, arg.getValue(), contentCharset), null);
        }
      }
      Content[] fileBodies = new PathRequestContent[sampler
          .getHTTPFiles().length];
      // Cannot retrieve parts once added
      for (int i = 0; i < sampler.getHTTPFiles().length; i++) {
        final HTTPFileArg file = sampler.getHTTPFiles()[i];
        if (StringUtils.isBlank(file.getParamName())) {
          throw new IllegalStateException("Param name is blank");
        }
        String mimeTypeFile = extractFileMimeType(hasContentTypeHeader, file);
        fileBodies[i] = new PathRequestContent(mimeTypeFile, Path.of(file.getPath()));
        String fileName = Paths.get((file.getPath())).getFileName().toString();
        postBody.append(buildFilePartRequestBody(file, fileName, boundary));
        multipartEntityBuilder.addFilePart(file.getParamName(), fileName, fileBodies[i],
            null);
      }
      postBody.append(MULTI_PART_SEPARATOR).append(boundary).append(MULTI_PART_SEPARATOR)
          .append(LINE_SEPARATOR);
      multipartEntityBuilder.close();
      request.body(multipartEntityBuilder);
    } else {
      if (!sampler.hasArguments() && sampler.getSendFileAsPostBody()) {
        // Only one File support in not multipart scenario
        final HTTPFileArg file = sampler.getHTTPFiles()[0];
        if (sampler.getHTTPFiles().length > 1) {
          LOG.info("Send multiples files is not currently supported, only first file will be "
              + "sending");
        }

        String mimeTypeFile = extractFileMimeType(hasContentTypeHeader, file);
        if (!DEFAULT_FILE_MIME_TYPE.equals(mimeTypeFile)) {
          request.addHeader(new HttpField(HTTPConstants.HEADER_CONTENT_TYPE, mimeTypeFile));
        }
        Content requestContent = new PathRequestContent(mimeTypeFile, Path.of(file.getPath()));
        request.body(requestContent);
        postBody.append("<actual file content, not shown here>");
      } else {
        if (!hasContentTypeHeader && ADD_CONTENT_TYPE_TO_POST_IF_MISSING) {
          request.addHeader(new HttpField(HTTPConstants.HEADER_CONTENT_TYPE,
              HTTPConstants.APPLICATION_X_WWW_FORM_URLENCODED));
        }
        Charset contentCharset = buildCharsetOrDefault(contentEncoding, StandardCharsets.UTF_8);
        if (sampler.getSendParameterValuesAsPostBody()) {
          for (JMeterProperty jMeterProperty : sampler.getArguments()) {
            HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
            postBody.append(arg.getEncodedValue(contentCharset.name()));
          }
          Content requestContent = new StringRequestContent(contentTypeHeader, postBody.toString(),
              contentCharset);
          request.body(requestContent);
        } else if (isMethodWithBody(sampler.getMethod())) {
          Fields fields = new Fields();
          for (JMeterProperty p : sampler.getArguments()) {
            HTTPArgument arg = (HTTPArgument) p.getObjectValue();
            String parameterName = arg.getName();
            if (!arg.isSkippable(parameterName)) {
              String parameterValue = arg.getValue();
              if (!arg.isAlwaysEncoded()) {
                // The FormRequestContent always urlencodes both name and value, in this case the
                // value is already encoded by the user so is needed to decode the value now, so
                // that when the httpclient encodes it, we end up with the same value as the user
                // had entered.
                parameterName = URLDecoder.decode(parameterName, contentCharset.name());
                parameterValue = URLDecoder.decode(parameterValue, contentCharset.name());
              }
              fields.add(parameterName, parameterValue);
            }
          }
          postBody.append(FormRequestContent.convert(fields));
          request.body(new FormRequestContent(fields, contentCharset));
        }
      }
    }
    result.setQueryString(postBody.toString());
  }

  private String extractMultipartBoundary(MultiPartRequestContent multipartEntityBuilder) {
    String contentType = multipartEntityBuilder.getContentType();
    String boundaryParam = contentType.substring(contentType.indexOf(" ") + 1);
    return boundaryParam.substring(boundaryParam.indexOf("=") + 1);
  }

  private Charset buildCharsetOrDefault(String contentEncoding, Charset defaultCharset) {
    return !contentEncoding.isEmpty() ? Charset.forName(contentEncoding) : defaultCharset;
  }

  private String buildArgumentPartRequestBody(HTTPArgument arg, Charset contentCharset,
                                              String contentEncoding, String boundary)
      throws UnsupportedEncodingException {
    String disposition = "name=\"" + arg.getEncodedName() + "\"";
    String contentType = arg.getContentType() + "; charset=" + contentCharset.name();
    String encoding = StringUtils.isNotBlank(contentEncoding) ? contentEncoding : "8bit";
    return buildPartBody(boundary, disposition, contentType, encoding,
        arg.getEncodedValue(contentCharset.name()));
  }

  private String buildPartBody(String boundary, String disposition, String contentType,
                               String encoding, String value) {
    return MULTI_PART_SEPARATOR + boundary + LINE_SEPARATOR +
        HttpFields.build()
            .add("Content-Disposition", "form-data; " + disposition)
            .add(HttpHeader.CONTENT_TYPE.toString(), contentType)
            .add("Content-Transfer-Encoding", encoding)
            .toString()
        + value + LINE_SEPARATOR;
  }

  private String buildFilePartRequestBody(HTTPFileArg file, String fileName, String boundary) {
    String disposition = "name=\"" + file.getParamName() + "\"; filename=\"" + fileName + "\"";
    return buildPartBody(boundary, disposition, file.getMimeType(), "binary",
        "<actual file content, not shown here>");
  }

  private String extractFileMimeType(boolean hasContentTypeHeader, HTTPFileArg file) {
    String ret = null;
    if (!hasContentTypeHeader) {
      if (file.getMimeType() != null && !file.getMimeType().isEmpty()) {
        ret = file.getMimeType();
      } else if (ADD_CONTENT_TYPE_TO_POST_IF_MISSING) {
        ret = HTTPConstants.APPLICATION_X_WWW_FORM_URLENCODED;
      }
    }
    return ret == null ? DEFAULT_FILE_MIME_TYPE : ret;
  }

  private boolean isMethodWithBody(String method) {
    return METHODS_WITH_BODY.contains(method);
  }

  private boolean isSupportedMethod(String method) {
    return SUPPORTED_METHODS.contains(method);
  }

  private String buildHeadersString(HttpFields headers) {
    if (headers == null) {
      return "";
    } else {
      String ret = HttpFields.build(headers).remove(HTTPConstants.HEADER_COOKIE).toString()
          .replace("\r\n", "\n");
      return ret.substring(0,
          ret.length() - 1); // removing final separator not included in jmeter headers
    }
  }

  private void setResultContentResponse(HTTPSampleResult result, ContentResponse contentResponse,
                                        HTTP2Sampler sampler) throws IOException {
    String contentType = contentResponse.getHeaders() != null
        ? contentResponse.getHeaders().get(HTTPConstants.HEADER_CONTENT_TYPE)
        : null;
    if (contentType != null) {
      result.setContentType(contentType);
      result.setEncodingAndType(contentType);
    }

    // When a resource is cached, the sample result is empty
    InputStream inputStream = new ByteArrayInputStream(contentResponse.getContent());
    result.setResponseData(sampler.readResponse(result, inputStream,
        contentResponse.getContent().length));

    if (result.getEndTime() == 0) {
      result.sampleEnd();
    } else {
      result.setEndTime(result.currentTimeInMillis());
    }

    result.setResponseCode(String.valueOf(contentResponse.getStatus()));
    String responseMessage = contentResponse.getReason() != null ? contentResponse.getReason()
        : HttpStatus.getMessage(contentResponse.getStatus());
    result.setResponseMessage(responseMessage);
    result.setSuccessful(contentResponse.getStatus() >= 200 && contentResponse.getStatus() <= 399);
    result.setResponseHeaders(extractResponseHeaders(contentResponse, responseMessage));
    if (result.isRedirect()) {
      result.setRedirectLocation(extractRedirectLocation(contentResponse));
    }

    if (sampler.getAutoRedirects()) {
      result.setURL(contentResponse.getRequest().getURI().toURL());
    }

    long headerBytes =
        (long) result.getResponseHeaders().length()   // condensed length (without \r)
            + (long) contentResponse.getHeaders().asString().length() // Add \r for each header
            + 1L // Add \r for initial header
            + 2L; // final \r\n before data
    result.setHeadersSize((int) headerBytes);
  }

  private String extractResponseHeaders(ContentResponse contentResponse,
                                        String message) {
    return contentResponse.getVersion() + " " + contentResponse.getStatus() + " " + message + "\n"
        + buildHeadersString(contentResponse.getHeaders());
  }

  private String extractRedirectLocation(ContentResponse contentResponse) {
    String redirectLocation = contentResponse.getHeaders() != null
        ? contentResponse.getHeaders().get(HTTPConstants.HEADER_LOCATION)
        : null;
    if (redirectLocation == null) {
      throw new IllegalArgumentException("Missing location header in redirect");
    }
    return redirectLocation;
  }

  private void saveCookiesInCookieManager(ContentResponse response, URL url,
                                          CookieManager cookieManager) {
    if (cookieManager == null) {
      return;
    }
    for (HttpField field : response.getHeaders()) {
      if (field.is(HTTPConstants.HEADER_SET_COOKIE)) {
        String cookieHeader = field.getValue();
        if (cookieHeader != null) {
          cookieManager.addCookieFromHeader(cookieHeader, url);
        }
      }
    }
  }

  public void clearCookies() {
    httpClient.getCookieStore().removeAll();
  }

  public void clearAuthenticationResults() {
    httpClient.getAuthenticationStore().clearAuthenticationResults();
  }

  public String dump() {
    return httpClient.dump();
  }

}



