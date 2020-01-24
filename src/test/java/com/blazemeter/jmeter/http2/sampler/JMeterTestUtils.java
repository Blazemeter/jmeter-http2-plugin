package com.blazemeter.jmeter.http2.sampler;

import kg.apc.emulators.TestJMeterUtils;

public class JMeterTestUtils {

  private static boolean jeerEnvironmentInitialized = false;

  public JMeterTestUtils() {
  }
  
  public static void setupJmeterEnv() {
    if(!jeerEnvironmentInitialized) {
      jeerEnvironmentInitialized = true;
      TestJMeterUtils.createJmeterEnv();
    }
  }
}
