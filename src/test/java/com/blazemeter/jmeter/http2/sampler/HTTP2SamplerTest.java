package com.blazemeter.jmeter.http2.sampler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.blazemeter.jmeter.http2.core.HTTP2Client;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.assertj.core.api.JUnitSoftAssertions;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class HTTP2SamplerTest {

  public static final String RESPONSE_HEADERS = "header1: valu1\nheader2: value2";
  public static final String RESPONSE_CONTENT = "Dummy Response";
  public static final String ENCODING = "UTF-8";
  @Rule
  public final JUnitSoftAssertions softly = new JUnitSoftAssertions();
  @Mock
  private HTTP2Client client;
  @Mock
  private ContentResponse response;
  @Mock
  private HttpFields responseHeaders;
  private HTTP2Sampler sampler;

  @BeforeClass
  public static void setupClass() {
    JMeterTestUtils.setupJmeterEnv();
  }

  @Before
  public void setup() {
    sampler = new HTTP2Sampler(client);
  }

  @Test
  public void shouldReturnSuccessSampleResultWhenSuccessRequest() throws Exception {
    when(client.doGet(any())).thenReturn(response);
    when(response.getStatus()).thenReturn(HttpStatus.OK_200);
    when(responseHeaders.asString()).thenReturn(RESPONSE_HEADERS);
    when(response.getHeaders()).thenReturn(responseHeaders);
    when(response.getContentAsString()).thenReturn(RESPONSE_CONTENT);
    when(response.getEncoding()).thenReturn(ENCODING);
    configureSampler(HTTPConstants.GET);
    HTTPSampleResult result = sampler.sample();
    validateResponse(result);
  }

  private void validateResponse(HTTPSampleResult result) {
    softly.assertThat(result.isSuccessful())
        .isEqualTo(response.getStatus() >= 200 && response.getStatus() <= 399);
    softly.assertThat(result.getResponseCode()).isEqualTo(String.valueOf(response.getStatus()));
    softly.assertThat(result.getResponseHeaders()).isEqualTo(response.getHeaders().asString());
    softly.assertThat(result.getResponseDataAsString()).isEqualTo(response.getContentAsString());
  }

  @Test
  public void shouldReturnFailureSampleResultWhenResponse400() throws Exception {
    when(client.doGet(any())).thenReturn(response);
    when(response.getStatus()).thenReturn(HttpStatus.BAD_REQUEST_400);
    when(responseHeaders.asString()).thenReturn(RESPONSE_HEADERS);
    when(response.getHeaders()).thenReturn(responseHeaders);
    when(response.getContentAsString()).thenReturn(RESPONSE_CONTENT);
    when(response.getEncoding()).thenReturn(ENCODING);
    configureSampler(HTTPConstants.GET);
    HTTPSampleResult result = sampler.sample();
    validateResponse(result);
  }

  @Test
  public void shouldReturnErrorMessageWhenMethodIsNotGET() {
    configureSampler(HTTPConstants.POST);
    HTTPSampleResult result = sampler.sample();
    validateErrorResponse(result, UnsupportedOperationException.class.getName());
  }

  public void validateErrorResponse(HTTPSampleResult result, String code) {
    softly.assertThat(result.isSuccessful()).isEqualTo(false);
    softly.assertThat(result.getResponseCode()).isEqualTo(code);
  }

  @Test
  public void shouldReturnErrorMessageWhenThreadIsInterrupted() throws Exception {
    when(client.doGet(any())).thenThrow(new InterruptedException());
    configureSampler(HTTPConstants.GET);
    HTTPSampleResult result = sampler.sample();
    validateErrorResponse(result, InterruptedException.class.getName());
  }

  @Test
  public void shouldReturnErrorMessageWhenClientThrowException() throws Exception {
    when(client.doGet(any())).thenThrow(new Exception());
    configureSampler(HTTPConstants.GET);
    HTTPSampleResult result = sampler.sample();
    validateErrorResponse(result, Exception.class.getName());
  }

  public void configureSampler(String method) {
    sampler.setMethod(method);
    sampler.setDomain("server");
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(80);
    sampler.setPath("");
  }

}
