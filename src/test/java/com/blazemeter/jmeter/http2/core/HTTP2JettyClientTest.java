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
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;
import jodd.net.MimeTypes;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.AuthManager.Mechanism;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.assertj.core.api.JUnitSoftAssertions;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.UserStore;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;
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
  private static final String SERVER_IMAGE = "/test/image.png";
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
  private static final int SERVER_PORT = 6666;
  private static final String[] ROLES = new String[]{"can-access"};
  private static final String BASIC_HTML_TEMPLATE = "<!DOCTYPE html><html><head><title>Page "
      + "Title</title></head><body><div><img src='image.png'></div></body></html>";
  private final String imagePath = getClass().getResource("blazemeter-labs-logo"
      + ".png").getPath();
  private static final String MESSAGE_CACHED = "(ex cache)";
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
    startServer(setupServer(createGetServerResponse()));
    HTTPSampleResult result = client.sample(sampler, createURL(SERVER_PATH_200),
        HTTPConstants.GET, false, 0);
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
    startServer(setupServer(createGetServerResponse()));
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
            resp.getWriter().write(BASIC_HTML_TEMPLATE);
            resp.addHeader(HTTPConstants.EXPIRES,
                "Sat, 25 Sep 2041 00:00:00 GMT");
            return;
          case SERVER_IMAGE:
            resp.getOutputStream().write(new byte[]{1, 2, 3, 4, 5});
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
        }
      }
    };
  }

  private Server setupServer(HttpServlet servlet) throws Exception {
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

    return server;
  }

  private void startServer(Server server) throws Exception {
    server.start();
  }

  @Test
  public void shouldReturnFailureSampleResultWhenResponse400() throws Exception {
    HTTPSampleResult expected = createExpectedResult(false, HttpStatus.BAD_REQUEST_400,
        REQUEST_HEADERS);
    startServer(setupServer(createGetServerResponse()));
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
    expected.setResponseMessage(HttpStatus.getMessage(responseCode));
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
    HTTPSampleResult expected = createExpectedResult(true, HttpStatus.OK_200, REQUEST_HEADERS);
    expected
        .setResponseData(HTTP2JettyClientTest.BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    startServer(setupServer(createGetServerResponse()));
    sampler.setImageParser(true);
    HTTPSampleResult result = client
        .sample(sampler, createURL(SERVER_PATH_200_EMBEDDED), HTTPConstants.GET, false, 0);
    validateEmbeddedResources(result, expected);
  }

  @Test
  public void shouldUseCookiesFromFirstRequestOnSecondRequestWhenSecondRequestIsSent()
      throws Exception {
    HTTPSampleResult expected = createExpectedResult(true, HttpStatus.OK_200, REQUEST_HEADERS);
    expected.setCookies(RESPONSE_DATA_COOKIES);
    expected.setResponseData(RESPONSE_DATA_COOKIES,
        StandardCharsets.UTF_8.name());
    startServer(setupServer(createGetServerResponse()));
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
    HTTPSampleResult expected = createExpectedResult(true, HttpStatus.OK_200,
        "Accept-Encoding: gzip\r\nUser-Agent: Jetty/11.0.6\r\nHeader1: "
            + "value1\r\nHeader2: value2\r\n\r\n");
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    startServer(setupServer(createGetServerResponse()));
    configureHeaderManagerToSampler();
    HTTPSampleResult result = client
        .sample(sampler, createURL(SERVER_PATH_200), HTTPConstants.GET, false, 0);
    validateResponse(result, expected);
  }

  @Test
  public void shouldReturnSuccessDigestAuthSampleResultWhenAuthDigestIsSet() throws Exception {
    HTTPSampleResult expected = new HTTPSampleResult();
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    expected.setResponseCode(String.valueOf(HttpStatus.OK_200));
    expected.setSuccessful(true);
    expected.setRequestHeaders(REQUEST_HEADERS);
    Server server = setupServer(createGetServerResponse());
    configureAuthenticationMechanisms(server, Constraint.__DIGEST_AUTH);
    startServer(server);
    configureAuthManager(Mechanism.DIGEST);
    HTTPSampleResult result = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200), HTTPConstants.GET, false, 0);
    validateResponse(result, expected);
  }

  @Test
  public void shouldReturnSuccessBasicAuthSampleResultWhenPreemptiveIsFalse() throws Exception {
    HTTPSampleResult expected = new HTTPSampleResult();
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    expected.setResponseCode(String.valueOf(HttpStatus.OK_200));
    expected.setSuccessful(true);
    expected.setRequestHeaders(REQUEST_HEADERS);
    Server server = setupServer(createGetServerResponse());
    configureAuthenticationMechanisms(server, Constraint.__BASIC_AUTH);
    startServer(server);
    configureAuthManager(Mechanism.BASIC);
    HTTPSampleResult result = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200), HTTPConstants.GET, false, 0);
    validateResponse(result, expected);
  }

  @Test
  public void shouldReturnSuccessBasicAuthSampleResultWhenHeaderIsSet() throws Exception {
    JMeterUtils.setProperty("httpJettyClient.auth.preemptive", "true");
    HTTPSampleResult expected = new HTTPSampleResult();
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    expected.setResponseCode(String.valueOf(HttpStatus.OK_200));
    expected.setSuccessful(true);
    expected.setRequestHeaders("Accept-Encoding: gzip\r\n"
        + "User-Agent: Jetty/11.0.6\r\n"
        + "Authorization: Basic dXNlcm5hbWU6cGFzc3dvcmQ=\r\n"
        + "\r\n");
    Server server = setupServer(createGetServerResponse());
    configureAuthenticationMechanisms(server, Constraint.__BASIC_AUTH);
    startServer(server);
    configureAuthManager(Mechanism.BASIC);
    HTTPSampleResult result = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200), HTTPConstants.GET, false, 0);
    validateResponse(result, expected);
  }

  @Test
  public void shouldGetRedirectedResultWithSubSampleWhenFollowRedirectEnabledAndRedirected()
      throws Exception {
    HTTPSampleResult expected = createExpectedResult(true, HttpStatus.OK_200, REQUEST_HEADERS);
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    expected.setRedirectLocation("https://localhost:6666/test/200");
    startServer(setupServer(createGetServerResponse()));
    configureSampler(HTTPConstants.GET);
    sampler.setFollowRedirects(true);
    HTTPSampleResult result = client
        .sample(sampler, createURL(SERVER_PATH_302), HTTPConstants.GET, false, 0);
    validateRedirects(result, expected);
  }

  @Test
  public void shouldGetOnlyRedirectedResultWhenFollowRedirectDisabledAndRedirected()
      throws Exception {
    startServer(setupServer(createGetServerResponse()));
    configureSampler(HTTPConstants.GET);
    HTTPSampleResult result = client
        .sample(sampler, createURL(SERVER_PATH_302), HTTPConstants.GET, false, 0);
    softly.assertThat(result.getResponseCode()).isEqualTo("302");
    softly.assertThat(result.getSubResults().length).isEqualTo(0);
  }

  @Test
  public void shouldGetOnlyRedirectedResultWhenRedirectAutomaticallyEnabledAndRedirected()
      throws Exception {
    startServer(setupServer(createGetServerResponse()));
    configureSampler(HTTPConstants.GET);
    sampler.setAutoRedirects(true);
    HTTPSampleResult result = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_302), HTTPConstants.GET, false, 0);
    softly.assertThat(result.getResponseCode()).isEqualTo("200");
    softly.assertThat(result.getSubResults().length).isEqualTo(0);
  }

  @Test
  public void shouldGetFileDataWithFileIsSentAsBodyPart() throws Exception {
    HTTPSampleResult expected = createExpectedResult(true, HttpStatus.OK_200,
        "Accept-Encoding: gzip\r\n"
            + "User-Agent: Jetty/11.0.6\r\n"
            + "Content-Type: image/png\r\n"
            + "Content-Length: 9018\r\n"
            + "\r\n");
    String filePath = getClass().getResource("blazemeter-labs-logo.png").getPath();
    InputStream inputStream = Files.newInputStream(Paths.get(filePath));
    expected.setResponseData(sampler.readResponse(expected, inputStream, 0));
    configureSampler(HTTPConstants.POST);
    HTTPFileArg fileArg = new HTTPFileArg(filePath, "", "image/png");
    sampler.setHTTPFiles(new HTTPFileArg[]{fileArg});
    startServer(setupServer(createGetServerResponse()));
    HTTPSampleResult result = client
        .sample(sampler, createURL(SERVER_PATH_200_FILE_SENT), HTTPConstants.POST, false, 0);
    validateResponse(result, expected);
  }

  @Test
  public void shouldReturnErrorMessageWhenResponseTimeIsOver() throws Exception {
    long timeout = 1000;
    startServer(setupServer(createGetServerResponse()));
    configureSampler(HTTPConstants.GET);
    sampler.setResponseTimeout(String.valueOf(timeout));
    Stopwatch waitTime = Stopwatch.createStarted();
    TimeoutException exception = assertThrows(TimeoutException.class, () -> {
      client.sample(sampler, createURL(SERVER_PATH_SLOW), HTTPConstants.GET, false, 0);
    });
    softly.assertThat(exception).isInstanceOf(TimeoutException.class);
    softly.assertThat(waitTime.elapsed(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(timeout);
  }

  @Test
  public void shouldNoUseCacheWhenNotUseExpire() throws Exception {
    HTTPSampleResult expected = createExpectedResultsAndServerResponse("200");
    configureCacheManagerToSampler(false, false);
    HTTPSampleResult result = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200_EMBEDDED), HTTPConstants.GET, false, 0);
    // First request must connect to the server
    validateEmbeddedResources(result, expected);
    HTTPSampleResult resultNotCached = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200_EMBEDDED), HTTPConstants.GET, false, 0);
    // Same request connect again because use expire is false
    validateEmbeddedResources(resultNotCached, expected);
  }

  @Test
  public void shouldNotGetSubResultWhenResourceIsCachedWithNoMsg() throws Exception {
    String message = "message";
    String responseCode = "300";
    JMeterUtils.setProperty("cache_manager.cached_resource_mode", "RETURN_CUSTOM_STATUS");
    JMeterUtils.setProperty("RETURN_CUSTOM_STATUS.message", message);
    JMeterUtils.setProperty("RETURN_CUSTOM_STATUS.code", responseCode);
    HTTPSampleResult expected = createExpectedResultsAndServerResponse("200");
    configureCacheManagerToSampler(true, false);
    HTTPSampleResult result = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200_EMBEDDED), HTTPConstants.GET, false, 0);
    // First request must connect to the server
    validateEmbeddedResources(result, expected);
    expected.setResponseCode(responseCode);
    HTTPSampleResult resultCached = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200_EMBEDDED), HTTPConstants.GET, false, 0);
    // Same request use cached result with no message, request and data response
    expected.setRequestHeaders("");
    expected.setResponseData("", StandardCharsets.UTF_8.name());
    validateEmbeddedResultCached(resultCached, expected, message);
  }

  @Test
  public void shouldNotGetSubResultWhenResourceIsCachedWithMsg() throws Exception {
    String message = "message";
    JMeterUtils.setProperty("cache_manager.cached_resource_mode", "RETURN_200_CACHE");
    JMeterUtils.setProperty("RETURN_200_CACHE.message", message);
    HTTPSampleResult expected = createExpectedResultsAndServerResponse("200");
    configureCacheManagerToSampler(true, false);
    HTTPSampleResult result = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200_EMBEDDED), HTTPConstants.GET, false, 0);
    // First request must connect to the server
    validateEmbeddedResources(result, expected);
    HTTPSampleResult resultCached = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200_EMBEDDED), HTTPConstants.GET, false, 0);
    // Same request use cached result with message response from property system
    expected.setRequestHeaders("");
    expected.setResponseData("",
        StandardCharsets.UTF_8.name());
    validateEmbeddedResultCached(resultCached, expected, message);
  }

  @Test
  public void shouldGetSubResultWhenCacheCleanBetweenIterations() throws Exception {
    HTTPSampleResult expected = createExpectedResultsAndServerResponse("200");
    configureCacheManagerToSampler(false, true);
    HTTPSampleResult result = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200_EMBEDDED), HTTPConstants.GET, false, 0);
    // First request must connect to the server
    validateEmbeddedResources(result, expected);
    HTTPSampleResult resultNotCached = client.sample(sampler, new URL(HTTPConstants.PROTOCOL_HTTPS,
        HOST_NAME, SERVER_PORT, SERVER_PATH_200_EMBEDDED), HTTPConstants.GET, false, 0);
    // Same request connect again because clear cache iteration is enabled
    validateEmbeddedResources(resultNotCached, expected);
  }

  private HTTPSampleResult createExpectedResultsAndServerResponse(String responseCode)
      throws Exception {
    HTTPSampleResult expected = new HTTPSampleResult();
    expected.setSuccessful(true);
    expected.setResponseCode(responseCode);
    expected.setRequestHeaders(REQUEST_HEADERS);
    expected.setResponseData(BASIC_HTML_TEMPLATE,
        StandardCharsets.UTF_8.name());
    startServer(setupServer(createGetServerResponse()));
    sampler.setImageParser(true); // Indicates download embedded resources

    return expected;
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

  private void configureAuthManager(Mechanism mechanism) {
    AuthManager authManager = new AuthManager();

    Authorization authorization = new Authorization();
    authorization.setURL(HTTPConstants.PROTOCOL_HTTPS
        .concat("://")
        .concat(HOST_NAME)
        .concat(":")
        .concat(String.valueOf(SERVER_PORT))
        .concat(SERVER_PATH_200));
    authorization.setUser("username");
    authorization.setPass("password");
    authorization.setRealm("realm");
    authorization.setMechanism(mechanism);

    authManager.addAuth(authorization);
    sampler.setAuthManager(authManager);
  }

  private void configureCacheManagerToSampler(boolean useExpire, boolean clearCacheIteration) {
    CacheManager cacheManager = new CacheManager();
    cacheManager.setUseExpires(useExpire);
    cacheManager.setClearEachIteration(clearCacheIteration);
    cacheManager.testIterationStart(null); // Use to initialize private attrs
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
    softly.assertThat(results[1].getUrlAsString())
        .isEqualTo("https://localhost:6666/test/image.png");
  }

  /**
   * Validate same result as expected, but also control that not sample result was added.
   *
   * @param messageResponse if passed, validate if message response is equal to defined.
   */
  private void validateEmbeddedResultCached(HTTPSampleResult result, HTTPSampleResult expected,
      String messageResponse) {
    this.validateResponse(result, expected);
    softly.assertThat(result.getResponseMessage()).isEqualTo(messageResponse);
    softly.assertThat(result.getResponseData().length).isEqualTo(0);
  }


  private void configureAuthenticationMechanisms(Server server, String mechanism) {
    HashLoginService loginService = getLoginService();

    switch (mechanism) {
      case Constraint.__BASIC_AUTH:
        server.setHandler(addBasicAuth(server, loginService));
        break;
      case Constraint.__DIGEST_AUTH:
        server.setHandler(addDigestAuth(server, loginService));
        break;
    }

  }

  private ConstraintSecurityHandler addDigestAuth(Server server, HashLoginService loginService) {
    ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
    securityHandler.setAuthenticator(new DigestAuthenticator());
    securityHandler.setConstraintMappings(
        Collections.singletonList(getConstraintByMechanism(Constraint.__DIGEST_AUTH)));

    return setCommonPropertiesAndReturnHandler(securityHandler, server, loginService);
  }

  private ConstraintSecurityHandler addBasicAuth(Server server, HashLoginService loginService) {
    ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
    securityHandler.setAuthenticator(new BasicAuthenticator());
    securityHandler.setConstraintMappings(
        Collections.singletonList(getConstraintByMechanism(Constraint.__BASIC_AUTH)));

    return setCommonPropertiesAndReturnHandler(securityHandler, server, loginService);
  }

  private ConstraintSecurityHandler setCommonPropertiesAndReturnHandler(
      ConstraintSecurityHandler securityHandler, Server server, HashLoginService loginService) {
    securityHandler.setRealmName("realm");
    securityHandler.setLoginService(loginService);
    securityHandler.setHandler(server.getHandler());

    return securityHandler;
  }

  private HashLoginService getLoginService() {
    HashLoginService loginService = new HashLoginService();
    loginService.setName("realm");
    loginService.setUserStore(buildUserStore());

    return loginService;
  }

  private ConstraintMapping getConstraintByMechanism(String mechanism) {
    Constraint constraint = new Constraint();
    constraint.setName(mechanism);
    constraint.setAuthenticate(true);
    constraint.setRoles(ROLES);

    ConstraintMapping mapping = new ConstraintMapping();
    mapping.setPathSpec("/*");
    mapping.setConstraint(constraint);

    return mapping;
  }

  private UserStore buildUserStore() {
    UserStore userStore = new UserStore();
    userStore.addUser("username", new Password("password"), ROLES);
    return userStore;
  }
}
