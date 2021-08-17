package com.blazemeter.jmeter.http2.visualizers;

import java.util.Collection;

@Deprecated
public class ViewResultsFullVisualizer extends
    org.apache.jmeter.visualizers.ViewResultsFullVisualizer {

  public static final String VIEW_RESULT_TREE_HTTP2_TITLE = "DEPRECATED View Result Tree Http2";

  public ViewResultsFullVisualizer() {
    super();
    setName(VIEW_RESULT_TREE_HTTP2_TITLE);
  }

  public Collection<String> getMenuCategories() {
    return null;
  }

  @Override
  public String getLabelResource() {
    return VIEW_RESULT_TREE_HTTP2_TITLE;
  }

  @Override
  public String getStaticLabel() {
    return VIEW_RESULT_TREE_HTTP2_TITLE;
  }
}
