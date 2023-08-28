package com.blazemeter.jmeter.http2.core;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import jodd.net.MimeTypes;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class ServerBuilder {

  public static final String HOST_NAME = "localhost";
  public static final int SERVER_PORT = 6666;
  public static final String SERVER_RESPONSE = "Hello World!";
  public static final String SERVER_IMAGE = "/test/image.png";
  public static final String SERVER_PATH = "/test";
  public static final String SERVER_PATH_SET_COOKIES = "/test/set-cookies";
  public static final String SERVER_PATH_USE_COOKIES = "/test/use-cookies";
  public static final String RESPONSE_DATA_COOKIES = "testCookie=test";
  public static final String RESPONSE_DATA_COOKIES2 = "testCookie2=test";
  public static final String SERVER_PATH_200 = "/test/200";
  public static final String SERVER_PATH_SLOW = "/test/slow";
  public static final String SERVER_PATH_200_GZIP = "/test/gzip";
  public static final String SERVER_PATH_200_EMBEDDED = "/test/embedded";
  public static final String SERVER_PATH_200_FILE_SENT = "/test/file";
  public static final String SERVER_PATH_BIG_RESPONSE = "/test/big-response";
  public static final String SERVER_PATH_400 = "/test/400";
  public static final String SERVER_PATH_302 = "/test/302";
  public static final String SERVER_PATH_200_WITH_BODY = "/test/body";
  public static final String SERVER_PATH_DELETE_DATA = "/test/delete";
  public static final String BASIC_HTML_TEMPLATE = "<!DOCTYPE html><html><head><title>Page "
      + "Title</title></head><body><div><img src='image.png'></div></body></html>";
  public static final byte[] BINARY_RESPONSE_BODY = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
  public static final String AUTH_USERNAME = "username";
  public static final String AUTH_PASSWORD = "password";
  public static final String AUTH_REALM = "realm";
  public static final String KEYSTORE_PASSWORD = "storepwd";
  public static final int BIG_BUFFER_SIZE = 4 * 1024 * 1024;

  private boolean ALPN;
  private final TeardownableServer server = new TeardownableServer();
  private final HttpConfiguration httpsConfig = new HttpConfiguration();
  private boolean withSSL;
  private HTTP2ServerConnectionFactory http2ConnectionFactory;
  private HttpConnectionFactory http1ConnectionFactory;
  private HTTP2CServerConnectionFactory http2cConnectionFactory;
  private boolean clientAuth;
  private boolean isBasicAuth;
  private boolean isDigestAuth;

  public ServerBuilder() {
    httpsConfig.addCustomizer(new SecureRequestCustomizer());
  }

  public ServerBuilder withHTTP1() {
    http1ConnectionFactory = new HttpConnectionFactory(httpsConfig);
    return this;
  }

  public ServerBuilder withHTTP2C() {
    http2cConnectionFactory = new HTTP2CServerConnectionFactory(httpsConfig);
    return this;
  }

  public ServerBuilder withHTTP2() {
    http2ConnectionFactory = new HTTP2ServerConnectionFactory(httpsConfig);
    return withHTTP1();//For handshake proposes
  }

  public ServerBuilder withSSL() {
    this.withSSL = true;
    return this;
  }

  public ServerBuilder withALPN() {
    this.ALPN = true;
    return this;
  }

  public ServerBuilder withNeedClientAuth() {
    this.clientAuth = true;
    return this;
  }

  public ServerBuilder withBasicAuth() {
    this.isBasicAuth = true;
    return this;
  }

  public ServerBuilder withDigestAuth() {
    this.isDigestAuth = true;
    return this;
  }

  public TeardownableServer buildServer() {

    ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
    alpn.setDefaultProtocol(http1ConnectionFactory.getProtocol());
    ServerConnector http1UpgradedConnector = null;
    if (http2cConnectionFactory != null && http1ConnectionFactory != null) {
      http1UpgradedConnector = new ServerConnector(server, http1ConnectionFactory,
          http2cConnectionFactory);
      http1UpgradedConnector.setReusePort(false);
    }
    ConnectionFactory ssl = setupSslConnectionFactory(alpn, http1UpgradedConnector);
    List<ConnectionFactory> connectionFactories = new ArrayList<>();
    if (withSSL) {
      connectionFactories.add(ssl);
    }
    if (ALPN) {
      connectionFactories.add(alpn);
    }
    connectionFactories.addAll(buildConnectionFactories());

    ServerConnector connector = buildServerConnector(connectionFactories);

    server.addConnector(connector);

    ServletContextHandler context = new ServletContextHandler(server, "/", true, false);
    context.addServlet(new ServletHolder(buildServlet()), SERVER_PATH + "/*");

    if (isBasicAuth) {
      configureAuthHandler(server, new BasicAuthenticator(), Constraint.__BASIC_AUTH);
      return server;
    }
    if (isDigestAuth) {
      configureAuthHandler(server, new DigestAuthenticator(), Constraint.__DIGEST_AUTH);
    }
    return server;
  }

  private ConnectionFactory setupSslConnectionFactory(ALPNServerConnectionFactory alpn,
                                                      ServerConnector http1UpgradedConnector) {
    SslContextFactory.Server sslFactory = buildServerSslContextFactory();
    sslFactory.setNeedClientAuth(clientAuth);
    return new SslConnectionFactory(sslFactory,
        ALPN ? alpn.getDefaultProtocol()
            : http1UpgradedConnector != null ? http1UpgradedConnector.getDefaultProtocol()
            : http1ConnectionFactory.getProtocol());
  }

  private ServerConnector buildServerConnector(List<ConnectionFactory> connectionFactories) {
    ServerConnector connector =
        new ServerConnector(server, 1, 1,
            connectionFactories.toArray(new ConnectionFactory[0]));
    connector.setPort(SERVER_PORT);
    connector.setReusePort(false);
    return connector;
  }

  private List<ConnectionFactory> buildConnectionFactories() {
    List<ConnectionFactory> connectionFactories = new ArrayList<>();
    Consumer<ConnectionFactory> factoryAppender = (c) -> {
      if (c != null) {
        connectionFactories.add(c);
      }
    };
    factoryAppender.accept(http1ConnectionFactory);
    factoryAppender.accept(http2cConnectionFactory);
    factoryAppender.accept(http2ConnectionFactory);
    return connectionFactories;
  }

  private SslContextFactory.Server buildServerSslContextFactory() {
    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
    sslContextFactory.setKeyStorePath(getKeyStorePathAsUriPathInSSLContextFactoryFormat());
    sslContextFactory.setKeyStorePassword(KEYSTORE_PASSWORD);
    return sslContextFactory;
  }

  private String getKeyStorePathAsUriPathInSSLContextFactoryFormat() {
    try {
      // Generate a absolute path in URI format with compatibility with Windows
      // IMPORTANT: SSLContextFactory need a URI in relative format, internally they concat the path
      return new File("./").toURI().relativize(getClass().getResource("keystore.p12").toURI())
          .getPath();
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  private HttpServlet buildServlet() {
    return new HttpServlet() {

      @Override
      protected void service(HttpServletRequest req, HttpServletResponse resp)
          throws IOException {
        switch (req.getServletPath() + req.getPathInfo()) {
          case SERVER_PATH_200:
            resp.setStatus(HttpStatus.OK_200);
            resp.setContentType(MimeTypes.MIME_TEXT_HTML + ";" + StandardCharsets.UTF_8.name());
            resp.getWriter().write(SERVER_RESPONSE);
            break;
          case SERVER_PATH_SLOW:
            try {
              Thread.sleep(10000);
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
            resp.setStatus(HttpStatus.OK_200);
            break;
          case SERVER_PATH_400:
            resp.setStatus(HttpStatus.BAD_REQUEST_400);
            break;
          case SERVER_PATH_302:
            resp.addHeader(HTTPConstants.HEADER_LOCATION,
                "https://localhost:" + SERVER_PORT + SERVER_PATH_200);
            resp.setStatus(HttpStatus.FOUND_302);
            break;
          case SERVER_PATH_200_WITH_BODY:
            String bodyRequest = req.getReader().lines().collect(Collectors.joining());
            resp.getWriter().write(bodyRequest);
            break;
          case SERVER_PATH_SET_COOKIES:
            resp.addHeader(HTTPConstants.HEADER_SET_COOKIE,
                RESPONSE_DATA_COOKIES);
            resp.addHeader(HTTPConstants.HEADER_SET_COOKIE,
                RESPONSE_DATA_COOKIES2);
            break;
          case SERVER_PATH_USE_COOKIES:
            String cookie = req.getHeader(HTTPConstants.HEADER_COOKIE);
            resp.getWriter().write(cookie);
            break;
          case SERVER_PATH_200_EMBEDDED:
            resp.setContentType(MimeTypes.MIME_TEXT_HTML + ";" + StandardCharsets.UTF_8.name());
            resp.getWriter().write(BASIC_HTML_TEMPLATE);
            resp.addHeader(HTTPConstants.EXPIRES,
                "Sat, 25 Sep 2041 00:00:00 GMT");
            break;
          case SERVER_IMAGE:
            resp.getOutputStream().write(new byte[] {1, 2, 3, 4, 5});
          case SERVER_PATH_200_FILE_SENT:
            resp.setContentType("image/png");
            byte[] requestBody = req.getInputStream().readAllBytes();
            resp.getOutputStream().write(requestBody);
            break;
          case SERVER_PATH_200_GZIP:
            resp.addHeader("Content-Encoding", "gzip");
            GZIPOutputStream gzipOutputStream = new GZIPOutputStream(resp.getOutputStream());
            gzipOutputStream.write(BINARY_RESPONSE_BODY);
            gzipOutputStream.close();
            break;
          case SERVER_PATH_DELETE_DATA:
            resp.setStatus(HttpStatus.OK_200);
            break;
          case SERVER_PATH_BIG_RESPONSE:
            resp.getOutputStream().write(new byte[(int) BIG_BUFFER_SIZE]);
            resp.setContentType("image/jpg");
            break;
        }
      }
    };
  }


  private void configureAuthHandler(Server server, Authenticator authenticator,
                                    String mechanism) {
    ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
    String[] roles = new String[] {"can-access"};
    securityHandler.setAuthenticator(authenticator);
    securityHandler.setConstraintMappings(
        Collections.singletonList(buildConstraintMapping(mechanism, roles)));
    securityHandler.setRealmName(AUTH_REALM);
    securityHandler.setLoginService(buildLoginService(roles));
    securityHandler.setHandler(server.getHandler());
    server.setHandler(securityHandler);
  }

  private ConstraintMapping buildConstraintMapping(String mechanism, String[] roles) {
    Constraint constraint = new Constraint();
    constraint.setName(mechanism);
    constraint.setAuthenticate(true);
    constraint.setRoles(roles);

    ConstraintMapping ret = new ConstraintMapping();
    ret.setPathSpec("/*");
    ret.setConstraint(constraint);
    return ret;
  }

  private HashLoginService buildLoginService(String[] roles) {
    UserStore userStore = new UserStore();
    userStore.addUser(AUTH_USERNAME, new Password(AUTH_PASSWORD), roles);

    HashLoginService ret = new HashLoginService();
    ret.setName(AUTH_REALM);
    ret.setUserStore(userStore);
    return ret;
  }

  public static class TeardownableServer extends Server {

    @Override
    protected void doStop() throws Exception {
      Arrays.stream(this.getConnectors()).forEach(
          serverConnector -> {
            try {
              serverConnector.stop();
            } catch (Exception e) {
              e.printStackTrace();
            }
          });
      super.doStop();
    }

    @Override
    protected void stop(LifeCycle l) throws Exception {
      Arrays.stream(this.getConnectors()).forEach(
          serverConnector -> {
            try {
              serverConnector.stop();
            } catch (Exception e) {
              e.printStackTrace();
            }
          });
      l.stop();
      super.stop(l);
    }
  }
}




