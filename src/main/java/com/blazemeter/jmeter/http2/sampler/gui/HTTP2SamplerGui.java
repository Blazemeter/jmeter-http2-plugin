package com.blazemeter.jmeter.http2.sampler.gui;

import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import java.awt.BorderLayout;
import java.io.IOException;
import java.util.Properties;
import org.apache.jmeter.samplers.gui.AbstractSamplerGui;
import org.apache.jmeter.testelement.TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2SamplerGui extends AbstractSamplerGui {

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2SamplerGui.class);
  private final HTTP2SamplerPanel http2SamplerPanel;

  public HTTP2SamplerGui() {
    http2SamplerPanel = new HTTP2SamplerPanel(true);
    http2SamplerPanel.resetFields();

    setLayout(new BorderLayout(0, 5));
    setBorder(makeBorder());

    add(makeTitlePanel(), BorderLayout.NORTH);
    add(http2SamplerPanel, BorderLayout.CENTER);
    add(new BlazemeterLabsLogo(), BorderLayout.PAGE_END);
  }

  @Override
  public String getStaticLabel() {
    return "bzm - HTTP2 Sampler";
  }

  @Override
  public String getLabelResource() {
    throw new IllegalStateException("This shouldn't be called");
  }

  @Override
  public TestElement createTestElement() {
    HTTP2Sampler http2Sampler = new HTTP2Sampler();
    configureTestElement(http2Sampler);
    return http2Sampler;
  }

  @Override
  public void modifyTestElement(TestElement testElement) {
    testElement.clear();
    configureTestElement(testElement);
    if (testElement instanceof HTTP2Sampler) {
      HTTP2Sampler http2Sampler = (HTTP2Sampler) testElement;
      http2Sampler.setConnectTimeout(http2SamplerPanel.getConnectTimeOut());
      http2Sampler.setResponseTimeout(http2SamplerPanel.getResponseTimeOut());
      http2Sampler.setProxyHost(http2SamplerPanel.getProxyHost());
      http2Sampler.setProxyScheme(http2SamplerPanel.getProxyScheme());
      http2Sampler.setProxyPortInt(http2SamplerPanel.getProxyPort());
      http2Sampler.setProxyUser(http2SamplerPanel.getProxyUser());
      http2Sampler.setProxyPass(http2SamplerPanel.getProxyPass());
      try {
        final Properties properties = new Properties();
        properties.load(this.getClass().getClassLoader().getResourceAsStream("project.properties"));
        http2Sampler.setProperty("version", properties.getProperty("version"));
      } catch (IOException e) {
        LOG.warn("Could not write plugin version");
      }
      http2SamplerPanel.getUrlConfigGui().modifyTestElement(http2Sampler);
    }
  }

  @Override
  public void configure(TestElement testElement) {
    super.configure(testElement);
    if (testElement instanceof HTTP2Sampler) {
      HTTP2Sampler http2Sampler = (HTTP2Sampler) testElement;
      http2SamplerPanel.setConnectTimeOut(String.valueOf(http2Sampler.getConnectTimeout()));
      http2SamplerPanel.setResponseTimeOut(String.valueOf(http2Sampler.getResponseTimeout()));
      http2SamplerPanel.setProxyScheme(http2Sampler.getProxyScheme());
      http2SamplerPanel.setProxyHost(http2Sampler.getProxyHost());
      http2SamplerPanel.setProxyPort(String.valueOf(http2Sampler.getProxyPortInt()));
      http2SamplerPanel.setProxyUser(http2Sampler.getProxyUser());
      http2SamplerPanel.setProxyPass(http2Sampler.getProxyPass());
      http2SamplerPanel.getUrlConfigGui().configure(http2Sampler);
    }
  }

  @Override
  public void clearGui() {
    super.clearGui();
    http2SamplerPanel.resetFields();
  }
}
