package com.blazemeter.jmeter.http2.proxy;

import static org.apache.jmeter.util.JMeterUtils.getPropDefault;

import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.gui.HTTP2SamplerGui;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.proxy.AbstractSamplerCreator;
import org.apache.jmeter.protocol.http.proxy.DefaultSamplerCreator;
import org.apache.jmeter.protocol.http.proxy.HttpRequestHdr;
import org.apache.jmeter.protocol.http.proxy.SamplerCreator;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2SampleCreator extends AbstractSamplerCreator {

  private static final String PROXY_ENABLED = "HTTP2Sampler.proxy_enabled";
  private static final Logger LOG = LoggerFactory.getLogger(HTTP2SampleCreator.class);

  private static final SamplerCreator DEFAULT_SAMPLER_CREATOR = new DefaultSamplerCreator();

  private final boolean proxyEnabled = getPropDefault(PROXY_ENABLED, false);

  @Override
  public String[] getManagedContentTypes() {
    if (!proxyEnabled) {
      LOG.info("HTTP2 Sample disable by default for proxy recording");
      LOG.info("Enable HTTP2 for proxy recording with property {}=true", PROXY_ENABLED);
      return ArrayUtils.EMPTY_STRING_ARRAY;
    }
    LOG.debug("getManagedContentTypes()");
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
    LOG.debug(contentTypesList.toArray().toString());
    return contentTypesList.toArray(new String[0]);
  }

  @Override
  public HTTPSamplerBase createSampler(HttpRequestHdr httpRequestHdr, Map<String, String> map,
                                       Map<String, String> map1) {
    LOG.debug("createSampler()");

    LOG.debug(httpRequestHdr.getUrl());

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
    LOG.debug("populateSampler()");
    // Force the default sampler gui to HTTP2
    LOG.debug(httpRequestHdr.getUrl());
    LOG.debug(httpRequestHdr.getRawPostData().toString());

    if (httpRequestHdr.getHeaderManager() != null) {
      HeaderManager hm = httpRequestHdr.getHeaderManager();

      Header ae = hm.getFirstHeaderNamed("Accept-Encoding");
      if (ae != null) {
        String acceptEncoding = ae.getValue();
        acceptEncoding = acceptEncoding.replace(", br", "").replace("br", "");
        hm.getHeaders().remove("Accept-Encoding");
        Header h = new Header();
        h.setName("Accept-Encoding");
        h.setValue(acceptEncoding);
        hm.add(h);
      }
    }

    DEFAULT_SAMPLER_CREATOR.populateSampler(httpSamplerBase, httpRequestHdr, map, map1);

  }

}
