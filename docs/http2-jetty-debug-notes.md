# HTTP/2 Jetty Debug Notes

## Context
- Issue: `cancel_stream_error/input_shutdown` in gzip HTTP/2 test.
- Root cause: `GzipCompression` had a null `InflaterPool` in Jetty 12; led to NPE and stream reset.
- Fix: initialize and start `InflaterPool` before registering gzip decoder.

## What Was Kept (Permanent Fixes)
- `InflaterPool` initialization in `HTTP2JettyClient` for gzip.
- Tests keep HTTP/2 enabled for gzip (to catch regressions).
- Low-level logging guarded by a single toggle.

## Diagnostics and Toggles
All diagnostics are **system properties** (use `-Dkey=value`).

Low-level logging:
- `bzm-http2-plugin.lowLevelLog`  
  Enables frame-level logging and file logging to `target/http2-debug.log`.

Diagnostic toggles:
- `bzm-http2-plugin.skipHttp2Settings`  
  Skip custom HTTP/2 SETTINGS configuration.
- `bzm-http2-plugin.directSend`  
  Use `request.send()` directly (bypass listener flow).
- `bzm-http2-plugin.disableGzipDecoder`  
  Disable gzip decoder registration.
- `bzm-http2-plugin.disableBrotliDecoder`  
  Disable brotli decoder registration.
- `bzm-http2-plugin.disableZstdDecoder`  
  Disable zstd decoder registration.
- `bzm-http2-plugin.disableDeflateDecoder`  
  Disable deflate decoder registration.

## Notes on Tests
- Gzip test forces HTTP/2 so regressions show up early.
- Compression integration tests use HTTP/1.1 for gzip and HTTP/2 for br/zstd/deflate.

## How to Enable Low-Level Logs
Example:
```
java -Dbzm-http2-plugin.lowLevelLog=true ...
```

## Why Only One Logging Toggle
To avoid overhead and file I/O during normal runs, low-level logging is
fully disabled unless `bzm-http2-plugin.lowLevelLog=true` is set.
