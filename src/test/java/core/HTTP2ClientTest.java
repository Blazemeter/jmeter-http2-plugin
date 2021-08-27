package core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.core.HTTP2Client;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Before;
import org.junit.Test;

public class HTTP2ClientTest {

  private static final String SERVER_RESPONSE = "Hello World!";

  private ServerConnector connector;
  private static final String SERVER_PATH = "/test";
  private HTTP2Client client;

  @Before
  public void setup() {
    client = new HTTP2Client();
  }

  @Test
  public void shouldPostResponseWhenPostMethodIsSent() throws Exception {
    startServer(createPostServerResponse());
    ContentResponse response = client.doPost(
        new URL(HTTPConstants.PROTOCOL_HTTPS, "localhost", connector.getLocalPort(), SERVER_PATH)
        , null, SERVER_PATH);
    assertThat(response.getContentAsString()).isEqualTo(SERVER_RESPONSE);
  }

  @Test(expected = ExecutionException.class)
  public void shouldThrowConnectExceptionWhenServerIsInaccessible() throws Exception {
    client.doPost(
        new URL(HTTPConstants.PROTOCOL_HTTPS, "localhost", 80, SERVER_PATH), null, SERVER_PATH);
  }

  private HttpServlet createGetServerResponse() {
    final byte[] content = SERVER_RESPONSE.getBytes(StandardCharsets.UTF_8);
    return new HttpServlet() {
      @Override
      protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.getOutputStream().write(content);
      }
    };
  }

  private HttpServlet createPostServerResponse() {
    final byte[] content = SERVER_RESPONSE.getBytes(StandardCharsets.UTF_8);
    return new HttpServlet() {
      @Override
      protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.getOutputStream().write(content);
      }
    };
  }

  private void startServer(HttpServlet servlet) throws Exception {
    Server server = new Server();
    HttpConfiguration httpsConfig = new HttpConfiguration();
    httpsConfig.addCustomizer(new SecureRequestCustomizer());
    ConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);
    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
    sslContextFactory.setKeyStoreResource(
        Resource.newResource(getClass().getResource("keystore.p12").getPath()));
    sslContextFactory.setKeyStorePassword("storepwd");
    sslContextFactory.setUseCipherSuitesOrder(true);
    sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
    ConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, h2.getProtocol());
    connector = new ServerConnector(server, 1, 1, ssl, h2);
    server.addConnector(connector);
    ServletContextHandler context = new ServletContextHandler(server, "/", true, false);
    context.addServlet(new ServletHolder(servlet), SERVER_PATH + "/*");
    server.start();
  }
}
