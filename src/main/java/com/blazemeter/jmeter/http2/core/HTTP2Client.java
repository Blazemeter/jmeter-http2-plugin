package com.blazemeter.jmeter.http2.core;

import java.net.URISyntaxException;
import java.net.URL;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin.Address;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.http2.client.http.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HTTP2Client {

  private final HttpClient httpClient;
  private HTTP2StateListener http2StateListener;

  public HTTP2Client() {
    ClientConnector clientConnector = new ClientConnector();
    SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
    sslContextFactory.setTrustAll(true);
    clientConnector.setSslContextFactory(sslContextFactory);
    org.eclipse.jetty.http2.client.HTTP2Client http2Client =
        new org.eclipse.jetty.http2.client.HTTP2Client(
            clientConnector);
    HttpClientTransport transport = new HttpClientTransportDynamic(clientConnector,
        new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client),
        HttpClientConnectionFactory.HTTP11);
    this.httpClient = new HttpClient(transport);
  }

  public void setProxy(String host, int port, String protocol) {
    HttpProxy proxy =
        new HttpProxy(new Address(host, port), HTTPConstants.PROTOCOL_HTTPS.equals(protocol));
    httpClient.getProxyConfiguration().getProxies().add(proxy);
  }

  public Request createRequest(URL url) throws URISyntaxException {
    Request request = httpClient.newRequest(url.toURI());
    if (http2StateListener != null) {
      request.onRequestBegin(l -> http2StateListener.onConnectionEnd());
      request.onResponseBegin(l -> http2StateListener.onLatencyEnd());
    }
    return request;
  }

  public void start() throws Exception {
    httpClient.start();
  }

  public void stop() throws Exception {
    httpClient.stop();
  }

  public void setHTTP2StateListener(HTTP2StateListener http2StateListener) {
    this.http2StateListener = http2StateListener;
  }
}
