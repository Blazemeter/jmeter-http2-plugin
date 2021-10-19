package com.blazemeter.jmeter.http2.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import org.apache.jmeter.util.JsseSSLManager;
import org.apache.jmeter.util.SSLManager;
import org.apache.jmeter.util.keystore.JmeterKeyStore;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class JMeterJettySslContextFactory extends SslContextFactory.Client {

  private final JmeterKeyStore keys;

  public JMeterJettySslContextFactory() {
    setTrustAll(true);
    String keyStorePath = System.getProperty("javax.net.ssl.keyStore");
    if (keyStorePath != null && !keyStorePath.isEmpty()) {
      setKeyStorePath("file://" + keyStorePath);
      keys = getKeyStore((JsseSSLManager) SSLManager.getInstance());
      /*
       we need to set password after getting keystore since getKeystore may ask the user for the
       password.
      */
      setKeyStorePassword(System.getProperty("javax.net.ssl.keyStorePassword"));
    } else {
      keys = null;
    }
  }

  private JmeterKeyStore getKeyStore(JsseSSLManager sslManager) {
    try {
      Method keystoreMethod = SSLManager.class.getDeclaredMethod("getKeyStore");
      keystoreMethod.setAccessible(true);
      return (JmeterKeyStore) keystoreMethod.invoke(sslManager);
    } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  // Overwritten to avoid warning logging
  @Override
  protected void checkTrustAll() {
  }

  // Overwritten to avoid warning logging
  @Override
  protected void checkEndPointIdentificationAlgorithm() {
  }

  // Overwritten to provide jmeter SSLManager configured keyManagers
  @Override
  protected KeyManager[] getKeyManagers(KeyStore keyStore) throws Exception {
    // based in logic extracted from JsseSSLManager.createContext
    KeyManager[] ret = super.getKeyManagers(keyStore);
    if (keys == null) {
      return ret;
    }
    for (int i = 0; i < ret.length; i++) {
      if (ret[i] instanceof X509KeyManager) {
        ret[i] = new WrappedX509KeyManager((X509KeyManager) ret[i], keys);
      }
    }
    return ret;
  }

  // based in logic extracted from JsseSSLManager.WrappedX509KeyManager
  private static class WrappedX509KeyManager extends X509ExtendedKeyManager {

    private final X509KeyManager manager;
    private final JmeterKeyStore store;

    private WrappedX509KeyManager(X509KeyManager parent, JmeterKeyStore ks) {
      this.manager = parent;
      this.store = ks;
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
      return store.getClientAliases(keyType, issuers);
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
      return manager.getServerAliases(keyType, issuers);
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
      return store.getCertificateChain(alias);
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
      return store.getPrivateKey(alias);
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
      return store.getAlias();
    }

    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers,
        SSLEngine engine) {
      return store.getAlias();
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
      return this.manager.chooseServerAlias(keyType, issuers, socket);
    }

    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
      return manager instanceof X509ExtendedKeyManager
          ? ((X509ExtendedKeyManager) manager).chooseEngineServerAlias(keyType, issuers, engine)
          : null;
    }

  }

}
