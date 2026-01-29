package com.blazemeter.jmeter.http2;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;

public class Installer {

  private static final List<String> OLD_DEPENDENCIES_PREFIXES = Arrays.asList(
      "jetty-client", "jetty-util", "jetty-http", "jetty-io", "jetty-alpn-client",
      "http2-client", "http2-common", "http2-hpack", "jetty-osgi-alpn");
  private static final int JAVA_VERSION_REQUIRED = 17;
  private static final int NEW_DEPENDENCY_MAJOR_VERSION = 12;

  /**
   * Checks if JMeter is running in GUI mode.
   * Uses the standard Java method to detect headless mode.
   * This works even before JMeter is fully initialized.
   * 
   * @return true if GUI is available, false if running in headless mode
   */
  private static boolean isGuiMode() {
    // Check both GraphicsEnvironment and system property for headless mode
    // This is the standard approach used in Java applications
    try {
      return !GraphicsEnvironment.isHeadless();
    } catch (Exception e) {
      // Fallback: check system property
      String headless = System.getProperty("java.awt.headless");
      return headless == null || !"true".equalsIgnoreCase(headless);
    }
  }

  public static void main(String[] args) {
    if (getVersion() < JAVA_VERSION_REQUIRED) {
      // Only show dialog if JMeter is running in GUI mode (not headless)
      if (isGuiMode()) {
        JOptionPane.showMessageDialog(null,
            "The HTTP2 Plugin requires Java 17 or higher, please upgrade your Java version and "
                + "restart JMeter before using the plugin.",
            "Java 17 is required", JOptionPane.WARNING_MESSAGE);
      }
      return;
    }
    File pluginsFolder = new File(
        Installer.class.getProtectionDomain().getCodeSource().getLocation()
            .getFile()).getParentFile();
    File dependencyFolder = pluginsFolder.getParentFile();
    if (!(pluginsFolder.canRead() && pluginsFolder.canWrite())
        || !(dependencyFolder.canWrite() && dependencyFolder.canRead())) {
      // Only show dialog if JMeter is running in GUI mode (not headless)
      if (isGuiMode()) {
        JOptionPane.showMessageDialog(null, "Read or Write permissions denied",
            "Permission Access", JOptionPane.WARNING_MESSAGE);
      }
      return;
    }

    Arrays.stream(Objects.requireNonNull(pluginsFolder.listFiles()))
        .filter(f -> f.getName().matches("jmeter-bzm-http2-1[.\\d]+\\.jar"))
        .forEach(File::delete);
    Arrays.stream(Objects.requireNonNull(dependencyFolder.listFiles()))
        .filter(d -> OLD_DEPENDENCIES_PREFIXES
            .stream()
            .anyMatch(oldDeps -> {
              Pattern pattern = Pattern.compile(oldDeps + "-(\\d+)[.\\d]+(v\\d+)?.jar");
              Matcher matcher = pattern.matcher(d.getName());
              if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1))
                    < NEW_DEPENDENCY_MAJOR_VERSION;
              }
              return false;
            }))
        .forEach(File::delete);
  }

  private static int getVersion() {
    String versionString = System.getProperty("java.version");
    String[] versionElements = versionString.split("\\.");
    return versionElements[0].equals("1") ? Integer.parseInt(versionElements[1])
        : Integer.parseInt(versionElements[0]);
  }
}
