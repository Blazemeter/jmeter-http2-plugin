package com.blazemeter.jmeter.http2.sampler;

import com.blazemeter.jmeter.http2.core.HTTP2Client;
import com.blazemeter.jmeter.http2.core.HTTP2SampleResultBuilder;
import java.net.MalformedURLException;
import java.net.URL;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.eclipse.jetty.client.api.ContentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Sampler extends HTTPSamplerBase {

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2Sampler.class);

  public HTTP2Sampler() {
    setName("HTTP Sampler");
  }

  @Override
  protected HTTPSampleResult sample(URL url, String s, boolean b, int i) {
    HTTP2SampleResultBuilder resultBuilder = new HTTP2SampleResultBuilder();
    HTTP2Client client = new HTTP2Client();
    if (!getProxyHost().isEmpty()) {
      client.setProxy(getProxyHost(), getProxyPortInt(), getProxyScheme());
    }
    try {
      resultBuilder.withUrl(getUrl());
      ContentResponse contentResponse = client.doGet(getUrl());
      resultBuilder.withContentResponse(contentResponse);
    } catch (MalformedURLException e) {
      LOG.error("Error while parsing the URL", e);
      resultBuilder.withFailure(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.error("The sampling has been interrupted", e);
      resultBuilder.withFailure(e);
    } catch (Exception e) {
      LOG.error("Error while sampling", e);
      resultBuilder.withFailure(e);
    }
    return resultBuilder.build();
  }
}
