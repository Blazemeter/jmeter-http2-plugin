
package com.blazemeter.jmeter.http2.sampler;

import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.Stream.Listener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.Callback;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

public class HTTP2StreamHandler extends HTTPSamplerBase implements Stream.Listener {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(HTTP2StreamHandler.class);
    private static final boolean IGNORE_FAILED_EMBEDDED_RESOURCES = JMeterUtils.
            getPropDefault("httpsampler.ignore_failed_embedded_resources", false);


    private final CompletableFuture<Void> completedFuture = new CompletableFuture<>();
    private HTTP2SampleResult result;
    private HTTP2Connection parent;
    private byte[] responseBytes;
    private HeaderManager headerManager;
    private CookieManager cookieManager;
    private boolean first = true;
    private int timeout = 0;

    private static final Method setParentSampleSuccessMethod;

    static {
        Method setParentSampleSuccess = null;
        try {
            setParentSampleSuccess = HTTPSamplerBase.class.getDeclaredMethod("setParentSampleSuccess", HTTPSampleResult.class, boolean.class);
            setParentSampleSuccess.setAccessible(true);
        } catch (ReflectiveOperationException ex) {
            LOG.error("Can not find setParentSampleSuccess in HTTPSamplerBase class", ex);
        }
        setParentSampleSuccessMethod = setParentSampleSuccess;
    }


    public HTTP2StreamHandler(HTTP2Connection parent, HeaderManager headerManager,
                              CookieManager cookieManager, HTTP2SampleResult sampleResult) {
        this.result = sampleResult;
        this.parent = parent;
        this.cookieManager = cookieManager;
        this.headerManager = headerManager;
    }

    public CompletableFuture<Void> getCompletedFuture() {
        return completedFuture;
    }

    @Override
    public Listener onPush(Stream stream, PushPromiseFrame frame) {
        MetaData.Request requestMetadata = ((MetaData.Request) frame.getMetaData());

        URL url = null;
        try {
            url = requestMetadata.getURI().toURI().toURL();
        } catch (MalformedURLException | URISyntaxException e) {
            LOG.error("Failed when parsed Push URL", e);
        }

        HTTP2SampleResult sampleSubResult = result.createSubResult();
        sampleSubResult.setSampleLabel(url.toString());
        sampleSubResult.setURL(url);
        sampleSubResult.setHTTPMethod(requestMetadata.getMethod());

        for (HttpField h : requestMetadata.getFields()) {
            switch (h.getName()) {
                case HTTPConstants.HEADER_CONTENT_TYPE:
                case "content-type":
                    sampleSubResult.setContentType(h.getValue());
                    sampleSubResult.setEncodingAndType(h.getValue());
                    break;
                case HTTPConstants.HEADER_CONTENT_ENCODING:
                    sampleSubResult.setDataEncoding(h.getValue());
                    break;
            }
        }

        String rawHeaders = requestMetadata.getFields().toString();
        // we do this replacement and remove final char to be consistent with jmeter HTTP request sampler
        String headers = rawHeaders.replaceAll("\r\n", "\n");
        sampleSubResult.setRequestHeaders(headers);
        sampleSubResult.sampleStart();
        sampleSubResult.setSync(result.isSync());
        result.addSubResult(sampleSubResult);
        HTTP2StreamHandler hTTP2StreamHandler = new HTTP2StreamHandler(this.parent, headerManager,
                cookieManager, sampleSubResult);

        this.parent.addStreamHandler(hTTP2StreamHandler);
        hTTP2StreamHandler.setTimeout(timeout);
        return hTTP2StreamHandler;
    }

    @Override
    public void onHeaders(Stream stream, HeadersFrame frame) {

        MetaData.Response responseMetadata = ((MetaData.Response) frame.getMetaData());
        result.setResponseCode(Integer.toString(responseMetadata.getStatus()));
        result.setResponseMessage(responseMetadata.getReason());
        for (HttpField h : frame.getMetaData().getFields()) {
            switch (h.getName()) {
                case HTTPConstants.HEADER_CONTENT_TYPE:
                case "content-type":
                    result.setContentType(h.getValue());
                    result.setEncodingAndType(h.getValue());
                    break;
                case HTTPConstants.HEADER_CONTENT_ENCODING:
                    result.setDataEncoding(h.getValue());
                    break;
            }
        }

        String messageLine = responseMetadata.getHttpVersion() + " "
                + responseMetadata.getStatus() + " " + HttpStatus.getMessage(responseMetadata.getStatus());

        result.setResponseMessage(messageLine);
        String rawHeaders = frame.getMetaData().getFields().toString();
        // we do this replacement and remove final char to be consistent with jmeter HTTP request sampler
        String headers = rawHeaders.replaceAll("\r\n", "\n");
        String responseHeaders = messageLine + "\n" + headers.substring(0, headers.length() - 1);
        result.setResponseHeaders(responseHeaders);
        result.setHeadersSize(rawHeaders.length());
        result.setHttpFieldsResponse(frame.getMetaData().getFields());
    }

    @Override
    public void onData(Stream stream, DataFrame frame, Callback callback) {
        callback.succeeded();
        byte[] bytes = new byte[frame.getData().remaining()];
        frame.getData().get(bytes);

        try {
            if (first) {
                result.latencyEnd();
                first = false;
            }
            setResponseBytes(bytes);

            if (frame.isEndStream()) {
                result.setSuccessful(isSuccessCode(Integer.parseInt(result.getResponseCode())));
                result.setResponseData(this.responseBytes);
                if (result.isRedirect()) {
                    // TODO redirect
                }

                if ((result.isEmbebedResults()) && (result.getEmbebedResultsDepth() > 0)
                        && (result.getDataType().equals(SampleResult.TEXT))) {
                    downloadPageResources(result, null, 0);
                }

                if (result.isSecondaryRequest()) {
                    HTTP2SampleResult parent = (HTTP2SampleResult) result.getParent();
                    // set primary request failed if at least one secondary
                    // request fail
                    invokeSetParentSampleSuccess(parent,
                            parent.isSuccessful() && (result == null || result.isSuccessful()));
                }
                completeStream();
            }
        } catch (Exception e) {
            e.printStackTrace(); // TODO
        }

    }

    private void invokeSetParentSampleSuccess(HTTPSampleResult res, boolean initialValue) {
        if (setParentSampleSuccessMethod != null) {
            try {
                setParentSampleSuccessMethod.invoke(this, res, initialValue);
            } catch (ReflectiveOperationException ex) {
                LOG.warn("Failed to invoke setParentSampleSuccess method in class HTTPSamplerBase", ex);
            }
        }
    }

    @Override
    public void onReset(Stream stream, ResetFrame frame) {
        result.setResponseCode(String.valueOf(frame.getError()));
        result.setResponseMessage(ErrorCode.from(frame.getError()).name());
        result.setSuccessful(((frame.getError() == ErrorCode.NO_ERROR.code))
                || (frame.getError() == ErrorCode.CANCEL_STREAM_ERROR.code));
        completeStream();
    }


    private void setResponseBytes(byte[] bytes) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            if (this.responseBytes != null) {
                outputStream.write(this.responseBytes);
            }
            outputStream.write(bytes);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        this.responseBytes = outputStream.toByteArray();
    }

    @Override
    protected HTTPSampleResult sample(java.net.URL url, String s, boolean b, int i) {
        // NOOP
        return null;
    }

    /**
     * Determine if the HTTP status code is successful or not i.e. in range 200 to 399 inclusive
     *
     * @param code status code to check
     * @return whether in range 200-399 or not
     */
    protected boolean isSuccessCode(int code) {
        return code >= 200 && code <= 399;
    }

    protected HTTP2SampleResult getHTTP2SampleResult() {
        return this.result;
    }

    protected int getTimeout() {
        return timeout;
    }

    protected void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    private void completeStream() {
        result.sampleEnd();
        result.setPendingResponse(false);
        if (!result.isSync()) {
            result.completeAsyncSample();
        }
        completedFuture.complete(null);
    }

}
