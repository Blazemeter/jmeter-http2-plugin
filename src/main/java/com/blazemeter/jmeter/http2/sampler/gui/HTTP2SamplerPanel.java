package com.blazemeter.jmeter.http2.sampler.gui;

import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.border.Border;
import org.apache.jmeter.gui.util.HorizontalPanel;
import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.protocol.http.config.gui.UrlConfigGui;
import org.apache.jmeter.util.JMeterUtils;

public class HTTP2SamplerPanel extends JPanel {

  private UrlConfigGui urlConfigGui;
  private final JTextField connectTimeOutField = new JTextField(10);
  private final JTextField responseTimeOutField = new JTextField(10);
  private final JTextField proxySchemeField = new JTextField(5);
  private final JTextField proxyHostField = new JTextField(10);
  private final JTextField proxyPortField = new JTextField(10);
  private final JTextField proxyUserField = new JTextField(5);
  private final JPasswordField proxyPassField = new JPasswordField(5);

  public HTTP2SamplerPanel(boolean isSampler) {
    setLayout(new BorderLayout(0, 5));
    setBorder(BorderFactory.createEmptyBorder());
    add(createTabbedConfigPane(isSampler));
  }

  private JTabbedPane createTabbedConfigPane(boolean isSampler) {
    final JTabbedPane tabbedPane = new JTabbedPane();
    urlConfigGui = new UrlConfigGui(isSampler, false, false);
    tabbedPane.add(JMeterUtils.getResString("web_testing_basic"), urlConfigGui);
    final JPanel advancedPanel = createAdvancedConfigPanel();
    tabbedPane.add(JMeterUtils.getResString("web_testing_advanced"), advancedPanel);

    return tabbedPane;
  }

  private UrlConfigGui createUrlConfigGui() {
    final UrlConfigGui configGui = new UrlConfigGui(true, true, true);
    configGui.setBorder(makeBorder());
    return configGui;
  }

  private Border makeBorder() {
    return BorderFactory.createEmptyBorder(10, 10, 5, 10);
  }

  private JPanel createAdvancedConfigPanel() {
    JPanel advancedPanel = new VerticalPanel();
    advancedPanel.setBorder(makeBorder());
    advancedPanel.add(createTimeOutPanel());
    advancedPanel.add(createProxyPanel());
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
    proxyPanel.add(createProxyLoginPanel());
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

  private JPanel createProxyLoginPanel() {
    JPanel proxyLoginPanel = new HorizontalPanel();
    proxyLoginPanel
        .add(createPanelWithLabelForField(proxyUserField, JMeterUtils.getResString("username")));
    proxyLoginPanel
        .add(createPanelWithLabelForField(proxyPassField, JMeterUtils.getResString("password")));
    return proxyLoginPanel;
  }

  public void resetFields() {
    urlConfigGui.clear();
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
}
