package com.blazemeter.jmeter.http2.core;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jetty.client.BufferingResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2FutureResponseListener extends BufferingResponseListener
    implements Future<ContentResponse> {

  protected static final Logger LOG = LoggerFactory.getLogger(HTTP2FutureResponseListener.class);
  
  // Track if onComplete was called
  private volatile boolean onCompleteCalled = false;
  private final CountDownLatch latch = new CountDownLatch(1);
  private Request request;
  private HttpClient fallbackHttp1Client;
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
    LOG.debug("=== HTTP2FutureResponseListener CREATED ===");
    LOG.debug("maxLength: {}", maxLength);
    LOG.debug("Thread: {}", Thread.currentThread().getName());
  }

  public void setRequest(Request request) {
    this.request = request;
    LOG.debug("=== setRequest() called ===");
    LOG.debug("Request URI: {}", request != null ? request.getURI() : "null");
    LOG.debug("Thread: {}", Thread.currentThread().getName());
  }

  public Request getRequest() {
    return request;
  }

  public void setFallbackHttp1Client(HttpClient fallbackHttp1Client) {
    this.fallbackHttp1Client = fallbackHttp1Client;
  }

  protected void setStart() {
    if (this.responseStart == 0) {
      this.responseStart = System.currentTimeMillis();
    }
  }

  protected void setEnd() {
    this.responseEnd = System.currentTimeMillis();
  }

  public long getResponseStart() {
    return this.responseStart;
  }

  public long getResponseEnd() {
    return this.responseEnd;
  }

  public void completeWith(ContentResponse response, long responseStart, long responseEnd) {
    this.response = response;
    this.failure = null;
    if (responseStart > 0) {
      this.responseStart = responseStart;
    }
    this.responseEnd = responseEnd > 0 ? responseEnd : System.currentTimeMillis();
    this.onCompleteCalled = true;
    this.latch.countDown();
  }

  /**
   * Called when the request fails before completion.
   * This method is called BEFORE onComplete() when there's a failure.
   * This is our opportunity to intercept protocol_error early.
   */
  @Override
  public void onFailure(Response response, Throwable failure) {
    LOG.warn("=== onFailure() CALLED ===");
    LOG.warn("Thread: {}", Thread.currentThread().getName());
    LOG.warn("Response: {}", response != null ? "present" : "null");
    String failureInfo = failure != null
        ? failure.getClass().getName() + ": " + failure.getMessage()
        : "null";
    LOG.warn("Failure: {}", failureInfo);
    
    // Store the failure immediately
    this.failure = failure;
    
    // Check if this is a protocol_error
    if (failure != null) {
      LOG.debug("Checking isProtocolError in onFailure() for: {}", failure.getClass().getName());
      boolean isProtocolError = ProtocolErrorException.isProtocolError(failure);
      LOG.debug("isProtocolError returned: {}", isProtocolError);
      
      if (isProtocolError) {
        LOG.warn("=== PROTOCOL_ERROR DETECTED IN onFailure() ===");
        LOG.warn("Original failure: {}: {}", failure.getClass().getName(), failure.getMessage());
        LOG.warn("HTTP/2 protocol_error detected in onFailure() - "
            + "replacing with ProtocolErrorException");
        String message = failure.getMessage();
        // Replace failure with ProtocolErrorException so it can be caught specifically
        this.failure = new ProtocolErrorException(
            message != null ? message : "protocol_error", 
            failure);
        LOG.warn("Replaced with ProtocolErrorException: {}", this.failure.getClass().getName());
      }
    }
    
    // Call super to maintain normal behavior
    super.onFailure(response, failure);
  }

  @Override
  public void onComplete(Result result) {
    // CRITICAL: Mark that onComplete was called
    onCompleteCalled = true;
    
    LOG.debug("=== onComplete() CALLED ===");
    LOG.debug("Thread: {}", Thread.currentThread().getName());
    LOG.debug("Result: {}", result != null ? "present" : "null");
    
    if (result != null) {
      String failureInfo = result.getFailure() != null
          ? result.getFailure().getClass().getName() + ": "
              + result.getFailure().getMessage()
          : "null";
      LOG.debug("Result.getFailure(): {}", failureInfo);
      LOG.debug("Result.getResponse(): {}", 
          result.getResponse() != null ? "present" : "null");
      if (result.getResponse() != null) {
        LOG.debug("Response status: {}, version: {}", 
            result.getResponse().getStatus(), result.getResponse().getVersion());
      }
    }
    
    setEnd();
    failure = result != null ? result.getFailure() : null;
    
    LOG.debug("failure set: {}",
        failure != null ? failure.getClass().getName() + ": " + failure.getMessage() : "null");
    
    // CRITICAL: Detect protocol_error immediately when failure is set
    // This allows us to replace it with ProtocolErrorException before it propagates
    if (failure != null) {
      LOG.debug("Checking isProtocolError for: {}", failure.getClass().getName());
      boolean isProtocolError = ProtocolErrorException.isProtocolError(failure);
      LOG.debug("isProtocolError returned: {}", isProtocolError);
      
      if (isProtocolError) {
        LOG.warn("=== PROTOCOL_ERROR DETECTED IN onComplete() ===");
        LOG.warn("Original failure: {}: {}", failure.getClass().getName(), failure.getMessage());
        LOG.warn("HTTP/2 protocol_error detected in onComplete() - "
            + "replacing with ProtocolErrorException");
        String message = failure.getMessage();
        // Replace failure with ProtocolErrorException so it can be caught specifically
        failure = new ProtocolErrorException(
            message != null ? message : "protocol_error", 
            failure);
        LOG.warn("Replaced with ProtocolErrorException: {}", failure.getClass().getName());
      }
    }
    
    if (result != null && result.getResponse() != null) {
      Response httpResponse = result.getResponse();
      LOG.info("Response completed: status={}, version={}, reason={}, failure={}",
          httpResponse.getStatus(), httpResponse.getVersion(), httpResponse.getReason(),
          failure != null ? failure.getClass().getName() : "none");
      
      if (httpResponse.getVersion() != null) {
        LOG.info("HTTP version negotiated: {}", httpResponse.getVersion());
      }
      
      // In Jetty 12, ContentResponse is abstract - create a wrapper implementation
      response = new ContentResponseWrapper(httpResponse, getContent(),
          getMediaType(), getEncoding());
    } else {
      LOG.warn("Response is null in onComplete()");
    }
    
    if (failure != null) {
      LOG.error("Request failed with exception: type={}, message={}", 
          failure.getClass().getName(), failure.getMessage());
      if (failure instanceof IOException) {
        IOException ioException = (IOException) failure;
        String message = ioException.getMessage();
        LOG.error("IOException message: {}", message);
        if (message != null && message.contains("protocol_error")) {
          LOG.error("HTTP/2 protocol_error in onComplete() - ALPN negotiation likely failed");
        }
      }
      LOG.debug("Full failure stack trace:", failure);
    }
    
    latch.countDown();
  }
  
  /**
   * Wrapper class to implement ContentResponse interface in Jetty 12
   * where ContentResponse is abstract.
   */
  private static class ContentResponseWrapper implements ContentResponse {
    private final Response response;
    private final byte[] content;
    private final String mediaType;
    private final String encoding;
    
    ContentResponseWrapper(Response response, byte[] content, String mediaType, String encoding) {
      this.response = response;
      this.content = content != null ? content : new byte[0];
      this.mediaType = mediaType;
      this.encoding = encoding;
    }
    
    @Override
    public int getStatus() {
      return response.getStatus();
    }
    
    @Override
    public String getReason() {
      return response.getReason();
    }
    
    @Override
    public org.eclipse.jetty.http.HttpVersion getVersion() {
      return response.getVersion();
    }
    
    @Override
    public org.eclipse.jetty.http.HttpFields getHeaders() {
      return response.getHeaders();
    }
    
    @Override
    public org.eclipse.jetty.http.HttpFields getTrailers() {
      return response.getTrailers();
    }
    
    @Override
    public Request getRequest() {
      return response.getRequest();
    }
    
    @Override
    public byte[] getContent() {
      return content;
    }
    
    @Override
    public String getMediaType() {
      return mediaType;
    }
    
    @Override
    public String getEncoding() {
      return encoding;
    }
    
    @Override
    public String getContentAsString() {
      if (encoding != null) {
        try {
          return new String(content, Charset.forName(encoding));
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
          LOG.warn("Unsupported charset '{}', falling back to UTF-8", encoding, e);
        }
      }
      return new String(content, StandardCharsets.UTF_8);
    }
    
    // ContentResponse extends Response, so we need to implement Response methods
    // In Jetty 12, abort() returns CompletableFuture<Boolean> instead of Callback
    @Override
    public java.util.concurrent.CompletableFuture<Boolean> abort(Throwable failure) {
      return response.abort(failure);
    }
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    LOG.error("=== cancel() called ===");
    cancelled = true;
    // In Jetty 12, abort() returns CompletableFuture<Boolean>
    if (request != null) {
      request.abort(new CancellationException());
    }
    return true;
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
    LOG.debug("=== get() called (no timeout) ===");
    LOG.debug("Thread: {}", Thread.currentThread().getName());
    LOG.debug("onCompleteCalled before await: {}", onCompleteCalled);
    setStart();
    latch.await();
    LOG.debug("latch.await() completed, onCompleteCalled: {}", onCompleteCalled);
    try {
      return getResult();
    } catch (ProtocolErrorException e) {
      ContentResponse fallback = tryHttp11Fallback();
      if (fallback != null) {
        return fallback;
      }
      LOG.error("ProtocolErrorException caught in get(), wrapping in ExecutionException");
      // Wrap ProtocolErrorException in ExecutionException to maintain interface contract
      // The calling code will unwrap it and handle the fallback
      throw new ExecutionException(e);
    }
  }

  @Override
  public ContentResponse get(long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException,
      TimeoutException {
    LOG.debug("=== get(timeout) called ===");
    LOG.debug("Timeout: {} {}", timeout, unit);
    LOG.debug("Thread: {}", Thread.currentThread().getName());
    LOG.debug("onCompleteCalled before await: {}", onCompleteCalled);
    setStart();
    boolean expired = !latch.await(timeout, unit);
    LOG.debug("latch.await() completed, expired: {}, onCompleteCalled: {}",
        expired, onCompleteCalled);
    if (expired) {
      LOG.warn("Timeout expired, throwing TimeoutException");
      throw new TimeoutException();
    }
    try {
      return getResult();
    } catch (ProtocolErrorException e) {
      ContentResponse fallback = tryHttp11Fallback();
      if (fallback != null) {
        return fallback;
      }
      LOG.error("ProtocolErrorException caught in get(timeout), wrapping in ExecutionException");
      // Wrap ProtocolErrorException in ExecutionException to maintain interface contract
      // The calling code will unwrap it and handle the fallback
      throw new ExecutionException(e);
    }
  }

  private ContentResponse getResult() throws ExecutionException, ProtocolErrorException {
    LOG.debug("=== getResult() called ===");
    LOG.debug("Thread: {}", Thread.currentThread().getName());
    LOG.debug("onCompleteCalled: {}", onCompleteCalled);
    LOG.debug("isCancelled(): {}", isCancelled());
    String failureInfo = failure != null
        ? failure.getClass().getName() + ": " + failure.getMessage()
        : "null";
    LOG.debug("failure: {}", failureInfo);
    LOG.debug("response: {}", response != null ? "present" : "null");
    
    // If onComplete was never called, log a warning
    if (!onCompleteCalled) {
      LOG.warn("getResult() called but onComplete() was NEVER called!");
      LOG.warn("This suggests the error was handled before onComplete() could execute");
      LOG.warn("The error may have been thrown synchronously or handled by "
          + "BufferingResponseListener");
    }
    
    if (isCancelled()) {
      LOG.warn("Request was cancelled");
      throw (CancellationException) new CancellationException().initCause(failure);
    }
    if (failure != null) { // Failure and Response can coexist.
      if (response == null) { // Only generate exception response when an response not exist
        // Generated by nginx GOAWAY
        LOG.error("Request failed without response: exception type={}, message={}",
            failure.getClass().getName(), failure.getMessage());
        
        // Check if this is a protocol_error and throw ProtocolErrorException instead
        // Add detailed logging to diagnose detection
        LOG.error("Checking if failure is protocol_error: type={}, message={}", 
            failure.getClass().getName(), failure.getMessage());
        boolean isProtocolError = ProtocolErrorException.isProtocolError(failure);
        LOG.error("ProtocolErrorException.isProtocolError() returned: {}", isProtocolError);
        
        if (isProtocolError) {
          String message = failure.getMessage();
          LOG.error("HTTP/2 protocol_error detected in getResult() - "
              + "throwing ProtocolErrorException");
          LOG.error("  - ALPN negotiation may have failed during TLS handshake");
          LOG.error("  - Server rejected HTTP/2 connection");
          LOG.error("  - This will trigger HTTP/1.1 fallback");
          throw new ProtocolErrorException(message != null ? message : "protocol_error", failure);
        } else {
          LOG.error("Failure is NOT detected as protocol_error, will throw ExecutionException");
        }
        
        if (failure instanceof IOException) {
          IOException ioException = (IOException) failure;
          String message = ioException.getMessage();
          LOG.error("IOException details: {}", message);
        }
        LOG.debug("Full failure stack trace (no response):", failure);
        throw new ExecutionException(failure);
      } else {
        // It is a failure caused after obtaining the response,
        // analyzing what type of failure it is, and incorporating mechanisms to manage it.
        LOG.warn("Request failed after response received: status={}, version={}, exception={}",
            response.getStatus(), response.getVersion(), failure.getClass().getName());
        
        // Check if this is a protocol_error even though we have a response
        if (ProtocolErrorException.isProtocolError(failure)) {
          String message = failure.getMessage();
          LOG.error("HTTP/2 protocol_error detected after response - "
              + "throwing ProtocolErrorException");
          throw new ProtocolErrorException(message != null ? message : "protocol_error", failure);
        }
        
        LOG.debug("Failure after response received:", failure);
        throw new ExecutionException(failure);
      }
    }

    if (response == null) {
      LOG.warn("Response is null in getResult() but no failure was set");
    } else {
      LOG.debug("Response retrieved successfully: status={}, version={}", 
          response.getStatus(), response.getVersion());
    }
    return response;
  }

  private ContentResponse tryHttp11Fallback() {
    if (fallbackHttp1Client == null || request == null) {
      return null;
    }
    try {
      Request http11Request = fallbackHttp1Client.newRequest(request.getURI())
          .method(request.getMethod())
          .followRedirects(request.isFollowRedirects());
      if (request.getHeaders() != null) {
        HttpFields originalHeaders = request.getHeaders();
        HttpFields requestHeaders = http11Request.getHeaders();
        if (requestHeaders instanceof HttpFields.Mutable) {
          HttpFields.Mutable newHeaders = (HttpFields.Mutable) requestHeaders;
          originalHeaders.forEach(field -> {
            String name = field.getName();
            if (!name.startsWith(":")) {
              newHeaders.put(name, field.getValue());
            }
          });
          if (!newHeaders.contains(HttpHeader.HOST)) {
            URI uri = request.getURI();
            String host = uri.getHost() != null ? uri.getHost() : uri.getAuthority();
            int port = uri.getPort();
            int defaultPort = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            boolean includePort = port > 0 && port != defaultPort;
            String hostValue = includePort ? host + ":" + port : host;
            newHeaders.put(HttpHeader.HOST, hostValue);
          }
        }
      }
      if (request.getBody() != null) {
        http11Request.body(request.getBody());
      }
      LOG.warn("Retrying request with HTTP/1.1 in listener fallback: {}", request.getURI());
      return http11Request.send();
    } catch (Exception e) {
      LOG.error("HTTP/1.1 fallback in listener failed", e);
      return null;
    }
  }
}
