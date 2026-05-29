package com.blazemeter.jmeter.http2.core;

import java.io.File;

/**
 * Converts {@code javax.net.ssl.*} store paths to Jetty {@code file:} URIs.
 */
public final class SslStorePathResolver {

  /** JSSE / JMeter sentinel for non-file keystores (e.g. PKCS#11). */
  public static final String NON_FILE_KEYSTORE_LOCATION = "NONE";

  private SslStorePathResolver() {
    // Utility class.
  }

  /**
   * Whether the property points at a filesystem keystore (not PKCS#11 {@code NONE}).
   */
  public static boolean isFileBasedStoreLocation(final String storePath) {
    return storePath != null
        && !storePath.isEmpty()
        && !NON_FILE_KEYSTORE_LOCATION.equalsIgnoreCase(storePath);
  }

  /**
   * Same as JMeter ({@code new File(path)}), then {@link File#toURI()} for Jetty.
   *
   * @param storePath filesystem path from {@code javax.net.ssl.keyStore} or trustStore
   * @return Jetty-compatible {@code file:} URI
   */
  public static String toJettyFileUri(final String storePath) {
    return new File(storePath).getAbsoluteFile().toURI().toString();
  }

}
