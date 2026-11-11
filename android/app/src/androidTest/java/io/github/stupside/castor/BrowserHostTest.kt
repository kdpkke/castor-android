package io.github.stupside.castor

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import castor.scraping.v1.Scraping.ResolveRequest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BrowserHostTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  // The browser needs its protection before any page is shown; no engine may be running before a test starts.
  @Before
  fun prepareBrowser() = runBlocking<Unit> {
    withContext(Dispatchers.Main) { BrowserEngine.get(context).ready().await() }
    context.stopService(Intent(context, CastorService::class.java))
    withTimeout(20_000) {
      while (EngineHost.phase != EngineLifecycle.Phase.Stopped) delay(20)
    }
  }

  // The browser belongs to the screen, so a page can be browsed while no engine runs.
  @Test
  fun theBrowserShowsAPageWhileNoEngineRuns() = runBlocking<Unit> {
    val page = MockWebServer()
    page.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          MockResponse().setHeader("Content-Type", "text/html").setBody("<p>Fixture</p>")
      }
    page.start()
    val address = page.url("/page").toString()
    val resolver = withContext(Dispatchers.Main) { BrowserHost.acquire("screen", context) }
    try {
      assertNull("A fresh browser shows no page", resolver.page.value)
      withContext(Dispatchers.Main) { resolver.showPage(address) }
      withTimeout(30_000) {
        while (resolver.page.value?.let { it.url == address && !it.loading } != true) delay(50)
      }
      assertEquals(address, resolver.page.value?.url)
      val request = withContext(Dispatchers.IO) { page.takeRequest(5, TimeUnit.SECONDS) }
      assertEquals("/page", request?.path)
      assertEquals(EngineLifecycle.Phase.Stopped, EngineHost.phase)
      assertEquals("", enginePids())
    } finally {
      withContext(Dispatchers.Main) { BrowserHost.release("screen") }
      page.shutdown()
    }
  }

  // A player kept for a cast outlives the screen that browsed it, and is let go with the engine that uses it.
  @Test
  fun aPageKeptForACastOutlivesTheScreenAndGoesWithItsEngine() = runBlocking<Unit> {
    val fixture =
      instrumentation.context.assets.open("player.html").bufferedReader().use { it.readText() }
    val page = MockWebServer()
    val frame = MockWebServer()
    val access = AtomicInteger()
    frame.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          when (request.requestUrl!!.encodedPath) {
            "/player" -> MockResponse().setHeader("Content-Type", "text/html").setBody(fixture)
            "/access" ->
              MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"token\":${access.incrementAndGet()}}")
            "/manifest" ->
              MockResponse()
                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                .setBody(
                  "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\nopaque-reference\n"
                )
            else -> MockResponse().setResponseCode(404)
          }
      }
    frame.start()
    page.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          MockResponse()
            .setHeader("Content-Type", "text/html")
            .setBody("<iframe src='${frame.url("/player")}'></iframe>")
      }
    page.start()
    val engineOwner = Any()
    val resolver = withContext(Dispatchers.Main) { BrowserHost.acquire("screen", context) }
    val shared = withContext(Dispatchers.Main) { BrowserHost.acquire(engineOwner, context) }
    try {
      assertSame("Both owners share one browser", resolver, shared)
      // The resolver is called directly: the bridge rewrites stream URLs, which the player lookup keys on.
      val response =
        resolver.resolve(
          ResolveRequest.newBuilder().addUrls(page.url("/page").toString()).build()
        )
      val kept =
        checkNotNull(resolver.playerSource(response.streamsList.single())) {
          "The player's playlist must be kept"
        }
      withContext(Dispatchers.Main) { BrowserHost.release("screen") }
      assertEquals("The kept player answers after the screen left", 200, resolver.readPlayer(kept).status)

      // What the engine's service does when its engine stops.
      withContext(Dispatchers.Main) { resolver.releasePlayers() }
      try {
        resolver.readPlayer(kept)
        fail("A released player still answered")
      } catch (error: IllegalStateException) {
        assertEquals("The captured player was released", error.message)
      }

      withContext(Dispatchers.Main) { BrowserHost.release(engineOwner) }
      val next = withContext(Dispatchers.Main) { BrowserHost.acquire("screen", context) }
      try {
        assertNotSame("A browser used after its engine is gone is a new one", resolver, next)
      } finally {
        withContext(Dispatchers.Main) { BrowserHost.release("screen") }
      }
    } finally {
      withContext(Dispatchers.Main) {
        BrowserHost.release("screen")
        BrowserHost.release(engineOwner)
      }
      page.shutdown()
      frame.shutdown()
    }
  }

  private fun enginePids(): String =
    ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("pidof libcastor.so")
      )
      .bufferedReader()
      .use { it.readText() }
      .trim()
}
