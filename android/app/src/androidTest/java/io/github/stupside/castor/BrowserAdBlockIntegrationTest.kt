package io.github.stupside.castor

import androidx.test.platform.app.InstrumentationRegistry
import castor.scraping.v1.Scraping.ResolveRequest
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class BrowserAdBlockIntegrationTest {
  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun bundledRulesBlockKnownAdHostsAndPreserveLocalPlayback() {
    val rules = BrowserAdBlock.bundled(context)
    assertTrue(rules.blocks("https://doubleclick.net/preroll.mp4"))
    assertTrue(rules.blocks("https://pagead2.googlesyndication.com/ad.m3u8"))
    assertFalse(rules.blocks("http://localhost:4500/media/main.m3u8"))
    assertFalse(rules.blocks("https://cdn.example.org/main.m3u8"))
  }

  @Test
  fun adsNeverReachTheOriginOrWinOverTheRealVideo() =
    runBlocking<Unit> {
      val requests = CopyOnWriteArrayList<RecordedRequest>()
      val origin = MockWebServer()
      origin.start(InetAddress.getByName("0.0.0.0"), 0)
      val page = "http://127.0.0.1:${origin.port}"
      val ads = "http://127.0.0.2:${origin.port}"
      origin.dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val body =
              when (request.path) {
                "/page" ->
                  """
            <script src="$ads/ad.js"></script><iframe src="$ads/frame"></iframe>
            <video src="$ads/preroll.mp4"></video><video src="$ads/extensionless"></video>
            <video><source src="$ads/source.webm"></video>
            <script>
              fetch('$ads/ad.m3u8'); fetch('$ads/ad.mpd');
              setTimeout(() => { location.href='$ads/hijack'; }, 1000);
              // An ad candidate must not end extraction before this real player starts.
              setTimeout(() => {
                fetch('/main.m3u8');
                const iframe = document.createElement('iframe'); iframe.src='/player';
                document.body.appendChild(iframe);
              }, 4500);
            </script>
          """
                    .trimIndent()
                "/player" -> "<script>fetch('/iframe.m3u8')</script>"
                else -> "#EXTM3U\n#EXT-X-ENDLIST\n"
              }
            return MockResponse().setHeader("Content-Type", "text/html").setBody(body)
          }
        }
      val browser =
        withContext(Dispatchers.Main) {
          BrowserResolver(context, BrowserAdBlock.read("0.0.0.0 127.0.0.2".reader()))
        }
      try {
        val result =
          withTimeout(15_000) {
            browser.resolve(
              ResolveRequest.newBuilder().addUrls("$ads/blocked-page").addUrls("$page/page").build()
            )
          }
        assertEquals(
          setOf("$page/main.m3u8", "$page/iframe.m3u8"),
          result.streamsList.map { it.stream.url }.toSet(),
        )
        assertTrue(
          "No blocked script, frame, video or fetch should reach the origin: $requests",
          requests.none { it.getHeader("Host")?.startsWith("127.0.0.2:") == true },
        )
        assertNull(browser.page.value)
      } finally {
        withContext(Dispatchers.Main) { browser.close() }
        origin.shutdown()
      }
    }

  @Test
  fun workerRequestsUseTheSameFilterAndNormalFetchesStillWork() =
    runBlocking<Unit> {
      val requests = CopyOnWriteArrayList<RecordedRequest>()
      val origin = MockWebServer()
      origin.start(InetAddress.getByName("0.0.0.0"), 0)
      val page = "http://127.0.0.1:${origin.port}"
      val ads = "http://127.0.0.2:${origin.port}"
      origin.dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val body =
              when (request.path) {
                "/page" ->
                  """
            <script>
              navigator.serviceWorker.register('/worker.js').then(() => navigator.serviceWorker.ready)
                .then(r => r.active.postMessage('start'));
            </script>
          """
                    .trimIndent()
                "/worker.js" ->
                  """
            self.addEventListener('install', e => self.skipWaiting());
            self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
            self.addEventListener('message', e => e.waitUntil((async () => {
              try { await fetch('$ads/worker-ad.mp4', {mode:'no-cors'}); } catch (_) {}
              await fetch('/worker-ok');
              const pages = await self.clients.matchAll();
              pages.forEach(p => p.postMessage('done'));
            })()));
          """
                    .trimIndent()
                else -> "ok"
              }
            return MockResponse()
              .setHeader(
                "Content-Type",
                if (request.path == "/worker.js") "application/javascript" else "text/html",
              )
              .setBody(body)
          }
        }
      val browser =
        withContext(Dispatchers.Main) {
          BrowserResolver(context, BrowserAdBlock.read("0.0.0.0 127.0.0.2".reader()))
        }
      val pending = async {
        browser.resolve(ResolveRequest.newBuilder().addUrls("$page/page").build())
      }
      try {
        withTimeout(15_000) { while (requests.none { it.path == "/worker-ok" }) delay(100) }
        assertTrue(
          "Worker ads must be intercepted before connecting",
          requests.none { it.getHeader("Host")?.startsWith("127.0.0.2:") == true },
        )
      } finally {
        pending.cancelAndJoin()
        withContext(Dispatchers.Main) { browser.close() }
        origin.shutdown()
      }
    }
}
