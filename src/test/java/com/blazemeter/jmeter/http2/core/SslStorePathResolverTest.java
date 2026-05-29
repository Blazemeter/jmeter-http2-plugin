package com.blazemeter.jmeter.http2.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import org.junit.Test;

public class SslStorePathResolverTest {

  private static final String RELATIVE_STORE = "certs/keystore.p12";

  @Test
  public void naiveFilePrefixOnRelativePathTreatsFirstSegmentAsUriAuthority() {
    URI malformed = URI.create("file://" + RELATIVE_STORE);
    assertThat(malformed.getAuthority()).isEqualTo("certs");
    assertThat(malformed.getPath()).isEqualTo("/keystore.p12");
  }

  @Test
  public void naiveFilePrefixOnRelativePathRejectsAuthorityOnUnix() {
    assumeTrue(!isWindows());
    assertThatThrownBy(() -> Paths.get(URI.create("file://" + RELATIVE_STORE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authority");
  }

  @Test
  public void toJettyFileUriMatchesFileToUriForRelativePath() {
    assertThat(SslStorePathResolver.toJettyFileUri(RELATIVE_STORE))
        .isEqualTo(new File(RELATIVE_STORE).getAbsoluteFile().toURI().toString());
  }

  @Test
  public void toJettyFileUriMatchesFileToUriForAbsolutePath() {
    String absolute = new File(System.getProperty("user.dir"), RELATIVE_STORE)
        .getAbsolutePath();
    assertThat(SslStorePathResolver.toJettyFileUri(absolute))
        .isEqualTo(new File(absolute).getAbsoluteFile().toURI().toString());
  }

  @Test
  public void toJettyFileUriHasNoUriAuthorityForRelativePath() {
    URI jettyUri = URI.create(SslStorePathResolver.toJettyFileUri(RELATIVE_STORE));
    assertThat(jettyUri.getAuthority()).isNull();
    assertThat(Paths.get(jettyUri))
        .isEqualTo(new File(RELATIVE_STORE).getAbsoluteFile().toPath());
  }

  @Test
  public void noneIsNotAFileBasedStoreLocation() {
    assertThat(SslStorePathResolver.isFileBasedStoreLocation("NONE")).isFalse();
    assertThat(SslStorePathResolver.isFileBasedStoreLocation("none")).isFalse();
    assertThat(SslStorePathResolver.isFileBasedStoreLocation(RELATIVE_STORE)).isTrue();
  }

  private static boolean isWindows() {
    return File.separatorChar == '\\';
  }

}
