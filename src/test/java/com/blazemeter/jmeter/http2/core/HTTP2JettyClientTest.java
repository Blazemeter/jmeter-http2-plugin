package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import com.google.common.io.Resources;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import jodd.net.MimeTypes;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.control.AuthManager;
import org.apache.jmeter.protocol.http.control.AuthManager.Mechanism;
import org.apache.jmeter.protocol.http.control.Authorization;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.assertj.core.api.JUnitSoftAssertions;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpFields.Mutable;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpStatus.Code;
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
  private static final int SERVER_PORT = 6666;
  private static final String SERVER_RESPONSE = "Hello World!";
  private static final String SERVER_IMAGE = "/test/image.png";
  private static final String SERVER_PATH = "/test";
  private static final String SERVER_PATH_SET_COOKIES = "/test/set-cookies";
  private static final String SERVER_PATH_USE_COOKIES = "/test/use-cookies";
  private static final String RESPONSE_DATA_COOKIES = "testCookie=test";
  private static final String RESPONSE_DATA_COOKIES2 = "testCookie2=test";
  private static final String SERVER_PATH_200 = "/test/200";
  private static final String SERVER_PATH_SLOW = "/test/slow";
  private static final String SERVER_PATH_200_GZIP = "/test/gzip";
  private static final String SERVER_PATH_200_EMBEDDED = "/test/embedded";
  private static final String SERVER_PATH_200_FILE_SENT = "/test/file";
  private static final String SERVER_PATH_BIG_RESPONSE = "/test/big-response";
  private static final String SERVER_PATH_400 = "/test/400";
  private static final String SERVER_PATH_302 = "/test/302";
  private static final String SERVER_PATH_200_WITH_BODY = "/test/body";
  private static final String SERVER_PATH_DELETE_DATA = "/test/delete";
  private static final String TEST_ARGUMENT_1 = "valueTest1";
  private static final String TEST_ARGUMENT_2 = "valueTest2";
  private static final String BASIC_HTML_TEMPLATE = "<!DOCTYPE html><html><head><title>Page "
      + "Title</title></head><body><div><img src='image.png'></div></body></html>";
  private static final byte[] BINARY_RESPONSE_BODY = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
  private static final String AUTH_USERNAME = "username";
  private static final String AUTH_PASSWORD = "password";
  private static final String AUTH_REALM = "realm";
  private static final String KEYSTORE_PASSWORD = "storepwd";
  private static final int BIG_BUFFER_SIZE = 4 * 1024 * 1024;

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
    configureSampler();
    client = new HTTP2JettyClient();
    client.start();
  }

  private void configureSampler() {
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain("server");
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(SERVER_PORT);
    sampler.setPath("");
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
    buildStartedServer();
    HTTPSampleResult result = sampleWithGet();
    assertThat(result.getResponseDataAsString()).isEqualTo(SERVER_RESPONSE);
  }

  private void buildStartedServer() throws Exception {
    buildServer().start();
  }

  private Server buildServer() {
    return buildServer(buildServerSslContextFactory());
  }

  private SslContextFactory.Server buildServerSslContextFactory() {
    SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
    sslContextFactory.setKeyStorePath(getKeyStorePath());
    sslContextFactory.setKeyStorePassword(KEYSTORE_PASSWORD);
    return sslContextFactory;
  }

  private String getKeyStorePath() {
    try {
      String[] arrPath = getClass().getResource("keystore.p12").toURI()
          .toString().split(":/");
      return "/" + arrPath[arrPath.length - 1];
    } catch (URISyntaxException e) {
      return null;
    }
  }

  private Server buildServer(SslContextFactory.Server sslContextFactory) {
    HttpConfiguration httpsConfig = new HttpConfiguration();
    httpsConfig.addCustomizer(new SecureRequestCustomizer());

    Server server = new Server();

    // HTTP Connector
    ServerConnector http = new ServerConnector(server, new HttpConnectionFactory(httpsConfig),
        new HTTP2CServerConnectionFactory(httpsConfig));
    http.setReusePort(false);
    server.addConnector(http);

    HttpConnectionFactory h1 = new HttpConnectionFactory(httpsConfig);

    ConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);
    ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
    alpn.setDefaultProtocol(http.getDefaultProtocol());

    ConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
    connector =
        new ServerConnector(server, 1, 1, ssl, alpn, h2, h1);
    connector.setPort(SERVER_PORT);
    connector.setReusePort(false);
    server.addConnector(connector);

    ServletContextHandler context = new ServletContextHandler(server, "/", true, false);
    context.addServlet(new ServletHolder(buildServlet()), SERVER_PATH + "/*");

    return server;
  }

  private HttpServlet buildServlet() {
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
            gzipOutputStream.write(HTTP2JettyClientTest.BINARY_RESPONSE_BODY);
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

  private HTTPSampleResult sampleWithGet() throws Exception {
    return sampleWithGet(SERVER_PATH_200);
  }

  private HTTPSampleResult sampleWithGet(String path) throws Exception {
    return sample(path, HTTPConstants.GET);
  }

  private HTTPSampleResult sample(String path, String method) throws Exception {
    client.loadProperties(); // Ensure to load the changes of properties in execution context
    return client.sample(sampler, buildBaseResult(createURL(path), method), false, 0);
  }

  private URL createURL(String path) throws MalformedURLException {
    return new URL(HTTPConstants.PROTOCOL_HTTPS, HOST_NAME, SERVER_PORT, path);
  }

  private HTTPSampleResult buildBaseResult(URL url, String method) {
    HTTPSampleResult ret = new HTTPSampleResult();
    ret.setURL(url);
    ret.setHTTPMethod(method);
    return ret;
  }

  @Test
  public void shouldThrowConnectExceptionWhenServerIsInaccessible() {
    try {
      client.sample(sampler,
          buildBaseResult(new URL(HTTPConstants.PROTOCOL_HTTPS, HOST_NAME, 123, SERVER_PATH_200),
              HTTPConstants.GET), false, 0);
    } catch (Exception ex) {
      assert ((ex instanceof ExecutionException) || (ex instanceof TimeoutException));
    }
  }

  @Test
  public void shouldReturnSuccessSampleResultWhenSuccessResponseWithContentTypeGzip()
      throws Exception {
    buildStartedServer();
    HeaderManager hm = new HeaderManager();
    hm.add(new Header(HttpHeader.ACCEPT_ENCODING.asString(), "gzip"));
    sampler.setHeaderManager(hm);
    HTTPSampleResult result = sampleWithGet(SERVER_PATH_200_GZIP);
    assertThat(result.getResponseHeaders().indexOf("content-encoding: gzip")).isNotEqualTo(-1);
  }

  @Test
  public void shouldSendBodyInformationWhenRequestWithBodyRaw() throws Exception {
    buildStartedServer();
    sampler.addArgument("", TEST_ARGUMENT_1);
    sampler.addArgument("", TEST_ARGUMENT_2);
    HTTPSampleResult expected = buildOkResult(TEST_ARGUMENT_1 + TEST_ARGUMENT_2,
        "application/octet-stream");
    validateResponse(sample(SERVER_PATH_200_WITH_BODY, HTTPConstants.POST), expected);
  }

  private HTTPSampleResult buildOkResult(String requestBody, String requestContentType) {
    return buildResult(true, HttpStatus.Code.OK, null,
        requestBody != null ? requestBody.getBytes(StandardCharsets.UTF_8) : null,
        requestContentType);
  }

  private HTTPSampleResult buildResult(boolean successful, HttpStatus.Code statusCode,
                                       HttpFields headers, byte[] requestBody,
                                       String requestContentType) {

    Mutable httpFields = HttpFields.build();

    if (requestContentType != null) {
      httpFields.add(HttpHeader.CONTENT_TYPE, requestContentType);
    }
    if (headers != null) {
      httpFields.add(headers);
    }
    if (requestBody != null) {
      String requestBodyLength = Integer.toString(requestBody.length);
      httpFields.add(HttpHeader.CONTENT_LENGTH, requestBodyLength);
    }
    String headersString = httpFields.toString().replace("\r\n", "\n");
    headersString = headersString.substring(0, headersString.length() - 1); // remove last \n

    HTTPSampleResult expected = new HTTPSampleResult();
    expected.setSuccessful(successful);
    expected.setResponseCode(String.valueOf(statusCode.getCode()));
    expected.setResponseMessage(statusCode.getMessage());
    expected.setSentBytes(requestBody != null ? requestBody.length : 0);
    expected.setRequestHeaders(headersString);
    expected.setResponseData(requestBody);
    return expected;
  }

  private void validateResponse(SampleResult result, SampleResult expected) {
    softly.assertThat(result.getRequestHeaders()).isEqualTo(expected.getRequestHeaders());
    softly.assertThat(result.isSuccessful()).isEqualTo(expected.isSuccessful());
    softly.assertThat(result.getResponseCode()).isEqualTo(expected.getResponseCode());
    softly.assertThat(result.getResponseMessage()).isEqualTo(expected.getResponseMessage());
    softly.assertThat(result.getSentBytes()).isEqualTo(expected.getSentBytes());
    softly.assertThat(result.getResponseDataAsString())
        .isEqualTo(expected.getResponseDataAsString());
  }

  @Test
  public void shouldSendBodyInformationWhenRequestWithArguments() throws Exception {
    buildStartedServer();
    sampler.setMethod(HTTPConstants.POST);
    sampler.addArgument("test1", TEST_ARGUMENT_1);
    sampler.addArgument("test2", TEST_ARGUMENT_2);
    HTTPSampleResult expected = buildOkResult("test1=" + TEST_ARGUMENT_1 + "&" + "test2="
        + TEST_ARGUMENT_2, "application/x-www-form-urlencoded");
    validateResponse(sample(SERVER_PATH_200_WITH_BODY, HTTPConstants.POST), expected);
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
    buildStartedServer();
    sampler.setMethod(HTTPConstants.DELETE);
    sampler.addArgument("", TEST_ARGUMENT_1);
    sampler.addArgument("", TEST_ARGUMENT_2);
    HTTPSampleResult expected = buildOkResult(TEST_ARGUMENT_1 + TEST_ARGUMENT_2,
        "application/octet-stream");
    validateResponse(sample(SERVER_PATH_200_WITH_BODY, HTTPConstants.DELETE), expected);
  }

  @Test
  public void shouldReturnFailureSampleResultWhenResponse400() throws Exception {
    buildStartedServer();
    HTTPSampleResult expected = buildResult(false, Code.BAD_REQUEST, null, null, null);
    validateResponse(sampleWithGet(SERVER_PATH_400), expected);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void shouldReturnErrorMessageWhenMethodIsNotSupported() throws Exception {
    client.sample(sampler, buildBaseResult(createURL(SERVER_PATH_200), "MethodNotSupported"), false,
        0);
  }

  @Test
  public void shouldGetEmbeddedResourcesWithSubSampleWhenImageParserIsEnabled() throws Exception {
    buildStartedServer();
    sampler.setImageParser(true);
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED),
        buildOkResultWithResponse(BASIC_HTML_TEMPLATE));
  }

  private void validateEmbeddedResources(HTTPSampleResult result, HTTPSampleResult expected)
      throws MalformedURLException {
    SampleResult[] results = result.getSubResults();
    validateResponse(result, expected);
    softly.assertThat(result.getSubResults().length).isGreaterThan(0);
    assertResultTypeAndUrl(results[0], SampleResult.TEXT, SERVER_PATH_200_EMBEDDED);
    assertResultTypeAndUrl(results[1], SampleResult.BINARY, SERVER_IMAGE);
  }

  private void assertResultTypeAndUrl(SampleResult result, String type, String path)
      throws MalformedURLException {
    softly.assertThat(result.getDataType()).isEqualTo(type);
    softly.assertThat(result.getUrlAsString()).isEqualTo(createURL(path).toString());
  }

  private HTTPSampleResult buildOkResultWithResponse(String responseBody) {
    HTTPSampleResult expected = buildOkResult(null, null);
    expected.setResponseData(responseBody, StandardCharsets.UTF_8.name());
    return expected;
  }

  @Test
  public void shouldNotDownloadEmbeddedResourcesWhenUrlDoesNotMatchFilter() throws Exception {
    buildStartedServer();
    sampler.setImageParser(true);
    sampler.setEmbeddedUrlRE(".+css");
    validateEmbeddedResourcesWithUrlFilter(sampleWithGet(SERVER_PATH_200_EMBEDDED),
        buildOkResultWithResponse(BASIC_HTML_TEMPLATE));
  }

  private void validateEmbeddedResourcesWithUrlFilter(HTTPSampleResult result,
                                                      HTTPSampleResult expected) {
    SampleResult[] results = result.getSubResults();
    validateResponse(result, expected);
    softly.assertThat(result.getSubResults().length).isEqualTo(1);
    softly.assertThat(results[0].getDataType()).isNotEqualTo(SampleResult.BINARY);
  }

  @Test
  public void shouldUseCookiesFromFirstRequestOnSecondRequestWhenSecondRequestIsSent()
      throws Exception {
    buildStartedServer();
    CookieManager cookieManager = new CookieManager();
    cookieManager.testStarted(HOST_NAME);
    sampler.setCookieManager(cookieManager);
    sampleWithGet(SERVER_PATH_SET_COOKIES);
    HTTPSampleResult result = sampleWithGet(SERVER_PATH_SET_COOKIES);
    HTTPSampleResult expected = buildOkResult(null, null);
    expected.setCookies(RESPONSE_DATA_COOKIES + "; " + RESPONSE_DATA_COOKIES2);
    validateResponse(result, expected);
    softly.assertThat(result.getCookies()).isEqualTo(expected.getCookies());
  }

  @Test
  public void shouldReturnSuccessSampleResultWhenSuccessRequestWithHeaders() throws Exception {
    buildStartedServer();
    HeaderManager hm = new HeaderManager();
    String headerName1 = "Header1";
    String headerValue1 = "value1";
    hm.add(new Header(headerName1, headerValue1));
    String headerName2 = "Header2";
    String headerValue2 = "value2";
    hm.add(new Header(headerName2, headerValue2));
    sampler.setHeaderManager(hm);
    Mutable httpFields = HttpFields.build()
        .add(headerName1, headerValue1)
        .add(headerName2, headerValue2);
    HTTPSampleResult expected = buildResult(true, Code.OK,
        httpFields, null, null);
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    validateResponse(sampleWithGet(), expected);
  }

  @Test
  public void shouldReturnSuccessDigestAuthSampleResultWhenAuthDigestIsSet() throws Exception {
    Server server = buildServer();
    configureDigestAuth(server);
    server.start();
    configureAuthManager(Mechanism.DIGEST);
    validateResponse(sampleWithGet(), buildOkResultWithResponse(SERVER_RESPONSE));
  }

  private void configureDigestAuth(Server server) {
    configureAuthHandler(server, new DigestAuthenticator(), Constraint.__DIGEST_AUTH);
  }

  private void configureAuthHandler(Server server, Authenticator authenticator, String mechanism) {
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

  private void configureAuthManager(Mechanism mechanism) throws MalformedURLException {
    Authorization authorization = new Authorization();
    authorization.setURL(createURL(SERVER_PATH_200).toString());
    authorization.setUser(AUTH_USERNAME);
    authorization.setPass(AUTH_PASSWORD);
    authorization.setRealm(AUTH_REALM);
    authorization.setMechanism(mechanism);

    AuthManager authManager = new AuthManager();
    authManager.addAuth(authorization);
    sampler.setAuthManager(authManager);
  }

  @Test
  public void shouldReturnSuccessBasicAuthSampleResultWhenPreemptiveIsFalse() throws Exception {
    Server server = buildServer();
    configureBasicAuth(server);
    server.start();
    configureAuthManager(Mechanism.BASIC);
    validateResponse(sampleWithGet(), buildOkResultWithResponse(SERVER_RESPONSE));
  }

  private void configureBasicAuth(Server server) {
    configureAuthHandler(server, new BasicAuthenticator(), Constraint.__BASIC_AUTH);
  }

  @Test
  public void shouldReturnSuccessBasicAuthSampleResultWhenHeaderIsSet() throws Exception {
    Mutable httpFields = HttpFields.build()
        .add(HttpHeader.AUTHORIZATION,
            "Basic " + base64Encode(AUTH_USERNAME + ":" + AUTH_PASSWORD));
    Server server = buildServer();
    configureBasicAuth(server);
    server.start();
    JMeterUtils.setProperty("httpJettyClient.auth.preemptive", "true");
    configureAuthManager(Mechanism.BASIC);
    HTTPSampleResult expected = buildResult(true, Code.OK,
        httpFields, null, null);
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    validateResponse(sampleWithGet(), expected);
  }

  private String base64Encode(String input) {
    return Base64.getEncoder().encodeToString(input.getBytes());
  }

  @Test
  public void shouldGetRedirectedResultWithSubSampleWhenFollowRedirectEnabledAndRedirected()
      throws Exception {
    buildStartedServer();
    sampler.setFollowRedirects(true);
    HTTPSampleResult expected = buildOkResultWithResponse(SERVER_RESPONSE);
    expected.setRedirectLocation("https://localhost:6666/test/200");
    validateRedirects(sampleWithGet(SERVER_PATH_302), expected);
  }

  private void validateRedirects(HTTPSampleResult result, HTTPSampleResult expected) {
    validateResponse(result, expected);
    softly.assertThat(result.getSubResults().length).isGreaterThan(0);
    softly.assertThat(result.getRedirectLocation()).isEqualTo(expected.getRedirectLocation());
  }

  @Test
  public void shouldGetOnlyRedirectedResultWhenFollowRedirectDisabledAndRedirected()
      throws Exception {
    buildStartedServer();
    HTTPSampleResult result = sampleWithGet(SERVER_PATH_302);
    softly.assertThat(result.getResponseCode()).isEqualTo("302");
    softly.assertThat(result.getSubResults().length).isEqualTo(0);
  }

  @Test
  public void shouldGetOnlyRedirectedResultWhenRedirectAutomaticallyEnabledAndRedirected()
      throws Exception {
    buildStartedServer();
    sampler.setAutoRedirects(true);
    HTTPSampleResult result = sampleWithGet(SERVER_PATH_302);
    softly.assertThat(result.getResponseCode()).isEqualTo("200");
    softly.assertThat(result.getSubResults().length).isEqualTo(0);
    softly.assertThat(result.getUrlAsString())
        .isEqualTo("https://localhost:" + SERVER_PORT + SERVER_PATH_200);
  }

  @Test
  public void shouldGetFileDataWithFileIsSentAsBodyPart() throws Exception {
    buildStartedServer();
    URL urlFile = getClass().getResource("blazemeter-labs-logo.png");
    String pathFile = new File(urlFile.getFile()).toPath().toAbsolutePath().toString();
    HTTPFileArg fileArg = new HTTPFileArg(pathFile, "", "image/png");
    sampler.setHTTPFiles(new HTTPFileArg[] {fileArg});
    HTTPSampleResult result = sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST);
    HTTPSampleResult expected = buildResult(true, Code.OK, null, Resources.toByteArray(urlFile),
        "image/png");
    validateResponse(result, expected);
  }

  @Test(expected = TimeoutException.class)
  public void shouldThrowTimeoutExceptionWhenResponseBiggerThanTimeout() throws Exception {
    buildStartedServer();
    long timeout = 1000;
    sampler.setResponseTimeout(String.valueOf(timeout));
    sampleWithGet(SERVER_PATH_SLOW);
  }

  @Test
  public void shouldNoUseCacheWhenNotUseExpire() throws Exception {
    buildStartedServer();
    sampler.setImageParser(true);
    configureCacheManagerToSampler(false, false);
    // First request must connect to the server
    HTTPSampleResult expected = buildOkResultWithResponse(BASIC_HTML_TEMPLATE);
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), expected);
    // Same request connect again because use expire is false
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), expected);
  }

  private void configureCacheManagerToSampler(boolean useExpire, boolean clearCacheIteration) {
    CacheManager cacheManager = new CacheManager();
    cacheManager.setUseExpires(useExpire);
    cacheManager.setClearEachIteration(clearCacheIteration);
    cacheManager.testIterationStart(null); // Used to initialize private attrs
    sampler.setCacheManager(cacheManager);
  }

  @Test
  public void shouldNotGetSubResultWhenResourceIsCachedWithCode() throws Exception {
    buildStartedServer();
    sampler.setImageParser(true);
    String message = "message";
    String responseCode = "300";
    JMeterUtils.setProperty("cache_manager.cached_resource_mode", "RETURN_CUSTOM_STATUS");
    JMeterUtils.setProperty("RETURN_CUSTOM_STATUS.message", message);
    JMeterUtils.setProperty("RETURN_CUSTOM_STATUS.code", responseCode);
    configureCacheManagerToSampler(true, false);
    // First request must connect to the server
    HTTPSampleResult expected = buildOkResultWithResponse(BASIC_HTML_TEMPLATE);
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), expected);
    // Same request use cached result with no message, request and data response
    expected.setResponseCode(responseCode);
    expected.setResponseMessage(message);
    expected.setRequestHeaders("");
    expected.setResponseData("", StandardCharsets.UTF_8.name());
    validateEmbeddedResultCached(sampleWithGet(SERVER_PATH_200_EMBEDDED), expected);
  }

  /**
   * Validate same result as expected, but also control that not sample result was added.
   */
  private void validateEmbeddedResultCached(HTTPSampleResult result, HTTPSampleResult expected) {
    this.validateResponse(result, expected);
    softly.assertThat(result.getResponseData().length).isEqualTo(0);
  }

  @Test
  public void shouldNotGetSubResultWhenResourceIsCachedWithoutCode() throws Exception {
    buildStartedServer();
    sampler.setImageParser(true);
    String message = "message";
    JMeterUtils.setProperty("cache_manager.cached_resource_mode", "RETURN_200_CACHE");
    JMeterUtils.setProperty("RETURN_200_CACHE.message", message);
    configureCacheManagerToSampler(true, false);
    // First request must connect to the server
    HTTPSampleResult expected = buildOkResultWithResponse(BASIC_HTML_TEMPLATE);
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), expected);
    // Same request use cached result with message response from property system
    expected.setRequestHeaders("");
    expected.setResponseData("", StandardCharsets.UTF_8.name());
    expected.setResponseMessage(message);
    validateEmbeddedResultCached(sampleWithGet(SERVER_PATH_200_EMBEDDED), expected);
  }

  @Test
  public void shouldGetSubResultWhenCacheCleanBetweenIterations() throws Exception {
    buildStartedServer();
    sampler.setImageParser(true);
    configureCacheManagerToSampler(false, true);
    // First request must connect to the server
    HTTPSampleResult expected = buildOkResultWithResponse(BASIC_HTML_TEMPLATE);
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), expected);
    // Same request connect again because clear cache iteration is enabled
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), expected);
  }

  @Test
  public void shouldGetTwoFilesAndTwoParams() throws Exception {
    buildStartedServer();
    List<HTTPArgument> args = Arrays.asList(buildArg("Param1", "Valor1"),
        buildArg("Param2", "Valor2"));
    List<HTTPFileArg> files = Arrays.asList(buildFile("blazemeter-labs-logo1"),
        buildFile("blazemeter-labs-logo2"));
    configureMultipartSampler(args, files);
    HTTPSampleResult result = sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST);
    HTTPSampleResult expected = buildMultipartResult(args, files, result);
    validateMultipartResponse(result, expected);
  }

  private void configureMultipartSampler(List<HTTPArgument> argsList, List<HTTPFileArg> files) {
    sampler.setImageParser(true); // Indicates download embedded resources
    sampler.setDoMultipart(true);
    if (!argsList.isEmpty()) {
      Arguments args = new Arguments();
      argsList.forEach(args::addArgument);
      sampler.setArguments(args);
    }
    if (!files.isEmpty()) {
      sampler.setHTTPFiles(files.toArray(new HTTPFileArg[0]));
    }
  }

  private HTTPArgument buildArg(String name, String value) {
    return new HTTPArgument(name, value);
  }

  private HTTPFileArg buildFile(String name) {
    String filePath =
        (new File(getClass().getResource("blazemeter-labs-logo.png").getFile())).toPath()
            .toAbsolutePath().toString();
    return new HTTPFileArg(filePath, name, "image/png");
  }

  private HTTPSampleResult buildMultipartResult(List<HTTPArgument> args, List<HTTPFileArg> files,
                                                HTTPSampleResult result) throws IOException {
    // Get JettyHttpClientBoundary from result
    String boundary = result.getResponseDataAsString().split("\\r\\n")[0];
    HTTPSampleResult expected = buildOkResult(null, null);
    Mutable httpFields = HttpFields.build()
        .add(HttpHeader.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary.substring(2));
    expected.setRequestHeaders(expected.getRequestHeaders().concat(httpFields.toString()));
    expected.setResponseData(buildByteArrayFromFilesAndParams(expected, args, files, boundary));
    return expected;
  }

  private byte[] buildByteArrayFromFilesAndParams(HTTPSampleResult expected,
                                                  List<HTTPArgument> args,
                                                  List<HTTPFileArg> files, String boundary)
      throws IOException {

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    String newLine = "\r\n";
    String dashDash = "--";
    // Set body response for Arguments
    args.forEach(httpArgument -> {
      Mutable headerParam = HttpFields.build()
          .add("Content-Disposition", "form-data; name=\"" + httpArgument.getEncodedName() + "\"")
          .add(HttpHeader.CONTENT_TYPE, "null" + "\r\n\r\n" + httpArgument.getEncodedValue());
      try {
        String headerParamWithBoundary = boundary + newLine + headerParam.toString();
        output.write(headerParamWithBoundary.getBytes());
      } catch (IOException e) {
        e.printStackTrace();
      }
    });

    // Set body response for Files
    files.forEach(file -> {
      String fileName = Paths.get((file.getPath())).getFileName().toString();
      Mutable headerFile = HttpFields.build()
          .add("Content-Disposition", "form-data; name=\"" + file.getParamName()
              + "\"; " + "filename=\"" + fileName + "\"")
          .add(HttpHeader.CONTENT_TYPE, file.getMimeType());
      try {
        String filePath = file.getPath();
        InputStream inputStream = Files.newInputStream(Paths.get(filePath));
        byte[] data = sampler.readResponse(expected, inputStream, 0);
        String headerFileWithBoundary = boundary + newLine + headerFile.toString();
        output.write(headerFileWithBoundary.getBytes());
        output.write(data);
        output.write(newLine.getBytes());
      } catch (IOException e) {
        e.printStackTrace();
      }
    });

    String finalResponse = dashDash + newLine;
    output.write(boundary.getBytes());
    output.write(finalResponse.getBytes());

    return output.toByteArray();
  }

  private void validateMultipartResponse(HTTPSampleResult result, HTTPSampleResult expected) {
    softly.assertThat(result.isSuccessful()).isEqualTo(expected.isSuccessful());
    softly.assertThat(result.getResponseCode()).isEqualTo(expected.getResponseCode());
    softly.assertThat(result.getResponseDataAsString())
        .isEqualToIgnoringNewLines(expected.getResponseDataAsString());
    softly.assertThat(result.getRequestHeaders())
        .isEqualToIgnoringNewLines(expected.getRequestHeaders());
  }

  @Test
  public void shouldGetOneFileAndOneParam() throws Exception {
    buildStartedServer();
    List<HTTPArgument> args = Collections.singletonList(buildArg("Param1", "Valor1"));
    List<HTTPFileArg> files = Collections.singletonList(buildFile("blazemeter-labs-logo"));
    configureMultipartSampler(args, files);
    HTTPSampleResult result = sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST);
    HTTPSampleResult expected = buildMultipartResult(args, files, result);
    validateMultipartResponse(result, expected);
  }

  @Test
  public void shouldGetOnlyTwoFiles() throws Exception {
    buildStartedServer();
    List<HTTPArgument> args = Collections.emptyList();
    List<HTTPFileArg> files = Arrays.asList(buildFile("blazemeter-labs-logo1"),
        buildFile("blazemeter-labs-logo2"));
    configureMultipartSampler(args, files);
    HTTPSampleResult result = sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST);
    HTTPSampleResult expected = buildMultipartResult(args, files, result);
    validateMultipartResponse(result, expected);
  }

  @Test
  public void shouldGetOnlyTwoParams() throws Exception {
    buildStartedServer();
    List<HTTPArgument> args = Arrays.asList(buildArg("Param1", "Valor1"),
        buildArg("Param2", "Valor2"));
    List<HTTPFileArg> files = Collections.emptyList();
    configureMultipartSampler(args, files);
    HTTPSampleResult result = sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST);
    HTTPSampleResult expected = buildMultipartResult(args, files, result);
    validateMultipartResponse(result, expected);
  }

  @Test
  public void shouldReturnErrorInBlankFileName() throws Exception {
    buildStartedServer();
    configureMultipartSampler(Collections.singletonList(buildArg("Param1", "Valor1")),
        Collections.singletonList(buildFile("")));
    IllegalStateException exception = assertThrows(IllegalStateException.class,
        () -> sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST));
    softly.assertThat(exception.toString())
        .contains("java.lang.IllegalStateException: Param name is blank");
  }

  @Test
  public void shouldUseMultipartWhenHasFilesAndNotSendAsPostBody() throws Exception {
    buildStartedServer();
    List<HTTPArgument> args = Collections.emptyList();
    List<HTTPFileArg> files = Arrays.asList(buildFile("blazemeter-labs-logo1"),
        buildFile("blazemeter-labs-logo2"));
    configureMultipartSampler(args, files);
    sampler.setDoMultipart(false);
    HTTPSampleResult result = sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST);
    HTTPSampleResult expected = buildMultipartResult(args, files, result);
    validateMultipartResponse(result, expected);
  }

  @Test
  public void shouldNotUseMultipartWhenHasOneFileWithEmptyParamName() throws Exception {
    buildStartedServer();
    URL urlFile = getClass().getResource("blazemeter-labs-logo.png");
    String pathFile = new File(urlFile.getFile()).toPath().toAbsolutePath().toString();
    sampler.setHTTPFiles(new HTTPFileArg[] {new HTTPFileArg(pathFile, "", "image/png")});
    HTTPSampleResult expected = buildResult(true, HttpStatus.Code.OK, null,
        Resources.toByteArray(urlFile), "image/png");
    validateResponse(sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST), expected);
  }

  @Test(expected = ExecutionException.class)
  public void shouldThrowExceptionWhenServerRequiresClientCertAndNoneIsConfigured()
      throws Exception {
    buildStartedServerWithClientAuthRequirement();
    sampleWithGet();
  }

  private void buildStartedServerWithClientAuthRequirement() throws Exception {
    SslContextFactory.Server contextFactory = buildServerSslContextFactory();
    contextFactory.setNeedClientAuth(true);
    buildServer(contextFactory).start();
  }

  @Test
  public void shouldGetSuccessResponseWhenServerRequiresClientCertAndOneIsConfigured()
      throws Exception {
    buildStartedServerWithClientAuthRequirement();
    String keyStorePropertyName = "javax.net.ssl.keyStore";
    String keyStorePasswordPropertyName = "javax.net.ssl.keyStorePassword";
    System.setProperty(keyStorePropertyName, getKeyStorePath());
    System.setProperty(keyStorePasswordPropertyName, KEYSTORE_PASSWORD);
    client.stop();
    client = new HTTP2JettyClient();
    client.start();
    try {
      HTTPSampleResult result = sampleWithGet();
      assertThat(result.getResponseDataAsString()).isEqualTo(SERVER_RESPONSE);
    } finally {
      System.setProperty(keyStorePropertyName, "");
      System.setProperty(keyStorePasswordPropertyName, "");
    }
  }

  @Test
  public void shouldGetResponseWhenBufferSizeIsSmallerOrTheSameAsMaxBufferSize() throws Exception {
    buildStartedServer();
    JMeterUtils.setProperty("httpJettyClient.maxBufferSize", String.valueOf(BIG_BUFFER_SIZE));
    HTTPSampleResult result = sampleWithGet(SERVER_PATH_BIG_RESPONSE);
    //Since no text response was set, we validate the size of the response body instead.
    assertThat(result.getBodySizeAsLong()).isEqualTo(BIG_BUFFER_SIZE);
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldThrowAnExceptionWhenBufferSizeIsBiggerThanMaxBufferSize() throws Exception {
    buildStartedServer();
    JMeterUtils.setProperty("httpJettyClient.maxBufferSize", String.valueOf(BIG_BUFFER_SIZE - 1));
    sampleWithGet(SERVER_PATH_BIG_RESPONSE);
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldNotGetAResponseWhenBufferSizeIsBiggerThanMaxBufferSize() throws Exception {
    buildStartedServer();
    JMeterUtils.setProperty("httpJettyClient.maxBufferSize", String.valueOf(BIG_BUFFER_SIZE - 1));
    //There is no response, since an exception is thrown in this case
    sampleWithGet(SERVER_PATH_BIG_RESPONSE);
  }

}
