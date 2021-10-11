package com.blazemeter.jmeter.http2.core;

import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.core.utils.CacheManagerJettyHelper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.message.BasicHttpResponse;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.AuthManager.Mechanism;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.Origin.Address;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Request.Content;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.client.util.AbstractAuthentication;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.DigestAuthentication;
import org.eclipse.jetty.client.util.FormRequestContent;
import org.eclipse.jetty.client.util.PathRequestContent;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2JettyClient {

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2JettyClient.class);
  private static final Set<String> SUPPORTED_METHODS = new HashSet<>(Arrays
      .asList(HTTPConstants.GET, HTTPConstants.POST, HTTPConstants.PUT, HTTPConstants.PATCH,
          HTTPConstants.OPTIONS, HTTPConstants.DELETE));
  private static final Set<String> METHODS_WITH_BODY = new HashSet<>(Arrays
      .asList(HTTPConstants.POST, HTTPConstants.PUT, HTTPConstants.PATCH));
  private static final boolean ADD_CONTENT_TYPE_TO_POST_IF_MISSING = JMeterUtils.getPropDefault(
      "http.post_add_content_type_if_missing", false);
  private static final Pattern PORT_PATTERN = Pattern.compile("\\d+");
  private final HttpClient httpClient;

  public HTTP2JettyClient() {
    ClientConnector clientConnector = new ClientConnector();
    SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
    sslContextFactory.setTrustAll(true);
    clientConnector.setSslContextFactory(sslContextFactory);
    HTTP2Client http2Client = new HTTP2Client(clientConnector);
    HttpClientTransport transport = new HttpClientTransportDynamic(clientConnector,
        new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client),
        HttpClientConnectionFactory.HTTP11);
    this.httpClient = new HttpClient(transport);
  }

  public HTTPSampleResult sample(HTTP2Sampler sampler, URL url, String method,
      boolean areFollowingRedirect, int depth)
      throws URISyntaxException, IOException, InterruptedException,
      ExecutionException, TimeoutException {
    HTTPSampleResult result = new HTTPSampleResult();
    result.setSampleLabel(getSampleLabel(url, sampler));
    result.setHTTPMethod(method);
    result.setURL(url);
    if (!sampler.getProxyHost().isEmpty()) {
      setProxy(sampler.getProxyHost(), sampler.getProxyPortInt(), sampler.getProxyScheme());
    }
    result.sampleStart();
    setAuthManager(sampler);
    HttpRequest request = createRequest(url, result);
    setTimeouts(sampler, request);
    request.followRedirects(sampler.getAutoRedirects());
    request.method(method);
    CacheManager cacheManager = sampler.getCacheManager();
    if (sampler.getHeaderManager() != null) {
      setHeaders(request, sampler.getHeaderManager(), url, cacheManager, areFollowingRedirect,
          sampler);
    }
    // If result of request is cached, then return it
    if (cacheManager != null && HTTPConstants.GET.equalsIgnoreCase(method) && cacheManager
        .inCache(url, CacheManagerJettyHelper.convertJettyHeadersToApacheHeaders(request.getHeaders()))) {
      return CacheManagerJettyHelper.updateSampleResultForResourceInCache(result);
    }
    CookieManager cookieManager = sampler.getCookieManager();
    if (cookieManager != null) {
      result.setCookies(buildCookies(request, url, cookieManager));
    }
    setBody(request, sampler, result);
    HttpResponse httpResponse;
    if (!isSupportedMethod(method)) {
      throw new UnsupportedOperationException(
          String.format("Method %s is not supported", method));
    } else {
      ContentResponse contentResponse = request.send();
      saveCookiesInCookieManager(contentResponse, url, sampler.getCookieManager());
      setResultContentResponse(result, contentResponse, sampler);
      result.sampleEnd();
      if (result.isRedirect()) {
        String redirectLocation = contentResponse.getHeaders() != null
            ? contentResponse.getHeaders().get(HTTPConstants.HEADER_LOCATION)
            : null;
        if (redirectLocation == null) {
          throw new IllegalArgumentException("Missing location header in redirect");
        }
        result.setRedirectLocation(redirectLocation);
      }
      httpResponse = CacheManagerJettyHelper.createApacheHttpResponseFromJettyContentResponse(contentResponse);
    }

    result.setRequestHeaders(getHeadersAsString(request.getHeaders()));

    if (cacheManager != null) {
      cacheManager.saveDetails(httpResponse, result);
    }

    result = sampler.resultProcessing(areFollowingRedirect, depth, result);
    return result;
  }

  private String getHeadersAsString(HttpFields headers) {
    if (headers == null) {
      return "";
    } else {
      return headers.stream().filter(h -> !h.getName().equals(HTTPConstants.HEADER_COOKIE))
          .map(h -> h.getName() + ": " + h.getValue()).collect(Collectors.joining("\r\n"))
          + "\r\n\r\n";
    }
  }

  private void saveCookiesInCookieManager(ContentResponse response, URL url,
      CookieManager cookieManager) {
    String cookieHeader = response.getHeaders().get(HTTPConstants.HEADER_SET_COOKIE);
    if (cookieHeader != null && cookieManager != null) {
      cookieManager.addCookieFromHeader(cookieHeader, url);
    }
  }

  private String buildCookies(HttpRequest request, URL url, CookieManager cookieManager) {
    if (cookieManager == null) {
      return null;
    }
    String cookieString = cookieManager.getCookieHeaderForURL(url);
    HttpField cookieHeader = new HttpField(HTTPConstants.HEADER_COOKIE, cookieString);
    request.addHeader(cookieHeader);
    return cookieString;
  }

  private void setProxy(String host, int port, String protocol) {
    HttpProxy proxy =
        new HttpProxy(new Address(host, port), HTTPConstants.PROTOCOL_HTTPS.equals(protocol));
    httpClient.getProxyConfiguration().getProxies().add(proxy);
  }

  private HttpRequest createRequest(URL url, HTTPSampleResult result) throws URISyntaxException,
      IllegalArgumentException {
    Request request = httpClient.newRequest(url.toURI());
    if (request instanceof HttpRequest && result != null) {
      HttpRequest httpRequest = (HttpRequest) request;
      httpRequest.onRequestBegin(l -> result.connectEnd());
      httpRequest.onResponseBegin(l -> result.latencyEnd());
      return httpRequest;
    } else {
      throw new IllegalArgumentException("HttpRequest is expected");
    }
  }

  private void setAuthManager(HTTP2Sampler sampler) {

    AuthManager authManager = sampler.getAuthManager();
    if (authManager != null) {
      StreamSupport.stream(authManagerToSpliterator(authManager), false)
          .map(this::getAuthorizationObjectFromProperty)
          .filter(auth -> isSupportedMechanism(auth) && isNotEmptyURL(auth))
          .forEach(this::addAuthenticationToJettyClient);
    }
  }

  private void addAuthenticationToJettyClient(Authorization auth) {
    AuthenticationStore authenticationStore = httpClient.getAuthenticationStore();
    String authName = auth.getMechanism().name();
    if (authName.equals(Mechanism.BASIC.name()) && JMeterUtils.getPropDefault(
        "httpJettyClient.auth.preemptive", false)) {
      authenticationStore.addAuthenticationResult(
          new BasicAuthentication.BasicResult(URI.create(auth.getURL()), auth.getUser(),
              auth.getPass()));
    } else {
      AbstractAuthentication authentication =
          authName.equals(Mechanism.BASIC.name()) ? new BasicAuthentication(
              URI.create(auth.getURL()), auth.getRealm(), auth.getUser(), auth.getPass())
              : new DigestAuthentication(URI.create(auth.getURL()), auth.getRealm(), auth.getUser(),
                  auth.getPass());
      authenticationStore.addAuthentication(authentication);
    }
  }

  private boolean isSupportedMechanism(Authorization auth) {
    String authName = auth.getMechanism().name();
    return authName.equals(Mechanism.BASIC.name()) || authName.equals(Mechanism.DIGEST.name());
  }

  private Spliterator<JMeterProperty> authManagerToSpliterator(AuthManager authManager) {
    return authManager.getAuthObjects().spliterator();
  }

  private Authorization getAuthorizationObjectFromProperty(JMeterProperty jMeterProperty) {
    return (Authorization) jMeterProperty.getObjectValue();
  }

  private boolean isNotEmptyURL(Authorization authorization) {
    return authorization.getURL() != null && !authorization.getURL().isEmpty();
  }

  public void start() throws Exception {
    if (!httpClient.isStarted()) {
      httpClient.start();
    }
  }

  public void stop() throws Exception {
    httpClient.stop();
  }

  private void setResultContentResponse(HTTPSampleResult result, ContentResponse contentResponse,
      HTTP2Sampler sampler) throws IOException {
    result.setSuccessful(contentResponse.getStatus() >= 200 && contentResponse.getStatus() <= 399);
    result.setResponseCode(String.valueOf(contentResponse.getStatus()));
    result
        .setResponseMessage(contentResponse.getReason() != null ? contentResponse.getReason() : "");
    result.setResponseHeaders(contentResponse.getHeaders().asString());

    InputStream inputStream = new ByteArrayInputStream(contentResponse.getContent());
    // When a resource is cached, the sample result is empty
    result.setResponseData(sampler.readResponse(result, inputStream,
        contentResponse.getContent().length));

    String contentType = contentResponse.getHeaders() != null
        ? contentResponse.getHeaders().get(HTTPConstants.HEADER_CONTENT_TYPE)
        : null;
    if (contentType != null) {
      result.setContentType(contentType);
      result.setEncodingAndType(contentType);
    }

    long headerBytes =
        (long) result.getResponseHeaders().length()
            + (long) contentResponse.getHeaders().asString().length()
            + 1L
            + 2L;
    result.setHeadersSize((int) headerBytes);

  }

  private void setBody(HttpRequest request, HTTP2Sampler sampler, HTTPSampleResult result)
      throws IOException {
    String contentEncoding = sampler.getContentEncoding();
    Charset contentCharset =
        !contentEncoding.isEmpty() ? Charset.forName(contentEncoding) : StandardCharsets.UTF_8;
    String contentTypeHeader =
        request.getHeaders() != null ? request.getHeaders().get(HTTPConstants.HEADER_CONTENT_TYPE)
            : null;
    boolean hasContentTypeHeader = contentTypeHeader != null && contentTypeHeader.isEmpty();
    StringBuilder postBody = new StringBuilder();
    Content requestContent;
    if (!sampler.hasArguments() && sampler.getSendFileAsPostBody()) {
      // Only one File support in not multipart scenario
      HTTPFileArg file = sampler.getHTTPFiles()[0];
      if (sampler.getHTTPFiles().length > 1) {
        LOG.info("Send multiples files is not currently supported, only first file will be "
            + "sending");
      }
      String mimeTypeFile = null;
      if (!hasContentTypeHeader) {
        if (file.getMimeType() != null && !file.getMimeType().isEmpty()) {
          mimeTypeFile = file.getMimeType();
        } else if (ADD_CONTENT_TYPE_TO_POST_IF_MISSING) {
          mimeTypeFile = HTTPConstants.APPLICATION_X_WWW_FORM_URLENCODED;
        }
      }
      if (mimeTypeFile != null) {
        request.addHeader(new HttpField(HTTPConstants.HEADER_CONTENT_TYPE, mimeTypeFile));
        requestContent = new PathRequestContent(mimeTypeFile, Path.of(file.getPath()));
      } else {
        requestContent = new PathRequestContent(Path.of(file.getPath()));
      }
      request.body(requestContent);
      postBody.append("<actual file content, not shown here>");
    } else {
      if (!hasContentTypeHeader && ADD_CONTENT_TYPE_TO_POST_IF_MISSING) {
        request.addHeader(new HttpField(HTTPConstants.HEADER_CONTENT_TYPE,
            HTTPConstants.APPLICATION_X_WWW_FORM_URLENCODED));
      }
      if (sampler.getSendParameterValuesAsPostBody()) {
        for (JMeterProperty jMeterProperty : sampler.getArguments()) {
          HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
          postBody.append(arg.getEncodedValue(contentCharset.name()));
        }
        requestContent = new StringRequestContent(contentTypeHeader, postBody.toString(),
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
        requestContent = new FormRequestContent(fields, contentCharset);
        postBody.append(FormRequestContent.convert(fields));
        request.body(requestContent);
      }
      result.setQueryString(postBody.toString());
    }
  }

  private boolean isSupportedMethod(String method) {
    return SUPPORTED_METHODS.contains(method);
  }

  private boolean isMethodWithBody(String method) {
    return METHODS_WITH_BODY.contains(method);
  }

  private void setHeaders(HttpRequest request, HeaderManager headerManager, URL url,
      CacheManager cacheManager, boolean areFollowingRedirect, HTTP2Sampler sampler)
      throws URISyntaxException {
    StreamSupport.stream(headerManager.getHeaders().spliterator(), false)
        .map(prop -> (Header) prop.getObjectValue())
        .filter(header -> (!header.getName().isEmpty()) && (!HTTPConstants.HEADER_CONTENT_LENGTH
            .equalsIgnoreCase(header.getName())))
        .forEach(header -> {
          request.addHeader(createJettyHeader(header, url));
        });

    if (cacheManager != null) {
      URI uri = new URI(url.toString());
      HttpRequestBase reqBase = CacheManagerJettyHelper.createApacheHttpRequest(uri, request.getMethod(),
          areFollowingRedirect, sampler);
      cacheManager.setHeaders(url, reqBase);
      if (reqBase.getFirstHeader(HTTPConstants.VARY) != null) {
        request.addHeader(new HttpField(HTTPConstants.VARY,
            reqBase.getFirstHeader(HTTPConstants.VARY).getValue()));
      }
      if (reqBase.getFirstHeader(HTTPConstants.IF_MODIFIED_SINCE) != null) {
        request.addHeader(new HttpField(HTTPConstants.IF_MODIFIED_SINCE,
            reqBase.getFirstHeader(HTTPConstants.LAST_MODIFIED).getValue()));
      }
      if (reqBase.getFirstHeader(HTTPConstants.IF_NONE_MATCH) != null) {
        request.addHeader(new HttpField(HTTPConstants.IF_NONE_MATCH,
            reqBase.getFirstHeader(HTTPConstants.ETAG).getValue()));
      }
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

  private String getSampleLabel(URL url, HTTP2Sampler sampler) {
    return SampleResult.isRenameSampleLabel() ? sampler.getName() : url.toString();
  }

  private void setTimeouts(HTTP2Sampler sampler, HttpRequest request) {
    if (sampler.getConnectTimeout() > 0) {
      httpClient.setConnectTimeout(sampler.getConnectTimeout());
    }
    if (sampler.getResponseTimeout() > 0) {
      request.timeout(sampler.getResponseTimeout(), TimeUnit.MILLISECONDS);
    }
  }
}
