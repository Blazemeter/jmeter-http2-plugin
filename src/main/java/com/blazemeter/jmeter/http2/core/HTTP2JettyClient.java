package com.blazemeter.jmeter.http2.core;

import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpTrace;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPHC4Impl.HttpDelete;
import org.apache.jmeter.protocol.http.sampler.HTTPHC4Impl.HttpGetWithEntity;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HttpWebdav;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.Origin.Address;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Request.Content;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.client.util.FormRequestContent;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HTTP2JettyClient {

  private static final Set<String> SUPPORTED_METHODS = new HashSet<>(Arrays
      .asList(HTTPConstants.GET, HTTPConstants.POST, HTTPConstants.PUT, HTTPConstants.PATCH,
          HTTPConstants.OPTIONS, HTTPConstants.DELETE));
  private static final Set<String> METHODS_WITH_BODY = new HashSet<>(Arrays
      .asList(HTTPConstants.POST, HTTPConstants.PUT, HTTPConstants.PATCH));
  private static final boolean ADD_CONTENT_TYPE_TO_POST_IF_MISSING = JMeterUtils.getPropDefault(
      "http.post_add_content_type_if_missing", false);
  private static final Pattern PORT_PATTERN = Pattern.compile("\\d+");
  private final HttpClient httpClient;
  private static final CachedResourceMode CACHED_RESOURCE_MODE =
      CachedResourceMode.valueOf(
          JMeterUtils.getPropDefault("cache_manager.cached_resource_mode", //$NON-NLS-1$
              CachedResourceMode.RETURN_NO_SAMPLE.toString()));

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

    HttpRequest request = createRequest(url, result);
    request.followRedirects(false);
    request.method(method);

    final CacheManager cacheManager = sampler.getCacheManager();
    if (sampler.getHeaderManager() != null) {
      setHeaders(request, sampler.getHeaderManager(), url, cacheManager, areFollowingRedirect,
          sampler);
    }

    // Get headers request and convert to pass to cache manager
    final HttpFields fields = request.getHeaders();
    final List<Header> headersRequest = StreamSupport.stream(
        Spliterators
            .spliterator(fields.getFieldNames().asIterator(), fields.size(), Spliterator.SIZED),
        false)
        .map(name -> {
          final String value = fields.getField(name).getValue();
          return new Header(name, value);
        })
        .collect(Collectors.toList());

    // If result of request is cached, then return it
    if (cacheManager != null && HTTPConstants.GET.equalsIgnoreCase(method) && cacheManager
        .inCache(url, (org.apache.http.Header[]) headersRequest.toArray())) {
      return updateSampleResultForResourceInCache(result);
    }

    setBody(request, sampler, result);
    if (!isSupportedMethod(method)) {
      throw new UnsupportedOperationException(
          String.format("Method %s is not supported", method));
    } else {
      ContentResponse contentResponse = request.send();
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

      // Update cache with new information
      if (cacheManager != null) {
        // Map content response data with http response
        final HttpResponse httpResponse = createHttpResponse(contentResponse);
        cacheManager.saveDetails(httpResponse, result);
      }
    }

    result.setRequestHeaders(request.getHeaders() != null ? request.getHeaders().asString() : "");
    result = sampler.resultProcessing(areFollowingRedirect, depth, result);
    return result;
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
    result.setResponseData(sampler.readResponse(result, inputStream,
        contentResponse.getContent().length));
    String contentType = contentResponse.getHeaders() != null
        ? contentResponse.getHeaders().get(HTTPConstants.HEADER_CONTENT_TYPE)
        : null;
    if (contentType != null) {
      result.setContentType(contentType);
      result.setEncodingAndType(contentType);
    }
  }

  private void setBody(HttpRequest request, HTTP2Sampler sampler, HTTPSampleResult result)
      throws UnsupportedEncodingException {
    final String contentEncoding = sampler.getContentEncoding();
    Charset contentCharset =
        !contentEncoding.isEmpty() ? Charset.forName(contentEncoding) : StandardCharsets.UTF_8;
    String contentTypeHeader =
        request.getHeaders() != null ? request.getHeaders().get(HTTPConstants.HEADER_CONTENT_TYPE)
            : null;
    boolean hasContentTypeHeader = contentTypeHeader != null && contentTypeHeader.isEmpty();
    if (!hasContentTypeHeader && ADD_CONTENT_TYPE_TO_POST_IF_MISSING) {
      request.addHeader(new HttpField(HTTPConstants.HEADER_CONTENT_TYPE,
          HTTPConstants.APPLICATION_X_WWW_FORM_URLENCODED));
    }
    StringBuilder postBody = new StringBuilder();
    Content requestContent;
    if (sampler.getSendParameterValuesAsPostBody()) {
      for (JMeterProperty jMeterProperty : sampler.getArguments()) {
        HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
        postBody.append(arg.getEncodedValue(contentCharset.name()));
      }
      requestContent = new StringRequestContent(contentTypeHeader, postBody.toString(),
          contentCharset);
      result.setQueryString(postBody.toString());
      request.body(requestContent);
    } else if (isMethodWithBody(sampler.getMethod())) {
      PropertyIterator args = sampler.getArguments().iterator();
      Fields fields = new Fields();
      while (args.hasNext()) {
        HTTPArgument arg = (HTTPArgument) args.next().getObjectValue();
        String parameterName = arg.getName();
        if (!arg.isSkippable(parameterName)) {
          String parameterValue = arg.getValue();
          if (!arg.isAlwaysEncoded()) {
            // The FormRequestContent always urlencodes both name and value, in this case the value
            // is already encoded by the user so is needed to decode the value now, so that when the
            // httpclient encodes it, we end up with the same value as the user had entered.
            parameterName = URLDecoder.decode(parameterName, contentCharset.name());
            parameterValue = URLDecoder.decode(parameterValue, contentCharset.name());
          }
          fields.add(parameterName, parameterValue);
        }
      }
      requestContent = new FormRequestContent(fields, contentCharset);
      result.setQueryString(FormRequestContent.convert(fields));
      request.body(requestContent);
    }
  }

  private boolean isSupportedMethod(String method) {
    return SUPPORTED_METHODS.contains(method);
  }

  private boolean isMethodWithBody(String method) {
    return METHODS_WITH_BODY.contains(method);
  }

  private void setHeaders(HttpRequest request, final HeaderManager headerManager, final URL url,
      CacheManager cacheManager, final boolean areFollowingRedirect, final HTTP2Sampler sampler)
      throws URISyntaxException {
    StreamSupport.stream(headerManager.getHeaders().spliterator(), false)
        .map(prop -> (Header) prop.getObjectValue())
        .filter(header -> !HTTPConstants.HEADER_CONTENT_LENGTH.equalsIgnoreCase(header.getName()))
        .forEach(header -> {
          if (!header.getName().isEmpty()) {
            request.addHeader(createJettyHeader(header, url));
          }
        });

    if (cacheManager != null) {
      // Convert URL to URI
      final URI uri = new URI(url.toString());
      cacheManager.setHeaders(url, createHttpRequest(uri, request.getMethod(),
          areFollowingRedirect, sampler));
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

  /**
   * Update HTTPSampleResult for a resource in cache.
   *
   * @param res {@link HTTPSampleResult}
   * @return HTTPSampleResult
   */
  private HTTPSampleResult updateSampleResultForResourceInCache(HTTPSampleResult res) {
    switch (CACHED_RESOURCE_MODE) {
      case RETURN_NO_SAMPLE:
        return null;
      case RETURN_200_CACHE:
        res.sampleEnd();
        res.setResponseCodeOK();
        res.setResponseMessage(RETURN_200_CACHE_MESSAGE);
        res.setSuccessful(true);
        return res;
      case RETURN_CUSTOM_STATUS:
        res.sampleEnd();
        res.setResponseCode(RETURN_CUSTOM_STATUS_CODE);
        res.setResponseMessage(RETURN_CUSTOM_STATUS_MESSAGE);
        res.setSuccessful(true);
        return res;
      default:
        // Cannot happen
        throw new IllegalStateException("Unknown CACHED_RESOURCE_MODE");
    }
  }

  /**
   * Create an HttpResponse from a ContentResponse of Jetty
   * @param contentResponse
   * @return
   */
  private CloseableHttpResponse createHttpResponse(final ContentResponse contentResponse) {
    // Get atts that Cache need
    final Header vary = new Header(HTTPConstants.VARY,
        contentResponse.getHeaders().get(HTTPConstants.VARY));
    final Header lastModified = new Header(HTTPConstants.LAST_MODIFIED,
        contentResponse.getHeaders().get(HTTPConstants.LAST_MODIFIED));
    final Header expires = new Header(HTTPConstants.EXPIRES,
        contentResponse.getHeaders().get(HTTPConstants.EXPIRES));
    final Header etag = new Header(HTTPConstants.ETAG,
        contentResponse.getHeaders().get(HTTPConstants.ETAG);
    final Header cacheControl = new Header(HTTPConstants.CACHE_CONTROL,
        contentResponse.getHeaders().get(HTTPConstants.CACHE_CONTROL));
    final Header date = new Header(HTTPConstants.DATE,
        contentResponse.getHeaders().get(HTTPConstants.DATE));

    // Set atts in http response

    return null;
  }

  private enum CachedResourceMode {
    RETURN_200_CACHE(),
    RETURN_NO_SAMPLE(),
    RETURN_CUSTOM_STATUS()
  }

  /**
   * SampleResult message when resource was in cache and mode is RETURN_200_CACHE
   */
  private static final String RETURN_200_CACHE_MESSAGE =
      JMeterUtils.getPropDefault("RETURN_200_CACHE.message", "(ex cache)");//$NON-NLS-1$ $NON-NLS-2$

  /**
   * Custom response code for cached resource
   */
  private static final String RETURN_CUSTOM_STATUS_CODE =
      JMeterUtils.getProperty("RETURN_CUSTOM_STATUS.code");//$NON-NLS-1$

  /**
   * Custom response message for cached resource
   */
  private static final String RETURN_CUSTOM_STATUS_MESSAGE =
      JMeterUtils.getProperty("RETURN_CUSTOM_STATUS.message"); //$NON-NLS-1$

  /**
   * @param uri {@link URI}
   * @param method HTTP Method
   * @param areFollowingRedirect Are we following redirects
   * @return {@link HttpRequestBase}
   */
  private HttpRequestBase createHttpRequest(final URI uri, final String method,
      final boolean areFollowingRedirect,
      final HTTP2Sampler sampler) {
    HttpRequestBase result;
    if (method.equals(HTTPConstants.POST)) {
      result = new HttpPost(uri);
    } else if (method.equals(HTTPConstants.GET)) {
      // Some servers fail if Content-Length is equal to 0
      // so to avoid this we use HttpGet when there is no body (Content-Length will not be set)
      // otherwise we use HttpGetWithEntity
      if (!areFollowingRedirect
          && ((!sampler.hasArguments() && sampler.getSendFileAsPostBody())
          || sampler.getSendParameterValuesAsPostBody())) {
        result = new HttpGetWithEntity(uri);
      } else {
        result = new HttpGet(uri);
      }
    } else if (method.equals(HTTPConstants.PUT)) {
      result = new HttpPut(uri);
    } else if (method.equals(HTTPConstants.HEAD)) {
      result = new HttpHead(uri);
    } else if (method.equals(HTTPConstants.TRACE)) {
      result = new HttpTrace(uri);
    } else if (method.equals(HTTPConstants.OPTIONS)) {
      result = new HttpOptions(uri);
    } else if (method.equals(HTTPConstants.DELETE)) {
      result = new HttpDelete(uri);
    } else if (method.equals(HTTPConstants.PATCH)) {
      result = new HttpPatch(uri);
    } else if (HttpWebdav.isWebdavMethod(method)) {
      result = new HttpWebdav(method, uri);
    } else {
      throw new IllegalArgumentException("Unexpected method: '" + method + "'");
    }
    return result;
  }

}
