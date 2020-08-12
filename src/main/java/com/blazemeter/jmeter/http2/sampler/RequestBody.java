package com.blazemeter.jmeter.http2.sampler;

import java.io.UnsupportedEncodingException;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.util.EncoderCache;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jorphan.util.JOrphanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestBody {

    private static final String ARG_VAL_SEP = "=";
    private static final String QRY_SEP = "&";

    private static final Logger LOG = LoggerFactory.getLogger(RequestBody.class);

    private final String payload;
    private final String encoding;

    private RequestBody(String payload, String encoding) {
        this.payload = payload;
        this.encoding = encoding;
    }

    public static RequestBody from(String method, String contentEncoding, Arguments args, boolean sendParamsAsBody)
            throws UnsupportedEncodingException {
        switch(method) {
            case HTTPConstants.GET:
                return new RequestBody(buildGetRequest(contentEncoding, args), contentEncoding);
            default:
                return new RequestBody(buildPostBody(method, contentEncoding, args, sendParamsAsBody), contentEncoding);

        }
    }

    private static String buildPostBody(String method, String contentEncoding, Arguments args, boolean sendParamsAsBody)
            throws UnsupportedEncodingException {
        if ((HTTPConstants.POST.equals(method) 
            || HTTPConstants.PATCH.equals(method)
            || HTTPConstants.PUT.equals(method))
            && !sendParamsAsBody) {

            PropertyIterator iter = args.getArguments().iterator();

            if (!iter.hasNext()) {
                return "";
            }

            if (JOrphanUtils.isBlank(contentEncoding)) {
                contentEncoding = EncoderCache.URL_ARGUMENT_ENCODING;
            }

            StringBuilder buf = new StringBuilder(args.getArguments().size() * 15);
            
            boolean first = true;
            while (iter.hasNext()) {
                HTTPArgument item;
                /* Copied from jmeter http sampler code:
                 * N.B. Revision 323346 introduced the ClassCast check, but then used iter.next()
                 * to fetch the item to be cast, thus skipping the element that did not cast.
                 * Reverted to work more like the original code, but with the check in place.
                 * Added a warning message so can track whether it is necessary
                 */
                Object objectValue = iter.next().getObjectValue();
                if (objectValue instanceof HTTPArgument) {
                    item = (HTTPArgument) objectValue;
                } else {
                    LOG.warn("Unexpected argument type: {}", objectValue.getClass().getName());
                    item = new HTTPArgument((Argument) objectValue);
                }
                final String encodedName = item.getEncodedName();
                if (encodedName.isEmpty()) {
                    continue; // Skip parameters with a blank name (allows use of optional variables in parameter lists)
                }
                if (!first) {
                    buf.append(QRY_SEP);
                } else {
                    first = false;
                }
                buf.append(encodedName);
                if (item.getMetaData() == null) {
                    buf.append(ARG_VAL_SEP);
                } else {
                    buf.append(item.getMetaData());
                }

                try {
                    buf.append(item.getEncodedValue(contentEncoding));
                } catch (UnsupportedEncodingException e) {
                    LOG.warn(
                            "Unable to encode parameter in encoding {}, parameter value not included in query string",
                            contentEncoding);
                }
            }
            return buf.toString();
        } else {
            StringBuilder postBodyBuffer = new StringBuilder();
            for (JMeterProperty jMeterProperty : args) {
                HTTPArgument arg = (HTTPArgument) jMeterProperty.getObjectValue();
                postBodyBuffer.append(arg.getEncodedValue(contentEncoding));
            }
            return postBodyBuffer.toString();
        }
    }

    private static String buildGetRequest(String contentEncoding, Arguments args) throws UnsupportedEncodingException {
        StringBuilder requestBuilder = new StringBuilder();
        PropertyIterator iter = args.getArguments().iterator();

        while(iter.hasNext()) {
            HTTPArgument httpArgument = (HTTPArgument) iter.next().getObjectValue();
            String argName = "",
                   argValue = "",
                   queryString = "";

            if(httpArgument.isAlwaysEncoded()) {
                argName = httpArgument.getEncodedName();
                argValue = httpArgument.getEncodedValue(contentEncoding);
            } else {
                argName = httpArgument.getName();
                argValue = httpArgument.getValue();
            }

            queryString = (iter.hasNext())
                    ? "%s"+ ARG_VAL_SEP +"%s" + QRY_SEP
                    : "%s"+ ARG_VAL_SEP +"%s";
            requestBuilder.append(String.format(queryString, argName, argValue));
        }

        return requestBuilder.toString();
    }

    public String getPayload() {
        return payload;
    }

    public byte[] getPayloadBytes() throws UnsupportedEncodingException {
        return payload.getBytes(encoding);
    }

    public String getEncoding() {
        return encoding;
    }

}