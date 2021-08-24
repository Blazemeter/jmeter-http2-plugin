package com.blazemeter.jmeter.http2.sampler;

import com.blazemeter.jmeter.http2.core.HTTP2Client;
import com.blazemeter.jmeter.http2.core.HTTP2SampleResultBuilder;
import com.blazemeter.jmeter.http2.core.HTTP2StateListener;
import com.helger.commons.annotation.VisibleForTesting;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Sampler extends HTTPSamplerBase implements LoopIterationListener, ThreadListener {

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2Sampler.class);
  private static final ThreadLocal<Map<HTTP2ClientKey, HTTP2Client>> CONNECTIONS = ThreadLocal
      .withInitial(HashMap::new);
  private HTTP2Client client;

  public HTTP2Sampler() {
    setName("HTTP2 Sampler");
  }

  @VisibleForTesting
  public HTTP2Sampler(HTTP2Client client) {
    this();
    this.client = client;
  }

  @Override
  public HTTPSampleResult sample() {
    return sample(null, "", false, 0);
  }

  @Override
  protected HTTPSampleResult sample(URL url, String s, boolean b, int i) {
    HTTP2SampleResultBuilder resultBuilder = new HTTP2SampleResultBuilder();
    try {
      resultBuilder.withLabel(getSampleLabel(resultBuilder)).withMethod(getMethod())
          .withUrl(getUrl());
      if (client == null) {
        client = getClient();
        client.setHTTP2StateListener(new HTTP2StateListener() {
          @Override
          public void onConnectionEnds() {
            resultBuilder.withConnectionEnd();
          }

          @Override
          public void onLatencyEnds() {
            resultBuilder.withLatencyEnd();
          }
        });
      }
      if (!getProxyHost().isEmpty()) {
        client.setProxy(getProxyHost(), getProxyPortInt(), getProxyScheme());
      }
      if (getMethod().equals(HTTPConstants.GET)) {
        Request request = client.createRequest(getUrl());
        resultBuilder.withRequestHeaders(
            request.getHeaders() != null ? request.getHeaders().asString() : "");
        ContentResponse contentResponse = request.send();
        resultBuilder.withContentResponse(contentResponse);
      } else {
        throw new UnsupportedOperationException(
            String.format("Method %s is not supported", getMethod()));
      }
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

  private String getSampleLabel(HTTP2SampleResultBuilder resultBuilder)
      throws MalformedURLException {
    if (resultBuilder.isRenameSampleLabel()) {
      return getName();
    } else {
      return getUrl().toString();
    }
  }

  private HTTP2Client buildClient() throws Exception {
    HTTP2Client client = new HTTP2Client();
    client.start();
    CONNECTIONS.get().put(buildConnectionKey(), client);
    return client;
  }

  private HTTP2ClientKey buildConnectionKey() throws MalformedURLException {
    return new HTTP2ClientKey(getUrl(), !getProxyHost().isEmpty(), getProxyScheme(), getProxyHost(),
        getProxyPortInt());
  }

  private HTTP2Client getClient() throws Exception {
    Map<HTTP2ClientKey, HTTP2Client> clients = CONNECTIONS.get();
    HTTP2ClientKey key = buildConnectionKey();
    return clients.containsKey(key) ? clients.get(key)
        : buildClient();
  }

  @Override
  public void iterationStart(LoopIterationEvent iterEvent) {
    JMeterVariables jMeterVariables = JMeterContextService.getContext().getVariables();
    if (!jMeterVariables.isSameUserOnNextIteration()) {
      closeConnections();
    }

  }

  private void closeConnections() {
    Map<HTTP2ClientKey, HTTP2Client> clients = CONNECTIONS.get();
    for (HTTP2Client client : clients.values()) {
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
