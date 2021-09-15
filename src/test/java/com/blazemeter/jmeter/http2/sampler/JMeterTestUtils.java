package com.blazemeter.jmeter.http2.sampler;

import java.io.File;
import kg.apc.emulators.TestJMeterUtils;
import org.apache.jmeter.util.JMeterUtils;

public class JMeterTestUtils {

  private static boolean jeerEnvironmentInitialized = false;
  private static String filePrefix;

  public JMeterTestUtils() {
  }
  
  public static void setupJmeterEnv() {
    if(!jeerEnvironmentInitialized) {
      jeerEnvironmentInitialized = true;
      TestJMeterUtils.createJmeterEnv();
    }
  }

  /**
   * Set jmeter home and return file prefix
   * @return file prefix which is path from jmeter home to jmeter.properties
   */
  public static String setupJMeterHome() {
    if (filePrefix == null) {
      String prefix = "/opt/apache-jmeter-5.2.1";
      for (int i = 0; i < 5 && !new File(prefix, "bin/jmeter.properties").canRead(); i++) {
        prefix = "../" + prefix;
      }
      // Used to be done in initializeProperties
      String home = new File(prefix).getAbsolutePath();
      filePrefix = prefix + "/bin/";
      System.out.println("Setting JMeterHome: "+home);
      JMeterUtils.setJMeterHome(home);
    }
    return filePrefix;
  }
}
