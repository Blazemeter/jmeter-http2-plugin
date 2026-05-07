package com.blazemeter.jmeter.http2.proxy;

import static com.blazemeter.jmeter.http2.core.LowLevelDebugLog.lowLevelDebug;
import static org.apache.jmeter.util.JMeterUtils.getPropDefault;

import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.gui.HTTP2SamplerGui;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.jmeter.protocol.http.proxy.AbstractSamplerCreator;
import org.apache.jmeter.protocol.http.proxy.DefaultSamplerCreator;
import org.apache.jmeter.protocol.http.proxy.HttpRequestHdr;
import org.apache.jmeter.protocol.http.proxy.SamplerCreator;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2SampleCreator extends AbstractSamplerCreator {

  /**
   * Controls whether this creator participates in JMeter's HTTP(S) Test Script Recorder. When
   * {@code true} (default), the standard HTTP sampler path is used for recorded samples; when
   * {@code false}, recorded traffic is built as {@link HTTP2Sampler} elements.
   */
  public static final String PROXY_ENABLED = "HTTP2Sampler.proxy_enabled"; // $NON-NLS-1$

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2SampleCreator.class);

  private static final SamplerCreator DEFAULT_SAMPLER_CREATOR = new DefaultSamplerCreator();

  private final boolean proxyEnabled = getPropDefault(PROXY_ENABLED, true);

  @Override
  public String[] getManagedContentTypes() {
    if (proxyEnabled) {
      LOG.info("BlazeMeter's HTTP is enabled by default for Proxy Recording.");
      LOG.info("Disable BlazeMeter HTTP for Proxy Recording with property {}=false",
          PROXY_ENABLED);
      return ArrayUtils.EMPTY_STRING_ARRAY;
    }
    lowLevelDebug("getManagedContentTypes()");
    String[] contentTypes = new String[] {
        "text/plain", "text/html", "text/xml", "application/xhtml+xml", "application/octet-stream",
        "application/x-www-form-urlencoded",
        "text/css", "text/javascript",
        "text/csv", "application/json", "application/ld+json",
        "application/xml", "application/atom+xml",
        "application/gzip", "application/zip", "application/x-7z-compressed", "application/x-tar",
        "image/gif", "image/bmp", "image/jpeg", "image/pn", "image/avif", "audio/aac",
        "image/svg+xml",
        "font/ttf", "font/woff", "font/woff2", "font/otf",
        null};
    String[] charsets = new String[] {"UTF-8"};
    // Create a list with all default content types and the combinations with different charsets
    ArrayList<String> contentTypesList = new ArrayList<>();
    contentTypesList.addAll(Arrays.asList(contentTypes));
    for (String contentType : contentTypes) {
      for (String charset : charsets) {
        // With space
        contentTypesList.add(contentType + "; charset=" + charset);
        // Without space
        contentTypesList.add(contentType + ";charset=" + charset);
      }
    }
    lowLevelDebug("managed content types: {}", contentTypesList);
    return contentTypesList.toArray(new String[0]);
  }

  @Override
  public HTTPSamplerBase createSampler(HttpRequestHdr httpRequestHdr, Map<String, String> map,
                                       Map<String, String> map1) {
    lowLevelDebug("createSampler()");

    lowLevelDebug("url: {}", httpRequestHdr.getUrl());

    HTTP2Sampler sampler = new HTTP2Sampler();

    sampler.setProperty(TestElement.GUI_CLASS, HTTP2SamplerGui.class.getName());

    // Defaults
    sampler.setHttp1UpgradeEnabled(false);
    sampler.setFollowRedirects(false);
    sampler.setUseKeepAlive(true);

    return sampler;
  }

  @Override
  public void populateSampler(HTTPSamplerBase httpSamplerBase, HttpRequestHdr httpRequestHdr,
                              Map<String, String> map, Map<String, String> map1) throws Exception {
    lowLevelDebug("populateSampler()");
    // Force the default sampler gui to HTTP2
    lowLevelDebug("url: {}", httpRequestHdr.getUrl());
    lowLevelDebug("raw post data: {}", httpRequestHdr.getRawPostData());

    // In Jetty 12, Brotli compression is now supported
    // We no longer need to remove "br" from Accept-Encoding header
    // The HTTP2JettyClient will handle Brotli decoding automatically
    // when the server responds with Content-Encoding: br

    DEFAULT_SAMPLER_CREATOR.populateSampler(httpSamplerBase, httpRequestHdr, map, map1);

  }

}
