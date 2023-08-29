package com.blazemeter.jmeter.http2.core;

import static com.blazemeter.jmeter.http2.core.ServerBuilder.AUTH_PASSWORD;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.AUTH_REALM;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.AUTH_USERNAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.BASIC_HTML_TEMPLATE;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.BIG_BUFFER_SIZE;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.HOST_NAME;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.KEYSTORE_PASSWORD;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.RESPONSE_DATA_COOKIES;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.RESPONSE_DATA_COOKIES2;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_IMAGE;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_EMBEDDED;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_FILE_SENT;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_GZIP;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_200_WITH_BODY;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_302;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_400;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_BIG_RESPONSE;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_SET_COOKIES;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PATH_SLOW;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_PORT;
import static com.blazemeter.jmeter.http2.core.ServerBuilder.SERVER_RESPONSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.ServerBuilder.TeardownableServer;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import com.google.common.io.Resources;
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
import java.util.function.Supplier;
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
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpFields.Mutable;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpStatus.Code;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class HTTP2JettyClientTest extends HTTP2TestBase {

  private static final String TEST_ARGUMENT_1 = "valueTest1";
  private static final String TEST_ARGUMENT_2 = "valueTest2";
  //Supplier due to mutable otherwise values may vary when adding headers on top
  private static final Supplier<Mutable> HOST_HTTP_2_UPGRADE_HEADERS = () -> HttpFields.build()
      .add(HttpHeader.UPGRADE, "h2c")
      .add(HttpHeader.HTTP2_SETTINGS, "")
      .add(HttpHeader.CONNECTION, "Upgrade, HTTP2-Settings")
      .add(HttpHeader.HOST, "localhost:6666");
  private static final Supplier<Mutable> HOST_HEADER = () -> HttpFields.build()
      .add(HttpHeader.HOST, "localhost:6666");

  @Rule
  public final JUnitSoftAssertions softly = new JUnitSoftAssertions();
  private HTTP2JettyClient client;
  private HTTP2Sampler sampler;
  private TeardownableServer server;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setup() throws Exception {
    sampler = new HTTP2Sampler();
    configureSampler(sampler);
    client = new HTTP2JettyClient();
    client.start();
  }

  private static void configureSampler(HTTP2Sampler sampler) {
    sampler.setMethod(HTTPConstants.GET);
    sampler.setDomain("server");
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(SERVER_PORT);
    sampler.setPath("");
  }

  @After
  public void teardown() throws Exception {
    sampler.threadFinished();
    client.stop();
    client = null;
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void shouldInitializeHTTP2JettyClient() {
    boolean http1UpgradeRequired = true;
    String name = "TestClient";

    HTTP2JettyClient httpClient = new HTTP2JettyClient(http1UpgradeRequired, name);

    assertNotNull(httpClient);
    assertNotNull(httpClient.getBufferPool());
    assertNotNull(httpClient.getHttpClient());

  }

  @Test
  public void shouldGetResponseWhenGetMethodIsSent() throws Exception {
    buildStartedServer();
    HTTPSampleResult result = sampleWithGet();
    assertThat(result.getResponseDataAsString()).isEqualTo(SERVER_RESPONSE);
  }

  private void buildStartedServer() throws Exception {
    server = new ServerBuilder()
        .withHTTP2()
        .withALPN()
        .withHTTP2C()
        .withSSL()
        .buildServer();
    server.start();
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
    assertThat(result.getResponseHeaders().indexOf("Content-Encoding: gzip")).isNotEqualTo(-1);
  }

  @Test
  public void shouldSendBodyInformationWhenRequestWithBodyRaw() throws Exception {
    buildStartedServer();
    sampler.addArgument("", TEST_ARGUMENT_1);
    sampler.addArgument("", TEST_ARGUMENT_2);
    String requestBody = TEST_ARGUMENT_1 + TEST_ARGUMENT_2;
    HTTPSampleResult httpSampleResult = buildResult(true, Code.OK,
        HOST_HEADER.get(),
        requestBody.getBytes(StandardCharsets.UTF_8), "application/octet-stream");

    validateResponse(sample(SERVER_PATH_200_WITH_BODY, HTTPConstants.POST), httpSampleResult);
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

    if (headers != null) {
      httpFields.add(headers);
    }
    if (requestContentType != null) {
      httpFields.add(HttpHeader.CONTENT_TYPE, requestContentType);
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
    String requestBody = "test1=" + TEST_ARGUMENT_1 + "&" + "test2=" + TEST_ARGUMENT_2;
    HTTPSampleResult expected = buildResult(true, HttpStatus.Code.OK,
        HOST_HEADER.get(),
        requestBody.getBytes(StandardCharsets.UTF_8),
        "application/x-www-form-urlencoded");
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
    String requestBody = TEST_ARGUMENT_1 + TEST_ARGUMENT_2;
    HTTPSampleResult expected =
        buildResult(true, HttpStatus.Code.OK, HttpFields.build().add(HttpHeader.HOST, "localhost"
                + ":6666"),
            requestBody.getBytes(StandardCharsets.UTF_8),
            "application/octet-stream");

    validateResponse(sample(SERVER_PATH_200_WITH_BODY, HTTPConstants.DELETE), expected);
  }

  @Test
  public void shouldReturnFailureSampleResultWhenResponse400() throws Exception {
    buildStartedServer();
    HTTPSampleResult expected = buildResult(false, Code.BAD_REQUEST,
        HOST_HEADER.get(),
        null,
        null);
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

    HTTPSampleResult httpSampleResult = buildResult(true, Code.OK,
        HOST_HEADER.get(),
        null,
        null);
    httpSampleResult.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED),
        httpSampleResult);
  }

  private void validateEmbeddedResources(HTTPSampleResult result, HTTPSampleResult expected)
      throws MalformedURLException {
    SampleResult[] results = result.getSubResults();
    validateResponse(result, expected);
    softly.assertThat(results.length).isGreaterThan(0);
    assertResultTypeAndUrl(results[0], SampleResult.TEXT, SERVER_PATH_200_EMBEDDED);
    assertResultTypeAndUrl(results[1], SampleResult.BINARY, SERVER_IMAGE);
  }

  private void assertResultTypeAndUrl(SampleResult result, String type, String path)
      throws MalformedURLException {
    softly.assertThat(result.getDataType()).isEqualTo(type);
    softly.assertThat(result.getUrlAsString()).isEqualTo(createURL(path).toString());
  }

  @Test
  public void shouldNotDownloadEmbeddedResourcesWhenUrlDoesNotMatchFilter() throws Exception {
    buildStartedServer();
    sampler.setImageParser(true);
    sampler.setEmbeddedUrlRE(".+css");
    HTTPSampleResult httpSampleResult = buildResult(true, Code.OK,
        HOST_HEADER.get(),
        null, null);
    httpSampleResult.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    validateEmbeddedResourcesWithUrlFilter(sampleWithGet(SERVER_PATH_200_EMBEDDED),
        httpSampleResult);
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
    server = new ServerBuilder()
        .withHTTP1()
        .withSSL()
        .buildServer();
    server.start();
    CookieManager cookieManager = new CookieManager();
    cookieManager.testStarted(HOST_NAME);
    sampler.setCookieManager(cookieManager);
    //First request
    sampleWithGet(SERVER_PATH_SET_COOKIES);
    //Second request
    HTTPSampleResult result = sampleWithGet(SERVER_PATH_SET_COOKIES);
    HTTPSampleResult expected = buildOkResult(null, null);
    String replace = HOST_HTTP_2_UPGRADE_HEADERS.get().toString().replace("\r\n", "\n");
    expected.setRequestHeaders(replace.substring(0, replace.length() - 1));
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
        .add(headerName2, headerValue2)
        .add(HttpHeader.HOST, "localhost:6666");
    HTTPSampleResult expected = buildResult(true, Code.OK,
        httpFields, null, null);
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    validateResponse(sampleWithGet(), expected);
  }

  @Test
  public void shouldReturnSuccessDigestAuthSampleResultWhenAuthDigestIsSet() throws Exception {
    server = new ServerBuilder()
        .withHTTP1()
        .withSSL()
        .withDigestAuth()
        .buildServer();
    server.start();
    configureAuthManager(Mechanism.DIGEST);
    HTTPSampleResult expected = buildResult(true, HttpStatus.Code.OK, HOST_HEADER.get(),
        null, null);
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    validateResponse(sampleWithGet(), expected);
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
    server = new ServerBuilder()
        .withHTTP1()
        .withSSL()
        .withBasicAuth()
        .buildServer();
    server.start();
    configureAuthManager(Mechanism.BASIC);

    HTTPSampleResult expected = buildResult(true, HttpStatus.Code.OK, HOST_HEADER.get(),
        null, null);
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
    validateResponse(sampleWithGet(), expected);
  }


  @Test
  public void shouldReturnSuccessBasicAuthSampleResultWhenHeaderIsSet() throws Exception {
    Mutable httpFields = HOST_HEADER.get()
        .add(HttpHeader.AUTHORIZATION,
            "Basic " + base64Encode(AUTH_USERNAME + ":" + AUTH_PASSWORD));
    server = new ServerBuilder()
        .withHTTP1()
        .withSSL()
        .withBasicAuth()
        .buildServer();
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
    HTTPSampleResult expected = buildResult(true, Code.OK,
        HOST_HEADER.get(), null, null);
    expected.setResponseData(SERVER_RESPONSE, StandardCharsets.UTF_8.name());
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
    String requestHeaders = HttpFields.build()
        .add(HttpHeader.CONTENT_TYPE, "image/png")
        .add(HttpHeader.HOST, "localhost:6666")
        .add(HttpHeader.CONTENT_LENGTH, "9018")
        .toString().replace("\r\n", "\n");
    requestHeaders = requestHeaders.substring(0, requestHeaders.length() - 1);
    HTTPSampleResult expected = buildResult(true, Code.OK, null,
        Resources.toByteArray(urlFile),
        "image/png");
    expected.setRequestHeaders(requestHeaders);
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
    HTTPSampleResult firstRequestExpected = buildResult(true, Code.OK,
        HOST_HEADER.get(), null, null);
    firstRequestExpected.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), firstRequestExpected);
    // Same request connect again because use expire is false
    HTTPSampleResult secondRequestExpected = buildResult(true, Code.OK,
        HOST_HTTP_2_UPGRADE_HEADERS.get(), null, null);
    secondRequestExpected.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), secondRequestExpected);
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
    HTTPSampleResult firstRequestExpected = buildResult(true, Code.OK,
        HOST_HEADER.get(), null, null);
    firstRequestExpected.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    // First request must connect to the server
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), firstRequestExpected);
    // Same request use cached result with no message, request and data response
    firstRequestExpected.setResponseCode(responseCode);
    firstRequestExpected.setResponseMessage(message);
    firstRequestExpected.setRequestHeaders("");
    firstRequestExpected.setResponseData("", StandardCharsets.UTF_8.name());
    validateEmbeddedResultCached(sampleWithGet(SERVER_PATH_200_EMBEDDED), firstRequestExpected);
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
    HTTPSampleResult expected = buildResult(true, Code.OK,
        HOST_HEADER.get(), null, null);
    expected.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
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
    HTTPSampleResult expected = buildResult(true, Code.OK,
        HOST_HEADER.get(), null, null);
    expected.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), expected);
    HTTPSampleResult secondRequestExpected = buildResult(true, Code.OK,
        HOST_HTTP_2_UPGRADE_HEADERS.get(), null, null);
    secondRequestExpected.setResponseData(BASIC_HTML_TEMPLATE, StandardCharsets.UTF_8.name());
    // Same request connect again because clear cache iteration is enabled
    validateEmbeddedResources(sampleWithGet(SERVER_PATH_200_EMBEDDED), secondRequestExpected);
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
    Mutable httpFields = HOST_HEADER.get()
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
    String requestHeaders = HttpFields.build()
        .add(HttpHeader.CONTENT_TYPE, "image/png")
        .add(HttpHeader.HOST, "localhost:6666")
        .add(HttpHeader.CONTENT_LENGTH, "9018")
        .toString().replace("\r\n", "\n");
    requestHeaders = requestHeaders.substring(0, requestHeaders.length() - 1);
    HTTPSampleResult expected = buildResult(true, HttpStatus.Code.OK,
        null,
        Resources.toByteArray(urlFile), "image/png");
    expected.setRequestHeaders(requestHeaders);
    validateResponse(sample(SERVER_PATH_200_FILE_SENT, HTTPConstants.POST), expected);
  }

  @Test(expected = ExecutionException.class)
  public void shouldThrowExceptionWhenServerRequiresClientCertAndNoneIsConfigured()
      throws Exception {
    server = new ServerBuilder()
        .withHTTP1()
        .withHTTP2()
        .withALPN()
        .withHTTP2C()
        .withSSL()
        .withNeedClientAuth()
        .buildServer();
    server.start();
    sampleWithGet();
  }

  private String getKeyStorePathAsUriPathWithNetSslKeyStoreFormat() {
    try {
      // Generate a absolute path in URI format with compatibility with Windows
      // IMPORTANT: javax.net.ssl.keyStore use a particular format,
      // this method try to generate in that format and with compatibility with Windows
      return "/" + new File("//").toURI().relativize(getClass().getResource("keystore.p12").toURI())
          .getPath();
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void shouldGetSuccessResponseWhenServerRequiresClientCertAndOneIsConfigured()
      throws Exception {
    server = new ServerBuilder()
        .withHTTP1()
        .withHTTP2()
        .withALPN()
        .withHTTP2C()
        .withSSL()
        .withNeedClientAuth()
        .buildServer();
    server.start();
    String keyStorePropertyName = "javax.net.ssl.keyStore";
    String keyStorePasswordPropertyName = "javax.net.ssl.keyStorePassword";
    System.setProperty(keyStorePropertyName, getKeyStorePathAsUriPathWithNetSslKeyStoreFormat());
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

  private String relativizePath(String path) {
    return new File(".").toURI().relativize(new File(path).toURI()).getPath();
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
  public void shouldThrowAnExceptionWhenBufferSizeIsBiggerThanMaxBufferSize() throws Throwable {
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

  @Test
  public void shouldSuccessfulSendRequestWhenExclusiveHTTP2ServerWithoutALPN() throws Throwable {
    server = new ServerBuilder().withSSL().withALPN().withHTTP2().buildServer();
    server.start();
    HTTP2FutureResponseListener listener = new HTTP2FutureResponseListener();
    sampler.setFutureResponseListener(listener);

    client = new HTTP2JettyClient(true, "Test");
    client.start();
    HttpRequest httpRequest = client.sampleAsync(
        sampler,
        buildBaseResult(createURL(SERVER_PATH_200), HTTPConstants.GET),
        sampler.geFutureResponseListener());
    httpRequest.send(listener);
    ContentResponse contentResponse = listener.get();
    assertThat(contentResponse.getContent()).isNotEmpty();
  }

  @Test
  public void shouldSuccessfulSendRequestWhenExclusiveHTTP1ServerWithoutALPN() throws Exception {
    server = new ServerBuilder().withSSL().withHTTP1().buildServer();
    server.start();
    HTTPSampleResult result = sampleWithGet();
    assertThat(result.getResponseDataAsString()).isEqualTo(SERVER_RESPONSE);
  }
}
