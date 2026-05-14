# BlazeMeter HTTP Plugin for JMeter (HTTP/1.1, HTTP/2, HTTP/3/QUIC)

---
<picture>
 <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/Blazemeter/jmeter-bzm-commons/refs/heads/master/src/main/resources/dark-theme/blazemeter-by-perforce-logo.png">
 <img src="https://raw.githubusercontent.com/Blazemeter/jmeter-bzm-commons/refs/heads/master/src/main/resources/light-theme/blazemeter-by-perforce-logo.png">
</picture>

This plugin provides a `bzm - HTTP Sampler` (multi-protocol: **HTTP/1.1**, **HTTP/2**, **HTTP/3 (QUIC)**) and an `HTTP2 Async Controller` for running many requests concurrently (multiplexing).

_**IMPORTANT:** Java 17 required_

## To create your test

### Option A: Manual setup (recommended)

1. Install the plugin from the [JMeter Plugins Manager](https://www.blazemeter.com/blog/how-install-jmeter-plugins-manager).
2. Create a Thread Group.

3. Add the HTTP Sampler (Add-> Sampler-> bzm - HTTP Sampler).
s
![](docs/addHTTP2Sampler.png)

4. Configure the sampler URL + client behavior (profile/protocols) as described below.
5. Add timers, assertions, listeners, etc.

### Option B: Experimental recording (HTTP(S) Test Script Recorder)

This plugin can act as a sampler creator during JMeter proxy recording, but it is **disabled by default**.

1. Enable recording support by adding to `user.properties` (or `jmeter.properties`), then restart JMeter:
   - `HTTP2Sampler.proxy_enabled=true`
2. Use JMeter’s **HTTP(S) Test Script Recorder** as usual.

Notes:

- This is **experimental**. If you hit issues, disable it and record with the default HTTP sampler.
- The recorder creates `bzm - HTTP Sampler` elements, but you may still want to adjust protocol/profile settings afterwards.

## Configuring the sampler

The sampler UI has two tabs: **Basic** (URL + request basics) and **Advanced** (client behavior + timeouts/proxy/embedded resources).

### Basic tab

![](docs/http2Sampler-basic.png)

This is the standard JMeter URL configuration panel (domain, port, path, method, redirects, body, etc.).

| **Field** | **Description** | **Default** |
|---|---|---|
| Protocol | `http` or `https` (URL scheme). This is independent from HTTP/1.1 vs HTTP/2 vs HTTP/3 negotiation. | `http` |
| Server name or IP | Target host (no `http://` prefix). | |
| Port number | Target port. | `80` for http, `443` for https |
| Method | Standard HTTP methods supported by JMeter. | `GET` |
| Path | Path + query (e.g. `/api/v1/items?limit=10`). | `/` |
| Content encoding | Character encoding used for request body (unrelated to HTTP `Content-Encoding` header). | |
| Redirect Automatically / Follow Redirects | Same semantics as JMeter’s HTTP Request sampler. | |
| Use KeepAlive | Reuse connections when possible (same semantics as JMeter’s HTTP Request sampler). | |
| Browser-compatible headers | Add headers that mimic common browser behavior (same semantics as JMeter’s HTTP Request sampler). | |
| Use multipart/form-data | Same semantics as JMeter’s HTTP Request sampler. | |

### Advanced tab

![](docs/http2Sampler-advanced.png)

#### Client Behavior → Profile

A **profile** is a named bundle of protocol defaults (which protocols are enabled, whether ALPN is enabled, fallback/caches, and timings). Profiles make it easy to mimic “how browsers behave” vs “legacy compatibility”.

The available profiles are:

- **Browser-like (Most Browsers)** (`browser-like`) (default)
- **Browser-compatible (Other Browsers)** (`browser-compatible`)
- **Legacy / Older Systems** (`legacy`)
- **Browser-like (Custom)** (`browser-like-custom`) (persists per-sampler toggles below)

#### Client Behavior → Protocols / Fallback / Cache / Timing

| **Control** | **What it does** |
|---|---|
| Enable HTTP/3 (Alt-Svc + QUIC) | Allows HTTP/3 when the origin advertises it via `Alt-Svc`. Typically requires `https` and UDP/QUIC reachability. |
| Enable HTTP/2 | Allows HTTP/2. Over TLS this is typically negotiated via ALPN. For cleartext origins see H2C options below. |
| Enable HTTP/1.1 | Allows HTTP/1.1. If disabled, the client will avoid HTTP/1.1 unless forced by server/proxy constraints. |
| H2C Upgrade (HTTP/1.1 Upgrade) | For cleartext (`http://`) origins, allow HTTP/1.1 → HTTP/2 (h2c) upgrade. Visible when HTTP/1.1 and HTTP/2 are enabled. |
| Enable ALPN | Enables ALPN negotiation (TLS) for HTTP/2 and HTTP/1.1. (HTTP/3 discovery is via Alt-Svc, not ALPN.) |
| Automatic fallback | Enables automatic fallback between protocols (e.g. prefer HTTP/3, then fall back to HTTP/2/1.1 as needed). |
| Fallback on HTTP/2 protocol_error | If an HTTP/2 `protocol_error` occurs, retry using HTTP/1.1 (when fallback is enabled). |
| Alt-Svc cache | Caches `Alt-Svc` advertisements used for HTTP/3 discovery. |
| HTTP/1.1-only cache (HTTPS) | Caches “this HTTPS origin should be treated as HTTP/1.1-only” after failures, to avoid repeated negotiation overhead. |
| H2C cache (HTTP) | Caches h2c capability for cleartext origins. |
| HTTP/2 prior knowledge for cleartext (h2c) | Forces HTTP/2 prior knowledge (h2c) for cleartext origins, skipping the upgrade dance. Use only if you know the server speaks h2c. |
| Happy Eyeballs delay (ms) | When trying HTTP/3, delay before starting HTTP/2 in parallel (H3+H2) to reduce latency while avoiding extra connections. |
| HTTP/3 broken cooldown (ms) | How long to consider HTTP/3 “broken” for an origin after failures before retrying. |
| HTTP/1.1-only cache TTL (ms) | How long to keep the “HTTP/1.1-only” decision for an HTTPS origin. |
| H2C cache TTL (ms) | How long to keep the “h2c supported” decision for a cleartext origin. |

#### Timeouts (milliseconds)

| **Field** | **Description** |
|---|---|
| Connect | Milliseconds to wait for a connection to open. |
| Response | Milliseconds to wait for a response. |

#### Proxy Server

| **Field** | **Description** |
|---|---|
| Scheme | Proxy scheme (typically `http`). |
| Server name or IP | Proxy host. |
| Port Number | Proxy port. |

#### Embedded resources

| **Field** | **Description** |
|---|---|
| Retrieve All Embedded Resources | Parses HTML and downloads embedded resources (images, JS, CSS, etc.). |
| Parallel downloads | When enabled, embedded resources are fetched concurrently; “pool size = 1” forces synchronous behavior. |
| URLs must match | Regex filter for which embedded resources to download. |

## Protocols: how to use HTTP/1.1 vs HTTP/2 vs HTTP/3

The simplest way is to pick a profile and only override when you need a specific behavior.

Common configurations:

- **Prefer HTTP/3, fallback to HTTP/2/1.1**: profile `browser-like` (default). Ensure **Enable HTTP/3** and **Automatic fallback** are enabled.
- **HTTP/2-only (TLS)**: disable **Enable HTTP/1.1**, disable **Enable HTTP/3**, keep **Enable HTTP/2** enabled. (For `https://` origins, ALPN typically must be enabled.)
- **HTTP/1.1-only**: keep **Enable HTTP/1.1** enabled, disable HTTP/2 and HTTP/3.
- **Cleartext h2c (upgrade)**: use `http://` scheme, enable HTTP/1.1 + HTTP/2, and enable **H2C Upgrade**.
- **Cleartext h2c (prior knowledge)**: use `http://` scheme, enable HTTP/2, enable **HTTP/2 prior knowledge for cleartext (h2c)** (only if the server supports it).

HTTP/3 notes:

- HTTP/3 is **discovered via Alt-Svc** (and optionally cached), not via ALPN.

## Buffer capacity

By default, the size of the downloaded resources is set to 2 MB (2097152 bytes) but, the limit can be increased by adding the `httpJettyClient.maxBufferSize` property on the jmeter.properties file in bytes.

## Multiplexing

One of the main features that were incorporated in HTTP2 was the multiplexing capability.
Multiplexing in HTTP2 allows for multiple concurrent requests and responses to be transmitted over a single connection, improving efficiency and reducing latency.

Currently, the plugin does not handle any limit for the asynchronous requests. However, it could be limited by setting the `maxConcurrentAsyncInController` property or it's also possible to tune the maximum number of requests per connection  
`httpJettyClient.maxRequestsPerConnection` which is 100 by default.

> IMPORTANT: All HTTP2 requests outside a HTTP2 Async Controller will run synchronous (multiplexing disabled)

## HTTP2 Async Controller

All HTTP2 samplers embedded in a HTTP2 Async controller will run asynchronous.

![HTTP2 Controller Demonstration](docs/http2-async-controller.gif)

**Considerations**:

1. The amount of asynchronous requests will be determined by the JMeter property `httpJettyClient.maxConcurrentAsyncInController` which by default is `100`
2. If there are any elements within the Async Controller that are not HTTP2Samplers, they will function as a synchronization point for all asynchronous requests that occur before those elements. This means that before executing the different element, the controller will wait for all previous requests to complete.
3. Listeners such as View Result Tree will process the elements that finish first, so the order in which they display results may not necessarily follow the TestPlan order.
4. To emit a parent sample for the async controller duration (from the first async request start to the last completion), enable **Generate Parent Sample** in the controller UI. Child samples will be attached as sub-results of that parent sample.

## ALPN

The plugin offers Application-Layer Protocol Negotiation (ALPN) support, which facilitates the negotiation of application-layer protocols within the TLS handshake.

The plugin supports the following protocols for ALPN negotiation:

- HTTP/1.1
- HTTP/2 (over TLS)
- HTTP/2 (over cleartext, h2c)

For TLS/SSL configuration please refer to [SSL Manager](https://jmeter.apache.org/usermanual/component_reference.html#SSL_Manager) and [Keystore Configuration](https://jmeter.apache.org/usermanual/component_reference.html#Keystore_Configuration).

## Auth Manager

Currently, we only support the Basic and Digest authentication mechanisms.

To make use of Basic preemptive authentication results, make sure to create and set the property `httpJettyClient.auth.preemptive` to true in the jmeter.properties file.

## Embedded Resources

To retrieve all embedded resources asynchronously, you simply need to choose "Parallel Downloads" as the option, unless the value is equal to 1.
If you would like to download embedded resources in a synchronous way choose "Parallel Downloads" and set the number to 1.

## Properties

This document describes JMeter properties. The properties present in jmeter.properties also should be set in the user.properties file. These properties are only taken into account after restarting JMeter as they are usually resolved when the class is loaded.

### Protocol Profiles

You can select a profile that controls default protocol behavior. Per-property overrides always take precedence over the profile defaults.

- **browser-like** (default)
- **browser-like-custom**
- **browser-compatible**
- **legacy**

Profile selection:

| **Attribute** | **Description** | **Default** |
|---|---|---|
| **httpJettyClient.profile** | Protocol behavior profile | browser-like |

Profile defaults summary:

| **Profile** | **HTTP/3** | **HTTP/2** | **HTTP/1.1** | **ALPN** | **Happy Eyeballs** |
|---|---:|---:|---:|---:|---:|
| browser-like | on | on | on | on | 250 ms |
| browser-like-custom | on | on | on | on | 250 ms |
| browser-compatible | on | on | on | on | off (0 ms) |
| legacy | off | off | on | off | off (0 ms) |

Notes:

- HTTP/3 is discovered via Alt-Svc, not ALPN.
- Happy Eyeballs is only used for HTTP/3 (H3 + H2).

### What “profile” means (in practice)

- The **global** profile is `httpJettyClient.profile` (applies when a sampler has no per-sampler overrides).
- Each sampler can store a profile in `HTTP2Sampler.profile`.
- Profiles define defaults for:
  - which protocols are enabled (HTTP/3, HTTP/2, HTTP/1.1)
  - whether ALPN is used
  - fallback + caching behavior
  - timing knobs used for HTTP/3 → HTTP/2 “Happy Eyeballs”

In the UI:

- If you use **Browser-like (Custom)**, toggles are saved per-sampler.
- If you use any other profile, the UI typically persists only the selected profile and relies on profile defaults.

### UI to Sampler Mapping

The sampler UI stores settings as per-sampler properties. These values override profile defaults at runtime. Only **Browser-like (Custom)** persists individual toggles; other profiles store only the selected profile and use defaults.

| **UI Control** | **Sampler Property** |
|---|---|
| Client Behavior → Profile | `HTTP2Sampler.profile` |
| Protocols → Enable HTTP/3 | `HTTP2Sampler.enableHttp3` |
| Protocols → Enable HTTP/2 | `HTTP2Sampler.enableHttp2` |
| Protocols → Enable HTTP/1.1 | `HTTP2Sampler.enableHttp1` |
| Protocols → Enable ALPN | `HTTP2Sampler.alpnEnabled` |
| Fallback → Automatic fallback | `HTTP2Sampler.fallbackEnabled` |
| Fallback → protocol_error fallback | `HTTP2Sampler.protocolErrorFallbackEnabled` |
| Cache → Alt-Svc cache | `HTTP2Sampler.altSvcCacheEnabled` |
| Cache → HTTP/1.1-only cache | `HTTP2Sampler.http1OnlyCacheEnabled` |
| Cache → H2C cache | `HTTP2Sampler.h2cCacheEnabled` |
| Cache → HTTP/2 prior knowledge (cleartext) | `HTTP2Sampler.http2PriorKnowledge` |
| Timing → Happy Eyeballs delay (ms) | `HTTP2Sampler.happyEyeballsDelayMs` |
| Timing → HTTP/3 broken cooldown (ms) | `HTTP2Sampler.http3BrokenCooldownMs` |
| Timing → HTTP/1.1-only cache TTL (ms) | `HTTP2Sampler.http1OnlyCooldownMs` |
| Timing → H2C cache TTL (ms) | `HTTP2Sampler.h2cCacheTtlMs` |
| H2C Upgrade (HTTP/1.1 Upgrade) | `HTTP2Sampler.http1_upgrade` |

### JMeter properties

| **Attribute** | **Description** | **Default** |
|---|---|---:|
| **httpJettyClient.maxBufferSize** | Maximum size of the downloaded resources in bytes | 2097152 |
| **httpJettyClient.minThreads** | Minimum number of threads per http client | 1 |
| **httpJettyClient.maxThreads** | Maximum number of threads per http client | 5 |
| **httpJettyClient.maxRequestsQueuedPerDestination** | Maximum number of requests that may be queued to a destination | 32767 |
| **httpJettyClient.maxConnectionsPerDestination** | Sets the max number of connections to open to each destinations | 100 |
| **httpJettyClient.byteBufferPoolFactor** | Factor number used in the allocation of memory in the buffer of http client | 4 |
| **httpJettyClient.strictEventOrdering** | Force request events ordering | false |
| **httpJettyClient.sharedThreadPool** | Use a shared thread pool across HTTP clients | false |
| **httpJettyClient.idleTimeout** | Max time, in milliseconds, a connection can be idle | 60000 |
| **httpJettyClient.auth.preemptive** | Use of Basic preemptive authentication results | false |
| **httpJettyClient.maxConcurrentPushedStreams** | Maximum number of server push streams concurrently received | 100 |
| **httpJettyClient.maxConcurrentAsyncInController** | Maximum number of concurrent samplers inside a HTTP2 Async Controller | 1000 |
| **HTTPSampler.response_timeout** | Maximum waiting time without timeout defined (ms) | 0 |
| **http.post_add_content_type_if_missing** | Add Content-Type header if missing? | false |
| **httpJettyClient.enableHttp3** | Enable HTTP/3 support (Alt-Svc + QUIC) | profile |
| **httpJettyClient.enableHttp2** | Enable HTTP/2 support | profile |
| **httpJettyClient.enableHttp1** | Enable HTTP/1.1 support | profile |
| **httpJettyClient.alpnEnabled** | Enable ALPN | profile |
| **httpJettyClient.fallbackEnabled** | Enable automatic fallback between protocols | profile |
| **httpJettyClient.protocolErrorFallbackEnabled** | Enable fallback to HTTP/1.1 for HTTP/2 protocol_error | profile |
| **httpJettyClient.disableFallback** | Legacy flag (inverse of protocolErrorFallbackEnabled) | false |
| **httpJettyClient.goawayRetryEnabled** | Enable retry on HTTP/2 GOAWAY | true |
| **httpJettyClient.maxGoawayRetries** | Max retries on GOAWAY before fallback | 1 |
| **httpJettyClient.altSvcCacheEnabled** | Enable Alt-Svc cache for HTTP/3 discovery | profile |
| **httpJettyClient.http1OnlyCacheEnabled** | Enable HTTP/1.1-only cache for HTTPS origins | profile |
| **httpJettyClient.h2cCacheEnabled** | Enable H2C cache for cleartext origins | profile |
| **httpJettyClient.h2cUpgradeEnabled** | Default value for `HTTP2Sampler.http1_upgrade` | false |
| **httpJettyClient.h2cCacheTtlMs** | H2C cache TTL in milliseconds | profile |
| **httpJettyClient.http1OnlyCooldownMs** | HTTP/1.1-only cache TTL in milliseconds | profile |
| **httpJettyClient.http3BrokenCooldownMs** | Cooldown before retrying HTTP/3 after failures (ms) | profile |
| **httpJettyClient.happyEyeballsDelayMs** | Delay before starting HTTP/2 fallback for HTTP/3 (ms) | profile |
| **httpJettyClient.http2PriorKnowledge** | Force HTTP/2 prior knowledge for cleartext origins (h2c) | false |
| **httpJettyClient.quicMaxIdleTimeout** | QUIC max idle timeout in milliseconds | 30000 |
| **httpJettyClient.quicMaxBidirectionalStreams** | QUIC max bidirectional streams | 100 |
| **httpJettyClient.quicMaxUnidirectionalStreams** | QUIC max unidirectional streams | 100 |
| **httpJettyClient.settingsMaxHeaderListSize** | HTTP/2 SETTINGS_MAX_HEADER_LIST_SIZE | 4096 |

Legacy property (backward compatibility):

- **httpJettyClient.removeIdleDestinations**: if set to false, disables destination idle timeout.

