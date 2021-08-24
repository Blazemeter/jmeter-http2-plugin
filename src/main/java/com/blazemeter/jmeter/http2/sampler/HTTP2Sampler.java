package com.blazemeter.jmeter.http2.sampler;

import com.blazemeter.jmeter.http2.core.HTTP2Client;
import com.blazemeter.jmeter.http2.core.HTTP2SampleResultBuilder;
import com.helger.commons.annotation.VisibleForTesting;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.eclipse.jetty.client.api.ContentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Sampler extends HTTPSamplerBase implements LoopIterationListener, ThreadListener {

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2Sampler.class);
  private static final ThreadLocal<Map<String, HTTP2Client>> CONNECTIONS = ThreadLocal
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
      if (client == null) {
        client = getClient();
      }
      if (!getProxyHost().isEmpty()) {
        client.setProxy(getProxyHost(), getProxyPortInt(), getProxyScheme());
      }
      if (getMethod().equals(HTTPConstants.GET)) {
        resultBuilder.withUrl(getUrl());
        ContentResponse contentResponse = client.doGet(getUrl());
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

  private HTTP2Client buildClient() throws Exception {
    HTTP2Client client = new HTTP2Client();
    client.start();
    CONNECTIONS.get().put(buildConnectionId(), client);
    return client;
  }

  private String buildConnectionId() {
    return getDomain() + ":" + getPort();
  }

  private HTTP2Client getClient() throws Exception {
    Map<String, HTTP2Client> clients = CONNECTIONS.get();
    return clients.containsKey(buildConnectionId()) ? clients.get(buildConnectionId())
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
    for (HTTP2Client client : CONNECTIONS.get().values()) {
      try {
        client.stop();
      } catch (Exception e) {
        LOG.error("Error while closing connection", e);
      }
    }
    CONNECTIONS.get().clear();
  }

  @Override
  public void threadFinished() {
    closeConnections();
  }
}
