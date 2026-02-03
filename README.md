# HTTP2 Plugin for JMeter

---
<picture>
 <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/Blazemeter/jmeter-bzm-commons/refs/heads/master/src/main/resources/dark-theme/blazemeter-by-perforce-logo.png">
 <img src="https://raw.githubusercontent.com/Blazemeter/jmeter-bzm-commons/refs/heads/master/src/main/resources/light-theme/blazemeter-by-perforce-logo.png">
</picture>

This plugin provides an HTTP2 Sampler and a HTTP2 Controller in order to test you HTTP/2 endpoint.

_**IMPORTANT:** Java 17 required_

## To create your test:

1. Install the HTTP/2 plugin from the [plugins manager](https://www.blazemeter.com/blog/how-install-jmeter-plugins-manager).

2. Create a Thread Group.

3. Add the HTTP2 Sampler (Add-> Sampler-> bzm - HTTP2 Sampler).

![](docs/addHTTP2Sampler.png)

After that you can add timers, assertions, listeners, etc.

## Configuring the HTTP2 Sampler:

Let's explain the HTTP2 Sampler fields:

### Basic tab:

![](docs/http2Sampler-basic.png)

| **Field**                  | **Description**                                                                                                                                                       | **Default** |
|----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------|
| Protocol                   | Choose  HTTP or HTTPS                                                                                                                                                 | HTTP        |
| Server name or IP          | The domain name or IP address of the web server.  *[Do not include the http:// prefix.]*.                                                        |             |
| Port number                | The port the web server is listening to                                                                                                                               | 80          |
| Method                     | GET, POST, PUT, PATCH, DELETE and OPTIONS are the ones supported at the moment.                                                                                       |             |
| Path                       | The path to resource (For example:  `/servlets/myServlet`).                                                                                                           |             |
| Content Encoding           | Content encoding to be used (for POST, PUT, PATCH and FILE).  This is the character encoding to be used, and is not related to the Content-Encoding HTTP header.      |             |
| Redirect Automatically     | Sets the underlying HTTP protocol handler to automatically follow redirects, so they are not seen by JMeter, and therefore will not appear as samples.                |             |
| Follow Redirects           | If set, the JMeter sampler will check if the response is a redirect and will follow it. The initial redirect and further responses will appear as additional samples. |             |
| Use multipart/form-data    | Use a `multipart/form-data` or `application/x-www-form-urlencoded` post request                                                                                       |             |
| HTTP1 Upgrade              | Enables the usage of the Upgrade header for HTTP1 request. (Not enabling this sets HTTP2 as default).       |             |

### Advanced tab:

![](docs/http2Sampler-advanced.png)

| **Field**                                       | **Description**                                                                                                                                                                                                                      | **Default** |
|-------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------|
| **Timeouts (milliseconds):**                    |                                                                                                                                                                                                                                      |             |
| Connect                                         | Number of milliseconds to wait for a connection to open.                                                                                                                                                                             |             |
| Response                                        | The number of milliseconds to wait for a response.                                                                                                                                                                                   |             |
| **Proxy Server:**                               |                                                                                                                                                                                                                                      |             |
| Scheme                                          | The scheme identifies the protocol to be used to access the resource on the Internet.                                                                                                                                                | http        |
| Server name or IP                               | Hostname or IP address of a proxy server to perform request.  *[Do not include the http:// prefix]*.                                                                                                                                 |             |
| Port Number                                     | Port the proxy server is listening to.                                                                                                                                                                                               |             |
| Retrieve All Embedded Resources                 | Allows JMeter to parse the HTML file and send HTTP/HTTPS requests for all images, Java applets, JavaScript files, CSSs, etc. referenced in the file.                                                                                 |             |
| Parallel downloads                              | This feature allows the settings of a concurrent connection pool for retrieving embedded resources as part of the HTTP sampler.                                                                                                      |             |
| URLs must match                                 | Enables to filter the download of embedded resources that don't match the **regular expression**  set on it. For example, setting this regex `http:\/\/example\.invalid\/.*`, will only download the embedded resources that comes from `http://example.invalid/`.                              |             |

## HTTP/1 Upgrade
When HTTP/1 Upgrade is selected means that the first request made by the plugin will contain all required headers for http1 upgrade.

However, there is a known issue which will send the upgrade when receiving an HTTP1 response even thought the option of HTTP1 Upgrade is not selected.
Additionally after the first request which may contain upgrade headers or not (depending on user selection) afterwards all HTTP/1.1 requests made as long as the server responds in HTTP/1.1 will contain upgrade headers.

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

![HTTP2 Controller Demostration](docs/http2-async-controller.gif)
**Considerations**:
1. The amount of asynchronous requests will be determined by the JMeter property `httpJettyClient.maxConcurrentAsyncInController` which by default is `100`

1. If there are any elements within the Async Controller that are not HTTP2Samplers, they will function as a synchronization point for all asynchronous requests that occur before those elements. This means that before executing the different element, the controller will wait for all previous requests to complete.

1. Listeners such as View Result Tree will process the elements that finish first, so the order in which they display results may not necessarily follow the TestPlan order.

1. To emit a parent sample for the async controller duration (from the first async request start to the last completion), set the JMeter property `http2AsyncController.generateParentSample=true` or enable **Generate Parent Sample** in the controller UI. Child samples will be attached as sub-results of that parent sample.


## ALPN

The HTTP2 plugin offers Application-Layer Protocol Negotiation (ALPN) support, which facilitates the negotiation of application-layer protocols within the TLS handshake. This enables smooth communication between the client and server by allowing them to agree upon the protocols to be used.

The plugin supports the following protocols for ALPN negotiation:

- HTTP/1.1
- HTTP/2 (over TLS)
- HTTP/2 (over cleartext, h2c)

For TLS/SSL configuration please refer to [SSL Manager](https://jmeter.apache.org/usermanual/component_reference.html#SSL_Manager) and [Keystore Configuration](https://jmeter.apache.org/usermanual/component_reference.html#Keystore_Configuration)

## Auth Manager
Currently, we only give support to the Basic and Digest authentication mechanism.
To make use of Basic preemptive authentication results, make sure to create and set the property `httpJettyClient.auth.preemptive`
to true in the jmeter.properties file.

## Embedded Resources

To retrieve all embedded resources asynchronously, you simply need to choose "Parallel Downloads" as the option, unless the value is equal to 1.
If you would like to download embedded resources in a synchronous way choose "Parallel Downloads" and set the number to 1.

## Properties
This document describes JMeter properties. The properties present in jmeter.properties also should be set in the user.properties file. These properties are only taken into account after restarting JMeter as they are usually resolved when the class is loaded.

### Protocol Profiles
You can select a profile that controls default protocol behavior. Per-property overrides always
take precedence over the profile defaults.

- **browser-like** (default)
- **browser-like-custom**
- **browser-compatible**
- **legacy**

Profile selection:

| **Attribute**                       | **Description**                                       | **Default**     |
|-------------------------------------|-------------------------------------------------------|-----------------|
| **httpJettyClient.profile**         | Protocol behavior profile                             | browser-like    |

Profile defaults summary:

| **Profile**            | **HTTP/3** | **HTTP/2** | **HTTP/1.1** | **ALPN** | **Happy Eyeballs** |
|------------------------|-----------|-----------|-------------|---------|--------------------|
| browser-like           | on        | on        | on          | on      | 250 ms             |
| browser-like-custom    | on        | on        | on          | on      | 250 ms             |
| browser-compatible     | on        | on        | on          | on      | off (0 ms)         |
| legacy                 | off       | off       | on          | off     | off (0 ms)         |

Notes:
- HTTP/3 is discovered via Alt-Svc, not ALPN.
- Happy Eyeballs is only used for HTTP/3 (H3 + H2).

### UI to Sampler Mapping
The HTTP/2 Sampler UI stores settings as per-sampler properties. These values override profile
defaults at runtime. Only **Browser-like (Custom)** persists individual toggles; other profiles
store only the selected profile and use defaults.

| **UI Control**                                   | **Sampler Property**                      |
|--------------------------------------------------|-------------------------------------------|
| Client Behavior → Profile                        | `HTTP2Sampler.profile`                    |
| Protocols → Enable HTTP/3                        | `HTTP2Sampler.enableHttp3`                |
| Protocols → Enable HTTP/2                        | `HTTP2Sampler.enableHttp2`                |
| Protocols → Enable HTTP/1.1                      | `HTTP2Sampler.enableHttp1`                |
| Protocols → Enable ALPN                          | `HTTP2Sampler.alpnEnabled`                |
| Fallback → Automatic fallback                    | `HTTP2Sampler.fallbackEnabled`            |
| Fallback → protocol_error fallback               | `HTTP2Sampler.protocolErrorFallbackEnabled` |
| Cache → Alt-Svc cache                             | `HTTP2Sampler.altSvcCacheEnabled`         |
| Cache → HTTP/1.1-only cache                       | `HTTP2Sampler.http1OnlyCacheEnabled`      |
| Cache → H2C cache                                 | `HTTP2Sampler.h2cCacheEnabled`            |
| Cache → HTTP/2 prior knowledge (cleartext)        | `HTTP2Sampler.http2PriorKnowledge`        |
| Timing → Happy Eyeballs delay (ms)                | `HTTP2Sampler.happyEyeballsDelayMs`       |
| Timing → HTTP/3 broken cooldown (ms)              | `HTTP2Sampler.http3BrokenCooldownMs`      |
| Timing → HTTP/1.1-only cache TTL (ms)             | `HTTP2Sampler.http1OnlyCooldownMs`        |
| Timing → H2C cache TTL (ms)                       | `HTTP2Sampler.h2cCacheTtlMs`              |
| H2C Upgrade (HTTP/1.1 Upgrade)                    | `HTTP2Sampler.http1_upgrade`              |

| **Attribute**                                       | **Description**                                                                  | **Default** |
|-----------------------------------------------------|----------------------------------------------------------------------------------|-------------|
| **httpJettyClient.maxBufferSize**                   | Maximum size of the downloaded resources in bytes                                | 2097152     |
| **httpJettyClient.minThreads**                      | Minimum number of threads per http client                                        | 1           |
| **httpJettyClient.maxThreads**                      | Maximum number of threads per http client. If `httpJettyClient.sharedThreadPool` is true and this property is not set, the shared pool defaults to 500 threads. | 5           |
| **httpJettyClient.maxRequestsQueuedPerDestination** | Maximum number of requests that may be queued to a destination                   | 32767       |
| **httpJettyClient.maxConnectionsPerDestination**    | Sets the max number of connections to open to each destinations                  | 100         |
| **httpJettyClient.byteBufferPoolFactor**            | Factor number used in the allocation of memory in the buffer of http client      | 4           |
| **httpJettyClient.strictEventOrdering**             | Force request events ordering                                                    | false       |
| **httpJettyClient.sharedThreadPool**                | Use a shared thread pool across HTTP clients. When enabled and `httpJettyClient.maxThreads` is not set, the shared pool max defaults to 500. | false       |
| **httpJettyClient.idleTimeout**                     | Max time, in milliseconds, a connection can be idle (also used as destination idle timeout when enabled) | 60000       |
| **httpJettyClient.auth.preemptive**                 | Use of Basic preemptive authentication results                                   | false       |
| **httpJettyClient.maxConcurrentPushedStreams**      | Sets the maximum number of server push streams that is allowed to concurrently receive from a server | 100 |
| **httpJettyClient.maxConcurrentAsyncInController**  | Maximum number of concurrent http2 samplers inside a HTTP2 Async Controller      | 1000        |
| **HTTPSampler.response_timeout**                    | Maximum waiting time of request without timeout defined, in milliseconds         | 0           |
| **http.post_add_content_type_if_missing**           | Add to POST a Header Content-type: application/x-www-form-urlencoded if missing? | false       | 
| **httpJettyClient.enableHttp3**                     | Enable HTTP/3 support (Alt-Svc + QUIC)                                          | profile      |
| **httpJettyClient.enableHttp2**                     | Enable HTTP/2 support                                                           | profile      |
| **httpJettyClient.enableHttp1**                     | Enable HTTP/1.1 support                                                         | profile      |
| **httpJettyClient.alpnEnabled**                     | Enable ALPN (TLS negotiation for HTTP/2/1.1)                                    | profile      |
| **httpJettyClient.fallbackEnabled**                 | Enable automatic fallback between protocols                                    | profile      |
| **httpJettyClient.protocolErrorFallbackEnabled**    | Enable fallback to HTTP/1.1 for HTTP/2 protocol_error                           | profile      |
| **httpJettyClient.disableFallback**                 | Legacy flag (inverse of protocolErrorFallbackEnabled)                           | false        |
| **httpJettyClient.goawayRetryEnabled**              | Enable retry on HTTP/2 GOAWAY (RetryableRequestException)                        | true         |
| **httpJettyClient.maxGoawayRetries**                | Max retries on GOAWAY before fallback                                            | 1            |
| **httpJettyClient.altSvcCacheEnabled**              | Enable Alt-Svc cache for HTTP/3 discovery                                       | profile      |
| **httpJettyClient.http1OnlyCacheEnabled**           | Enable HTTP/1.1-only cache for HTTPS origins                                    | profile      |
| **httpJettyClient.h2cCacheEnabled**                 | Enable H2C cache for cleartext origins                                          | profile      |
| **httpJettyClient.h2cUpgradeEnabled**               | Default value for HTTP2Sampler.http1_upgrade (per-sampler override)             | false        |
| **httpJettyClient.h2cCacheTtlMs**                   | H2C cache TTL in milliseconds                                                   | profile      |
| **httpJettyClient.http1OnlyCooldownMs**             | HTTP/1.1-only cache TTL in milliseconds                                         | profile      |
| **httpJettyClient.http3BrokenCooldownMs**           | Cooldown before retrying HTTP/3 after failures (ms)                             | profile      |
| **httpJettyClient.happyEyeballsDelayMs**            | Delay before starting HTTP/2 fallback for HTTP/3 (ms)                           | profile      |
| **httpJettyClient.http2PriorKnowledge**             | Force HTTP/2 prior knowledge for cleartext origins (h2c)                        | false        |
| **httpJettyClient.quicMaxIdleTimeout**              | QUIC max idle timeout in milliseconds                                           | 30000        |
| **httpJettyClient.quicMaxBidirectionalStreams**     | QUIC max bidirectional streams                                                  | 100          |
| **httpJettyClient.quicMaxUnidirectionalStreams**    | QUIC max unidirectional streams                                                 | 100          |
| **httpJettyClient.settingsMaxHeaderListSize**       | HTTP/2 SETTINGS_MAX_HEADER_LIST_SIZE                                            | 4096         |

Legacy property (backward compatibility):
- **httpJettyClient.removeIdleDestinations**: if set to false, disables destination idle timeout.
