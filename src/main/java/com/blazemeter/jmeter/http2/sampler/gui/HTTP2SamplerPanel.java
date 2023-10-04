package com.blazemeter.jmeter.http2.sampler.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.util.Arrays;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.border.Border;
import org.apache.jmeter.gui.util.HorizontalPanel;
import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.protocol.http.config.gui.UrlConfigGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.JLabeledTextField;

public class HTTP2SamplerPanel extends JPanel {

  private static final String JBOOLEAN_PROPERTY_EDITOR_CLASS_NAME = "org.apache.jmeter.gui"
      + ".JBooleanPropertyEditor";
  private UrlConfigGui urlConfigGui;
  private final JTextField connectTimeOutField = new JTextField(10);
  private final JTextField responseTimeOutField = new JTextField(10);
  private final JTextField proxySchemeField = new JTextField(5);
  private final JTextField proxyHostField = new JTextField(10);
  private final JTextField proxyPortField = new JTextField(10);
  private final JTextField proxyUserField = new JTextField(5);
  private final JPasswordField proxyPassField = new JPasswordField(5);
  private final JCheckBox retrieveEmbeddedResourcesCheckBox = new JCheckBox(
      JMeterUtils.getResString("web_testing_retrieve_images"));
  private final JCheckBox concurrentDownloadCheckBox = new JCheckBox(
      JMeterUtils.getResString("web_testing_concurrent_download"));
  private final JTextField concurrentPoolField = new JTextField(2);
  private final JLabeledTextField embeddedResourcesRegexField = new JLabeledTextField(
      JMeterUtils.getResString("web_testing_embedded_url_pattern"), 20);
  private final JCheckBox http1Upgrade = new JCheckBox("HTTP1 Upgrade");

  public HTTP2SamplerPanel(boolean isSampler) {
    setLayout(new BorderLayout(0, 5));
    setBorder(BorderFactory.createEmptyBorder());
    add(createTabbedConfigPane(isSampler));
  }

  private JTabbedPane createTabbedConfigPane(boolean isSampler) {
    final JTabbedPane tabbedPane = new JTabbedPane();
    urlConfigGui = new UrlConfigGui(isSampler, true, true);
    replaceKeepAliveCheckWithHttp1Upgrade(urlConfigGui);
    tabbedPane.add(JMeterUtils.getResString("web_testing_basic"), urlConfigGui);
    final JPanel advancedPanel = createAdvancedConfigPanel();
    tabbedPane.add(JMeterUtils.getResString("web_testing_advanced"), advancedPanel);
    return tabbedPane;
  }

  private void replaceKeepAliveCheckWithHttp1Upgrade(UrlConfigGui urlConfigGui) {
    JPanel optionPanel = findOptionPanel(urlConfigGui);
    optionPanel.remove(2);

    http1Upgrade.setFont(null);
    http1Upgrade.setSelected(false);

    optionPanel.add(http1Upgrade);
  }

  private JPanel findOptionPanel(Container c) {
    if (isContainingKeepAliveCheck(c)) {
      return (JPanel) c;
    }
    JPanel ret = null;
    int i = 0;
    Component[] children = c.getComponents();
    while (ret == null && i < c.getComponentCount()) {
      Component child = children[i];
      if (child instanceof Container) {
        ret = findOptionPanel((Container) child);
      }
      i++;
    }
    return ret;
  }

  private boolean isContainingKeepAliveCheck(Container c) {
    if (!(c instanceof JPanel) || c.getComponentCount() != 5) {
      return false;
    }
    Predicate<Predicate<? super Component>> existsInPanel =
        (p) -> Arrays.stream(c.getComponents()).allMatch(p);
    boolean isJMeterV56 = JMeterUtils.getJMeterVersion().startsWith("5.6");

    if (isJMeterV56 && existsInPanel.test((component -> component.getClass().getName().equals(
        JBOOLEAN_PROPERTY_EDITOR_CLASS_NAME) || component instanceof JCheckBox))) {
      /*
        Since JMeter v5.6.2 the UrlConfigGUI changed to:
          autoRedirects: JCheckBox
          followRedirects: JCheckBox
          useKeepAlive: JBooleanPropertyEditor  <--
          useMultipart: JBooleanPropertyEditor
          useCompatibleMultiPartMode: JBooleanPropertyEditor
       */
      return true;
    }

    return !isJMeterV56 && existsInPanel.test((checkBox) -> checkBox instanceof JCheckBox);
  }

  private Border makeBorder() {
    return BorderFactory.createEmptyBorder(10, 10, 5, 10);
  }

  private JPanel createAdvancedConfigPanel() {
    JPanel advancedPanel = new VerticalPanel();
    advancedPanel.setBorder(makeBorder());
    advancedPanel.add(createTimeOutPanel());
    advancedPanel.add(createProxyPanel());
    advancedPanel.add(createEmbeddedResourcesPanel());
    return advancedPanel;
  }

  private JPanel createTimeOutPanel() {
    JPanel timeOutPanel = new HorizontalPanel();
    timeOutPanel.setBorder(BorderFactory.createTitledBorder(
        JMeterUtils.getResString("web_server_timeout_title")));
    timeOutPanel.add(createPanelWithLabelForField(connectTimeOutField,
        JMeterUtils.getResString("web_server_timeout_connect")));
    timeOutPanel.add(createPanelWithLabelForField(responseTimeOutField,
        JMeterUtils.getResString("web_server_timeout_response")));
    return timeOutPanel;
  }

  private JPanel createPanelWithLabelForField(JTextField field, String labelString) {
    JLabel label = new JLabel(labelString);
    label.setLabelFor(field);
    JPanel panel = new JPanel(new BorderLayout(5, 0));
    panel.add(label, BorderLayout.WEST);
    panel.add(field, BorderLayout.CENTER);
    return panel;
  }

  private JPanel createProxyPanel() {
    JPanel proxyPanel = new HorizontalPanel();
    proxyPanel.setBorder(BorderFactory
        .createTitledBorder(JMeterUtils.getResString("web_proxy_server_title")));
    proxyPanel.add(createProxyServerPanel());
    return proxyPanel;
  }

  private JPanel createProxyServerPanel() {
    JPanel proxyServerPanel = new HorizontalPanel();
    proxyServerPanel.add(createPanelWithLabelForField(proxySchemeField, JMeterUtils.getResString(
        "web_proxy_scheme")), BorderLayout.WEST);
    proxyServerPanel.add(createPanelWithLabelForField(proxyHostField, JMeterUtils.getResString(
        "web_server_domain")), BorderLayout.CENTER);
    proxyServerPanel.add(createPanelWithLabelForField(proxyPortField, JMeterUtils.getResString(
        "web_server_port")), BorderLayout.EAST);
    return proxyServerPanel;
  }

  private JPanel createEmbeddedResourcesPanel() {
    final JPanel embeddedResourcesPanel = new HorizontalPanel();
    embeddedResourcesPanel.setBorder(BorderFactory
        .createTitledBorder(BorderFactory.createEtchedBorder(),
            JMeterUtils.getResString("web_testing_retrieve_title")));
    retrieveEmbeddedResourcesCheckBox.addItemListener(e -> updateEnableStatus());
    concurrentDownloadCheckBox.addItemListener(e -> updateEnableStatus());
    concurrentPoolField.setMinimumSize(
        new Dimension(10, (int) concurrentPoolField.getPreferredSize().getHeight()));
    concurrentPoolField.setMaximumSize(
        new Dimension(30, (int) concurrentPoolField.getPreferredSize().getHeight()));
    embeddedResourcesPanel.add(retrieveEmbeddedResourcesCheckBox);
    embeddedResourcesPanel.add(concurrentDownloadCheckBox);
    embeddedResourcesPanel.add(concurrentPoolField);
    embeddedResourcesPanel.add(embeddedResourcesRegexField);
    return embeddedResourcesPanel;
  }

  private void updateEnableStatus() {
    concurrentDownloadCheckBox.setEnabled(retrieveEmbeddedResourcesCheckBox.isSelected());
    embeddedResourcesRegexField.setEnabled(retrieveEmbeddedResourcesCheckBox.isSelected());
    concurrentPoolField
        .setEnabled(retrieveEmbeddedResourcesCheckBox.isSelected() && concurrentDownloadCheckBox
            .isSelected());
  }

  public void resetFields() {
    urlConfigGui.clear();
    http1Upgrade.setSelected(false);
    retrieveEmbeddedResourcesCheckBox.setSelected(false);
    concurrentDownloadCheckBox.setSelected(false);
    concurrentPoolField.setText(String.valueOf(HTTPSamplerBase.CONCURRENT_POOL_SIZE));
    updateEnableStatus();
    connectTimeOutField.setText("");
    responseTimeOutField.setText("");
    proxySchemeField.setText("");
    proxyHostField.setText("");
    proxyPortField.setText("");
    proxyUserField.setText("");
    proxyPassField.setText("");
  }

  public UrlConfigGui getUrlConfigGui() {
    return urlConfigGui;
  }

  public boolean isHttp1UpgradeSelected() {
    return http1Upgrade.isSelected();
  }

  public void setHttp1UpgradeSelected(boolean enabled) {
    http1Upgrade.setSelected(enabled);
  }

  public String getConnectTimeOut() {
    return connectTimeOutField.getText();
  }

  public String getResponseTimeOut() {
    return responseTimeOutField.getText();
  }

  public String getProxyScheme() {
    return proxySchemeField.getText();
  }

  public String getProxyHost() {
    return proxyHostField.getText();
  }

  public String getProxyPort() {
    return proxyPortField.getText();
  }

  public String getProxyUser() {
    return proxyUserField.getText();
  }

  public String getProxyPass() {
    return new String(proxyPassField.getPassword());
  }

  public boolean getRetrieveEmbeddedResources() {
    return retrieveEmbeddedResourcesCheckBox.isSelected();
  }

  public boolean getConcurrentDownload() {
    return concurrentDownloadCheckBox.isSelected();
  }

  public String getConcurrentPool() {
    return concurrentPoolField.getText();
  }

  public String getEmbeddedResourcesRegex() {
    return embeddedResourcesRegexField.getText();
  }

  public void setConnectTimeOut(String connectTimeOut) {
    this.connectTimeOutField.setText(connectTimeOut);
  }

  public void setResponseTimeOut(String responseTimeOut) {
    this.responseTimeOutField.setText(responseTimeOut);
  }

  public void setProxyScheme(String proxyScheme) {
    this.proxySchemeField.setText(proxyScheme);
  }

  public void setProxyHost(String proxyHost) {
    this.proxyHostField.setText(proxyHost);
  }

  public void setProxyPort(String proxyPort) {
    this.proxyPortField.setText(proxyPort);
  }

  public void setProxyUser(String proxyUser) {
    this.proxyUserField.setText(proxyUser);
  }

  public void setProxyPass(String proxyPass) {
    this.proxyPassField.setText(proxyPass);
  }

  public void setRetrieveEmbeddedResources(boolean retrieveEmbeddedResources) {
    this.retrieveEmbeddedResourcesCheckBox.setSelected(retrieveEmbeddedResources);
  }

  public void setConcurrentDownload(boolean concurrentDownload) {
    this.concurrentDownloadCheckBox.setSelected(concurrentDownload);
  }

  public void setConcurrentPool(String concurrentPool) {
    this.concurrentPoolField.setText(concurrentPool);
  }

  public void setEmbeddedResourcesRegex(String embeddedResourcesRegex) {
    this.embeddedResourcesRegexField.setText(embeddedResourcesRegex);
  }
}
