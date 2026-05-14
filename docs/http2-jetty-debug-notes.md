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
- `blazemeter.http.lowLevelLog`  
  Enables frame-level logging and file logging to `target/http2-debug.log`.

Diagnostic toggles:
- `blazemeter.http.skipHttp2Settings`  
  Skip custom HTTP/2 SETTINGS configuration.
- `blazemeter.http.directSend`  
  Use `request.send()` directly (bypass listener flow).
- `blazemeter.http.disableGzipDecoder`  
  Disable gzip decoder registration.
- `blazemeter.http.disableBrotliDecoder`  
  Disable brotli decoder registration.
- `blazemeter.http.disableZstdDecoder`  
  Disable zstd decoder registration.
- `blazemeter.http.disableDeflateDecoder`  
  Disable deflate decoder registration.

## Notes on Tests
- Gzip test forces HTTP/2 so regressions show up early.
- Compression integration tests use HTTP/1.1 for gzip and HTTP/2 for br/zstd/deflate.

## How to Enable Low-Level Logs
Example:
```
java -Dblazemeter.http.lowLevelLog=true ...
```

## Why Only One Logging Toggle
To avoid overhead and file I/O during normal runs, low-level logging is
fully disabled unless `blazemeter.http.lowLevelLog=true` is set.
