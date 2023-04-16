package com.blazemeter.jmeter.http2.core;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jetty.client.HttpContentResponse;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2FutureResponseListener extends BufferingResponseListener
    implements Future<ContentResponse> {
  private static final Logger LOG = LoggerFactory.getLogger(HTTP2FutureResponseListener.class);
  private final CountDownLatch latch = new CountDownLatch(1);
  private HttpRequest request;
  private ContentResponse response;
  private Throwable failure;
  private volatile boolean cancelled;
  private long responseStart;
  private long responseEnd;

  public HTTP2FutureResponseListener() {
    this(2 * 1024 * 1024);
  }

  public HTTP2FutureResponseListener(int maxLength) {
    super(maxLength);
    setStart();
  }

  public void setRequest(HttpRequest request) {
    this.request = request;
  }

  public HttpRequest getRequest() {
    return request;
  }

  private void setStart() {
    if (this.responseStart == 0) {
      this.responseStart = System.currentTimeMillis();
    }
  }

  private void setEnd() {
    this.responseEnd = System.currentTimeMillis();
  }

  public long getResponseStart() {
    return this.responseStart;
  }

  public long getResponseEnd() {
    return this.responseEnd;
  }

  @Override
  public void onComplete(Result result) {
    setEnd();
    response =
        new HttpContentResponse(result.getResponse(), getContent(), getMediaType(), getEncoding());
    failure = result.getFailure();
    latch.countDown();
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    cancelled = true;
    return request.abort(new CancellationException());
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public boolean isDone() {
    return latch.getCount() == 0 || isCancelled();
  }

  @Override
  public ContentResponse get() throws InterruptedException, ExecutionException {
    setStart();
    latch.await();
    return getResult();
  }

  @Override
  public ContentResponse get(long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException,
      TimeoutException {
    setStart();
    boolean expired = !latch.await(timeout, unit);
    if (expired) {
      throw new TimeoutException();
    }
    return getResult();
  }

  private ContentResponse getResult() throws ExecutionException {
    if (isCancelled()) {
      throw (CancellationException) new CancellationException().initCause(failure);
    }
    if (failure != null) { // Failure and Response can coexist.
      if (response == null) { // Only generate exception response when an response not exist
        throw new ExecutionException(failure);
      } else {
        // It is a failure caused after obtaining the response,
        // analyzing what type of failure it is, and incorporating mechanisms to manage it.
        // Log as debug, because not is a critical exception.
        LOG.debug("Unexpected failure on response", failure);
      }
    }

    return response;
  }

}
