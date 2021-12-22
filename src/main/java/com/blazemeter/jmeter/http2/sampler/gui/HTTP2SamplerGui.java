package com.blazemeter.jmeter.http2.sampler.gui;

import com.blazemeter.jmeter.http2.sampler.HTTP2Sampler;
import com.blazemeter.jmeter.http2.sampler.HTTP2SamplerConverter;
import com.thoughtworks.xstream.XStream;
import java.awt.BorderLayout;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Properties;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.samplers.gui.AbstractSamplerGui;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP2SamplerGui extends AbstractSamplerGui {

  private static final Logger LOG = LoggerFactory.getLogger(HTTP2SamplerGui.class);
  private final HTTP2SamplerPanel http2SamplerPanel;

  static {
    try {
      Field field = SaveService.class.getDeclaredField("JMXSAVER");
      field.setAccessible(true);
      XStream jmxSaver = (XStream) field.get(null);
      jmxSaver.registerConverter(new HTTP2SamplerConverter(jmxSaver.getMapper()),
          XStream.PRIORITY_VERY_HIGH);
    } catch (IllegalAccessException | NoSuchFieldException e) {
      LOG.error("Error while creating HTTP2 jmx converter", e);
    }
  }

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
      http2Sampler.setImageParser(http2SamplerPanel.getRetrieveEmbeddedResources());
      http2Sampler.setConcurrentDwn(http2SamplerPanel.getConcurrentDownload());
      http2Sampler.setConcurrentPool(http2SamplerPanel.getConcurrentPool());
      http2Sampler.setEmbeddedUrlRE(http2SamplerPanel.getEmbeddedResourcesRegex());
      http2Sampler.setConnectTimeout(http2SamplerPanel.getConnectTimeOut());
      http2Sampler.setResponseTimeout(http2SamplerPanel.getResponseTimeOut());
      http2Sampler.setProxyHost(http2SamplerPanel.getProxyHost());
      http2Sampler.setProxyScheme(http2SamplerPanel.getProxyScheme());
      http2Sampler.setProxyPortInt(http2SamplerPanel.getProxyPort());
      http2Sampler.setProxyUser(http2SamplerPanel.getProxyUser());
      http2Sampler.setProxyPass(http2SamplerPanel.getProxyPass());
      http2Sampler.setProperty("version", getPluginVersion());
      http2SamplerPanel.getUrlConfigGui().modifyTestElement(http2Sampler);
      http2Sampler.setHttp1UpgradeEnabled(http2SamplerPanel.isHttp1UpgradeSelected());
    }
  }

  private String getPluginVersion() {
    try {
      final Properties properties = new Properties();
      properties.load(this.getClass().getClassLoader().getResourceAsStream("project.properties"));
      return properties.getProperty("version");
    } catch (IOException e) {
      LOG.warn("Could not write plugin version", e);
      return "";
    }
  }

  @Override
  public void configure(TestElement testElement) {
    super.configure(testElement);
    if (testElement instanceof HTTP2Sampler) {
      HTTP2Sampler http2Sampler = (HTTP2Sampler) testElement;
      http2SamplerPanel.setRetrieveEmbeddedResources(http2Sampler.isImageParser());
      http2SamplerPanel.setConcurrentDownload(http2Sampler.isConcurrentDwn());
      http2SamplerPanel.setConcurrentPool(http2Sampler.getConcurrentPool());
      http2SamplerPanel.setEmbeddedResourcesRegex(http2Sampler.getEmbeddedUrlRE());
      http2SamplerPanel
          .setConnectTimeOut(http2Sampler.getPropertyAsString(HTTPSamplerBase.CONNECT_TIMEOUT));
      http2SamplerPanel
          .setResponseTimeOut(http2Sampler.getPropertyAsString(HTTPSamplerBase.RESPONSE_TIMEOUT));
      http2SamplerPanel
          .setProxyScheme(http2Sampler.getPropertyAsString(HTTPSamplerBase.PROXYSCHEME));
      http2SamplerPanel.setProxyHost(http2Sampler.getPropertyAsString(HTTPSamplerBase.PROXYHOST));
      http2SamplerPanel.setProxyPort(http2Sampler.getPropertyAsString(HTTPSamplerBase.PROXYPORT));
      http2SamplerPanel.setProxyUser(http2Sampler.getPropertyAsString(HTTPSamplerBase.PROXYUSER));
      http2SamplerPanel.setProxyPass(http2Sampler.getPropertyAsString(HTTPSamplerBase.PROXYPASS));
      http2SamplerPanel.getUrlConfigGui().configure(http2Sampler);
      http2SamplerPanel.setHttp1UpgradeSelected(http2Sampler.isHttp1UpgradeEnabled());
    }
  }

  @Override
  public void clearGui() {
    super.clearGui();
    http2SamplerPanel.resetFields();
  }
}
