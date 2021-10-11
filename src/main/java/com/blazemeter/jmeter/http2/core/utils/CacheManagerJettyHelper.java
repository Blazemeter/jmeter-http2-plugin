package com.blazemeter.jmeter.http2.core.utils;

import java.net.URI;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpTrace;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpResponse;
import org.apache.jmeter.protocol.http.sampler.HTTPHC4Impl.HttpDelete;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HttpWebdav;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpFields;

public class CacheManagerJettyHelper {

  public static HttpRequestBase createApacheHttpRequest(URI uri, String method) {
    HttpRequestBase result;
    switch (method) {
      case HTTPConstants.POST:
        result = new HttpPost(uri);
        break;
      case HTTPConstants.GET:
        result = new HttpGet(uri);
        break;
      case HTTPConstants.PUT:
        result = new HttpPut(uri);
        break;
      case HTTPConstants.HEAD:
        result = new HttpHead(uri);
        break;
      case HTTPConstants.TRACE:
        result = new HttpTrace(uri);
        break;
      case HTTPConstants.OPTIONS:
        result = new HttpOptions(uri);
        break;
      case HTTPConstants.DELETE:
        result = new HttpDelete(uri);
        break;
      case HTTPConstants.PATCH:
        result = new HttpPatch(uri);
        break;
      default:
        if (HttpWebdav.isWebdavMethod(method)) {
          result = new HttpWebdav(method, uri);
        } else {
          throw new IllegalArgumentException(String.format("Unexpected method: '%s'", method));
        }
    }

    return result;
  }

  public static HTTPSampleResult updateSampleResultForResourceInCache(HTTPSampleResult res) {
    CachedResourceMode cachedResourceMode = CachedResourceMode.valueOf(JMeterUtils
        .getPropDefault("cache_manager.cached_resource_mode",
            CachedResourceMode.RETURN_NO_SAMPLE.toString()));
    String returnMessage;
    switch (cachedResourceMode) {
      case RETURN_NO_SAMPLE:
        return null;
      case RETURN_200_CACHE:
        returnMessage = JMeterUtils.getPropDefault("RETURN_200_CACHE.message", "(ex cache)");
        res.sampleEnd();
        res.setResponseCodeOK();
        res.setResponseMessage(returnMessage);
        res.setSuccessful(true);
        return res;
      case RETURN_CUSTOM_STATUS:
        String returnStatusCode = JMeterUtils.getProperty("RETURN_CUSTOM_STATUS.code");
        returnMessage = JMeterUtils.getPropDefault("RETURN_CUSTOM_STATUS.message", "(ex cache)");
        res.sampleEnd();
        res.setResponseCode(returnStatusCode);
        res.setResponseMessage(returnMessage);
        res.setSuccessful(true);
        return res;
      default:
        throw new IllegalStateException("Unknown CACHED_RESOURCE_MODE");
    }
  }

  public static org.apache.http.Header[] convertJettyHeadersToApacheHeaders(HttpFields fields) {
    return fields.stream()
        .map(h -> new BasicHeader(h.getName(), h.getValue()))
        .toArray(org.apache.http.Header[]::new);
  }

  public static HttpResponse createApacheHttpResponseFromJettyContentResponse(
      ContentResponse contentResponse) {
    HttpResponse httpResponse = new BasicHttpResponse(new ProtocolVersion("HTTP/2", 2, 2),
        contentResponse.getStatus(),
        contentResponse.getReason());
    httpResponse
        .addHeader(HTTPConstants.VARY, contentResponse.getHeaders().get(HTTPConstants.VARY));
    httpResponse.addHeader(HTTPConstants.LAST_MODIFIED,
        contentResponse.getHeaders().get(HTTPConstants.LAST_MODIFIED));
    httpResponse
        .addHeader(HTTPConstants.EXPIRES,
            contentResponse.getHeaders().get(HTTPConstants.EXPIRES));
    httpResponse
        .addHeader(HTTPConstants.ETAG, contentResponse.getHeaders().get(HTTPConstants.ETAG));
    httpResponse.addHeader(HTTPConstants.CACHE_CONTROL,
        contentResponse.getHeaders().get(HTTPConstants.CACHE_CONTROL));
    httpResponse
        .addHeader(HTTPConstants.DATE, contentResponse.getHeaders().get(HTTPConstants.DATE));

    return httpResponse;
  }

  private enum CachedResourceMode {
    RETURN_200_CACHE(),
    RETURN_NO_SAMPLE(),
    RETURN_CUSTOM_STATUS()
  }

}
