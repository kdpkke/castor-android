package io.github.stupside.castor

import androidx.test.platform.app.InstrumentationRegistry
import castor.scraping.v1.Scraping.ResolveRequest
import castor.scraping.v1.Scraping.ResolveResponse
import castor.v1.StreamOuterClass.Stream
import castor.v1.StreamOuterClass.StreamCandidate
import io.github.stupside.castor.data.Rpc
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class BrowserSessionTest {
  @Test
  fun playlistRedirectDoesNotForwardCredentialsToAnotherOrigin() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val resolver = withContext(Dispatchers.Main) { BrowserResolver(context) }
    val origin = MockWebServer()
    val edge = MockWebServer()
    edge.start()
    origin.enqueue(
      MockResponse().setResponseCode(302).setHeader("Location", edge.url("/index.m3u8"))
    )
    edge.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-ENDLIST\n"))
    origin.start()
    try {
      val playlists = BrowserPlaylists(resolver)
      val retained =
        playlists.retain(
          ResolveResponse.newBuilder()
            .addStreams(
              StreamCandidate.newBuilder()
                .setSourcePage(origin.url("/page").toString())
                .setStream(
                  Stream.newBuilder()
                    .setUrl(origin.url("/index.m3u8").toString())
                    .putHeaders("Authorization", "source-secret")
                    .putHeaders("Cookie", "source-session")
                )
            )
            .build(),
          "http://127.0.0.1:1",
        )
      assertEquals(
        200,
        playlists
          .read(java.net.URI(retained.getStreams(0).stream.url).path, "http://127.0.0.1:1")!!
          .status,
      )
      val initial = origin.takeRequest()
      val redirected = edge.takeRequest()
      assertEquals("source-secret", initial.getHeader("Authorization"))
      assertEquals("source-session", initial.getHeader("Cookie"))
      assertNull(redirected.getHeader("Authorization"))
      assertNull(redirected.getHeader("Cookie"))
    } finally {
      origin.shutdown()
      edge.shutdown()
      withContext(Dispatchers.Main) { resolver.close() }
    }
  }

  @Test
  fun cancellingPlaylistReadDisconnectsItsBlockedOrigin() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val resolver = withContext(Dispatchers.Main) { BrowserResolver(context) }
    val origin = MockWebServer()
    origin.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    origin.start()
    try {
      val playlists = BrowserPlaylists(resolver)
      val retained =
        playlists.retain(
          ResolveResponse.newBuilder()
            .addStreams(
              StreamCandidate.newBuilder()
                .setSourcePage(origin.url("/page").toString())
                .setStream(Stream.newBuilder().setUrl(origin.url("/index.m3u8").toString()))
            )
            .build(),
          "http://127.0.0.1:1",
        )
      val pending = async {
        playlists.read(java.net.URI(retained.getStreams(0).stream.url).path, "http://127.0.0.1:1")
      }
      assertNotNull(
        withContext(Dispatchers.IO) { origin.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) }
      )
      withTimeout(5000) { pending.cancelAndJoin() }
      assertTrue(pending.isCancelled)
    } finally {
      origin.shutdown()
      withContext(Dispatchers.Main) { resolver.close() }
    }
  }

  @Test
  fun capturedPlaylistRenewsExpiredAccessAndKeepsRelativeMediaReferences() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val ticket = AtomicInteger(1)
    val pages = AtomicInteger()
    val refused = AtomicBoolean()
    val origin = MockWebServer()
    origin.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val path = request.requestUrl!!
          return when (path.encodedPath) {
            "/page" -> {
              pages.incrementAndGet()
              MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<script>fetch('/live/index.m3u8?ticket=${ticket.get()}');</script>")
            }
            "/live/index.m3u8" -> {
              if (refused.get() || path.queryParameter("ticket") != ticket.get().toString())
                MockResponse().setResponseCode(403)
              else
                MockResponse()
                  .setBody(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:6,\npiece.ts\n"
                  )
            }
            else -> MockResponse().setResponseCode(404)
          }
        }
      }
    origin.start()
    val resolver = withContext(Dispatchers.Main) { BrowserResolver(context) }
    val bridge = BrowserServer(resolver, { emptyList() }, "fixture-token")
    try {
      val api = Rpc(bridge.url, "fixture-token")
      val response = api.result {
        api.scraping.resolve(
          ResolveRequest.newBuilder().addUrls(origin.url("/page").toString()).build()
        )
      }
      assertEquals("application/x-mpegURL", response.streamsList.single().stream.contentType)
      val playlist = response.streamsList.single().stream.url
      ticket.set(2)
      val simultaneous =
        async(Dispatchers.IO) {
          val other = URL(playlist).openConnection() as HttpURLConnection
          try {
            assertEquals(200, other.responseCode)
            other.inputStream.use { it.readBytes() }
          } finally {
            other.disconnect()
          }
        }
      val connection =
        withContext(Dispatchers.IO) { URL(playlist).openConnection() as HttpURLConnection }
      try {
        val body =
          withContext(Dispatchers.IO) {
            assertEquals(200, connection.responseCode)
            connection.inputStream.bufferedReader().use { it.readText() }
          }
        simultaneous.await()
        assertTrue(body, body.contains(origin.url("/live/piece.ts").toString()))
        assertTrue(body, body.contains("URI=\"${origin.url("/live/key.bin")}\""))
        assertEquals("Expired access must reopen the original page once", 2, pages.get())
        assertTrue(
          "The capability belongs to the loopback bridge",
          playlist.startsWith(bridge.url + "/"),
        )
        assertFalse(resolver.active.value)
      } finally {
        connection.disconnect()
      }
      refused.set(true)
      repeat(2) {
        withContext(Dispatchers.IO) {
          val rejected = URL(playlist).openConnection() as HttpURLConnection
          try {
            assertEquals(403, rejected.responseCode)
          } finally {
            rejected.disconnect()
          }
        }
      }
      assertEquals("A permanent refusal renews only once", 3, pages.get())
      bridge.playbackEnded()
      withContext(Dispatchers.IO) {
        val expired = URL(playlist).openConnection() as HttpURLConnection
        try {
          assertEquals(404, expired.responseCode)
        } finally {
          expired.disconnect()
        }
      }
      val missing =
        withContext(Dispatchers.IO) {
          URL(bridge.url + "/playlists/unknown.m3u8").openConnection() as HttpURLConnection
        }
      try {
        assertEquals(404, withContext(Dispatchers.IO) { missing.responseCode })
      } finally {
        missing.disconnect()
      }
    } finally {
      bridge.close()
      withContext(Dispatchers.Main) { resolver.close() }
      origin.shutdown()
    }
  }
}
