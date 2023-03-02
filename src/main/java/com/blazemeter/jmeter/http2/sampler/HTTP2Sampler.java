package com.blazemeter.jmeter.http2.sampler;

import static org.apache.jmeter.util.JMeterUtils.getPropDefault;

import com.blazemeter.jmeter.http2.core.HTTP2FutureResponseListener;
import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.helger.commons.annotation.VisibleForTesting;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.PatternSyntaxException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.protocol.http.parser.BaseParser;
import org.apache.jmeter.protocol.http.parser.LinkExtractorParseException;
import org.apache.jmeter.protocol.http.parser.LinkExtractorParser;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.ConversionUtils;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.util.JOrphanUtils;
import org.apache.oro.text.MalformedCachePatternException;
import org.apache.oro.text.regex.Pattern;
import org.apache.oro.text.regex.Perl5Matcher;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.api.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Sampler extends HTTPSamplerBase implements LoopIterationListener, ThreadListener {

  private static class OwnInheritableThreadLocal
      extends InheritableThreadLocal<Map<HTTP2ClientKey, HTTP2JettyClient>> {
    @Override
    protected Map<HTTP2ClientKey, HTTP2JettyClient> initialValue() {
      return new HashMap<>();
    }

    @Override
    protected Map<HTTP2ClientKey, HTTP2JettyClient> childValue(
        Map<HTTP2ClientKey, HTTP2JettyClient> parentValue) {
      return parentValue;
    }
  }

  public static final String SYNC_REQUEST = "HTTP2Sampler.sync_request";
  private static final Logger LOG = LoggerFactory.getLogger(HTTP2Sampler.class);
  /*
  private static final ThreadLocal<Map<HTTP2ClientKey, HTTP2JettyClient>> CONNECTIONS =
      ThreadLocal
          .withInitial(HashMap::new);
  */
  private static final OwnInheritableThreadLocal CONNECTIONS = new OwnInheritableThreadLocal();

  private static final boolean IGNORE_FAILED_EMBEDDED_RESOURCES =
      getPropDefault(
          "httpsampler.ignore_failed_embedded_resources", false); // $NON-NLS-1$

  private static final String HTTP1_UPGRADE_PROPERTY = "HTTP2Sampler.http1_upgrade";
  // Derive the mapping of content types to parsers
  private static final Map<String, String> PARSERS_FOR_CONTENT_TYPE = new ConcurrentHashMap<>();
  private static final String USER_AGENT = "User-Agent"; // $NON-NLS-1$
  private static final boolean USE_JAVA_REGEX = !getPropDefault(
      "jmeter.regex.engine", "oro").equalsIgnoreCase("oro");
  private static final String RESPONSE_PARSERS = // list of parsers
      JMeterUtils.getProperty("HTTPResponse.parsers"); //$NON-NLS-1$

  static {
    String[] parsers =
        JOrphanUtils.split(RESPONSE_PARSERS, " ", true); // returns empty array for null
    for (final String parser : parsers) {
      String classname = JMeterUtils.getProperty(parser + ".className"); //$NON-NLS-1$
      if (classname == null) {
        LOG.error("Cannot find .className property for {}, ensure you set property: '{}.className'",
            parser, parser);
        continue;
      }
      String typeList = JMeterUtils.getProperty(parser + ".types"); //$NON-NLS-1$
      if (typeList != null) {
        String[] types = JOrphanUtils.split(typeList, " ", true);
        for (final String type : types) {
          registerParser(type, classname);
        }
      } else {
        LOG.warn(
            "Cannot find .types property for {}, as a consequence parser " +
                "will not be used, to make it usable, define property:'{}.types'",
            parser, parser);
      }
    }
  }

  private final transient Callable<HTTP2JettyClient> clientFactory;
  private final boolean dumpAtThreadEnd = getPropDefault(
      "httpJettyClient.DumpAtThreadEnd", false);
  private boolean syncRequest = true;
  private HTTP2FutureResponseListener asyncListener;
  private int maxBufferSize;
  private int requestTimeout;
  private HTTPSampleResult result;

  public HTTP2Sampler() {
    setName("HTTP2 Sampler");
    setMethod(HTTPConstants.GET);
    clientFactory = this::getClient;
    this.syncRequest = getPropertyAsBoolean(SYNC_REQUEST, true);
  }

  @VisibleForTesting
  public HTTP2Sampler(Callable<HTTP2JettyClient> clientFactory) {
    this.clientFactory = clientFactory;
  }

  public void setSyncRequest(boolean sync) {
    this.syncRequest = sync;
  }

  private boolean isSyncRequest() {
    return this.syncRequest;
  }

  public HTTP2FutureResponseListener geFutureResponseListener() {
    return asyncListener;
  }

  public void setHttp1UpgradeEnabled(boolean http1UpgradeSelected) {
    setProperty(HTTP1_UPGRADE_PROPERTY, http1UpgradeSelected);
  }

  public boolean isHttp1UpgradeEnabled() {
    return getPropertyAsBoolean(HTTP1_UPGRADE_PROPERTY);
  }

  @Override
  protected HTTPSampleResult sample(URL url, String method, boolean areFollowingRedirect,
                                    int depth) {
    try {
      HTTP2JettyClient client = clientFactory.call();
      this.maxBufferSize = client.getMaxBufferSize();
      this.requestTimeout = client.getRequestTimeout();
      if (!isSyncRequest()) {
        if (Objects.isNull(this.asyncListener)) {
          this.result = buildResult(url, method); // Save the main result for next step
          HTTP2FutureResponseListener listener =
              new HTTP2FutureResponseListener(client.getMaxBufferSize());
          this.asyncListener = listener;
          HttpRequest req = client.sampleAsync(this, this.result, listener);
          req.send(listener); // Fire the Async
          return null;
        } else {
          // If there is a listener, it is processed using the result it had
          this.result = sampleFromListener(
              this.result, areFollowingRedirect, depth, this.asyncListener);
          return this.result;
        }
      } else {
        this.result = buildResult(url, method);
        return client.sample(this, this.result, areFollowingRedirect, depth);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (Objects.isNull(this.result)) {
        this.result = buildResult(url, method);
      }
      return buildErrorResult(e, this.result);
    } catch (Exception e) {
      if (Objects.isNull(this.result)) {
        this.result = buildResult(url, method);
      }
      return buildErrorResult(e, this.result);
    }
  }

  protected HttpRequest sampleAsync(HTTPSampleResult result, HTTP2FutureResponseListener listener)
      throws Exception {

    HTTP2JettyClient client = clientFactory.call();
    return client.sampleAsync(this, result, listener);

  }

  protected HTTPSampleResult sampleFromListener(HTTPSampleResult result,
                                                boolean areFollowingRedirect,
                                                int depth, HTTP2FutureResponseListener listener)
      throws Exception {
    HTTP2JettyClient client = clientFactory.call();
    return client.sampleFromListener(this, result, areFollowingRedirect,
        depth, listener);
  }

  private HTTPSampleResult buildResult(URL url, String method) {
    HTTPSampleResult result = new HTTPSampleResult();
    result.setSampleLabel(SampleResult.isRenameSampleLabel() ? getName() : url.toString());
    result.setHTTPMethod(method);
    result.setURL(url);
    return result;
  }

  private HTTPSampleResult buildErrorResult(Exception e, HTTPSampleResult result) {
    if (result.getStartTime() == 0) {
      result.sampleStart();
    }
    if (result.getEndTime() == 0) {
      if (!Objects.isNull(this.asyncListener) && this.asyncListener.getResponseEnd() != 0) {
        result.setEndTime(this.asyncListener.getResponseEnd());
      } else {
        result.sampleEnd();
      }
    }
    return errorResult(e, result);
  }

  private HTTP2JettyClient buildClient() throws Exception {
    HTTP2ClientKey connectionKey = buildConnectionKey();
    HTTP2JettyClient client = new HTTP2JettyClient(isHttp1UpgradeEnabled(),
        "http2[" + connectionKey.target + ":" + Thread.currentThread().getId() + "]");
    client.start();
    CONNECTIONS.get().put(connectionKey, client);
    return client;
  }

  private HTTP2ClientKey buildConnectionKey() throws MalformedURLException {
    return new HTTP2ClientKey(getUrl(), !getProxyHost().isEmpty(), getProxyScheme(), getProxyHost(),
        getProxyPortInt());
  }

  private HTTP2JettyClient getClient() throws Exception {
    Map<HTTP2ClientKey, HTTP2JettyClient> clients = CONNECTIONS.get();
    HTTP2ClientKey key = buildConnectionKey();
    return clients.containsKey(key) ? clients.get(key)
        : buildClient();
  }

  public HTTPSampleResult resultProcessing(final boolean pAreFollowingRedirect,
                                           final int frameDepth, final HTTPSampleResult pRes) {
    return super.resultProcessing(pAreFollowingRedirect, frameDepth, pRes);
  }

  static void registerParser(String contentType, String className) {
    LOG.info("Parser for {} is {}", contentType, className);
    PARSERS_FOR_CONTENT_TYPE.put(contentType, className);
  }

  private LinkExtractorParser getParser(HTTPSampleResult res)
      throws LinkExtractorParseException {
    String parserClassName =
        PARSERS_FOR_CONTENT_TYPE.get(res.getMediaType());
    if (!StringUtils.isEmpty(parserClassName)) {
      return BaseParser.getParser(parserClassName);
    }
    return null;
  }

  private String getUserAgent(HTTPSampleResult sampleResult) {
    String res = sampleResult.getRequestHeaders();
    int index = res.indexOf(USER_AGENT);
    if (index >= 0) {
      // see HTTPHC3Impl#getConnectionHeaders
      // see HTTPHC4Impl#getConnectionHeaders
      // see HTTPJavaImpl#getConnectionHeaders
      //': ' is used by JMeter to fill-in requestHeaders, see getConnectionHeaders
      final String userAgentPrefix = USER_AGENT + ": ";
      String userAgentHdr = res.substring(
          index + userAgentPrefix.length(),
          res.indexOf(
              '\n',
              // '\n' is used by JMeter to fill-in requestHeaders, see getConnectionHeaders
              index + userAgentPrefix.length() + 1));
      return userAgentHdr.trim();
    } else {
      if (LOG.isDebugEnabled()) {
        LOG.debug("No user agent extracted from requestHeaders:{}", res);
      }
      return null;
    }
  }

  private void setParentSampleSuccess(HTTPSampleResult res, boolean initialValue) {
    if (!IGNORE_FAILED_EMBEDDED_RESOURCES) {
      res.setSuccessful(initialValue);
      if (!initialValue) {
        StringBuilder detailedMessage = new StringBuilder(80);
        detailedMessage.append("Embedded resource download error:"); //$NON-NLS-1$
        for (SampleResult subResult : res.getSubResults()) {
          HTTPSampleResult httpSampleResult = (HTTPSampleResult) subResult;
          if (!httpSampleResult.isSuccessful()) {
            detailedMessage.append(httpSampleResult.getURL())
                .append(" code:") //$NON-NLS-1$
                .append(httpSampleResult.getResponseCode())
                .append(" message:") //$NON-NLS-1$
                .append(httpSampleResult.getResponseMessage())
                .append(", "); //$NON-NLS-1$
          }
        }
        res.setResponseMessage(detailedMessage.toString()); //$NON-NLS-1$
      }
    }
  }

  private static final class LazyJavaPatternCacheHolder {

    public static final LoadingCache<Pair<String, Integer>, java.util.regex.Pattern> INSTANCE =
        Caffeine
            .newBuilder()
            .maximumSize(getPropDefault("jmeter.regex.patterncache.size", 1000))
            .build(key -> {
              //noinspection MagicConstant
              return java.util.regex.Pattern.compile(key.getLeft(), key.getRight().intValue());
            });

    private LazyJavaPatternCacheHolder() {
      super();
    }

  }

  public static java.util.regex.Pattern compilePattern(String expression) {
    return compilePattern(expression, 0);
  }

  public static java.util.regex.Pattern compilePattern(String expression, int flags) {
    return LazyJavaPatternCacheHolder.INSTANCE.get(Pair.of(expression, Integer.valueOf(flags)));
  }

  private Predicate<URL> generateMatcherPredicate(String regex, String explanation,
                                                  boolean defaultAnswer) {
    if (StringUtils.isEmpty(regex)) {
      return s -> defaultAnswer;
    }
    if (USE_JAVA_REGEX) {
      try {
        java.util.regex.Pattern pattern = compilePattern(regex);
        return s -> pattern.matcher(s.toString()).matches();
      } catch (PatternSyntaxException e) {
        LOG.warn("Ignoring embedded URL {} string: {}", explanation, e.getMessage());
        return s -> defaultAnswer;
      }
    }
    try {
      Pattern pattern = JMeterUtils.getPattern(regex);
      Perl5Matcher matcher = JMeterUtils.getMatcher();
      return s -> matcher.matches(s.toString(), pattern);
    } catch (MalformedCachePatternException e) { // NOSONAR
      LOG.warn("Ignoring embedded URL {} string: {}", explanation, e.getMessage());
      return s -> defaultAnswer;
    }
  }

  private URL escapeIllegalURLCharacters(java.net.URL url) {
    if (url == null || "file".equals(url.getProtocol())) {
      return url;
    }
    try {
      return ConversionUtils.sanitizeUrl(url).toURL();
    } catch (Exception ex) { // NOSONAR
      LOG.error("Error escaping URL:'{}', message:{}", url, ex.getMessage());
      return url;
    }
  }

  @Override
  protected HTTPSampleResult downloadPageResources(final HTTPSampleResult pRes,
                                                   final HTTPSampleResult container,
                                                   final int frameDepth) {

    boolean orgSyncRequest = isSyncRequest();
    boolean interrupted = false;
    HTTPSampleResult res = pRes;
    Iterator<URL> urls = null;
    try {
      final byte[] responseData = res.getResponseData();
      if (responseData.length > 0) {  // Bug 39205
        final LinkExtractorParser parser = getParser(res);
        if (parser != null) {
          String userAgent = getUserAgent(res);
          urls = parser.getEmbeddedResourceURLs(userAgent, responseData, res.getURL(),
              res.getDataEncodingWithDefault());
        }
      }
    } catch (LinkExtractorParseException e) {
      e.printStackTrace(System.err);
      // Don't break the world just because this failed:
      res.addSubResult(errorResult(e, new HTTPSampleResult(res)));
      setParentSampleSuccess(res, false);
    }

    HTTPSampleResult lContainer = container;
    // Iterate through the URLs and download each image:
    if (urls != null && urls.hasNext()) {
      if (lContainer == null) {
        lContainer = new HTTPSampleResult(res);
        lContainer.addRawSubResult(res);
      }
      final HTTPSampleResult subres = lContainer;
      res = subres;

      // Get the URL matcher
      String allowRegex = "";
      String excludeRegex = "";
      Predicate<URL> allowPredicate = null;
      Predicate<URL> excludePredicate = null;
      try {
        allowRegex = getEmbeddedUrlRE();
        allowPredicate = generateMatcherPredicate(allowRegex, "allow", true);
        excludeRegex = getEmbededUrlExcludeRE();
        excludePredicate =
            generateMatcherPredicate(excludeRegex, "exclude", false);
      } catch (Exception ex) {
        ex.printStackTrace(System.err);
        throw ex;
      }
      // For concurrent get resources
      final List<URL> urlList = new ArrayList<>();

      int maxConcurrentDownloads = CONCURRENT_POOL_SIZE; // init with default value
      boolean isConcurrentDwn = isConcurrentDwn();

      if (isConcurrentDwn) {
        try {
          maxConcurrentDownloads = Integer.parseInt(getConcurrentPool());
        } catch (NumberFormatException nfe) {
          LOG.warn("Concurrent download resources selected, "// $NON-NLS-1$
              + "but pool size value is bad. Use default value"); // $NON-NLS-1$
        }

        // if the user choose a number of parallel downloads of 1
        // no need to use another thread, do the sample on the current thread
        if (maxConcurrentDownloads == 1) {
          LOG.warn("Number of parallel downloads set to 1, (sampler name={})", getName());
          isConcurrentDwn = false;
        }
      }

      setSyncRequest(!isConcurrentDwn); // Change default from main request based on sub request

      while (urls.hasNext()) {
        Object binURL = urls.next(); // See catch clause below
        try {
          URL url = (URL) binURL;
          if (url == null) {
            LOG.warn("Null URL detected (should not happen)");
          } else {
            try {
              url = escapeIllegalURLCharacters(url);
            } catch (Exception e) { // NOSONAR
              subres.addSubResult(
                  errorResult(new Exception(url.toString() + " is not a correct URI", e),
                      new HTTPSampleResult(res)));
              setParentSampleSuccess(subres, false);
              continue;
            }
            if (!allowPredicate.test(url)) {
              continue; // we have a pattern and the URL does not match, so skip it
            }
            if (excludePredicate.test(url)) {
              continue; // we have a pattern and the URL does not match, so skip it
            }
            try {
              url = url.toURI().normalize().toURL();
            } catch (MalformedURLException | URISyntaxException e) {
              subres.addSubResult(
                  errorResult(new Exception(url.toString() + " URI can not be normalized", e),
                      new HTTPSampleResult(subres)));
              setParentSampleSuccess(subres, false);
              continue;
            }

            if (isConcurrentDwn) {
              // if concurrent download emb. resources, add to a list for async gets later
              urlList.add(url);

            } else {
              // default: serial download embedded resources
              HTTPSampleResult binRes = sample(
                  url, HTTPConstants.GET, false, frameDepth + 1);
              subres.addSubResult(binRes);
              setParentSampleSuccess(subres,
                  subres.isSuccessful() && (binRes == null || binRes.isSuccessful()));
              try {
                Thread.sleep(10);
              } catch (InterruptedException e) {
                interrupted = true;
              }
            }
          }
        } catch (ClassCastException e) { // NOSONAR
          subres.addSubResult(errorResult(new Exception(binURL + " is not a correct URI", e),
              new HTTPSampleResult(subres)));
          setParentSampleSuccess(subres, false);
        }
        if (interrupted) {
          break;
        }
      }

      // IF for download concurrent embedded resources
      if (isConcurrentDwn && !urlList.isEmpty()) {
        final int[] processedUrls = {0};

        for (URL url : urlList) {
          try {
            String method = HTTPConstants.GET;
            HTTPSampleResult result = buildResult(url, method);

            HTTP2FutureResponseListener listener =
                new HTTP2FutureResponseListener(this.maxBufferSize) {
                  @Override
                  public void onComplete(Result compResult) {
                    super.onComplete(compResult);

                    String urlProcessing = this.getRequest().getURI().toString();

                    if (this.isDone() || this.isCancelled()) {
                      try {
                        HTTPSampleResult binRes = sampleFromListener(
                            result, false, frameDepth + 1, this);
                        subres.addSubResult(binRes);
                        setParentSampleSuccess(subres,
                            subres.isSuccessful() && (binRes == null
                                || binRes.isSuccessful()));

                      } catch (Exception e) {
                        subres.addSubResult(errorResult(new Exception(
                                "Error downloading URI:" + urlProcessing, e),
                            new HTTPSampleResult(subres)));
                        setParentSampleSuccess(subres, false);
                      }
                    }
                    processedUrls[0] += 1;
                  }
                };

            HttpRequest req = sampleAsync(result, listener);
            listener.setRequest(req);
            req.send(listener); // Execute the get in async way

          } catch (Exception ex) {
            subres.addSubResult(errorResult(new Exception(
                    "Error downloading URI:" + url, ex),
                new HTTPSampleResult(subres)));
            setParentSampleSuccess(subres, false);
          }
        }

        // Wait to finish the process of all urls
        int totUrls = urlList.size();
        // TODO: We need to implement a timeout for all the embedded resource process?
        int embeddedTimeout = 0; // Use the same timeout for response
        if (this.getResponseTimeout() > 0) {
          embeddedTimeout = this.getResponseTimeout();
        } else {
          embeddedTimeout = this.requestTimeout;
        }
        long start = System.currentTimeMillis();
        while (!interrupted) {
          if (processedUrls[0] >= totUrls) {
            break;
          }
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            interrupted = true;
          }
          if (embeddedTimeout > 0 && (System.currentTimeMillis() - start) >= embeddedTimeout) {
            // TODO: This doesn't stop the async execution, only allow to don't lock execution
            subres.addSubResult(errorResult(new Exception(
                    "Error downloading embedded resources, execution timeout"),
                new HTTPSampleResult(subres)));
            setParentSampleSuccess(subres, false);
            break;
          }
        }
      }
    }

    setSyncRequest(orgSyncRequest); // Restore the default setting to main request

    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    return res;
  }

  @Override
  public void iterationStart(LoopIterationEvent iterEvent) {
    this.asyncListener = null;
    JMeterVariables jMeterVariables = JMeterContextService.getContext().getVariables();
    if (!jMeterVariables.isSameUserOnNextIteration()) {
      clearUserStores();
    }
  }

  private void closeConnections() {
    Map<HTTP2ClientKey, HTTP2JettyClient> clients = CONNECTIONS.get();
    for (HTTP2JettyClient client : clients.values()) {
      try {
        client.stop();
      } catch (Exception e) {
        LOG.error("Error while closing connection", e);
      }
    }
    clients.clear();
  }

  private void dump() {
    Map<HTTP2ClientKey, HTTP2JettyClient> clients = CONNECTIONS.get();
    for (HTTP2JettyClient client : clients.values()) {
      try {
        LOG.debug(client.dump());
      } catch (Exception e) {
        LOG.error("Error while dump HTTP2JettyClient", e);
      }
    }
  }

  @Override
  public void testEnded() {
    super.testEnded();
    HTTP2JettyClient.clearBufferPool();
    System.gc(); // Force free memory
  }

  @Override
  public void threadFinished() {
    if (dumpAtThreadEnd) {
      dump();
    }
    closeConnections();
  }

  private void clearUserStores() {
    Map<HTTP2ClientKey, HTTP2JettyClient> clients = CONNECTIONS.get();
    for (HTTP2JettyClient client : clients.values()) {
      try {
        client.clearCookies();
        client.clearAuthenticationResults();
      } catch (Exception e) {
        LOG.error("Error while cleaning user store", e);
      }
    }
  }

  private static final class HTTP2ClientKey {

    private final String target;
    private final boolean hasProxy;
    private final String proxyScheme;
    private final String proxyHost;
    private final int proxyPort;

    private HTTP2ClientKey(URL url, boolean hasProxy, String proxyScheme, String proxyHost,
                           int proxyPort) {
      this.target = url.getProtocol() + "://" + url.getAuthority();
      this.hasProxy = hasProxy;
      this.proxyScheme = proxyScheme;
      this.proxyHost = proxyHost;
      this.proxyPort = proxyPort;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      HTTP2ClientKey that = (HTTP2ClientKey) o;
      return hasProxy == that.hasProxy &&
          proxyPort == that.proxyPort &&
          target.equals(that.target) &&
          proxyScheme.equals(that.proxyScheme) &&
          proxyHost.equals(that.proxyHost);
    }

    @Override
    public int hashCode() {
      return Objects.hash(target, hasProxy, proxyScheme, proxyHost, proxyPort);
    }
  }
}
