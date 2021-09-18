package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import jodd.net.MimeTypes;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
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
  private static final String SERVER_PATH = "/test";
  private static final String SERVER_PATH_200 = "/test/200";
  private static final String SERVER_PATH_200_EMBEDDED = "/test/embedded";
  private static final String SERVER_PATH_200_FILE_SENT = "/test/file";
  private static final String SERVER_PATH_400 = "/test/400";
  private static final String SERVER_PATH_302 = "/test/302";
  private static final int SERVER_PORT = 6666;
  private static final String BASIC_HTML_TEMPLATE = "<!DOCTYPE html><html><head><title>Page "
      + "Title</title></head><body><div><img src=%s></div></body></html>";
  private static final String pathToFile = "/src/main/resources/blazemeter-labs-light-logo.png";

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
            resp.getWriter().write(HTTP2JettyClientTest.getBasicHtmlTemplate());
            return;
          case SERVER_PATH_200_FILE_SENT:
            resp.setContentType(
                MimeTypes.MIME_APPLICATION_JSON + ";" + StandardCharsets.UTF_8.name());
            try {
              resp.getWriter().write(HTTP2JettyClientTest.getFileData());
            } catch (final URISyntaxException e) {
              e.printStackTrace();
            }
            return;
        }
        resp.setContentType(MimeTypes.MIME_TEXT_HTML + ";" + StandardCharsets.UTF_8.name());
        resp.getWriter().write(SERVER_RESPONSE);
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
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
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

  @Test
  public void shouldGetEmbeddedResourcesWithSubSampleWhenImageParserIsEnabled() throws Exception {
    HTTPSampleResult expected = new HTTPSampleResult();
    expected.setSuccessful(true);
    expected.setResponseCode(String.valueOf(HttpStatus.OK_200));
    expected.setRequestHeaders(REQUEST_HEADERS);
    expected.setResponseData(HTTP2JettyClientTest.getBasicHtmlTemplate(),
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
  public void shouldGetFileDataWithFileIsSentAsBodyPart() throws Exception {
    HTTPSampleResult expected = new HTTPSampleResult();
    expected.setSuccessful(true);
    expected.setResponseCode(String.valueOf(HttpStatus.OK_200));
    expected.setRequestHeaders(REQUEST_HEADERS);
    expected.setResponseData(HTTP2JettyClientTest.getFileData(),
        StandardCharsets.UTF_8.name());
    startServer(createGetServerResponse());
    sampler.setImageParser(true);
    final HTTPSampleResult result = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200_FILE_SENT), HTTPConstants.POST, false, 0);
    validateFileDataReceived(result, expected);
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
    softly.assertThat(results[1].getDataType()).isEqualTo(SampleResult.TEXT);
    softly.assertThat(results[1].getUrlAsString()).isEqualTo("https://localhost:6666/test/200");
  }

  private static String getBasicHtmlTemplate() {
    return String.format(BASIC_HTML_TEMPLATE,
        "https://localhost:" + SERVER_PORT + SERVER_PATH_200);
  }

  /**
   * Get data in array of bytes format for a new File.
   *
   * @return data in String parsed
   */
  private static String getFileData() throws IOException, URISyntaxException {
    // Get root directory from system to avoid environment problems in tests
    StringBuilder rootPath = new StringBuilder("file://");
    rootPath.append(System.getProperty("user.dir")); // get current working directory
    rootPath.append(pathToFile); // add path to file
    final String uriFile = rootPath.toString();
    // Load file from resources and convert it to x64 base like jmeter return a file
    final File file = new File(new URI((uriFile)));
    final byte[] encoded = Base64.encodeBase64(FileUtils.readFileToByteArray(file));
    return new String(encoded, StandardCharsets.US_ASCII);
  }

  /**
   * Get data in array of bytes format from a existing File.
   *
   * @param file File to convert
   */
  @Deprecated
  private static String getDataFromFile(final File file) throws IOException {
    final byte[] encoded = Base64.encodeBase64(FileUtils.readFileToByteArray(file));
    return new String(encoded, StandardCharsets.US_ASCII);
  }

  private void validateFileDataReceived(final HTTPSampleResult result,
      final HTTPSampleResult expected) {
    result.getDataType().equalsIgnoreCase(SampleResult.TEXT);
    result.getUrlAsString().equalsIgnoreCase("https://localhost:6666/test/file");
    validateResponse(result, expected);
  }
}
