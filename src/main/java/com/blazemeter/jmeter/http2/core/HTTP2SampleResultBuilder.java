package com.blazemeter.jmeter.http2.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.eclipse.jetty.client.api.ContentResponse;

public class HTTP2SampleResultBuilder {

  private final HTTPSampleResult result;

  public HTTP2SampleResultBuilder() {
    result = new HTTPSampleResult();
    result.sampleStart();
  }

  public HTTP2SampleResultBuilder withFailure(Exception e) {
    result.setSuccessful(false);
    result.setResponseCode(e.getClass().getName());
    result.setResponseMessage(e.getMessage());
    StringWriter sw = new StringWriter();
    e.printStackTrace(new PrintWriter(sw));
    result.setResponseData(sw.toString(), SampleResult.DEFAULT_HTTP_ENCODING);
    return this;
  }

  public HTTP2SampleResultBuilder withUrl(URL url) {
    result.setURL(url);
    return this;
  }

  public HTTPSampleResult build() {
    result.sampleEnd();
    return result;
  }

  public void withContentResponse(ContentResponse contentResponse) {
    result.setSuccessful(contentResponse.getStatus() >= 200 && contentResponse.getStatus() <= 399);
    result.setResponseCode(String.valueOf(contentResponse.getStatus()));
    result.setResponseHeaders(contentResponse.getHeaders().asString());
    result.setResponseData(contentResponse.getContentAsString(), contentResponse.getEncoding());
  }
}
