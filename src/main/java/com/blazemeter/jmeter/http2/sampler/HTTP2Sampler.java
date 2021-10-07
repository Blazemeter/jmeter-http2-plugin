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
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Sampler extends HTTPSamplerBase implements LoopIterationListener,
    ThreadListener {

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2Sampler.class);
  private static final ThreadLocal<Map<HTTP2ClientKey, HTTP2JettyClient>> CONNECTIONS =
      ThreadLocal
          .withInitial(HashMap::new);
  private transient Map<HTTP2ClientKey, HTTP2JettyClient> threadClonedConnections;
  private final Callable<HTTP2JettyClient> clientFactory;

  public HTTP2Sampler() {
    setName("HTTP2 Sampler");
    setMethod(HTTPConstants.GET);
    clientFactory = this::getClient;
  }

  @VisibleForTesting
  public HTTP2Sampler(Callable<HTTP2JettyClient> clientFactory) {
    this.clientFactory = clientFactory;
  }

  @Override
  protected HTTPSampleResult sample(URL url, String method, boolean areFollowingRedirect,
      int depth) {
    try {
      HTTP2JettyClient client = clientFactory.call();
      HTTPSampleResult result = client.sample(this, url, method, areFollowingRedirect, depth);
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.error("The sampling has been interrupted", e);
      return errorResult(e, new HTTPSampleResult());
    } catch (Exception e) {
      LOG.error("Error while sampling", e);
      return errorResult(e, new HTTPSampleResult());
    }
  }

  @Override
  public Object clone() {
    HTTP2Sampler clonedElement = (HTTP2Sampler) super.clone();
    clonedElement.threadClonedConnections = new HashMap<>(CONNECTIONS.get());
    return clonedElement;
  }

  private HTTP2JettyClient buildClient() throws Exception {
    HTTP2JettyClient client = new HTTP2JettyClient();
    client.start();
    CONNECTIONS.get().put(buildConnectionKey(), client);
    return client;
  }

  private HTTP2ClientKey buildConnectionKey() throws MalformedURLException {
    return new HTTP2ClientKey(getUrl(), !getProxyHost().isEmpty(), getProxyScheme(), getProxyHost(),
        getProxyPortInt());
  }

  private HTTP2JettyClient getClient() throws Exception {
    updateConnectionsIfCloned();
    Map<HTTP2ClientKey, HTTP2JettyClient> clients = CONNECTIONS.get();
    HTTP2ClientKey key = buildConnectionKey();
    return clients.containsKey(key) ? clients.get(key)
        : buildClient();
  }

  private void updateConnectionsIfCloned() {
    if (threadClonedConnections != null) {
      CONNECTIONS.remove();
      CONNECTIONS.set(threadClonedConnections);
    }
  }

  public HTTPSampleResult resultProcessing(final boolean pAreFollowingRedirect,
      final int frameDepth, final HTTPSampleResult pRes) {
    return super.resultProcessing(pAreFollowingRedirect, frameDepth, pRes);
  }

  @Override
  public void iterationStart(LoopIterationEvent iterEvent) {
    JMeterVariables jMeterVariables = JMeterContextService.getContext().getVariables();
    if (!jMeterVariables.isSameUserOnNextIteration()) {
      closeConnections();
    } else {
      clearClientAuthenticationResultsIfClearEachIterationChecked();
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

  private void clearClientAuthenticationResultsIfClearEachIterationChecked() {
    AuthManager authManager = getAuthManager();
    if (authManager.getClearEachIteration()) {
      Map<HTTP2ClientKey, HTTP2JettyClient> clients = CONNECTIONS.get();
      for (HTTP2JettyClient client : clients.values()) {
        client.clearClientAuthenticationResults();
      }
    }
  }

  @Override
  public void threadFinished() {
    closeConnections();
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
