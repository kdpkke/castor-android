package io.github.stupside.castor

import androidx.test.platform.app.InstrumentationRegistry
import castor.scraping.v1.Scraping.ResolveRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtensionController

class BrowserAdBlockIntegrationTest {
  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  fun prepareBrowserProtection() =
    runBlocking<Unit> {
      withContext(Dispatchers.Main) {
        withTimeout(90_000) { BrowserEngine.get(context).ready().await() }
      }
    }

  @Test
  fun bothOfficialBlockersAreInstalledAndEnabled() =
    runBlocking<Unit> {
      val extensions =
        withContext(Dispatchers.Main) {
            BrowserEngine.get(context).runtime.webExtensionController.list().await()
          }
          .associateBy { it.id }
      for (id in BrowserEngine.blockers.keys) {
        assertTrue(id, extensions.containsKey(id))
        assertEquals(id, 0, extensions.getValue(id).metaData!!.disabledFlags)
      }
    }

  @Test
  fun adScriptsCannotReplaceThePlayerAndBlockedDomVideosAreNotCaptured() =
    runBlocking<Unit> {
      val requests = CopyOnWriteArrayList<String>()
      MockWebServer().use { origin ->
        origin.dispatcher =
          object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
              requests += request.path.orEmpty()
              val body =
                when (request.path) {
                  "/page" ->
                    """
              <script src='/ads/custom_ads.js'></script>
              <video src='/adverts/preroll.mp4'></video><video src='/adverts/extensionless'></video>
              <script>setTimeout(() => {
                fetch('/main.m3u8');
                const frame=document.createElement('iframe'); frame.src='/player'; document.body.appendChild(frame);
              }, 4500)</script>
            """
                      .trimIndent()
                  // Real generic rules bundled in both extensions block these same-origin
                  // resources.
                  // No external advertising service is contacted, even if filtering regresses.
                  "/ads/custom_ads.js" -> "location.href='/amazon-offer';"
                  "/player" -> "<script>fetch('/iframe.m3u8')</script>"
                  else -> "#EXTM3U\n#EXT-X-ENDLIST\n"
                }
              return MockResponse()
                .setHeader(
                  "Content-Type",
                  if (request.path == "/ads/custom_ads.js") "application/javascript"
                  else "text/html",
                )
                .setBody(body)
            }
          }
        origin.start()
        val browser = withContext(Dispatchers.Main) { BrowserResolver(context) }
        try {
          val result =
            withTimeout(20_000) {
              browser.resolve(
                ResolveRequest.newBuilder().addUrls(origin.url("/page").toString()).build()
              )
            }
          assertEquals(
            setOf(origin.url("/main.m3u8").toString(), origin.url("/iframe.m3u8").toString()),
            result.streamsList.map { it.stream.url }.toSet(),
          )
          assertTrue(
            "Blocked ads must never reach the local origin: $requests",
            requests.none {
              it.startsWith("/ads/") || it.startsWith("/adverts/") || it == "/amazon-offer"
            },
          )
          assertTrue(
            "A real blocker rejection must be observed, not a network failure: ${browser.requestErrors}",
            browser.requestErrors.any {
              it.key.endsWith("/ads/custom_ads.js") &&
                it.value in setOf("NS_ERROR_ABORT", "NS_ERROR_BLOCKED_BY_POLICY")
            },
          )
        } finally {
          withContext(Dispatchers.Main) { browser.close() }
        }
      }
    }

  @Test
  fun eachBlockerIndependentlyStopsTheAdScript() =
    runBlocking<Unit> {
      val engine = withContext(Dispatchers.Main) { BrowserEngine.get(context) }
      val controller = engine.runtime.webExtensionController
      val extensions =
        withContext(Dispatchers.Main) { controller.list().await() }.associateBy { it.id }
      val requests = CopyOnWriteArrayList<String>()
      MockWebServer().use { origin ->
        origin.dispatcher =
          object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
              requests += request.path.orEmpty()
              return MockResponse()
                .setHeader("Content-Type", "text/html")
                .setHeader("Cache-Control", "no-store")
                .setBody(
                  if (request.path == "/page")
                    "<script src='/ads/custom_ads.js'></script><script>fetch('/control')</script>"
                  else "ok"
                )
            }
          }
        origin.start()
        // Only this local fixture uses a raw session. Production refuses loads with a blocker off.
        suspend fun filtering(): List<String> {
          // Re-enabling an event-page extension completes before its filter engine has warmed,
          // which can take tens of seconds on a CI emulator. Probe only local fixtures until
          // actual filtering is observable, like the app's startup check, rather than sleeping
          // for an assumed duration or treating the enabled flag as proof. Each attempt uses a
          // fresh session because Android may kill a content process under memory pressure.
          withTimeout(90_000) {
            while (true) {
              requests.clear()
              val session =
                withContext(Dispatchers.Main) {
                  GeckoSession().also {
                    it.open(engine.runtime)
                    it.setActive(true)
                    it.loadUri(origin.url("/page").toString())
                  }
                }
              val ran =
                try {
                  withTimeoutOrNull(10_000) { while ("/control" !in requests) delay(100) }
                } finally {
                  withContext(Dispatchers.Main) { session.close() }
                }
              if (ran != null && "/ads/custom_ads.js" !in requests) break
              delay(250)
            }
          }
          return requests.toList()
        }
        // A fresh AdGuard installs its default filters after its first-run page; switching it off
        // before then leaves it without filters, which the app never does. Test AdGuard alone
        // first, so it is never the blocker disabled during that first run.
        for (id in BrowserEngine.blockers.keys.sortedByDescending { it.startsWith("adguard") }) {
          val other = extensions.getValue(BrowserEngine.blockers.keys.single { it != id })
          withContext(Dispatchers.Main) {
            controller.disable(other, WebExtensionController.EnableSource.APP).await()
          }
          try {
            val seen = filtering()
            assertFalse("$id must block on its own: $seen", "/ads/custom_ads.js" in seen)
          } finally {
            withContext(Dispatchers.Main) {
              controller.enable(other, WebExtensionController.EnableSource.APP).await()
            }
          }
        }
        // Leave both blockers filtering again for the tests that follow.
        filtering()
      }
    }

  @Test
  fun aDisabledBlockerPreventsAnyPageFromLoading() =
    runBlocking<Unit> {
      val controller =
        withContext(Dispatchers.Main) { BrowserEngine.get(context).runtime.webExtensionController }
      val extensions =
        withContext(Dispatchers.Main) { controller.list().await() }.associateBy { it.id }
      MockWebServer().use { origin ->
        origin.start()
        for (id in BrowserEngine.blockers.keys) {
          val extension = extensions.getValue(id)
          withContext(Dispatchers.Main) {
            controller.disable(extension, WebExtensionController.EnableSource.APP).await()
          }
          val browser = withContext(Dispatchers.Main) { BrowserResolver(context) }
          try {
            val failure =
              withTimeout(10_000) {
                runCatching {
                    browser.resolve(
                      ResolveRequest.newBuilder().addUrls(origin.url("/page").toString()).build()
                    )
                  }
                  .exceptionOrNull()
              }
            assertTrue(failure?.message.orEmpty().contains("both be active"))
            assertEquals(0, origin.requestCount)
          } finally {
            withContext(Dispatchers.Main) {
              browser.close()
              controller.enable(extension, WebExtensionController.EnableSource.APP).await()
            }
          }
        }
      }
    }
}
