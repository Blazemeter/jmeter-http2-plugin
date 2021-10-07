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
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;
import java.util.stream.Collectors;
import jodd.net.MimeTypes;
import org.apache.jmeter.protocol.http.control.CookieManager;
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
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class HTTP2JettyClientTest {

  private static final String HOST_NAME = "localhost";
  private static final String SERVER_RESPONSE = "Hello World!";
  private static final String SERVER_PATH = "/test";
  private static final String SERVER_PATH_SET_COOKIES = "/test/set-cookies";
  private static final String SERVER_PATH_USE_COOKIES = "/test/use-cookies";
  private static final String RESPONSE_DATA_COOKIES = "testCookie=test";
  private static final String SERVER_PATH_200 = "/test/200";
  private static final String SERVER_PATH_SLOW = "/test/slow";
  private static final String SERVER_PATH_200_GZIP = "/test/gzip";
  private static final String SERVER_PATH_200_EMBEDDED = "/test/embedded";
  private static final String SERVER_PATH_200_FILE_SENT = "/test/file";
  private static final String SERVER_PATH_400 = "/test/400";
  private static final String SERVER_PATH_302 = "/test/302";
  private static final String SERVER_PATH_200_WITH_BODY = "/test/body";
  private static final String SERVER_PATH_DELETE_DATA = "/test/delete";
  private static final String TEST_ARGUMENT_1 = "valueTest1";
  private static final String TEST_ARGUMENT_2 = "valueTest2";
  private static final String STANDARD_CHARSETS = StandardCharsets.UTF_8.name();
  private static final int SERVER_PORT = 6666;
  private static final String BASIC_HTML_TEMPLATE = "<!DOCTYPE html><html><head><title>Page "
      + "Title</title></head><body><div><img src=%s></div></body></html>";
  private static final byte[] BINARY_RESPONSE_BODY = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

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
    HTTPSampleResult result = client
        .sample(sampler, createURL(SERVER_PATH_200), HTTPConstants.GET, false, 0);
    assertThat(result.getResponseDataAsString()).isEqualTo(SERVER_RESPONSE);
  }

  private URL createURL(String path) throws MalformedURLException {
    return new URL(HTTPConstants.PROTOCOL_HTTPS, HOST_NAME, SERVER_PORT, path);
  }

  @Test(expected = ExecutionException.class)
  public void shouldThrowConnectExceptionWhenServerIsInaccessible() throws Exception {
    client.sample(sampler,
        new URL(HTTPConstants.PROTOCOL_HTTPS, HOST_NAME, 123, SERVER_PATH_200),
        HTTPConstants.GET, false, 0);
  }

  @Test
  public void shouldReturnSuccessSampleResultWhenSuccessResponseWithContentTypeGzip()
      throws Exception {
    startServer(createGetServerResponse());
    HTTPSampleResult result = client
        .sample(sampler, createURL(SERVER_PATH_200_GZIP), HTTPConstants.GET, false, 0);
    assertThat(HTTP2JettyClientTest.BINARY_RESPONSE_BODY).isEqualTo(result.getResponseData());
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
          case SERVER_PATH_200_WITH_BODY:
            String bodyRequest = req.getReader().lines().collect(Collectors.joining());
            resp.getWriter().write(bodyRequest);
            break;
          case SERVER_PATH_SET_COOKIES:
            resp.addHeader(HTTPConstants.HEADER_SET_COOKIE,
                RESPONSE_DATA_COOKIES);
            break;
          case SERVER_PATH_USE_COOKIES:
            String cookie = req.getHeader(HTTPConstants.HEADER_COOKIE);
            resp.getWriter().write(cookie);
            break;
          case SERVER_PATH_200_EMBEDDED:
            resp.setContentType(MimeTypes.MIME_TEXT_HTML + ";" + StandardCharsets.UTF_8.name());
            resp.getWriter().write(HTTP2JettyClientTest.getBasicHtmlTemplate());
            return;
          case SERVER_PATH_200_FILE_SENT:
            resp.setContentType("image/png");
            byte[] requestBody = req.getInputStream().readAllBytes();
            resp.getOutputStream().write(requestBody);
            return;
          case SERVER_PATH_200_GZIP:
            resp.addHeader("Content-Encoding", "gzip");
            GZIPOutputStream gzipOutputStream = new GZIPOutputStream(resp.getOutputStream());
            gzipOutputStream.write(HTTP2JettyClientTest.BINARY_RESPONSE_BODY);
            gzipOutputStream.close();
            break;
          case SERVER_PATH_DELETE_DATA:
            resp.setStatus(HttpStatus.OK_200);
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
  public void shouldSendBodyInformationWhenRequestWithBodyRaw () throws Exception {
    String headers =  getCommonHeaders()
        .withContentLength(20)
        .withContentType("application/octet-stream")
        .build();
    HTTPSampleResult expected = createExpectedResult(true, HttpStatus.OK_200, headers);
    expected.setResponseData(TEST_ARGUMENT_1 + TEST_ARGUMENT_2, STANDARD_CHARSETS);
    startServer(createGetServerResponse());
    sampler.setMethod(HTTPConstants.POST);
    sampler.addArgument("", TEST_ARGUMENT_1);
    sampler.addArgument("", TEST_ARGUMENT_2);
    HTTPSampleResult result = client.sample(sampler, createURL(SERVER_PATH_200_WITH_BODY),
        HTTPConstants.POST, false, 0);
    validateResponse(result, expected);
  }

  @Test
  public void shouldSendBodyInformationWhenRequestWithArguments () throws Exception {
    String headers =  getCommonHeaders()
        .withContentLength(33)
        .withContentType("application/x-www-form-urlencoded")
        .build();
    HTTPSampleResult expected = createExpectedResult(true, HttpStatus.OK_200, headers);
    expected.setResponseData("test1=" + TEST_ARGUMENT_1 + "&" + "test2="
        + TEST_ARGUMENT_2, STANDARD_CHARSETS);
    startServer(createGetServerResponse());
    sampler.setMethod(HTTPConstants.POST);
    sampler.addArgument("test1", TEST_ARGUMENT_1);
    sampler.addArgument("test2", TEST_ARGUMENT_2);
    HTTPSampleResult result = client.sample(sampler, createURL(SERVER_PATH_200_WITH_BODY),
    HTTPConstants.POST, false, 0);
    validateResponse(result, expected);
  }

  @Test
  public void shouldSendArgumentsInUrlWhenDeleteMethodWithArguments() throws Exception {
    sampler.setMethod(HTTPConstants.DELETE);
    String argumentName1 = "test_1";
    String argumentName2 = "test_2";
    sampler.addArgument(argumentName1, TEST_ARGUMENT_1);
    sampler.addArgument(argumentName2, TEST_ARGUMENT_2);
    assertThat(sampler.getUrl().toString()).isEqualTo("https://server:" + SERVER_PORT +
        "/?" + argumentName1 + "=" + TEST_ARGUMENT_1 + "&" + argumentName2 + "=" + TEST_ARGUMENT_2);
  }

  @Test
  public void shouldSendBodyWhenDeleteMethodWithRawData() throws Exception {
    String headers =  getCommonHeaders()
        .withContentType("application/octet-stream")
        .withContentLength(20)
        .build();
    HTTPSampleResult expected = createExpectedResult(true, HttpStatus.OK_200, headers);
    expected.setResponseData(TEST_ARGUMENT_1 + TEST_ARGUMENT_2, STANDARD_CHARSETS);
    startServer(createGetServerResponse());
    sampler.setMethod(HTTPConstants.DELETE);
    sampler.addArgument("", TEST_ARGUMENT_1);
    sampler.addArgument("", TEST_ARGUMENT_2);
    HTTPSampleResult result = client.sample(sampler,createURL(SERVER_PATH_200_WITH_BODY),
        HTTPConstants.DELETE, false, 0);
    validateResponse(result, expected);
  }

  @Test
  public void shouldReturnFailureSampleResultWhenResponse400() throws Exception {
    String headers =  getCommonHeaders().build();
    HTTPSampleResult expected = createExpectedResult(false, HttpStatus.BAD_REQUEST_400,
        headers);
    startServer(createGetServerResponse());
    configureSampler(HTTPConstants.GET);
    HTTPSampleResult result = client
        .sample(sampler, createURL(SERVER_PATH_400), HTTPConstants.GET, false, 0);
    validateResponse(result, expected);
  }

  private HTTPSampleResult createExpectedResult(boolean successful, int responseCode,
      String headers) {
    HTTPSampleResult expected = new HTTPSampleResult();
    expected.setSuccessful(successful);
    expected.setResponseCode(String.valueOf(responseCode));
    expected.setRequestHeaders(headers);
    return expected;
  }

  @Test(expected = UnsupportedOperationException.class)
  public void shouldReturnErrorMessageWhenMethodIsNotSupported()
      throws URISyntaxException, IOException, InterruptedException, ExecutionException,
      TimeoutException {
    configureSampler("MethodNotSupported");
    client.sample(sampler, createURL(SERVER_PATH_200), "MethodNotSupported", false, 0);
  }

  @Test
  public void shouldGetEmbeddedResourcesWithSubSampleWhenImageParserIsEnabled() throws Exception {
    String headers =  getCommonHeaders().build();
    HTTPSampleResult expected = createExpectedResult(true, HttpStatus.OK_200, headers);
    expected.setResponseData(HTTP2JettyClientTest.getBasicHtmlTemplate(), STANDARD_CHARSETS);
    startServer(createGetServerResponse());
    sampler.setImageParser(true);
    HTTPSampleResult result = client
        .sample(sampler, createURL(SERVER_PATH_200_EMBEDDED), HTTPConstants.GET, false, 0);
    validateEmbeddedResources(result, expected);
  }

  @Test
  public void shouldUseCookiesFromFirstRequestOnSecondRequestWhenSecondRequestIsSent()
      throws Exception {
    String headers = getCommonHeaders().build();
    HTTPSampleResult expected = createExpectedResult(true, HttpStatus.OK_200, headers);
    expected.setCookies(RESPONSE_DATA_COOKIES);
    expected.setResponseData(RESPONSE_DATA_COOKIES, STANDARD_CHARSETS);
    startServer(createGetServerResponse());
    CookieManager cookieManager = new CookieManager();
    cookieManager.testStarted(HOST_NAME);
    sampler.setCookieManager(cookieManager);
    client.sample(sampler, createURL(SERVER_PATH_SET_COOKIES), HTTPConstants.GET, false, 0);
    HTTPSampleResult result = client
        .sample(sampler, createURL(SERVER_PATH_USE_COOKIES), HTTPConstants.GET, false, 0);
    validateResponse(result, expected);
    softly.assertThat(result.getCookies()).isEqualTo(expected.getCookies());
  }

  @Test
  public void shouldReturnSuccessSampleResultWhenSuccessRequestWithHeaders() throws Exception {
    String headers = getCommonHeaders().build();
    HTTPSampleResult expected = createExpectedResult(true, HttpStatus.OK_200,
        headers + "Header1: " + "value1\r\nHeader2: value2\r\n\r\n");
    expected.setResponseData(SERVER_RESPONSE, STANDARD_CHARSETS);
    startServer(createGetServerResponse());
    configureHeaderManagerToSampler();
    HTTPSampleResult result = client
        .sample(sampler, createURL(SERVER_PATH_200), HTTPConstants.GET, false, 0);
    validateResponse(result, expected);
  }

  @Test
  public void shouldGetRedirectedResultWithSubSampleWhenFollowRedirectEnabledAndRedirected()
      throws Exception {
    String headers = getCommonHeaders().build();

    HTTPSampleResult expected = createExpectedResult(true, HttpStatus.OK_200, headers);
    expected.setResponseData(SERVER_RESPONSE, STANDARD_CHARSETS);
    expected.setRedirectLocation("https://localhost:6666/test/200");
    startServer(createGetServerResponse());
    configureSampler(HTTPConstants.GET);
    sampler.setFollowRedirects(true);
    HTTPSampleResult result = client
        .sample(sampler, createURL(SERVER_PATH_302), HTTPConstants.GET, false, 0);
    validateRedirects(result, expected);
  }

  @Test
  public void shouldGetOnlyRedirectedResultWhenFollowRedirectDisabledAndRedirected()
      throws Exception {
    startServer(createGetServerResponse());
    configureSampler(HTTPConstants.GET);
    HTTPSampleResult result = client
        .sample(sampler, createURL(SERVER_PATH_302), HTTPConstants.GET, false, 0);
    softly.assertThat(result.getResponseCode()).isEqualTo("302");
    softly.assertThat(result.getSubResults().length).isEqualTo(0);
  }

  @Test
  public void shouldGetFileDataWithFileIsSentAsBodyPart() throws Exception {
    String headers = getCommonHeaders()
        .withContentType("image/png")
        .withContentLength(9018)
        .build();
    HTTPSampleResult expected = createExpectedResult(true, HttpStatus.OK_200,
        headers + "\r\n");
    String filePath = getClass().getResource("blazemeter-labs-logo.png").getPath();
    InputStream inputStream = Files.newInputStream(Paths.get(filePath));
    expected.setResponseData(sampler.readResponse(expected, inputStream, 0));
    configureSampler(HTTPConstants.POST);
    HTTPFileArg fileArg = new HTTPFileArg(filePath, "", "image/png");
    sampler.setHTTPFiles(new HTTPFileArg[]{fileArg});
    startServer(createGetServerResponse());
    HTTPSampleResult result = client
        .sample(sampler, createURL(SERVER_PATH_200_FILE_SENT), HTTPConstants.POST, false, 0);
    validateResponse(result, expected);
  }

  @Test
  public void shouldReturnErrorMessageWhenResponseTimeIsOver() throws Exception {
    long timeout = 1000;
    startServer(createGetServerResponse());
    configureSampler(HTTPConstants.GET);
    sampler.setResponseTimeout(String.valueOf(timeout));
    Stopwatch waitTime = Stopwatch.createStarted();
    TimeoutException exception = assertThrows(TimeoutException.class, () -> {
      client.sample(sampler, createURL(SERVER_PATH_SLOW), HTTPConstants.GET, false, 0);
    });
    softly.assertThat(exception).isInstanceOf(TimeoutException.class);
    softly.assertThat(waitTime.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(timeout);
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
    softly.assertThat(result.getRequestHeaders().replace("\r\n", ""))
        .isEqualTo(expected.getRequestHeaders().replace("\r\n", ""));
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

  private static class HeadersBuilder {
    private String encoding = "";
    private String agent = "";
    private String type = "";
    private String length = "";

    public HeadersBuilder withAcceptEncoding(String acceptEncoding) {
      this.encoding = acceptEncoding;
      return this;
    }

    public HeadersBuilder withUserAgent(String userAgent) {
      this.agent = userAgent;
      return this;
    }

    public HeadersBuilder withContentType(String type) {
      this.type = type;
      return this;
    }

    public HeadersBuilder withContentLength(int length) {
      this.length = String.valueOf(length);
      return this;
    }

    public String build() {
      StringBuilder headers = new StringBuilder();

      if (!this.encoding.isEmpty()) {
        headers = headers.append("Accept-Encoding: ")
            .append(this.encoding)
            .append("\r\n");
      }

      if (!this.agent.isEmpty()) {
        headers = headers.append("User-Agent: ")
            .append(this.agent)
            .append("\r\n");
      }

      if (!this.type.isEmpty()) {
        headers = headers.append("Content-Type: ")
            .append(this.type)
            .append("\r\n");
      }

      if (!this.length.isEmpty()) {
        headers = headers.append("Content-Length: ")
            .append(this.length)
            .append("\r\n");
      }

      return headers.toString();
    }
  }

  private HeadersBuilder getCommonHeaders() {
    return new HeadersBuilder()
        .withAcceptEncoding("gzip")
        .withUserAgent("Jetty/11.0.6");
  }
}
