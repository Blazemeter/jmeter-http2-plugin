package com.blazemeter.jmeter.http2.sampler;

import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;

//This class exist for backward compatibility purposes
@Deprecated
public class HTTP2Request extends AbstractSampler {

  public static final String POST_BODY_RAW = "HTTP2Request.postBodyRaw";
  public static final String ARGUMENTS = "HTTP2Request.Arguments";
  public static final String DOMAIN = "HTTP2Request.domain";
  public static final String PORT = "HTTPSampler.port";
  public static final String RESPONSE_TIMEOUT = "HTTP2Request.response_timeout";
  public static final String PROTOCOL = "HTTP2Request.protocol";
  public static final String CONTENT_ENCODING = "HTTP2Request.contentEncoding";
  public static final String PATH = "HTTP2Request.path";
  public static final String METHOD = "HTTP2Sampler.method";
  public static final String FOLLOW_REDIRECTS = "HTTP2Request.follow_redirects";
  public static final String AUTO_REDIRECTS = "HTTP2Request.auto_redirects";
  public static final String EMBEDDED_RESOURCES = "HTTPSampler.embedded_resources";
  public static final String EMBEDDED_URL_REGEX = "HTTPSampler.embedded_url_re";

  @Override
  public SampleResult sample(Entry e) {
    return null;
  }
}
