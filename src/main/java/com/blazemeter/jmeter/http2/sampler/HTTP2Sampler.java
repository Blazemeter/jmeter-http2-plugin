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
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Sampler extends HTTPSamplerBase implements LoopIterationListener, ThreadListener {

  private static final Pattern PORT_PATTERN = Pattern.compile("\\d+");
  private static final Logger LOG = LoggerFactory.getLogger(HTTP2Sampler.class);
  private static final ThreadLocal<Map<HTTP2ClientKey, HTTP2Client>> CONNECTIONS = ThreadLocal
      .withInitial(HashMap::new);
  private final Callable<HTTP2Client> clientFactory;

  public HTTP2Sampler() {
    setName("HTTP2 Sampler");
    clientFactory = this::getClient;
  }

  @VisibleForTesting
  public HTTP2Sampler(Callable<HTTP2Client> clientFactory) {
    this.clientFactory = clientFactory;
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

      HTTP2Client client = clientFactory.call();
      client.setHTTP2StateListener(new HTTP2StateListener() {
        @Override
        public void onConnectionEnd() {
          resultBuilder.withConnectionEnd();
        }

        @Override
        public void onLatencyEnd() {
          resultBuilder.withLatencyEnd();
        }
      });

      if (!getProxyHost().isEmpty()) {
        client.setProxy(getProxyHost(), getProxyPortInt(), getProxyScheme());
      }

      if (getMethod().equals(HTTPConstants.GET)) {
        Request request = client.createRequest(getUrl());
        resultBuilder.withRequestHeaders(getHeaders(request));

        if (getHeaderManager() != null) {
          setHeaders(request, getHeaderManager(), getUrl());
        }

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
    return resultBuilder.isRenameSampleLabel() ? getName() : getUrl().toString();
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

  private String getHeaders(Request request) {
    StringBuilder headers = new StringBuilder(
        request.getHeaders() != null
            ? request.getHeaders().asString()
            : "");

    if (headers.length() != 0) {
      headers.delete(headers.length() - 2, headers.length());
    }

    if (getHeaderManager() != null) {
      headers.append(getHeaderManagerAsString());
    }

    return headers.toString();
  }

  private String getHeaderManagerAsString() {
    StringBuilder buffer = new StringBuilder();

    CollectionProperty headers = getHeaderManager().getHeaders();
    headers.forEach(jMeterProperty -> {
      Header header = (Header) jMeterProperty.getObjectValue();
      buffer
          .append(header.getName())
          .append(": ")
          .append(header.getValue())
          .append("\r\n");
    });

    return buffer.toString();
  }

  private void setHeaders(Request request, HeaderManager headerManager, URL url) {
    CollectionProperty headers = headerManager.getHeaders();

    StreamSupport.stream(headerManager.getHeaders().spliterator(), false)
        .map(prop -> (Header) prop.getObjectValue())
        .forEach(header -> {
          String n = header.getName();

          if (!HTTPConstants.HEADER_CONTENT_LENGTH.equalsIgnoreCase(n)) {
            String v = header.getValue();
            if (HTTPConstants.HEADER_HOST.equalsIgnoreCase(n)) {
              int port = getPortFromHostHeader(v, url.getPort());
              // remove any port specification
              String newValue = v.replaceFirst(":\\d+$", "");  // $NON-NLS-1$ $NON-NLS-2$
              if (port != -1 && port == url.getDefaultPort()) {
                port = -1; // no need to specify the port if it is the default
              }
              if (port == -1) {
                request.headers(httpFields -> httpFields.put(HEADER_HOST, newValue));
              } else {
                int p = port;
                request.headers(httpFields -> httpFields.put(HEADER_HOST, newValue + ":" + p));
              }
            } else {
              request.headers(httpFields -> httpFields.put(n, v));
            }
          }
        });
  }

  private int getPortFromHostHeader(String hostHeaderValue, int defaultValue) {
    String[] hostParts = hostHeaderValue.split(":");
    if (hostParts.length > 1) {
      String portString = hostParts[hostParts.length - 1];
      if (PORT_PATTERN.matcher(portString).matches()) {
        return Integer.parseInt(portString);
      }
    }
    return defaultValue;
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
