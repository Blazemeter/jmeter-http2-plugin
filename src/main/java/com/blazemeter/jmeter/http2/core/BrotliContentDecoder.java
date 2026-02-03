package com.blazemeter.jmeter.http2.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.brotli.dec.BrotliInputStream;
import org.eclipse.jetty.client.ContentDecoder;
import org.eclipse.jetty.io.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ContentDecoder implementation for Brotli compression (br encoding).
 * <p>
 * This class decodes Brotli-compressed HTTP response content.
 * </p>
 * <p>
 * In Jetty 12.1.5, ContentDecoder.Factory API changed:
 * - Factory must implement newDecoderContentSource(Content.Source) instead of newContentDecoder()
 * - The method returns a Content.Source that wraps the compressed source and decodes on-the-fly
 * </p>
 */
public class BrotliContentDecoder {
  
  private static final Logger LOG = LoggerFactory.getLogger(BrotliContentDecoder.class);
  private static final int BUFFER_SIZE = 8192;
  
  /**
   * Factory for creating BrotliContentDecoder Content.Source instances.
   * <p>
   * This factory is registered with HttpClient's ContentDecoderFactories
   * to handle Content-Encoding: br responses.
   * </p>
   * <p>
   * Jetty 12.1.5 API:
   * - Constructor takes (String encoding, float weight)
   * - Implements newDecoderContentSource(Content.Source)
   * </p>
   */
  public static class Factory extends ContentDecoder.Factory {
    
    /**
     * Constructor for Jetty 12.1.5.
     * <p>
     * Calls super("br", 1.0f) to register Brotli decoder with weight 1.0.
     * </p>
     */
    public Factory() {
      super("br", 1.0f);
    }
    
    /**
     * Jetty 12.1.5 API: newDecoderContentSource().
     * <p>
     * Creates a Content.Source that decodes Brotli-compressed content.
     * </p>
     * 
     * @param compressed The compressed Content.Source
     * @return A new Content.Source that decodes Brotli content on-the-fly
     */
    @Override
    public Content.Source newDecoderContentSource(Content.Source compressed) {
      return new BrotliDecodingSource(compressed);
    }
  }
  
  /**
   * Content.Source implementation that decodes Brotli-compressed content on-the-fly.
   */
  private static class BrotliDecodingSource implements Content.Source {
    private final Content.Source compressed;
    private final ByteArrayOutputStream compressedBuffer = new ByteArrayOutputStream();
    private final AtomicReference<ByteArrayOutputStream> decompressedBuffer =
        new AtomicReference<>(new ByteArrayOutputStream());
    private final AtomicInteger position = new AtomicInteger(0);
    private final AtomicBoolean eof = new AtomicBoolean(false);
    private final AtomicReference<Throwable> failure = new AtomicReference<>(null);
    private final AtomicBoolean reading = new AtomicBoolean(false);
    private final AtomicBoolean decompressed = new AtomicBoolean(false);
    
    BrotliDecodingSource(Content.Source compressed) {
      this.compressed = compressed;
    }
    
    @Override
    public Content.Chunk read() {
      Throwable fail = failure.get();
      if (fail != null) {
        return Content.Chunk.from(fail);
      }
      
      if (eof.get() && position.get() >= decompressedBuffer.get().size()) {
        return Content.Chunk.EOF;
      }
      
      // If we haven't read all compressed data yet, read it and decompress when complete
      if (reading.compareAndSet(false, true)) {
        try {
          readAndDecompress();
        } catch (IOException e) {
          LOG.error("Failed to decode Brotli content", e);
          failure.set(e);
          return Content.Chunk.from(e);
        } finally {
          reading.set(false);
        }
      }
      
      if (!decompressed.get()) {
        return null;
      }

      // Return decompressed chunk
      ByteArrayOutputStream buffer = decompressedBuffer.get();
      int pos = position.get();
      
      if (pos < buffer.size()) {
        int remaining = buffer.size() - pos;
        int toRead = Math.min(remaining, BUFFER_SIZE);
        byte[] data = buffer.toByteArray();
        ByteBuffer byteBuffer = ByteBuffer.wrap(data, pos, toRead);
        position.set(pos + toRead);
        
        // Check if this is the last chunk
        boolean isLast = (position.get() >= buffer.size() && eof.get());
        return Content.Chunk.from(byteBuffer, isLast);
      }
      
      if (eof.get()) {
        return Content.Chunk.EOF;
      }
      
      // Need more data, return null to indicate we need to demand more
      return null;
    }
    
    private void readAndDecompress() throws IOException {
      // Read compressed chunks into buffer
      Content.Chunk chunk = compressed.read();
      
      while (chunk != null) {
        if (Content.Chunk.isFailure(chunk)) {
          throw new IOException("Failed to read compressed content", chunk.getFailure());
        }
        
        if (chunk.isLast()) {
          eof.set(true);
        }
        
        ByteBuffer buffer = chunk.getByteBuffer();
        if (buffer != null && buffer.hasRemaining()) {
          byte[] bytes = new byte[buffer.remaining()];
          buffer.get(bytes);
          compressedBuffer.write(bytes);
        }
        
        chunk.release();
        
        if (!eof.get()) {
          // Demand more content synchronously for now
          // In a real async implementation, this would be more complex
          chunk = compressed.read();
        } else {
          break;
        }
      }
      
      if (!eof.get()) {
        return;
      }

      if (decompressed.get()) {
        return;
      }

      // Decompress all collected bytes once EOF is reached
      if (compressedBuffer.size() > 0) {
        ByteArrayInputStream bis = new ByteArrayInputStream(compressedBuffer.toByteArray());
        BrotliInputStream brotliStream = new BrotliInputStream(bis);
        
        ByteArrayOutputStream decompressedOut = new ByteArrayOutputStream();
        byte[] readBuffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = brotliStream.read(readBuffer)) != -1) {
          decompressedOut.write(readBuffer, 0, bytesRead);
        }
        brotliStream.close();
        
        decompressedBuffer.set(decompressedOut);
        decompressed.set(true);
      } else {
        decompressedBuffer.set(new ByteArrayOutputStream());
        decompressed.set(true);
      }
    }
    
    @Override
    public void demand(Runnable demandCallback) {
      Throwable fail = failure.get();
      if (fail != null) {
        demandCallback.run();
        return;
      }
      
      if (eof.get() && position.get() >= decompressedBuffer.get().size()) {
        demandCallback.run();
        return;
      }
      
      // If we need more data, demand from compressed source
      if (!decompressed.get()) {
        compressed.demand(() -> {
          try {
            if (reading.compareAndSet(false, true)) {
              try {
                readAndDecompress();
              } finally {
                reading.set(false);
              }
            }
            demandCallback.run();
          } catch (IOException e) {
            failure.set(e);
            demandCallback.run();
          }
        });
      } else {
        // We have data available, call callback immediately
        demandCallback.run();
      }
    }
    
    @Override
    public void fail(Throwable failure) {
      this.failure.set(failure);
      compressed.fail(failure);
    }
  }
}
