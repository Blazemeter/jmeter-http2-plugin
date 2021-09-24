package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import com.google.common.base.Stopwatch;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jodd.net.MimeTypes;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.samplers.SampleResult;
import org.assertj.core.api.JUnitSoftAssertions;
import org.eclipse.jetty.http.HttpStatus;
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
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class HTTP2JettyClientTest {

  private static final String HOST_NAME = "localhost";
  private static final String SERVER_RESPONSE = "Hello World!";
  private static final String REQUEST_HEADERS = "Accept-Encoding: gzip\r\nUser-Agent: Jetty/11.0"
      + ".6\r\n\r\n";
  private static final String SERVER_IMAGE = "/test/image.png";
  private static final String SERVER_PATH = "/test";
  private static final String SERVER_PATH_200 = "/test/200";
  private static final String SERVER_PATH_SLOW = "/test/slow";
  private static final String SERVER_PATH_200_EMBEDDED = "/test/embedded";
  private static final String SERVER_PATH_200_FILE_SENT = "/test/file";
  private static final String SERVER_PATH_400 = "/test/400";
  private static final String SERVER_PATH_302 = "/test/302";
  private static final int SERVER_PORT = 6666;
  private static final String BASIC_HTML_TEMPLATE = "<!DOCTYPE html><html><head><title>Page "
      + "Title</title></head><body><div><img src='image.png'></div></body></html>";
  private final String imagePath = getClass().getResource("blazemeter-labs-logo"
      + ".png").getPath();

  @Rule
  public final JUnitSoftAssertions softly = new JUnitSoftAssertions();
  private ServerConnector connector;
  private HTTP2JettyClient client;
  private HTTP2Sampler sampler;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setup() throws Exception {
    sampler = new HTTP2Sampler();
    configureSampler(HTTPConstants.GET);
    client = new HTTP2JettyClient();
    client.start();
  }

  @After
  public void teardown() throws Exception {
    client.stop();
    if (connector != null) {
      connector.stop();
    }
  }

  @Test
  public void shouldGetResponseWhenGetMethodIsSent() throws Exception {
    startServer(createGetServerResponse());
    HTTPSampleResult result = client.sample(sampler,
        new URL(HTTPConstants.PROTOCOL_HTTPS, HOST_NAME, SERVER_PORT, SERVER_PATH_200),
        HTTPConstants.GET, false, 0);
    assertThat(result.getResponseDataAsString()).isEqualTo(SERVER_RESPONSE);
  }

  @Test(expected = ExecutionException.class)
  public void shouldThrowConnectExceptionWhenServerIsInaccessible() throws Exception {
    client.sample(sampler,
        new URL(HTTPConstants.PROTOCOL_HTTPS, HOST_NAME, 123, SERVER_PATH_200),
        HTTPConstants.GET, false, 0);
  }

  private HttpServlet createGetServerResponse() {
    return new HttpServlet() {
      @Override
      protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
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
          case SERVER_PATH_200_EMBEDDED:
            resp.setContentType(MimeTypes.MIME_TEXT_HTML + ";" + StandardCharsets.UTF_8.name());
            resp.getWriter().write(BASIC_HTML_TEMPLATE);
            return;
          case SERVER_IMAGE:
            resp.getOutputStream().write(new byte[]{1, 2, 3, 4, 5});
          case SERVER_PATH_200_FILE_SENT:
            resp.setContentType("image/png");
            byte[] requestBody = req.getInputStream().readAllBytes();
            resp.getOutputStream().write(requestBody);
            return;
        }
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
    connector.setPort(SERVER_PORT);
    server.addConnector(connector);
    ServletContextHandler context = new ServletContextHandler(server, "/", true, false);
    context.addServlet(new ServletHolder(servlet), SERVER_PATH + "/*");
    server.start();
  }

  @Test
  public void shouldReturnFailureSampleResultWhenResponse400() throws Exception {
    HTTPSampleResult expected = new HTTPSampleResult();
    expected.setSuccessful(false);
    expected.setResponseCode(String.valueOf(HttpStatus.BAD_REQUEST_400));
    expected.setRequestHeaders(REQUEST_HEADERS);
    startServer(createGetServerResponse());
    configureSampler(HTTPConstants.GET);
    HTTPSampleResult result = client.sample(sampler,
        new URL(HTTPConstants.PROTOCOL_HTTPS, HOST_NAME, SERVER_PORT, SERVER_PATH_400),
        HTTPConstants.GET, false, 0);
    validateResponse(result, expected);
  }


  @Test(expected = UnsupportedOperationException.class)
  public void shouldReturnErrorMessageWhenMethodIsNotSupported()
      throws URISyntaxException, IOException, InterruptedException, ExecutionException,
      TimeoutException {
    configureSampler("MethodNotSupported");
    client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS, HOST_NAME, 123, SERVER_PATH_200),
        "MethodNotSupported", false, 0);
  }

  @Ignore
  @Test
  public void shouldGetEmbeddedResourcesWithSubSampleWhenImageParserIsEnabled() throws Exception {
    HTTPSampleResult expected = new HTTPSampleResult();
    expected.setSuccessful(true);
    expected.setResponseCode(String.valueOf(HttpStatus.OK_200));
    expected.setRequestHeaders(REQUEST_HEADERS);
    expected.setResponseData(BASIC_HTML_TEMPLATE,
        StandardCharsets.UTF_8.name());
    startServer(createGetServerResponse());
    sampler.setImageParser(true);
    HTTPSampleResult result = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200_EMBEDDED), HTTPConstants.GET, false, 0);
    validateEmbeddedResources(result, expected);
  }


  @Test
  public void shouldReturnSuccessSampleResultWhenSuccessRequestWithHeaders() throws Exception {
    HTTPSampleResult expected = new HTTPSampleResult();
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    expected.setResponseCode(String.valueOf(HttpStatus.OK_200));
    expected.setSuccessful(true);
    expected.setRequestHeaders("Accept-Encoding: gzip\r\nUser-Agent: Jetty/11.0.6\r\nHeader1: "
        + "value1\r\nHeader2: value2\r\n\r\n");
    startServer(createGetServerResponse());
    configureHeaderManagerToSampler();
    HTTPSampleResult result = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200), HTTPConstants.GET, false, 0);
    validateResponse(result, expected);
  }

  @Test
  public void shouldGetRedirectedResultWithSubSampleWhenFollowRedirectEnabledAndRedirected()
      throws Exception {
    HTTPSampleResult expected = new HTTPSampleResult();
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    expected.setRequestHeaders(REQUEST_HEADERS);
    expected.setResponseCode(Integer.toString(HttpStatus.OK_200));
    expected.setSuccessful(true);
    expected.setRedirectLocation("https://localhost:6666/test/200");
    startServer(createGetServerResponse());
    configureSampler(HTTPConstants.GET);
    sampler.setFollowRedirects(true);
    HTTPSampleResult result = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_302), HTTPConstants.GET, false, 0);
    validateRedirects(result, expected);
  }

  @Test
  public void shouldGetOnlyRedirectedResultWhenFollowRedirectDisabledAndRedirected()
      throws Exception {
    startServer(createGetServerResponse());
    configureSampler(HTTPConstants.GET);
    HTTPSampleResult result = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_302), HTTPConstants.GET, false, 0);
    softly.assertThat(result.getResponseCode()).isEqualTo("302");
    softly.assertThat(result.getSubResults().length).isEqualTo(0);
  }

  @Test
  public void shouldGetFileDataWhenFileIsSentAsBodyPart() throws Exception {
    HTTPSampleResult expected = new HTTPSampleResult();
    expected.setSuccessful(true);
    expected.setResponseCode(String.valueOf(HttpStatus.OK_200));
    String filePath = getClass().getResource("blazemeter-labs-logo.png").getPath();
    InputStream inputStream = Files.newInputStream(Paths.get(filePath));
    expected.setResponseData(sampler.readResponse(expected, inputStream, 0));
    expected.setRequestHeaders("Accept-Encoding: gzip\r\n"
        + "User-Agent: Jetty/11.0.6\r\n"
        + "Content-Type: image/png\r\n"
        + "Content-Length: 9018\r\n"
        + "\r\n");
    configureSampler(HTTPConstants.POST);
    HTTPFileArg fileArg = new HTTPFileArg(filePath, "", "image/png");
    sampler.setHTTPFiles(new HTTPFileArg[]{fileArg});
    startServer(createGetServerResponse());
    HTTPSampleResult result = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200_FILE_SENT), HTTPConstants.POST, false, 0);
    validateResponse(result, expected);
  }

  @Ignore
  @Test
  public void shouldReturnErrorMessageWhenConnectTimeIsOver() {
    configureSampler(HTTPConstants.GET);
    sampler.setConnectTimeout("1");
    Exception exception = assertThrows(Exception.class, () -> {
      client.sample(sampler,
          new URL(HTTPConstants.PROTOCOL_HTTPS, HOST_NAME, SERVER_PORT, SERVER_PATH_200),
          HTTPConstants.GET, false, 0);
    });
    String actual = exception.getMessage();
    String expected = "java.net.SocketTimeoutException: Connect Timeout";
    softly.assertThat(actual).contains(expected);
  }

  @Test
  public void shouldReturnErrorMessageWhenResponseTimeIsOver() throws Exception {
    long timeout = 1000;
    startServer(createGetServerResponse());
    configureSampler(HTTPConstants.GET);
    sampler.setResponseTimeout(String.valueOf(timeout));
    Stopwatch waitTime = Stopwatch.createStarted();
    TimeoutException exception = assertThrows(TimeoutException.class, () -> {
      client.sample(sampler,
          new URL(HTTPConstants.PROTOCOL_HTTPS, HOST_NAME, SERVER_PORT, SERVER_PATH_SLOW),
          HTTPConstants.GET, false, 0);
    });
    softly.assertThat(exception).isInstanceOf(TimeoutException.class);
    softly.assertThat(waitTime.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(timeout);
  }

  @Ignore
  @Test
  public void shouldNotGetSubresultWhenResourceIsCachedWithNoMsg() throws Exception {
    HTTPSampleResult expected = new HTTPSampleResult();
    expected.setSuccessful(true);
    expected.setResponseCode(String.valueOf(HttpStatus.OK_200));
    expected.setRequestHeaders(REQUEST_HEADERS);
    expected.setResponseData(BASIC_HTML_TEMPLATE,
        StandardCharsets.UTF_8.name());
    startServer(createGetServerResponse());
    sampler.setImageParser(true);
    configureCacheManagerToSampler();
    HTTPSampleResult result = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200_EMBEDDED), HTTPConstants.GET, false, 0);
    // First request must connect to the server
    validateEmbeddedResources(result, expected);
    HTTPSampleResult resultCached = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200_EMBEDDED), HTTPConstants.GET, false, 0);
    // Same request should use results cached in Cache Manager
    validateEmbeddedResultCached(resultCached, expected);
  }

  @Ignore
  @Test
  public void shouldNotGetSubresultWhenResourceIsCachedWithOkMsg() {
    // Setear property ex cache
    // Comprobar que no haya subresultados para recurso (response body)
    // Comprobar que mensaje de respuesta sea (ex cache)

  }

  @Ignore
  @Test
  public void shouldNotGetSubresultWhenResourceIsCachedWithCustomMsg() {
    // Comprobar que no haya subresultados para recurso (response body)
    // Comprobar que mensaje de respuesta sea el customizado
  }

  @Ignore
  @Test
  public void shouldGetSubresultWhenCacheCleanBetweenIterationsAndNotMshOk() {
    // Setear property ex cache
    // Check Clear Cache in each iteration
    // Comprobar que trajo subresultado y es el mismo en ambas iterciones
    // Comprobar que no aparece mensaje (ex cache)
  }

  private void configureSampler(String method) {
    sampler.setMethod(method);
    sampler.setDomain("server");
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(SERVER_PORT);
    sampler.setPath("");
  }

  private void configureHeaderManagerToSampler() {
    HeaderManager hm = new HeaderManager();
    hm.add(new Header("Header1", "value1"));
    hm.add(new Header("Header2", "value2"));
    sampler.setHeaderManager(hm);
  }

  private void configureCacheManagerToSampler() {
    CacheManager cacheManager = new CacheManager();
    sampler.setCacheManager(cacheManager);
  }

  private void validateResponse(SampleResult result, SampleResult expected) {
    softly.assertThat(result.isSuccessful()).isEqualTo(expected.isSuccessful());
    softly.assertThat(result.getResponseCode()).isEqualTo(expected.getResponseCode());
    softly.assertThat(result.getResponseDataAsString())
        .isEqualTo(expected.getResponseDataAsString());
    softly.assertThat(result.getRequestHeaders()).isEqualTo(expected.getRequestHeaders());
  }

  private void validateRedirects(HTTPSampleResult result, HTTPSampleResult expected) {
    validateResponse(result, expected);
    softly.assertThat(result.getSubResults().length).isGreaterThan(0);
    softly.assertThat(result.getRedirectLocation()).isEqualTo(expected.getRedirectLocation());
  }

  private void validateEmbeddedResources(HTTPSampleResult result, HTTPSampleResult expected) {
    SampleResult[] results = result.getSubResults();
    validateResponse(result, expected);
    softly.assertThat(result.getSubResults().length).isGreaterThan(0);
    softly.assertThat(results[0].getDataType()).isEqualTo(SampleResult.TEXT);
    softly.assertThat(results[0].getUrlAsString())
        .isEqualTo("https://localhost:6666/test/embedded");
    softly.assertThat(results[1].getDataType()).isEqualTo(SampleResult.BINARY);
    softly.assertThat(results[1].getUrlAsString()).isEqualTo("https://localhost:6666/test/image.png");
  }

  /**
   * Validate same result as expected, but also control that not sample result was added for
   * resoruce.
   */
  private void validateEmbeddedResultCached(HTTPSampleResult result, HTTPSampleResult expected) {
    this.validateEmbeddedResources(result, expected);
    softly.assertThat(result.getResponseData().length).isEqualTo(0);
  }

}
