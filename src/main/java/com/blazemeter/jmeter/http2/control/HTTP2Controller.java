package com.blazemeter.jmeter.http2.control;

import com.blazemeter.jmeter.http2.core.HTTP2FutureResponseListener;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.NextIsNullException;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.threads.SamplePackage;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Controller extends GenericController implements Serializable {

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2Controller.class);
  private static final String GENERATE_ASYNC_CONTROLLER_SAMPLE_PROP =
      "http2AsyncController.generateParentSample";
  private static final String LIMIT_MAX_PARALLEL_PROP =
      "http2AsyncController.limitMaxParallel";
  private static final String MAX_CONCURRENT_PROP =
      "http2AsyncController.maxConcurrentAsyncInController";
  private static final ThreadLocal<AsyncParentContext> ASYNC_PARENT_CONTEXT =
      new ThreadLocal<>();

  private static final int DEFAULT_MAX_CONCURRENT_ASYNC_IN_CONTROLLER = 100;
  private int maxConcurrentAsyncInController = DEFAULT_MAX_CONCURRENT_ASYNC_IN_CONTROLLER;

  private transient List<HTTP2Sampler> http2SamplesSync = new ArrayList<>();
  private transient List<TestElement> subControllersAndSamplersBackup = new ArrayList();
  private transient boolean controllerSampleEmitted;
  private transient boolean asyncSamplesSeen;
  private boolean generateControllerSample;

  public HTTP2Controller() {
    super();
    maxConcurrentAsyncInController = Integer
        .parseInt(JMeterUtils.getPropDefault("httpJettyClient.maxConcurrentAsyncInController",
            String.valueOf(maxConcurrentAsyncInController)));
    generateControllerSample = JMeterUtils.getPropDefault(
        GENERATE_ASYNC_CONTROLLER_SAMPLE_PROP, false);
  }

  public void setLimitMaxParallel(boolean enabled) {
    setProperty(LIMIT_MAX_PARALLEL_PROP, enabled);
  }

  public boolean isLimitMaxParallel() {
    return getPropertyAsBoolean(LIMIT_MAX_PARALLEL_PROP, false);
  }

  public void setMaxConcurrentAsyncInController(int maxConcurrentAsyncInController) {
    setProperty(MAX_CONCURRENT_PROP, maxConcurrentAsyncInController);
  }

  public int getMaxConcurrentAsyncInController() {
    return getPropertyAsInt(MAX_CONCURRENT_PROP, getDefaultMaxConcurrentAsyncInController());
  }

  public int getDefaultMaxConcurrentAsyncInController() {
    return maxConcurrentAsyncInController;
  }

  private int getEffectiveMaxConcurrentAsyncInController() {
    return isLimitMaxParallel()
        ? getMaxConcurrentAsyncInController()
        : getDefaultMaxConcurrentAsyncInController();
  }

  public void setGenerateControllerSample(boolean enabled) {
    this.generateControllerSample = enabled;
    setProperty(GENERATE_ASYNC_CONTROLLER_SAMPLE_PROP, enabled);
  }

  public boolean isGenerateControllerSample() {
    return getPropertyAsBoolean(GENERATE_ASYNC_CONTROLLER_SAMPLE_PROP,
        generateControllerSample);
  }

  public static void registerAsyncSampleResult(HTTP2Sampler sampler, SampleResult result,
                                               HTTP2FutureResponseListener listener) {
    AsyncParentContext context = ASYNC_PARENT_CONTEXT.get();
    if (context == null || !context.enabled || result == null || sampler == null
        || !sampler.isAsyncParentSampleEnabled()) {
      return;
    }
    SampleResult copy = deepCopySampleResult(result);
    context.addChild(copy);
  }

  private HTTP2Sampler waitForDoneHTTP2() {
    boolean interrupted = false;
    // Try to check if the first request finish to return again that element first
    if (http2SamplesSync.size() > 0) {
      HTTP2Sampler http2Sam = http2SamplesSync.get(0);
      HTTP2FutureResponseListener http2FListener =
          http2Sam.geFutureResponseListener();
      while (!interrupted && (http2FListener != null)) {
        if (http2FListener.isDone() || http2FListener.isCancelled()) {
          String urlProcesed = http2FListener.getRequest().getURI().toString();
          LOG.debug("HTTP2 Future Finished, retrying the sample with that data {}", urlProcesed);
          http2SamplesSync.remove(0); // Remove the sample
          http2Sam.suppressPreProcessorsOnce();
          return http2Sam; // The second attempt take the data from the finished listener
        }
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          http2SamplesSync.clear();
          interrupted = true;
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    return null;
  }

  @Override
  protected void setDone(boolean done) {
    // NOPE, dont allow to set the Done on there
    LOG.debug("Set Done:" + done);
    super.setDone(done);
  }

  @Override
  public boolean isDone() {
    boolean done = super.isDone();
    LOG.debug("isDone? " + done);
    return done;
  }

  @Override
  protected TestElement getCurrentElement() throws NextIsNullException {
    LOG.debug("Current {} Size {}", current, subControllersAndSamplers.size());

    if (current == 0) {
      resetAsyncControllerSampleState();
      // The first time, backup the original sub elements
      // On iteration, we need to recover the original list
      if (subControllersAndSamplersBackup.size() == 0) {
        subControllersAndSamplersBackup.addAll(subControllersAndSamplers);
      } else {
        subControllersAndSamplers.clear();
        subControllersAndSamplers.addAll(subControllersAndSamplersBackup);
      }
    }

    if (http2SamplesSync.size() > getEffectiveMaxConcurrentAsyncInController()) {
      HTTP2Sampler http2samDone = waitForDoneHTTP2();
      if (!Objects.isNull(http2samDone)) {
        subControllersAndSamplers.add(current, http2samDone);
        return http2samDone;
      }
    }

    if (current < subControllersAndSamplers.size()) {
      TestElement sam = subControllersAndSamplers.get(current);
      if (sam instanceof HTTP2Sampler) {
        HTTP2Sampler http2Sam = ((HTTP2Sampler) sam);
        http2Sam.setAsyncParentSampleEnabled(isGenerateControllerSample());
        http2Sam.setSyncRequest(false); // Force to run async the first time
        LOG.debug("Convert http2 sample to Async and add to wait list");
        http2SamplesSync.add(http2Sam);
        asyncSamplesSeen = true;
        return http2Sam;
      } else { // Another type of element, use that for checkpoint mark
        HTTP2Sampler http2sam = waitForDoneHTTP2();
        if (Objects.isNull(http2sam)) {
          return sam;
        }
        subControllersAndSamplers.add(current, http2sam);
        return http2sam;
      }
    }
    if (current == (subControllersAndSamplers.size())) {
      // On the last, force a checkpoint moment
      LOG.debug("The last, force checkpoint");
      HTTP2Sampler http2samDone = waitForDoneHTTP2();
      if (!Objects.isNull(http2samDone)) {
        subControllersAndSamplers.add(current, http2samDone);
        return http2samDone;
      }
      if (http2SamplesSync.isEmpty()) {
        LOG.debug("No more elements!");
        emitAsyncControllerSampleIfNeeded();
      }
    }
    return null;
  }

  private void resetAsyncControllerSampleState() {
    controllerSampleEmitted = false;
    asyncSamplesSeen = false;
    if (isGenerateControllerSample()) {
      ASYNC_PARENT_CONTEXT.set(new AsyncParentContext(true));
    } else {
      ASYNC_PARENT_CONTEXT.remove();
    }
  }

  private void emitAsyncControllerSampleIfNeeded() {
    if (!isGenerateControllerSample() || controllerSampleEmitted || !asyncSamplesSeen) {
      return;
    }
    JMeterThread thread = JMeterContextService.getContext().getThread();
    AsyncParentContext context = ASYNC_PARENT_CONTEXT.get();
    if (thread == null || context == null) {
      return;
    }
    if (context.startTime == 0 || context.endTime == 0 || context.endTime < context.startTime) {
      return;
    }
    SamplePackage controllerSamplePackage = getSamplePackageFromContext();
    if (controllerSamplePackage == null) {
      return;
    }
    long duration = context.endTime - context.startTime;
    SampleResult controllerSample = new SampleResult(context.startTime, duration);
    controllerSample.setSampleLabel(getName());
    controllerSample.setSuccessful(context.failingSamples == 0);
    if (context.failingSamples == 0) {
      controllerSample.setResponseCodeOK();
    } else if (context.responseCode != null) {
      controllerSample.setResponseCode(context.responseCode);
    }
    controllerSample.setResponseMessage("Number of samples in transaction : "
        + context.totalSamples + ", number of failing samples : " + context.failingSamples);
    for (SampleResult child : context.children) {
      controllerSample.addSubResult(child, false);
    }
    JMeterVariables variables = JMeterContextService.getContext().getVariables();
    SampleEvent event = new SampleEvent(controllerSample,
        JMeterContextService.getContext().getThreadGroup().getName(), variables, true);
    for (SampleListener listener : controllerSamplePackage.getSampleListeners()) {
      listener.sampleOccurred(event);
    }
    controllerSampleEmitted = true;
    ASYNC_PARENT_CONTEXT.remove();
  }

  private SamplePackage getSamplePackageFromContext() {
    JMeterVariables vars = JMeterContextService.getContext().getVariables();
    if (vars == null) {
      return null;
    }
    Object pack = vars.getObject("JMeterThread.pack");
    return pack instanceof SamplePackage ? (SamplePackage) pack : null;
  }

  private static final class AsyncParentContext {
    private final List<SampleResult> children = new ArrayList<>();
    private long startTime;
    private long endTime;
    private int totalSamples;
    private int failingSamples;
    private String responseCode;
    private final boolean enabled;

    private AsyncParentContext(boolean enabled) {
      this.enabled = enabled;
    }

    private void addChild(SampleResult result) {
      children.add(result);
      totalSamples += 1;
      if (responseCode == null) {
        responseCode = result.getResponseCode();
      }
      if (!result.isSuccessful()) {
        failingSamples += 1;
      }
      long start = result.getStartTime();
      long end = result.getEndTime();
      if (start > 0 && (startTime == 0 || start < startTime)) {
        startTime = start;
      }
      if (end > 0 && end > endTime) {
        endTime = end;
      }
    }
  }

  private static SampleResult deepCopySampleResult(SampleResult original) {
    SampleResult copy;
    try {
      copy = (SampleResult) original.clone();
    } catch (Exception e) {
      copy = new SampleResult(original);
    }
    copy.setRequestHeaders(original.getRequestHeaders());
    copy.setResponseHeaders(original.getResponseHeaders());
    copy.setSamplerData(original.getSamplerData());
    copy.setResponseData(original.getResponseData());
    if (original.getURL() != null) {
      copy.setURL(original.getURL());
    }
    copy.removeSubResults();
    for (SampleResult sub : original.getSubResults()) {
      copy.addSubResult(deepCopySampleResult(sub), false);
    }
    copy.removeAssertionResults();
    for (org.apache.jmeter.assertions.AssertionResult assertion
        : original.getAssertionResults()) {
      copy.addAssertionResult(assertion);
    }
    return copy;
  }

}
