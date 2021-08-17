package com.blazemeter.jmeter.http2.sampler;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.protocol.http.util.HTTPFileArgs;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.eclipse.jetty.http.HttpFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Deprecated
public class HTTP2Request extends AbstractSampler implements ThreadListener, LoopIterationListener {

  public static final String ENCODING = StandardCharsets.ISO_8859_1.name();
  public static final String IP_SOURCE = "HTTP2Request.ipSource";
  public static final String IP_SOURCE_TYPE = "HTTP2Request.ipSourceType";
  // Embedded URLs must match this regex (if provided)
  public static final String EMBEDDED_URL_REGEX = "HTTPSampler.embedded_url_re";
  // Store MD5 hash instead of storing response
  public static final String MD5 = "HTTPSampler.md5";
  public static final String EMBEDDED_RESOURCES = "HTTPSampler.embedded_resources";
  public static final int SOURCE_TYPE_DEFAULT = HTTPSamplerBase.SourceType.HOSTNAME.ordinal();
  public static final String ARGUMENTS = "HTTP2Request.Arguments";
  public static final String POST_BODY_RAW = "HTTP2Request.postBodyRaw"; // TODO - belongs elsewhere
  public static final boolean POST_BODY_RAW_DEFAULT = false;
  public static final String DOMAIN = "HTTP2Request.domain";
  public static final String RESPONSE_TIMEOUT = "HTTP2Request.response_timeout";
  public static final String FOLLOW_REDIRECTS = "HTTP2Request.follow_redirects";
  public static final String AUTO_REDIRECTS = "HTTP2Request.auto_redirects";
  public static final String SYNC_REQUEST = "HTTP2Request.sync_request";
  public static final String PROTOCOL = "HTTP2Request.protocol";
  public static final String REQUEST_ID = "HTTP2Request.request_id";
  /**
   * This is the encoding used for the content, i.e. the charset name, not the header
   * "Content-Encoding"
   */
  public static final String CONTENT_ENCODING = "HTTP2Request.contentEncoding";
  public static final String PATH = "HTTP2Request.path";
  public static final String METHOD = "HTTP2Sampler.method";
  public static final String DEFAULT_METHOD = "GET";
  public static final String PORT = "HTTPSampler.port";
  public static final String UNSPECIFIED_PORT_AS_STRING = "0";
  private static final long serialVersionUID = 5859387434748163229L;
  private static final Logger LOG = LoggerFactory.getLogger(HTTP2Request.class);
  private static final String DEFAULT_RESPONSE_TIMEOUT = "20000"; //20 sec
  private static final String PROTOCOL_SCHEME = "HTTP2Sampler.scheme";
  private static final String FILE_ARGS = "HTTPsampler.Files";
  private static final String HEADER_MANAGER = "HTTP2Request.header_manager";
  private static final String COOKIE_MANAGER = "HTTP2Request.cookie_manager";
  private static final String CACHE_MANAGER = "HTTP2Request.cache_manager";
  private static final String HTTP_PREFIX = HTTPConstants.PROTOCOL_HTTP + "://";
  private static final String HTTPS_PREFIX = HTTPConstants.PROTOCOL_HTTPS + "://";
  private static final String DEFAULT_PROTOCOL = HTTPConstants.PROTOCOL_HTTPS;
  /**
   * A number to indicate that the port has not been set.
   */
  private static final int UNSPECIFIED_PORT = 0;
  private static final String NON_HTTP_RESPONSE_CODE = "Non HTTP response code";
  private static final String NON_HTTP_RESPONSE_MESSAGE = "Non HTTP response message";
  private static final String QRY_SEP = "&";
  private static final String QRY_PFX = "?";

  public HTTP2Request() {
    setName("HTTP2 Request");
  }

  @Override
  public void setName(String name) {
    if (name != null) {
      setProperty(TestElement.NAME, name);
    }
  }

  @Override
  public String getName() {
    return getPropertyAsString(TestElement.NAME);
  }

  @Override
  public SampleResult sample(Entry entry) {
    return sample();
  }

  /**
   * Perform a sample, and return the results.
   *
   * @return results of the sampling
   */
  public SampleResult sample() {
    LOG.warn("Implementation not supported");
    return null;
  }

  private boolean getSendParameterValuesAsPostBody() {
    if (getPostBodyRaw()) {
      return true;
    } else {
      boolean hasArguments = false;
      for (JMeterProperty jMeterProperty : getArguments()) {
        hasArguments = true;
        HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
        if (arg.getName() != null && arg.getName().length() > 0) {
          return false;
        }
      }
      return hasArguments;
    }
  }

  private boolean getPostBodyRaw() {
    return getPropertyAsBoolean(POST_BODY_RAW, POST_BODY_RAW_DEFAULT);
  }

  private String getContentEncoding() {
    String prop = getPropertyAsString(HTTP2Request.CONTENT_ENCODING);
    return (prop == null || prop.isEmpty()) ? ENCODING : prop;
  }

  private String buildConnectionId(String host, int port) {
    return host + ": " + port;
  }

  private String getMethod() {
    return getPropertyAsString(METHOD);
  }


  /**
   * Tell whether the default port for the specified protocol is used.
   *
   * @return true if the default port number for the protocol is used, false otherwise
   */
  private boolean isProtocolDefaultPort() {
    final int port = getPortIfSpecified();
    final String protocol = getProtocol();
    //CHECKSTYLE:OFF
    boolean isDefaultHTTPPort = HTTPConstants.PROTOCOL_HTTP
        .equalsIgnoreCase(protocol)
        && port == HTTPConstants.DEFAULT_HTTP_PORT;
    boolean isDefaultHTTPSPort = HTTPConstants.PROTOCOL_HTTPS
        .equalsIgnoreCase(protocol)
        && port == HTTPConstants.DEFAULT_HTTPS_PORT;
    //CHECKSTYLE:ON
    return port == UNSPECIFIED_PORT || isDefaultHTTPPort || isDefaultHTTPSPort;
  }

  /**
   * Get the port number from the port string, allowing for trailing blanks.
   *
   * @return port number or UNSPECIFIED_PORT (== 0)
   */
  private int getPortIfSpecified() {
    //CHECKSTYLE:OFF
    String port_s = getPropertyAsString(PORT, UNSPECIFIED_PORT_AS_STRING); // $NON-NLS-1$
    //CHECKSTYLE:ON
    try {
      return Integer.parseInt(port_s.trim());
    } catch (NumberFormatException e) {
      return UNSPECIFIED_PORT;
    }
  }

  /**
   * Get the port; apply the default for the protocol if necessary.
   *
   * @return the port number, with default applied if required.
   */
  public int getPort() {
    final int port = getPortIfSpecified();
    if (port == UNSPECIFIED_PORT) {
      String protocol = getProtocol();
      if (HTTPConstants.PROTOCOL_HTTPS.equalsIgnoreCase(protocol)) {
        return HTTPConstants.DEFAULT_HTTPS_PORT;
      } else if (HTTPConstants.PROTOCOL_HTTP.equalsIgnoreCase(protocol)) {
        return HTTPConstants.DEFAULT_HTTP_PORT;
      } else {
        LOG.error("Unexpected protocol: " + protocol);
      }
    }
    return port;
  }

  public void setProtocol(String value) {
    setProperty(PROTOCOL, value);
  }

  public String getProtocol() {
    String protocol = getPropertyAsString(PROTOCOL);
    if (protocol == null || protocol.length() == 0) {
      return DEFAULT_PROTOCOL;
    }
    return protocol;
  }

  /**
   * Determine if the file should be sent as the entire Content body, i.e. without any additional
   * wrapping.
   *
   * @return true if specified file is to be sent as the body, i.e. there is a single file entry
   * which has a non-empty path and an empty Parameter name.
   */
  public boolean getSendFileAsPostBody() {
    // If there is one file with no parameter name, the file will be sent as post body.
    HTTPFileArg[] files = getHTTPFiles();
    return (files.length == 1)
        && (files[0].getPath().length() > 0)
        && (files[0].getParamName().length() == 0);
  }

  private String getDomain() {
    return getPropertyAsString(DOMAIN);
  }

  private String getResponseTimeout() {
    return getPropertyAsString(RESPONSE_TIMEOUT);
  }

  private String getContextPath() {
    return getPropertyAsString(PATH);
  }

  private Arguments getArguments() {
    return (Arguments) getProperty(ARGUMENTS).getObjectValue();
  }

  //CHECKSTYLE:OFF
  private HTTPFileArgs getHTTPFileArgs() {
    return (HTTPFileArgs) getProperty(FILE_ARGS).getObjectValue();
  }
  //CHECKSTYLE:ON

  //CHECKSTYLE:OFF
  public HTTPFileArg[] getHTTPFiles() { // $NON-NLS-1$
    final HTTPFileArgs fileArgs = getHTTPFileArgs();
    return fileArgs == null ? new HTTPFileArg[]{} : fileArgs.asArray();
  }
  //CHECKSTYLE:ON

  public Boolean isEmbeddedResources() {
    return getPropertyAsBoolean(EMBEDDED_RESOURCES);
  }

  private Boolean isSyncRequest() {
    return getPropertyAsBoolean(SYNC_REQUEST);
  }

  public HeaderManager getHeaderManager() {
    return (HeaderManager) getProperty(HTTP2Request.HEADER_MANAGER).getObjectValue();
  }

  /**
   * Get the regular expression URLs must match.
   *
   * @return regular expression (or empty) string
   */
  public String getEmbeddedUrlRE() {
    return getPropertyAsString(EMBEDDED_URL_REGEX, "");
  }

  @Override
  public void addTestElement(TestElement el) {
    if (el instanceof HeaderManager) {
      setHeaderManager((HeaderManager) el);
    } else if (el instanceof CookieManager) {
      setCookieManager((CookieManager) el);
    } else if (el instanceof CacheManager) {
      setCacheManager((CacheManager) el);
    } else {
      super.addTestElement(el);
    }
  }

  private void setHeaderManager(HeaderManager value) {
    HeaderManager mgr = getHeaderManager();
    if (mgr != null) {
      value = mgr.merge(value);
      LOG.debug("Existing HeaderManager '{}' merged with '{}'", mgr.getName(), value.getName());
      value.getHeaders().forEach(h -> LOG.debug("{} = {}", h.getName(), h.getStringValue()));
    }
    setProperty(new TestElementProperty(HEADER_MANAGER, value));
  }

  // private method to allow AsyncSample to reset the value without performing checks
  private void setCookieManagerProperty(CookieManager value) {
    setProperty(new TestElementProperty(COOKIE_MANAGER, value));
  }

  private void setCookieManager(CookieManager value) {
    CookieManager mgr = getCookieManager();
    if (mgr != null) {
      LOG.warn("Existing CookieManager {} superseded by {}", mgr.getName(), value.getName());
    }
    setCookieManagerProperty(value);
  }

  public CookieManager getCookieManager() {
    return (CookieManager) getProperty(COOKIE_MANAGER).getObjectValue();
  }

  private void setCacheManager(CacheManager value) {
    CacheManager mgr = getCacheManager();
    if (mgr != null) {
      LOG.warn("Existing CacheManager {} superseded by {}", mgr.getName(), value.getName());
    }
    setCacheManagerProperty(value);
  }

  // private method to allow AsyncSample to reset the value without performing checks
  private void setCacheManagerProperty(CacheManager value) {
    setProperty(new TestElementProperty(CACHE_MANAGER, value));
  }

  public CacheManager getCacheManager() {
    return (CacheManager) getProperty(CACHE_MANAGER).getObjectValue();
  }

  @Override
  public void threadStarted() {
  }

  @Override
  public void threadFinished() {

  }

  private void saveConnectionCookies(HttpFields hdrsResponse, URL url,
      CookieManager cookieManager) {
    // hdrsResponse might be null if the request failed before getting any response
    if (cookieManager != null && hdrsResponse != null) {
      List<String> hdrs = hdrsResponse.getValuesList(HTTPConstants.HEADER_SET_COOKIE);
      for (String hdr : hdrs) {
        cookieManager.addCookieFromHeader(hdr, url);
      }
    }
  }

  public void setEmbeddedResources(boolean embeddedResources) {
    setProperty(EMBEDDED_RESOURCES, embeddedResources, false);
  }

  public void setEmbeddedUrlRE(String regex) {
    setProperty(new StringProperty(EMBEDDED_URL_REGEX, regex));
  }

  @Override
  public void iterationStart(LoopIterationEvent iterEvent) {

  }
}

