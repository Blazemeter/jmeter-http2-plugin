package com.blazemeter.jmeter.http2.control;

import com.blazemeter.jmeter.http2.core.HTTP2FutureResponseListener;
import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.NextIsNullException;
import org.apache.jmeter.testelement.TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Controller extends GenericController implements Serializable {

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2Controller.class);

  private transient List<HTTP2Sampler> http2SamplesSync = new ArrayList<>();
  private transient List<TestElement> subControllersAndSamplersBackup = new ArrayList();

  public HTTP2Controller() {
    super();
  }

  private HTTP2Sampler waitForDoneHTTP2() {
    boolean interrupted = false;
    // Try to check if the first request finish to return again that element first
    if (http2SamplesSync.size() > 0) {
      HTTP2Sampler http2Sam = http2SamplesSync.get(0);
      HTTP2FutureResponseListener http2FListener =
          http2Sam.geFutureResponseListener();
      while (!interrupted) {
        if (http2FListener.isDone() || http2FListener.isCancelled()) {
          String urlProcesed = http2FListener.getRequest().getURI().toString();
          LOG.debug("HTTP2 Future Finished, retrying the sample with that data {}", urlProcesed);
          http2SamplesSync.remove(0); // Remove the sample
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
      // The first time, backup the original sub elements
      // On iteration, we need to recover the original list
      if (subControllersAndSamplersBackup.size() == 0) {
        subControllersAndSamplersBackup.addAll(subControllersAndSamplers);
      } else {
        subControllersAndSamplers.clear();
        subControllersAndSamplers.addAll(subControllersAndSamplersBackup);
      }
    }

    if (current < subControllersAndSamplers.size()) {
      TestElement sam = subControllersAndSamplers.get(current);
      if (sam instanceof HTTP2Sampler) {
        HTTP2Sampler http2Sam = ((HTTP2Sampler) sam);
        http2Sam.setSyncRequest(false); // Force to run async the first time
        LOG.debug("Convert http2 sample to Async and add to wait list");
        http2SamplesSync.add(http2Sam);
        return http2Sam;
      } else { // Is a other type of element, use that for checkpoint mark
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
    }
    if (subControllersAndSamplers.isEmpty()) {
      setDone(true);
      throw new NextIsNullException();
    }
    return null;
  }

}

