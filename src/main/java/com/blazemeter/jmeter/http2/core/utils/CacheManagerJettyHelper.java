package com.blazemeter.jmeter.http2.core.utils;

import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
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
import org.apache.jmeter.protocol.http.sampler.HTTPHC4Impl.HttpGetWithEntity;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HttpWebdav;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpFields;

public class CacheManagerJettyHelper {

  private static final CachedResourceMode CACHED_RESOURCE_MODE =
      CachedResourceMode.valueOf(
          JMeterUtils.getPropDefault("cache_manager.cached_resource_mode",
              CachedResourceMode.RETURN_NO_SAMPLE.toString()));
  private static final String RETURN_200_CACHE_MESSAGE =
      JMeterUtils.getPropDefault("RETURN_200_CACHE.message", "(ex cache)");
  private static final String RETURN_CUSTOM_STATUS_CODE =
      JMeterUtils.getProperty("RETURN_CUSTOM_STATUS.code");
  private static final String RETURN_CUSTOM_STATUS_MESSAGE =
      JMeterUtils.getProperty("RETURN_CUSTOM_STATUS.message");
  private static final String DEFAULT_EXPIRE_DATE = "Sat, 25 Sep 2041 00:00:00 GMT";

  public static HttpRequestBase createApacheHttpRequest(URI uri, String method,
      boolean areFollowingRedirect,
      HTTP2Sampler sampler) {
    HttpRequestBase result;
    switch (method) {
      case HTTPConstants.POST:
        result = new HttpPost(uri);
        break;
      case HTTPConstants.GET:
        if (!areFollowingRedirect
            && ((!sampler.hasArguments() && sampler.getSendFileAsPostBody())
            || sampler.getSendParameterValuesAsPostBody())) {
          result = new HttpGetWithEntity(uri);
        } else {
          result = new HttpGet(uri);
        }
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
