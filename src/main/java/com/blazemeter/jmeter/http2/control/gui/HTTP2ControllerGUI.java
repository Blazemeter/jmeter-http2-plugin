package com.blazemeter.jmeter.http2.control.gui;

import com.blazemeter.jmeter.http2.control.HTTP2Controller;
import java.awt.BorderLayout;
import org.apache.jmeter.control.gui.AbstractControllerGui;
import org.apache.jmeter.testelement.TestElement;

public class HTTP2ControllerGUI extends AbstractControllerGui {
  private static final long serialVersionUID = 240L;

  public HTTP2ControllerGUI() {
    init();
  }

  @Override
  public String getStaticLabel() {
    return "bzm - HTTP2 Async Controller";
  }

  @Override
  public TestElement createTestElement() {
    HTTP2Controller lc = new HTTP2Controller();
    configureTestElement(lc);
    return lc;
  }

  @Override
  public void modifyTestElement(TestElement el) {
    configureTestElement(el);
  }

  @Override
  public String getLabelResource() {
    return null;
  }

  private void init() {
    // WARNING: called from ctor so must not be overridden (i.e. must be private or final)
    setLayout(new BorderLayout());
    setBorder(makeBorder());
    add(makeTitlePanel(), BorderLayout.NORTH);
  }
}
