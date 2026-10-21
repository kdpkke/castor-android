package io.github.stupside.castor

import org.junit.Assert.*
import org.junit.Test

class BrowserReplayTest {
  @Test
  fun browserConnectionAndCacheStateIsNotReplayed() {
    val kept =
      BrowserResolver.replayable(
        mapOf(
          "cookie" to "session=1",
          "Authorization" to "Bearer page",
          "Accept-Encoding" to "gzip, deflate, br, zstd",
          "If-None-Match" to "\"etag\"",
          "If-Modified-Since" to "Tue, 20 Oct 2026 10:00:00 GMT",
          "If-Range" to "\"etag\"",
          "Range" to "bytes=0-",
          "Connection" to "keep-alive, X-Hop",
          "X-Hop" to "connection-scoped",
          "Origin" to "https://page.example",
        )
      )
    assertEquals(
      mapOf(
        "Cookie" to "session=1",
        "Authorization" to "Bearer page",
        "Origin" to "https://page.example",
      ),
      kept,
    )
  }

  @Test
  fun onlyANewerPinnedBlockerReplacesTheInstalledOne() {
    assertTrue(BrowserEngine.newerVersion("1.75.0", null))
    assertTrue(BrowserEngine.newerVersion("1.76.0", "1.75.0"))
    assertTrue(BrowserEngine.newerVersion("5.5.3.6", "5.5.3.5"))
    assertTrue(BrowserEngine.newerVersion("1.10.0", "1.9.9"))
    assertFalse(BrowserEngine.newerVersion("1.75.0", "1.75.0"))
    assertFalse(BrowserEngine.newerVersion("1.75", "1.75.0"))
    assertFalse(BrowserEngine.newerVersion("1.75.0", "1.76.0"))
    assertFalse(BrowserEngine.newerVersion("1.75.0", "1.75.0b1"))
  }
}
