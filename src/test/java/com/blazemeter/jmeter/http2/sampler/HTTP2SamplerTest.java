/*
package com.blazemeter.jmeter.http2.sampler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.blazemeter.jmeter.http2.core.HTTP2Implementation;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeoutException;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.assertj.core.api.JUnitSoftAssertions;
import org.eclipse.jetty.client.HttpContentResponse;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class HTTP2SamplerTest {

  public static final String RESPONSE_CONTENT = "Dummy Response";
  public static final String HEADER_MANAGER = "Header1: value1\r\nHeader2: value2\r\n\r\n";

  @Rule
  public final JUnitSoftAssertions softly = new JUnitSoftAssertions();
  @Mock
  private HTTP2Implementation client;
  @Mock
  private HttpRequest request;
  private HTTP2Sampler sampler;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setup() {
    sampler = new HTTP2Sampler(() -> client);
  }

  @Test
  public void shouldReturnSuccessSampleResultWhenSuccessRequest() throws Exception {
    ContentResponse response = createResponse(HttpStatus.OK_200);
    createMockRequest();
    when(request.send()).thenReturn(response);
    configureSampler(HTTPConstants.GET);
    validateResponse(sampler.sample(), response);
  }

  @Test
  public void shouldReturnFailureSampleResultWhenResponse400() throws Exception {
    ContentResponse response = createResponse(HttpStatus.BAD_REQUEST_400);
    createMockRequest();
    when(request.send()).thenReturn(response);
    configureSampler(HTTPConstants.GET);
    validateResponse(sampler.sample(), response);
  }

  @Test
  public void shouldReturnErrorMessageWhenMethodIsNotSupported() throws URISyntaxException {
    createMockRequest();
    configureSampler("MethodNotSupported");
    validateErrorResponse(sampler.sample(), UnsupportedOperationException.class.getName());
  }

  @Test
  public void shouldReturnErrorMessageWhenThreadIsInterrupted() throws Exception {
    createMockRequest();
    when(request.send()).thenThrow(new InterruptedException());
    configureSampler(HTTPConstants.GET);
    validateErrorResponse(sampler.sample(), InterruptedException.class.getName());
  }

  @Test
  public void shouldReturnErrorMessageWhenClientThrowException() throws Exception {
    createMockRequest();
    when(request.send()).thenThrow(new TimeoutException());
    configureSampler(HTTPConstants.GET);
    validateErrorResponse(sampler.sample(), TimeoutException.class.getName());
  }

  @Test
  public void shouldReturnSuccessSampleResultWhenSuccessRequestWithHeaders() throws Exception {
    ContentResponse response = createResponse(HttpStatus.OK_200);
    createMockRequest();
    when(request.getHeaders()).thenReturn(HttpFields.build().put("Header1", "value1").put(
        "Header2", "value2"));
    when(request.send()).thenReturn(response);
    configureSampler(HTTPConstants.GET);
    configureHeaderManagerToSampler();
    HTTPSampleResult result = sampler.sample();
    validateResponse(result, response);
    validateHeaders(result);
  }

  @Test
  public void shouldGetRedirectedResultWithSubSampleWhenFollowRedirectEnabledAndRedirected()
      throws Exception {

    ContentResponse response = createResponse(HttpStatus.FOUND_302);
    ContentResponse secondResponse = createResponse(HttpStatus.OK_200);
    createMockRequest();
    when(request.send()).thenReturn(response, secondResponse);
    configureSampler(HTTPConstants.GET);
    sampler.setFollowRedirects(true);
    validateRedirects(sampler.sample(), secondResponse);
  }

  @Test
  public void shouldGetOnlyRedirectedResultWhenFollowRedirectDisabledAndRedirected()
      throws Exception {

    ContentResponse response = createResponse(HttpStatus.FOUND_302);
    createMockRequest();
    when(request.send()).thenReturn(response);
    configureSampler(HTTPConstants.GET);
    HTTPSampleResult result = sampler.sample();
    softly.assertThat(result.isSuccessful())
        .isEqualTo(response.getStatus() >= 200 && response.getStatus() <= 399);
    softly.assertThat(result.getSubResults().length).isEqualTo(0);
  }

  private void createMockRequest() throws URISyntaxException {
    when(client.createRequest(any())).thenReturn(request);
  }

  private void configureSampler(String method) {
    sampler.setMethod(method);
    sampler.setDomain("server");
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(80);
    sampler.setPath("");
  }

  private void configureHeaderManagerToSampler() {
    HeaderManager hm = new HeaderManager();
    hm.add(new Header("Header1", "value1"));
    hm.add(new Header("Header2", "value2"));

    sampler.setHeaderManager(hm);
  }

  private ContentResponse createResponse(int statusCode) {
    return new HttpContentResponse(
        new HttpResponse(null, Collections.emptyList()).status(statusCode)
            .addHeader(new HttpField("Header1", "value1"))
            .addHeader(new HttpField("Header2", "value2"))
            .addHeader(new HttpField(HTTPConstants.HEADER_LOCATION, "1")),
        RESPONSE_CONTENT.getBytes(StandardCharsets.UTF_8), MimeTypes.Type.TEXT_PLAIN.toString(),
        StandardCharsets.UTF_8.name());
  }

  private void validateResponse(HTTPSampleResult result, ContentResponse response) {
    softly.assertThat(result.isSuccessful())
        .isEqualTo(response.getStatus() >= 200 && response.getStatus() <= 399);
    softly.assertThat(result.getResponseCode()).isEqualTo(String.valueOf(response.getStatus()));
    softly.assertThat(result.getResponseHeaders()).isEqualTo(response.getHeaders().asString());
    softly.assertThat(result.getResponseDataAsString()).isEqualTo(response.getContentAsString());
  }

  private void validateRedirects(HTTPSampleResult result, ContentResponse response) {
    softly.assertThat(result.isSuccessful())
        .isEqualTo(response.getStatus() >= 200 && response.getStatus() <= 399);
    softly.assertThat(result.getResponseCode()).isEqualTo(String.valueOf(response.getStatus()));
    softly.assertThat(result.getResponseHeaders()).isEqualTo(response.getHeaders().asString());
    softly.assertThat(result.getResponseDataAsString()).isEqualTo(response.getContentAsString());
    softly.assertThat(result.getSubResults().length).isGreaterThan(0);
    softly.assertThat(result.getRedirectLocation())
        .isEqualTo(response.getHeaders().get(HTTPConstants.HEADER_LOCATION));
  }

  private void validateHeaders(HTTPSampleResult result) {
    softly.assertThat(request.getHeaders().asString()).isEqualTo(HEADER_MANAGER);
    softly.assertThat(result.getRequestHeaders()).isEqualTo(HEADER_MANAGER);
  }

  private void validateErrorResponse(HTTPSampleResult result, String code) {
    softly.assertThat(result.isSuccessful()).isEqualTo(false);
    softly.assertThat(result.getResponseCode()).isEqualTo(code);
  }

}
*/
