package com.blazemeter.jmeter.http2.sampler;

import com.blazemeter.jmeter.http2.visualizers.ResultCollector;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpCookie;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.engine.event.LoopIterationListener;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.protocol.http.util.HTTPFileArgs;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;
import org.apache.jmeter.threads.SamplePackage;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2Request extends AbstractSampler implements ThreadListener, LoopIterationListener {

    public static final String ENCODING = StandardCharsets.ISO_8859_1.name();

    private static final long serialVersionUID = 5859387434748163229L;
    private static final Logger LOG = LoggerFactory.getLogger(HTTP2Request.class);

    private static final String DEFAULT_RESPONSE_TIMEOUT = "20000"; //20 sec
    public static final String METHOD = "HTTP2Sampler.method";
    private static final String PROTOCOL_SCHEME = "HTTP2Sampler.scheme";
    public static final String PORT = "HTTPSampler.port"; // $NON-NLS-1$
    private static final String FILE_ARGS = "HTTPsampler.Files"; // $NON-NLS-1$
    public static final String DEFAULT_METHOD = "GET";
    private static final String HEADER_MANAGER = "HTTP2Request.header_manager"; // $NON-NLS-1$
    private static final String COOKIE_MANAGER = "HTTP2Request.cookie_manager"; // $NON-NLS-1$
    private static final String CACHE_MANAGER = "HTTP2Request.cache_manager"; // $NON-NLS-1$
    private static final String HTTP_PREFIX = HTTPConstants.PROTOCOL_HTTP + "://"; // $NON-NLS-1$
    private static final String HTTPS_PREFIX = HTTPConstants.PROTOCOL_HTTPS + "://"; // $NON-NLS-1$
    private static final String DEFAULT_PROTOCOL = HTTPConstants.PROTOCOL_HTTPS;
    /**
     * A number to indicate that the port has not been set.
     */
    private static final int UNSPECIFIED_PORT = 0;
    public static final String UNSPECIFIED_PORT_AS_STRING = "0"; // $NON-NLS-1$
    private static final String NON_HTTP_RESPONSE_CODE = "Non HTTP response code";
    private static final String NON_HTTP_RESPONSE_MESSAGE = "Non HTTP response message";
    public static final String IP_SOURCE = "HTTP2Request.ipSource"; // $NON-NLS-1$
    public static final String IP_SOURCE_TYPE = "HTTP2Request.ipSourceType"; // $NON-NLS-1$
    // Embedded URLs must match this regex (if provided)
    public static final String EMBEDDED_URL_REGEX = "HTTPSampler.embedded_url_re"; // $NON-NLS-1$
    // Store MD5 hash instead of storing response
    public static final String MD5 = "HTTPSampler.md5"; // $NON-NLS-1$
    public static final String EMBEDDED_RESOURCES = "HTTPSampler.embedded_resources"; // $NON-NLS-1$
    public static final int SOURCE_TYPE_DEFAULT = HTTPSamplerBase.SourceType.HOSTNAME.ordinal();
    public static final String ARGUMENTS = "HTTP2Request.Arguments"; // $NON-NLS-1$
    public static final String POST_BODY_RAW = "HTTP2Request.postBodyRaw"; // TODO - belongs elsewhere
    public static final boolean POST_BODY_RAW_DEFAULT = false;
    public static final String DOMAIN = "HTTP2Request.domain"; // $NON-NLS-1$
    public static final String RESPONSE_TIMEOUT = "HTTP2Request.response_timeout"; // $NON-NLS-1$
    public static final String FOLLOW_REDIRECTS = "HTTP2Request.follow_redirects"; // $NON-NLS-1$
    public static final String AUTO_REDIRECTS = "HTTP2Request.auto_redirects"; // $NON-NLS-1$
    public static final String SYNC_REQUEST = "HTTP2Request.sync_request"; // $NON-NLS-1$
    public static final String PROTOCOL = "HTTP2Request.protocol"; // $NON-NLS-1$
    public static final String REQUEST_ID = "HTTP2Request.request_id"; // $NON-NLS-1$

    public static final String FORCE_MULTIPART = "HTTP2Request.force_multipart"; // $NON-NLS-1$
    public static final String MULTIPART_SUBTYPE = "HTTP2Request.multipart_subtype"; // $NON-NLS-1$
    public static final String FORCE_BOUNDARY = "HTTP2Request.force_boundary"; // $NON-NLS-1$

    public static final String PRI = "PRI"; // $NON-NLS-1$

    public static final String MULTIPART = "multipart/";
    public static final String MULTIPART_ALTERNATIVE = "alternative";
    public static final String MULTIPART_BYTERANGE = "byterange";
    public static final String MULTIPART_DIGEST = "digest";
    public static final String MULTIPART_ENCRYPTED = "encrypted";
    public static final String MULTIPART_FORMDATA = "form-data";
    public static final String MULTIPART_MIXED = "mixed";
    public static final String MULTIPART_MIXEDREPLACE = "x-mixed-replace";
    public static final String MULTIPART_PARALLEL = "parallel";
    public static final String MULTIPART_RELATED = "related";
    public static final String MULTIPART_REPORT = "report";
    public static final String MULTIPART_SIGNED = "signed";

    public static final String BOUNDARY = "boundary";

    /**
     * This is the encoding used for the content, i.e. the charset name, not the header
     * "Content-Encoding"
     */
    public static final String CONTENT_ENCODING = "HTTP2Request.contentEncoding"; // $NON-NLS-1$
    public static final String PATH = "HTTP2Request.path"; // $NON-NLS-1$

    private static final String QRY_SEP = "&"; // $NON-NLS-1$
    private static final String QRY_PFX = "?"; // $NON-NLS-1$

    private static ThreadLocal<Map<String, HTTP2Connection>> connections = ThreadLocal
            .withInitial(HashMap::new);

    private HTTP2Connection http2Connection;

    public HTTP2Request() {
        if (LOG.isDebugEnabled()) {
            StdErrLog logger = new StdErrLog();
            logger.setDebugEnabled(true);
            Log.setLog(logger);
        }
        setName("HTTP2 Request");
    }

    @Override
    public void setName(String name) {
        if (name != null) {
            setProperty(TestElement.NAME, name);
        }
    }

    @Override
    public String getName() {
        return getPropertyAsString(TestElement.NAME);
    }

    @Override
    public SampleResult sample(Entry entry) {
        return sample();
    }

    /**
     * Perform a sample, and return the results
     *
     * @return results of the sampling
     */
    public SampleResult sample() {

        JMeterContext threadContext = getThreadContext();
        int nbActiveThreadsInThreadGroup = threadContext.getThreadGroup().getNumberOfThreads();
        int nbTotalActiveThreads = JMeterContextService.getNumberOfThreads();
        HTTP2SampleResult sampleResult = new HTTP2SampleResult(threadContext);
        sampleResult.setSampleLabel(getName());
        sampleResult.setGroupThreads(nbActiveThreadsInThreadGroup);
        sampleResult.setAllThreads(nbTotalActiveThreads);
        sampleResult.setThreadName(getThreadName());
        sampleResult.setSync(isSyncRequest());
        try {
            URL url = getUrl();
            sampleResult.setURL(url);
            sampleResult.setHTTPMethod(getHttpMethod(getMethod()));
            setConnection(url, sampleResult);
            sample(url, getMethod(), getConnection(), sampleResult);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorResult(e, sampleResult);
            LOG.warn("InterruptedException: ",e);
        } catch (Exception e) {
            errorResult(e, sampleResult);
            LOG.warn("Exception: ",e);
        }
    /*As HTTP2 protocol is async then when the Sampler finish there is a possibility that the
     response did not come yet, so this method returns null because when the response finish a
     notifier method is called from HTTP2SampleResult.*/
        if (!isSyncRequest()) {
            SamplePackage pack = (SamplePackage) threadContext.getVariables()
                    .getObject(JMeterThread.PACKAGE_OBJECT);
            for (SampleListener l : pack.getSampleListeners()) {
                if (l instanceof ResultCollector) {
                    SampleEvent event = new SampleEvent(sampleResult, getThreadName(),
                            threadContext.getVariables(), false);
                    ((ResultCollector) l).sampleOccurred(event);
                }
            }
            return null;
        } else {
            return sampleResult;
        }
    }

    /**
     * Populates the provided HTTPSampleResult with details from the Exception. Does not create a new
     * instance, so should not be used directly to add a subsample.
     *
     * @param e   Exception representing the error.
     * @param res SampleResult to be modified
     * @return the modified sampling result containing details of the Exception.
     */
    private void errorResult(Throwable e, HTTP2SampleResult res) {
        res.setSampleLabel(res.getSampleLabel());
        res.setDataType(SampleResult.TEXT);
        ByteArrayOutputStream text = new ByteArrayOutputStream(200);
        e.printStackTrace(new PrintStream(text));
        res.setResponseData(text.toByteArray());
        res.setResponseCode(NON_HTTP_RESPONSE_CODE + ": " + e.getClass().getName());
        res.setResponseMessage(NON_HTTP_RESPONSE_MESSAGE + ": " + e.getMessage());
        res.setSuccessful(false);
        res.setPendingResponse(false);
    }

    protected void sample(URL url, String method, HTTP2Connection http2Connection,
                          HTTP2SampleResult sampleResult) {

        LOG.debug("Start : sample: {}, method: {}" , url.toString(), method);

        sampleResult.setSuccessful(false);
        sampleResult.setEmbebedResults(isEmbeddedResources());
        sampleResult.setEmbeddedUrlRE(getEmbeddedUrlRE());

        try {
            int timeout = Integer.parseInt(DEFAULT_RESPONSE_TIMEOUT);
            if (!getResponseTimeout().isEmpty()) {
                timeout = Integer.parseInt(getResponseTimeout());
            }

            RequestBody body = null;
            /*if (HTTPConstants.POST.equals(method) 
                || HTTPConstants.PUT.equals(method) 
                || HTTPConstants.PATCH.equals(method)) {*/
            
            if (!HttpMethod.fromString(getMethod()).equals(HttpMethod.GET) 
                && !HttpMethod.fromString(getMethod()).equals(HttpMethod.HEAD)) {
                body = RequestBody
                        .from(method, getContentEncoding(), getArguments(), getSendParameterValuesAsPostBody());
            }

            http2Connection
                    .send(method, url, getHeaderManager(), getCookieManager(), body, sampleResult, timeout);

            final CacheManager cacheManager = getCacheManager();
            if (cacheManager != null && HttpMethod.fromString(getMethod()).equals(HttpMethod.GET)) {
                // TODO implement cache Manager
            }
            if (isSyncRequest()) {
                for (HTTP2Connection h : connections.get().values()) {
                    for (HTTP2SampleResult r : h.awaitResponses()) {
                        saveConnectionCookies(r.getHttpFieldsResponse(), r.getURL(), getCookieManager());
                    }
                }
            } else {
                sampleResult.setSuccessful(true);
            }
        } catch (Exception e) {
            // TODO Auto-generated catch block
            errorResult(e, sampleResult);
        }
    }

    private boolean getSendParameterValuesAsPostBody() {
        if (getPostBodyRaw()) {
            return true;
        } else {
            boolean hasArguments = false;
            for (JMeterProperty jMeterProperty : getArguments()) {
                hasArguments = true;
                HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
                if (arg.getName() != null && arg.getName().length() > 0) {
                    return false;
                }
            }
            return hasArguments;
        }
    }

    private boolean getPostBodyRaw() {
        return getPropertyAsBoolean(POST_BODY_RAW, POST_BODY_RAW_DEFAULT);
    }

    private String getContentEncoding() {
        String prop = getPropertyAsString(HTTP2Request.CONTENT_ENCODING);
        return (prop == null || prop.isEmpty()) ? ENCODING : prop;
    }

    private HTTP2Connection getConnection() {
        return http2Connection;
    }

    public void addConnection(String id, HTTP2Connection connection) {
        connections.get().put(id, connection);
    }

    public void setConnection(URL url, HTTP2SampleResult sampleResult) throws Exception {

        String host = url.getHost().replaceAll("\\[", "").replaceAll("]", "");
        int port = url.getPort();

        if (port == -1) {
            port = url.getDefaultPort();
        }

        String connectionId = buildConnectionId(host, port);

        long startConnectTime = sampleResult.currentTimeInMillis();
        Map<String, HTTP2Connection> threadConnections = connections.get();
        http2Connection = threadConnections.get(connectionId);
        if (http2Connection != null) {
            if (http2Connection.isClosed()) {
                LOG.warn("http2Connection == closed");
                http2Connection.connect(host, port);
            }
        } else {
            http2Connection = new HTTP2Connection(connectionId,
                    HTTPConstants.PROTOCOL_HTTPS.equalsIgnoreCase(getProtocol()));
            http2Connection.connect(host, port);
            threadConnections.put(connectionId, http2Connection);
        }
        sampleResult.setConnectTime(sampleResult.currentTimeInMillis() - startConnectTime);
    }

    private String buildConnectionId(String host, int port) {
        return host + ": " + port;
    }

    private String getMethod() {
        return getPropertyAsString(METHOD);
    }

    /**
     * Get the URL, built from its component parts.
     * <p>
     * <p>
     * As a special case, if the path starts with "http[s]://", then the path is assumed to be the
     * entire URL.
     * </p>
     *
     * @return The URL to be requested by this sampler.
     * @throws MalformedURLException if url is malformed
     */
    public URL getUrl() throws MalformedURLException {
        StringBuilder pathAndQuery = new StringBuilder(100);
        String path = this.getContextPath();
        // Hack to allow entire URL to be provided in host field
        if (path.startsWith(HTTP_PREFIX)
                || path.startsWith(HTTPS_PREFIX)) {
            return new URL(path);
        }

        String domain = getDomain();
        String protocol = getProtocol();
        String method = getMethod();

        // HTTP URLs must be absolute, allow file to be relative
        if (!path.startsWith("/")) { // $NON-NLS-1$
            pathAndQuery.append("/"); // $NON-NLS-1$
        }
        pathAndQuery.append(path);

        if (HTTPConstants.GET.equals(method)
               || HTTPConstants.DELETE.equals(method)
               || HTTPConstants.OPTIONS.equals(method)) {
            // Get the query string encoded in specified encoding
            // If no encoding is specified by user, we will get it
            // encoded in UTF-8, which is what the HTTP spec says
            String queryString = null;
            try {
               queryString = RequestBody.from(method, getContentEncoding(), getArguments(), false)
                   .getPayload();
            } catch (UnsupportedEncodingException e) {
                LOG.debug("Content encoding not supported: {}", getContentEncoding(), e);
            }
            if (queryString != null && !queryString.isEmpty()) {
                pathAndQuery.append(path.contains(QRY_PFX) ? QRY_SEP : QRY_PFX);
                pathAndQuery.append(queryString);
            }
        }

        // If default port for protocol is used, we do not include port in URL
        if (isProtocolDefaultPort()) {
            return new URL(protocol, domain, pathAndQuery.toString());
        }
        return new URL(protocol, domain, getPort(), pathAndQuery.toString());
    }

    /**
     * Tell whether the default port for the specified protocol is used
     *
     * @return true if the default port number for the protocol is used, false otherwise
     */
    private boolean isProtocolDefaultPort() {
        final int port = getPortIfSpecified();
        final String protocol = getProtocol();
        boolean isDefaultHTTPPort = HTTPConstants.PROTOCOL_HTTP
                .equalsIgnoreCase(protocol)
                && port == HTTPConstants.DEFAULT_HTTP_PORT;
        boolean isDefaultHTTPSPort = HTTPConstants.PROTOCOL_HTTPS
                .equalsIgnoreCase(protocol)
                && port == HTTPConstants.DEFAULT_HTTPS_PORT;
        return port == UNSPECIFIED_PORT ||
                isDefaultHTTPPort ||
                isDefaultHTTPSPort;
    }

    /**
     * Get the port number from the port string, allowing for trailing blanks.
     *
     * @return port number or UNSPECIFIED_PORT (== 0)
     */
    private int getPortIfSpecified() {
        String port_s = getPropertyAsString(PORT, UNSPECIFIED_PORT_AS_STRING);
        try {
            return Integer.parseInt(port_s.trim());
        } catch (NumberFormatException e) {
            return UNSPECIFIED_PORT;
        }
    }

    /**
     * Get the port; apply the default for the protocol if necessary.
     *
     * @return the port number, with default applied if required.
     */
    public int getPort() {
        final int port = getPortIfSpecified();
        if (port == UNSPECIFIED_PORT) {
            String protocol = getProtocol();
            if (HTTPConstants.PROTOCOL_HTTPS.equalsIgnoreCase(protocol)) {
                return HTTPConstants.DEFAULT_HTTPS_PORT;
            } else if (HTTPConstants.PROTOCOL_HTTP.equalsIgnoreCase(protocol)) {
                return HTTPConstants.DEFAULT_HTTP_PORT;
            } else {
                LOG.error("Unexpected protocol: " + protocol);
            }
        }
        return port;
    }

    public void setProtocol(String value) {
        setProperty(PROTOCOL, value);
    }

    public String getProtocol() {
        String protocol = getPropertyAsString(PROTOCOL);
        if (protocol == null || protocol.length() == 0) {
            return DEFAULT_PROTOCOL;
        }
        return protocol;
    }

    /**
     * Determine if the file should be sent as the entire Content body, i.e. without any additional
     * wrapping.
     *
     * @return true if specified file is to be sent as the body, i.e. there is a single file entry
     * which has a non-empty path and an empty Parameter name.
     */
    public boolean getSendFileAsPostBody() {
        // If there is one file with no parameter name, the file will be sent as post body.
        HTTPFileArg[] files = getHTTPFiles();
        return (files.length == 1)
                && (files[0].getPath().length() > 0)
                && (files[0].getParamName().length() == 0);
    }


    private String getDomain() {
        return getPropertyAsString(DOMAIN);
    }

    private String getResponseTimeout() {
        return getPropertyAsString(RESPONSE_TIMEOUT);
    }

    private String getContextPath() {
        return getPropertyAsString(PATH);
    }

    // gets called from ctor, so has to be final
    public final void setArguments(Arguments value) {
        setProperty(new TestElementProperty(ARGUMENTS, value));
    }

    private Arguments getArguments() {
        return (Arguments) getProperty(ARGUMENTS).getObjectValue();
    }

    private HTTPFileArgs getHTTPFileArgs() {
        return (HTTPFileArgs) getProperty(FILE_ARGS).getObjectValue();
    }

    public HTTPFileArg[] getHTTPFiles() {
        final HTTPFileArgs fileArgs = getHTTPFileArgs();
        return fileArgs == null ? new HTTPFileArg[]{} : fileArgs.asArray();
    }

    public Boolean isEmbeddedResources() {
        return getPropertyAsBoolean(EMBEDDED_RESOURCES);
    }

    private Boolean isSyncRequest() {
        return getPropertyAsBoolean(SYNC_REQUEST);
    }

    public HeaderManager getHeaderManager() {
        return (HeaderManager) getProperty(HTTP2Request.HEADER_MANAGER).getObjectValue();
    }

    /**
     * Get the regular expression URLs must match.
     *
     * @return regular expression (or empty) string
     */
    public String getEmbeddedUrlRE() {
        return getPropertyAsString(EMBEDDED_URL_REGEX, "");
    }

    @Override
    public void addTestElement(TestElement el) {
        if (el instanceof HeaderManager) {
            setHeaderManager((HeaderManager) el);
        } else if (el instanceof CookieManager) {
            setCookieManager((CookieManager) el);
        } else if (el instanceof CacheManager) {
            setCacheManager((CacheManager) el);
        } else {
            super.addTestElement(el);
        }
    }

    private void setHeaderManager(HeaderManager value) {
        HeaderManager mgr = getHeaderManager();
        if (mgr != null) {
            value = mgr.merge(value);
            LOG.debug("Existing HeaderManager '{}' merged with '{}'", mgr.getName(), value.getName());
            value.getHeaders().forEach(h-> LOG.debug("{} = {}", h.getName(), h.getStringValue()));
        }
        setProperty(new TestElementProperty(HEADER_MANAGER, value));
    }

    // private method to allow AsyncSample to reset the value without performing checks
    private void setCookieManagerProperty(CookieManager value) {
        setProperty(new TestElementProperty(COOKIE_MANAGER, value));
    }

    private void setCookieManager(CookieManager value) {
        CookieManager mgr = getCookieManager();
        if (mgr != null) {
            LOG.warn("Existing CookieManager {} superseded by {}", mgr.getName(), value.getName());
        }
        setCookieManagerProperty(value);
    }

    public CookieManager getCookieManager() {
        return (CookieManager) getProperty(COOKIE_MANAGER).getObjectValue();
    }

    private void setCacheManager(CacheManager value) {
        CacheManager mgr = getCacheManager();
        if (mgr != null) {
            LOG.warn("Existing CacheManager {} superseded by {}", mgr.getName(), value.getName());
        }
        setCacheManagerProperty(value);
    }

    // private method to allow AsyncSample to reset the value without performing checks
    private void setCacheManagerProperty(CacheManager value) {
        setProperty(new TestElementProperty(CACHE_MANAGER, value));
    }

    public CacheManager getCacheManager() {
        return (CacheManager) getProperty(CACHE_MANAGER).getObjectValue();
    }

    @Override
    public void threadStarted() {
    }

    @Override
    public void threadFinished() {
        waitAllResponses();
        for (HTTP2Connection connection : connections.get().values()) {
            try {
                connection.disconnect();
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        connections.get().clear();
    }

    private void saveConnectionCookies(HttpFields hdrsResponse, URL url,
                                       CookieManager cookieManager) {
        // hdrsResponse might be null if the request failed before getting any response
        if (cookieManager != null && hdrsResponse != null) {
            List<String> hdrs = hdrsResponse.getValuesList(HTTPConstants.HEADER_SET_COOKIE);
            for (String hdr : hdrs) {
                cookieManager.addCookieFromHeader(hdr, url);
            }
        }
    }

    public void setEmbeddedResources(boolean embeddedResources) {
        setProperty(EMBEDDED_RESOURCES, embeddedResources, false);
    }

    public void setEmbeddedUrlRE(String regex) {
        setProperty(new StringProperty(EMBEDDED_URL_REGEX, regex));
    }

    @Override
    public void iterationStart(LoopIterationEvent iterEvent) {
        waitAllResponses();
    }

    private void waitAllResponses() {
        connections.get().values().forEach(c -> {
            try {
                c.awaitResponses();
            } catch (InterruptedException e) {
                LOG.warn("Interrupted while waiting for HTTP2 async responses", e);
            }
        });
    }

    private String getHttpMethod(String method) {
        switch(method) {
           case HTTPConstants.POST: return "POST" ; 
           case HTTPConstants.HEAD: return "HEAD" ; 
           case HTTPConstants.CONNECT: return "CONNECT" ; 
           case HTTPConstants.DELETE: return "DELETE" ; 
           case HTTPConstants.GET: return "GET" ;
           //case HTTPConstants.MOVE: return "MOVE" ; // WEBDAV
           case HTTPConstants.OPTIONS: return "OPTIONS";
           case HTTPConstants.PUT: return "PUT" ; 
           case HTTPConstants.TRACE: return "TRACE" ;
           case HTTPConstants.PATCH: return "PATCH" ;
           //case HTTPConstants.PROPFIND: return "PROPFIND" ; // WEBDAV
           //case HTTPConstants.PROPPATCH: return "PROPPATCH" ; // WEBDAV
           //case HTTPConstants.MKCOL: return "MKCOL" ; // WEBDAV
           //case HTTPConstants.COPY: return "COPY" ; // WEBDAV
           case HTTPConstants.MOVE: return "MOVE" ; // WEBDAV
           //case HTTPConstants.LOCK: return "LOCK" ; // WEBDAV
           //case HTTPConstants.UNLOCK: return "UNLOCK" ; // WEBDAV
           //case HTTPConstants.REPORT: return "REPORT" ; // WEBDAV
           //case HTTPConstants.MKCALENDAR: return "MKCALENDAR" ; // WEBDAV
           //case HTTPConstants.SEARCH: return "SEARCH" ; // WEBDAV
           case PRI: return PRI ; // HTTP/2
           default: return "GET" ;
        }
    }
    
}

