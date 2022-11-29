package com.blazemeter.jmeter.http2.sampler;

import com.blazemeter.jmeter.http2.core.HTTP2JettyClient;
import com.helger.commons.annotation.VisibleForTesting;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Sampler extends HTTPSamplerBase implements LoopIterationListener, ThreadListener {

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2Sampler.class);
  private static final ThreadLocal<Map<HTTP2ClientKey, HTTP2JettyClient>> CONNECTIONS =
      ThreadLocal
          .withInitial(HashMap::new);
  private static final String HTTP1_UPGRADE_PROPERTY = "HTTP2Sampler.http1_upgrade";
  private final transient Callable<HTTP2JettyClient> clientFactory;
  private final boolean dumpAtThreadEnd = JMeterUtils.getPropDefault(
      "httpJettyClient.DumpAtThreadEnd", false);

  public HTTP2Sampler() {
    setName("HTTP2 Sampler");
    setMethod(HTTPConstants.GET);
    clientFactory = this::getClient;
  }

  @VisibleForTesting
  public HTTP2Sampler(Callable<HTTP2JettyClient> clientFactory) {
    this.clientFactory = clientFactory;
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
    HTTPSampleResult result = buildResult(url, method);
    try {
      HTTP2JettyClient client = clientFactory.call();
      return client.sample(this, result, areFollowingRedirect, depth);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return buildErrorResult(e, result);
    } catch (Exception e) {
      return buildErrorResult(e, result);
    }
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
      result.sampleEnd();
    }
    return errorResult(e, result);
  }

  private HTTP2JettyClient buildClient() throws Exception {
    HTTP2JettyClient client = new HTTP2JettyClient(isHttp1UpgradeEnabled());
    client.start();
    CONNECTIONS.get().put(buildConnectionKey(), client);
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

  @Override
  public void iterationStart(LoopIterationEvent iterEvent) {
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
