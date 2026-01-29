package com.blazemeter.jmeter.http2.core;

import java.io.IOException;

/**
 * Custom exception for HTTP/2 protocol errors.
 * This exception is thrown when a protocol_error is detected,
 * allowing the code to specifically catch it and trigger HTTP/1.1 fallback.
 */
public class ProtocolErrorException extends IOException {
  
  private static final long serialVersionUID = 1L;
  private final String originalMessage;
  
  public ProtocolErrorException(String message) {
    super("HTTP/2 protocol_error: " + message);
    this.originalMessage = message;
  }
  
  public ProtocolErrorException(String message, Throwable cause) {
    super("HTTP/2 protocol_error: " + message, cause);
    this.originalMessage = message;
  }
  
  public String getOriginalMessage() {
    return originalMessage;
  }
  
  /**
   * Checks if the given exception is a protocol_error.
   * This method checks the exception message and class name for protocol_error indicators.
   */
  public static boolean isProtocolError(Throwable throwable) {
    if (throwable == null) {
      return false;
    }
    
    // Check if it's already a ProtocolErrorException
    if (throwable instanceof ProtocolErrorException) {
      return true;
    }
    
    // Check the exception message first (most reliable)
    String message = throwable.getMessage();
    if (message != null) {
      String lowerMessage = message.toLowerCase();
      if (lowerMessage.contains("protocol_error")
          || lowerMessage.contains("protocol error")
          || lowerMessage.contains("rst_stream")
          || lowerMessage.contains("frame_size_error")
          || lowerMessage.contains("invalid_frame_length")
          || "protocol_error".equals(lowerMessage)) {
        return true;
      }
    }
    
    // Check if it's an IOException with protocol_error
    if (throwable instanceof IOException) {
      // Already checked message above, but also check class name
      String className = throwable.getClass().getName().toLowerCase();
      if (className.contains("protocol") || className.contains("http2")) {
        return true;
      }
    }
    
    // Check the exception class name
    String className = throwable.getClass().getName().toLowerCase();
    if (className.contains("protocol") || className.contains("http2")) {
      String msg = throwable.getMessage();
      if (msg != null && msg.toLowerCase().contains("error")) {
        return true;
      }
    }
    
    // Recursively check the cause
    Throwable cause = throwable.getCause();
    if (cause != null && cause != throwable) {
      return isProtocolError(cause);
    }
    
    return false;
  }
}
