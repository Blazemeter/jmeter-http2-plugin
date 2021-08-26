package com.blazemeter.jmeter.http2.core;

import java.net.URISyntaxException;
import java.net.URL;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin.Address;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.http.HttpMethod;
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
  public ContentResponse doGet(URL url, HeaderManager headerManager) throws Exception {
    try {
      httpClient.start();
      Request request = httpClient.newRequest(url.toURI()).method(HttpMethod.GET);
      return setHeaders(request, headerManager, null).send();
    } finally {
      httpClient.stop();
    }
    return request;
  }

  private Request setHeaders(Request request, HeaderManager headerManager,
      CacheManager cacheManager) {
    Header[] arrayOfHeaders = null; // This could be useful when working on the cache manager
    // feature.
    if (headerManager != null) {
      CollectionProperty headers = headerManager.getHeaders();
      if (headers != null) {
        int i = 0;
        arrayOfHeaders = new Header[headers.size()];
        for (JMeterProperty jMeterProperty : headers) {
          Header header = (Header) jMeterProperty.getObjectValue();
          String n = header.getName();
          String v = header.getValue();
          arrayOfHeaders[i++] = header; // This could be useful when working on the cache manager
          request.headers(httpFields -> httpFields.put(n, v));
        }
      }
    }
    // This section could be useful when working on the cache manager
    // if (cacheManager != null) {
    // cacheManager.setHeaders(conn, arrayOfHeaders, u);
    // }

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
