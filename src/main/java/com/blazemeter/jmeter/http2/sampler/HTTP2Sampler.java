package com.blazemeter.jmeter.http2.sampler;

import com.blazemeter.jmeter.http2.core.HTTP2Implementation;
import com.helger.commons.annotation.VisibleForTesting;
import java.io.IOException;
import java.io.InputStream;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Sampler extends HTTPSamplerBase implements LoopIterationListener, ThreadListener {

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2Sampler.class);
  private static final ThreadLocal<Map<HTTP2ClientKey, HTTP2Implementation>> CONNECTIONS =
      ThreadLocal
          .withInitial(HashMap::new);
  private transient Map<HTTP2ClientKey, HTTP2Implementation> threadClonedConnectios;
  private final Callable<HTTP2Implementation> clientFactory;

  public HTTP2Sampler() {
    setName("HTTP2 Sampler");
    setMethod(HTTPConstants.GET);
    clientFactory = this::getClient;
  }

  @VisibleForTesting
  public HTTP2Sampler(Callable<HTTP2Implementation> clientFactory) {
    this.clientFactory = clientFactory;
  }

  @Override
  protected HTTPSampleResult sample(URL url, String method, boolean areFollowingRedirect,
      int depth) {
    try {
      HTTP2Implementation client = clientFactory.call();
      return client.sample(this, url, method, areFollowingRedirect, depth);
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
    clonedElement.threadClonedConnectios = new HashMap<>(CONNECTIONS.get());
    return clonedElement;
  }

  private HTTP2Implementation buildClient() throws Exception {
    HTTP2Implementation client = new HTTP2Implementation();
    client.start();
    CONNECTIONS.get().put(buildConnectionKey(), client);
    return client;
  }

  private HTTP2ClientKey buildConnectionKey() throws MalformedURLException {
    return new HTTP2ClientKey(getUrl(), !getProxyHost().isEmpty(), getProxyScheme(), getProxyHost(),
        getProxyPortInt());
  }

  private HTTP2Implementation getClient() throws Exception {
    Map<HTTP2ClientKey, HTTP2Implementation> clients = threadClonedConnectios != null
        ? threadClonedConnectios
        : CONNECTIONS.get();
    HTTP2ClientKey key = buildConnectionKey();
    return clients.containsKey(key) ? clients.get(key)
        : buildClient();
  }

  public HTTPSampleResult resultProcessing(final boolean pAreFollowingRedirect,
      final int frameDepth, final HTTPSampleResult pRes) {
    return super.resultProcessing(pAreFollowingRedirect, frameDepth, pRes);
  }

  public byte[] readResponse(SampleResult res, InputStream instream,
      long responseContentLength) throws IOException {
    return super.readResponse(res, instream, responseContentLength);
  }

  @Override
  public void iterationStart(LoopIterationEvent iterEvent) {
    JMeterVariables jMeterVariables = JMeterContextService.getContext().getVariables();
    if (!jMeterVariables.isSameUserOnNextIteration()) {
      closeConnections();
    }
  }

  private void closeConnections() {
    Map<HTTP2ClientKey, HTTP2Implementation> clients = CONNECTIONS.get();
    for (HTTP2Implementation client : clients.values()) {
      try {
        client.stop();
      } catch (Exception e) {
        LOG.error("Error while closing connection", e);
      }
    }
    clients.clear();
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
