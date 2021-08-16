package com.blazemeter.jmeter.http2.visualizers;

import java.util.Collection;

@Deprecated
public class SimpleDataWriter extends org.apache.jmeter.visualizers.SimpleDataWriter {

  private static final String SIMPLE_DATA_WRITER_HTTP2_TITLE = "DEPRECATED Simple Data Writer "
      + "Http2";

  public SimpleDataWriter() {
    super();
    setName(SIMPLE_DATA_WRITER_HTTP2_TITLE);
  }

  public Collection<String> getMenuCategories() {
    return null;
  }

  @Override
  public String getLabelResource() {
    return SIMPLE_DATA_WRITER_HTTP2_TITLE;
  }

  @Override
  public String getStaticLabel() {
    return SIMPLE_DATA_WRITER_HTTP2_TITLE;
  }
}
