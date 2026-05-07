package com.blazemeter.jmeter.http2.gui;

import com.blazemeter.jmeter.http2.proxy.HTTP2SampleCreator;
import com.blazemeter.jmeter.http2.util.BzmHttpPluginProperties;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.MenuElement;
import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.AbstractActionWithNoRunningTest;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.Restart;
import org.apache.jmeter.gui.plugin.MenuCreator;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tools → BlazeMeter HTTP → toggle {@link HTTP2SampleCreator#PROXY_ENABLED}. Uses
 * {@link BzmHttpPluginProperties}. */
public class BlazeMeterHttpProxyRecordingMenuCommand
    extends AbstractActionWithNoRunningTest implements MenuCreator {

  private static final Logger LOG =
      LoggerFactory.getLogger(BlazeMeterHttpProxyRecordingMenuCommand.class);

  /**
   * Action command registered with {@link ActionRouter}; internal name, not shown in the UI.
   */
  private static final String ACTION_NAME = "bzm_http2_proxy_recording_toggle";

  private static final String ROOT_MENU_LABEL = "BlazeMeter HTTP";
  private static final String LABEL_ENABLE_PROXY_RECORDING =
      "Enable Recording in JMeter Proxy Recorder";
  private static final String LABEL_DISABLE_PROXY_RECORDING =
      "Disable Recording in JMeter Proxy Recorder";

  private static final Set<String> ACTION_NAMES = Set.of(ACTION_NAME);

  @Override
  public Set<String> getActionNames() {
    return ACTION_NAMES;
  }

  @Override
  protected void doActionAfterCheck(ActionEvent e) {
    boolean current =
        BzmHttpPluginProperties.getPropDefault(HTTP2SampleCreator.PROXY_ENABLED, true);
    boolean next = !current;

    String binDir = JMeterUtils.getJMeterBinDir();
    if (StringUtils.isBlank(binDir)) {
      JMeterUtils.reportErrorToUser(
          "Cannot resolve JMeter bin directory; user.properties was not updated.",
          JMeterUtils.getResString("error_title"));
      return;
    }
    File userProperties = new File(binDir, "user.properties");

    try {
      BzmHttpPluginProperties.persistUserProperty(
          userProperties, HTTP2SampleCreator.PROXY_ENABLED, Boolean.toString(next));
      BzmHttpPluginProperties.syncRuntime(
          HTTP2SampleCreator.PROXY_ENABLED, Boolean.toString(next));
    } catch (IOException ex) {
      LOG.error("Could not write {}", userProperties.getAbsolutePath(), ex);
      JMeterUtils.reportErrorToUser("Could not update user.properties: " + ex.getMessage(),
          JMeterUtils.getResString("error_title"), ex);
      return;
    }

    int restart = JOptionPane.showConfirmDialog(
        GuiPackage.getInstance().getMainFrame(),
        "Restart JMeter now to apply the change?",
        "Restart JMeter",
        JOptionPane.YES_NO_OPTION,
        JOptionPane.QUESTION_MESSAGE);
    if (restart == JOptionPane.YES_OPTION) {
      Restart.restartApplication(null);
    }
  }

  @Override
  public JMenuItem[] getMenuItemsAtLocation(MENU_LOCATION location) {
    if (location != MENU_LOCATION.TOOLS) {
      return new JMenuItem[0];
    }
    boolean proxyEnabled =
        BzmHttpPluginProperties.getPropDefault(HTTP2SampleCreator.PROXY_ENABLED, true);

    JMenu root = new JMenu(ROOT_MENU_LABEL);
    JMenuItem toggle = new JMenuItem(
        proxyEnabled ? LABEL_DISABLE_PROXY_RECORDING : LABEL_ENABLE_PROXY_RECORDING);
    toggle.setName(ACTION_NAME);
    toggle.setActionCommand(ACTION_NAME);
    toggle.addActionListener(ActionRouter.getInstance());

    root.add(toggle);
    return new JMenuItem[] {root};
  }

  @Override
  public JMenu[] getTopLevelMenus() {
    return new JMenu[0];
  }

  @Override
  public boolean localeChanged(MenuElement menu) {
    return false;
  }

  @Override
  public void localeChanged() {
    // Labels are fixed English strings; no bundle wiring.
  }
}
