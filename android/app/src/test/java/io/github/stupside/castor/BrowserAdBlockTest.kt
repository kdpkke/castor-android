package io.github.stupside.castor

import org.junit.Assert.*
import org.junit.Test

class BrowserAdBlockTest {
  private val rules =
    BrowserAdBlock.read(
      """
      # Synthetic hosts rules, including comments and multiple aliases.
      127.0.0.1 localhost
      ::1 localhost
      0.0.0.0 ads.example other-ad.example # inline comment
      127.0.0.1 METRICS.EXAMPLE.
      192.0.2.1 content.example
      ||not-a-hosts-rule.example^
    """
        .trimIndent()
        .reader()
    )

  @Test
  fun blocksDomainsAndSubdomainsWithCanonicalHostnames() {
    for (url in
      listOf(
        "https://ads.example/video.mp4",
        "http://edge.ads.example/live.m3u8",
        "https://ADS.EXAMPLE.:8443/no-extension",
        "https://other-ad.example/",
        "https://metrics.example/",
      )) assertTrue(url, rules.blocks(url))
  }

  @Test
  fun preservesContentAndLoopbackWithoutSubstringMatching() {
    for (url in
      listOf(
        "https://notads.example/video.mp4",
        "https://ads.example.content.example/main.m3u8",
        "https://content.example/ads.example?redirect=https://ads.example/",
        "https://ads.example@content.example/main.m3u8",
        "http://localhost:4500/api",
        "http://127.0.0.1:4500/api",
        "http://[::1]:4500/api",
        "https://not-a-hosts-rule.example/",
        "not a URL",
        "data:video/mp4,ads.example",
        "blob:https://ads.example/id",
      )) assertFalse(url, rules.blocks(url))
  }

  @Test(expected = IllegalStateException::class)
  fun rejectsAnEmptyOrInvalidBundledList() {
    BrowserAdBlock.read("<html>download failed</html>".reader())
  }
}
