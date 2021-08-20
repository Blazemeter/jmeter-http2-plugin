package com.blazemeter.jmeter.http2.sampler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.blazemeter.jmeter.http2.core.HTTP2Client;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.assertj.core.api.JUnitSoftAssertions;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpFields;
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
    when(client.doGet(any(), isNull())).thenReturn(response);
    when(response.getStatus()).thenReturn(200);
    when(responseHeaders.asString()).thenReturn(RESPONSE_HEADERS);
    when(response.getHeaders()).thenReturn(responseHeaders);
    when(response.getContentAsString()).thenReturn(RESPONSE_CONTENT);
    when(response.getEncoding()).thenReturn(ENCODING);
    configureSampler(HTTPConstants.GET);
    HTTPSampleResult result = sampler.sample(null, "", false, 0);
    softly.assertThat(result.isSuccessful()).isEqualTo(true);
    softly.assertThat(result.getResponseCode()).isEqualTo("200");
    softly.assertThat(result.getResponseHeaders()).isEqualTo(RESPONSE_HEADERS);
    softly.assertThat(result.getResponseDataAsString()).isEqualTo(RESPONSE_CONTENT);
  }

  @Test
  public void shouldReturnFailureSampleResultWhenResponse400() throws Exception {
    when(client.doGet(any(), isNull())).thenReturn(response);
    when(response.getStatus()).thenReturn(400);
    when(responseHeaders.asString()).thenReturn(RESPONSE_HEADERS);
    when(response.getHeaders()).thenReturn(responseHeaders);
    when(response.getContentAsString()).thenReturn(RESPONSE_CONTENT);
    when(response.getEncoding()).thenReturn(ENCODING);
    configureSampler(HTTPConstants.GET);
    HTTPSampleResult result = sampler.sample(null, "", false, 0);
    softly.assertThat(result.isSuccessful()).isEqualTo(false);
    softly.assertThat(result.getResponseCode()).isEqualTo("400");
    softly.assertThat(result.getResponseHeaders()).isEqualTo(RESPONSE_HEADERS);
    softly.assertThat(result.getResponseDataAsString()).isEqualTo(RESPONSE_CONTENT);
  }

  @Test
  public void shouldResponseErrorMessageWhenMethodIsNotGET() {
    configureSampler(HTTPConstants.POST);
    HTTPSampleResult result = sampler.sample(null, "", false, 0);
    validateErrorResponse(result, UnsupportedOperationException.class.getName());
  }

  @Test
  public void shouldResponseErrorMessageWhenThreadIsInterrupted() throws Exception {
    when(client.doGet(any(), isNull())).thenThrow(new InterruptedException());
    configureSampler(HTTPConstants.GET);
    HTTPSampleResult result = sampler.sample(null, "", false, 0);
    validateErrorResponse(result, InterruptedException.class.getName());
  }

  @Test
  public void shouldResponseErrorMessageWhenClientThrowException() throws Exception {
    when(client.doGet(any(), isNull())).thenThrow(new Exception());
    configureSampler(HTTPConstants.GET);
    HTTPSampleResult result = sampler.sample(null, "", false, 0);
    validateErrorResponse(result, Exception.class.getName());
  }

  public void validateErrorResponse(HTTPSampleResult result, String code) {
    softly.assertThat(result.isSuccessful()).isEqualTo(false);
    softly.assertThat(result.getResponseCode()).isEqualTo(code);
  }

  public void configureSampler(String method) {
    sampler.setMethod(method);
    sampler.setDomain("server");
    sampler.setProtocol(HTTPConstants.PROTOCOL_HTTPS);
    sampler.setPort(80);
    sampler.setPath("");
  }

}
