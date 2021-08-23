package com.blazemeter.jmeter.http2.core;

import java.net.URISyntaxException;
import java.net.URL;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.Origin.Address;
import org.eclipse.jetty.client.api.ContentResponse;
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

  public HttpRequest createRequest(URL url) throws URISyntaxException, IllegalArgumentException {
    HttpRequest rq;
    Request request = httpClient.newRequest(url.toURI());

    if (request instanceof HttpRequest) {
      rq = (HttpRequest) request;
      if (http2StateListener != null) {
        rq.onRequestBegin(l -> http2StateListener.onConnectionEnd());
        rq.onResponseBegin(l -> http2StateListener.onLatencyEnd());
      }
    } else {
      throw new IllegalArgumentException("HttpRequest is expected");
    }

    return rq;
  }

  public void start() throws Exception {
    httpClient.start();
  }

  public void stop() throws Exception {
    httpClient.stop();
  }

  public ContentResponse doPost(URL url, HeaderManager headerManager, Arguments arguments,
      String path) throws Exception {
    try {
      httpClient.start();
      Request request = httpClient.newRequest(url.toURI()).method(HttpMethod.POST);

      // start - setting body - parameters
      if (arguments != null && arguments.getArgumentCount() > 0) {
        for (JMeterProperty jMeterProperty : arguments.getArguments()) {
          HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
          StringBuilder requestBody = new StringBuilder();
          requestBody.append(arg.getName());
          requestBody.append(arg.getValue());
          String contentType = arg.getContentType();
          request.body(new StringRequestContent(contentType, requestBody.toString()));
          request.param(arg.getName(), arg.getValue());
        }
        request.path(path);
      } else {
        System.out.print("Arguments null or size equal zero");
      }
      // end - setting body - parameters

      return request.send();

    } finally {
      httpClient.stop();
    }

  }

  public void setHTTP2StateListener(HTTP2StateListener http2StateListener) {
    this.http2StateListener = http2StateListener;
  }
}
