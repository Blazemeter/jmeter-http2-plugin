package com.blazemeter.jmeter.http2.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.blazemeter.jmeter.http2.HTTP2TestBase;
import com.blazemeter.jmeter.http2.core.HTTP2FutureResponseListener;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.JMeterTestUtils;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.jmeter.control.NextIsNullException;
import org.apache.jmeter.protocol.http.sampler.HTTPSampler;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.client.HttpRequest;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class HTTP2ControllerTest extends HTTP2TestBase {


  private static final String MAX_CONCURRENT_ASYNC_IN_CONTROLLER =
      "httpJettyClient.maxConcurrentAsyncInController";
  private HTTP2Controller http2Controller;
  private HTTP2Sampler firstSampler;
  private HTTP2Sampler secondSampler;
  private final HTTPSampler otherSamplerType = new HTTPSampler();
  @Mock
  private HttpRequest request;
  @Mock
  private HTTP2FutureResponseListener firstSamplerListener;
  @Mock
  private HTTP2FutureResponseListener secondSamplerListener;

  @Before
  public void setUp() {
    firstSampler = new HTTP2Sampler();
    secondSampler = new HTTP2Sampler();
    firstSampler.setFutureResponseListener(firstSamplerListener);
    secondSampler.setFutureResponseListener(secondSamplerListener);
    JMeterTestUtils.setupJmeterEnv();
  }


  @Test
  public void shouldModifyAndRetrieveSamplerToRunAsyncWhenProvideSyncSampler() throws Exception {
    setupHttp2Controller(false, 1);
    HTTP2Sampler currentElement = (HTTP2Sampler) http2Controller.next();
    assertThat(currentElement.isSyncRequest()).isFalse();
  }

  @Test(timeout = 7000)
  public void shouldBusyWaitOnlyForFirstSamplerWhenMaxConcurrentAsyncInControllerOvercome()
      throws Exception {
    setupHttp2Controller(false, 1);
    simulateSamplerExecution(secondSamplerListener, 80000);
    simulateSamplerExecution(firstSamplerListener, 3000);
    http2Controller.next();
    http2Controller.next();
  }

  private void setupHttp2Controller(boolean otherTypeOfRequest,
                                    int maxConcurrentAsyncInController) throws URISyntaxException {
    JMeterUtils.setProperty(MAX_CONCURRENT_ASYNC_IN_CONTROLLER,
        String.valueOf(maxConcurrentAsyncInController));
    http2Controller = new HTTP2Controller();
    http2Controller.addTestElement(firstSampler);
    http2Controller.addTestElement(secondSampler);
    when(request.getURI()).thenReturn(new URI("https://test.com"));
    when(firstSamplerListener.getRequest()).thenReturn(request);
    when(secondSamplerListener.getRequest()).thenReturn(request);
    if (otherTypeOfRequest) {
      http2Controller.addTestElement(otherSamplerType);
    }
  }

  private static void simulateSamplerExecution(HTTP2FutureResponseListener samplerListener,
                                               int delayInMillis) {
    //Thanks to mockito by default when(samplerListener.isDone()).thenReturn(false)
    //
    ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    executorService.schedule(() -> {
      System.out.printf("Scheduled service with a delay of %d executed", delayInMillis);
      when(samplerListener.isDone()).thenReturn(true);
      return null;
    }, delayInMillis, TimeUnit.MILLISECONDS);
  }

  @Test(timeout = 10000)
  public void shouldBusyWaitForAsyncSamplersWhenControllerReachOtherSamplerType()
      throws URISyntaxException {
    setupHttp2Controller(true, 2);
    simulateSamplerExecution(secondSamplerListener, 6000);
    simulateSamplerExecution(firstSamplerListener, 2000);
    Sampler next = null;
    for (int i = 0; i < 5; i++) {
      next = http2Controller.next();
    }
    assertThat(next).isInstanceOf(HTTPSampler.class);
  }

  // TODO:
  // Tests using isDone are discussed because it is a misconception.
  //isDone is more related to the row of the entire test and not to the controller itself.
  //The isDone setting is removed, because it is something that apparently manages the iterators
  // and thread group.
  //Analyze if it does not require refacoring incorporating any of these to maintain the tests.

  /*
  @Test(expected = NextIsNullException.class)
  public void shouldThrowNextIsNullWhenNextWithoutElementsLeft() throws NextIsNullException {
    http2Controller = new HTTP2Controller();
    http2Controller.getCurrentElement();
  }
  */

  /*
  @Test
  public void shouldSetControllerDoneWhenNoMoreElementsToBeProcessed() {
    http2Controller = new HTTP2Controller();
    http2Controller.next();
    assertThat(http2Controller.isDone()).isTrue();
  }
  */

  /*
  @Test
  public void shouldControllerDoneWhenSamplesProcessed() throws URISyntaxException {
    http2Controller = new HTTP2Controller();

    http2Controller.addTestElement(firstSampler);
    http2Controller.addTestElement(secondSampler);
    when(request.getURI()).thenReturn(new URI("https://test.com"));
    when(firstSamplerListener.isDone()).thenReturn(true);
    when(firstSamplerListener.getRequest()).thenReturn(request);
    when(secondSamplerListener.isDone()).thenReturn(true);
    when(secondSamplerListener.getRequest()).thenReturn(request);
    http2Controller.initialize();
    int resultCount = 0;
    while (!http2Controller.isDone()) {
      Sampler next = http2Controller.next();
      if (next == null) {
        continue;
      }
      SampleResult sampleResult = next.sample(null);
      if (sampleResult != null) {
        resultCount += 1;
      }
    }
    // NOTE: The mocking make an error in the results, because the async execution return a value
    // and is expected to return null in that case, and the real response in the second sample
    // execution
    assertThat(resultCount).isEqualTo(4);
  }
  */
}
