package io.github.stupside.castor.ui.main

import android.graphics.Color
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import castor.scraping.v1.Scraping.ResolveRequest
import io.github.stupside.castor.BrowserEngine
import io.github.stupside.castor.BrowserResolver
import io.github.stupside.castor.theme.CastorTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BrowserPresentationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun prepareBrowserProtection() =
    runBlocking<Unit> {
      withContext(Dispatchers.Main) {
        BrowserEngine.get(InstrumentationRegistry.getInstrumentation().targetContext)
          .ready()
          .await()
      }
    }

  @Before
  fun keepFixtureVisible() {
    compose.runOnUiThread {
      compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }

  @Test
  fun embeddedPagePaintsAndCapturesVideoAfterARealTap() =
    runBlocking<Unit> {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      val popupRequests = AtomicInteger()
      val server = MockWebServer()
      server.dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path?.startsWith("/popup") == true) popupRequests.incrementAndGet()
            return MockResponse()
              .setHeader("Content-Type", "text/html")
              .setBody(
                """<meta name="viewport" content="width=device-width,initial-scale=1">
          <style>html,body{margin:0;height:100%;background:#217a39}button{position:fixed;inset:0;width:100%;height:100%;background:#217a39;color:white;border:0;font-size:24px}</style>
          <button onclick="window.open('/popup-window'); const a=document.createElement('a'); a.href='/popup-link'; a.target='_blank'; document.body.appendChild(a); a.click(); fetch('/manual.m3u8')">Play test video</button>"""
              )
          }
        }
      server.start()
      val browser = withContext(Dispatchers.Main) { BrowserResolver(context) }
      val model = MainScreenViewModel()
      model.uiState.value =
        MainScreenUiState(
          ready = true,
          castId = "interactive-fixture",
          phase = CastPhase.Extracting,
          activeCast =
            castor.v1.CastOuterClass.Cast.newBuilder()
              .setSource(
                castor.v1.CastOuterClass.Source.newBuilder()
                  .setPages(
                    castor.v1.CastOuterClass.Source.Pages.newBuilder()
                      .addUrls(server.url("/page").toString())
                  )
              )
              .build(),
        )
      compose.setContent { CastorTheme { MainScreen(model, browser, "", {}) } }
      val pending = async {
        browser.resolve(ResolveRequest.newBuilder().addUrls(server.url("/page").toString()).build())
      }
      try {
        var location = IntArray(2)
        withTimeout(10_000) {
          while (true) {
            var ready = false
            compose.runOnIdle {
              browser.view.getLocationOnScreen(location)
              ready =
                browser.view.isShown &&
                  browser.view.height > 0 &&
                  browser.page.value?.loading == false
            }
            if (ready) break
            delay(100)
          }
        }
        delay(500)
        compose.runOnIdle {
          browser.dismissPage()
          assertNotNull("Dismiss must not interrupt an active resolution", browser.page.value)
        }
        // The production automatic scroll must expose the browser; do not repair it in the test.
        compose.runOnIdle { browser.view.getLocationOnScreen(location) }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val screenshot = automation.takeScreenshot()
        val x = location[0] + 20
        val y = location[1] + 20
        assertEquals(
          "The embedded page must paint instead of leaving a blank browser",
          Color.rgb(33, 122, 57),
          screenshot.getPixel(x, y),
        )
        var whiteTextPixels = 0
        val width = browser.view.width
        val height = browser.view.height
        for (sy in location[1] until minOf(location[1] + height, screenshot.height)) {
          for (sx in location[0] until minOf(location[0] + width, screenshot.width)) {
            val pixel = screenshot.getPixel(sx, sy)
            if (Color.red(pixel) > 225 && Color.green(pixel) > 225 && Color.blue(pixel) > 225)
              whiteTextPixels++
          }
        }
        assertTrue(
          "The player must paint its Play text as well as its background",
          whiteTextPixels > 50,
        )
        java.io
          .File(context.getExternalFilesDir(null), "page-player-fixture.png")
          .outputStream()
          .use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val now = android.os.SystemClock.uptimeMillis()
        for ((action, time) in
          listOf(MotionEvent.ACTION_DOWN to now, MotionEvent.ACTION_UP to now + 50)) {
          MotionEvent.obtain(now, time, action, x.toFloat(), y.toFloat(), 0).let {
            automation.injectInputEvent(it, true)
            it.recycle()
          }
        }
        val result = withTimeout(10_000) { pending.await() }
        assertTrue(result.streamsList.any { it.stream.url.endsWith("/manual.m3u8") })
        assertEquals(
          "Play must not load popups or replace the embedded player",
          0,
          popupRequests.get(),
        )
      } finally {
        pending.cancelAndJoin()
        withContext(Dispatchers.Main) { browser.close() }
        server.shutdown()
      }
    }

  @Test
  fun missingStreamKeepsThePageAndRetryPreservesTheUsersPlayClick() =
    runBlocking<Unit> {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      val loads = AtomicInteger()
      val server = MockWebServer()
      server.dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/page") loads.incrementAndGet()
            return MockResponse()
              .setHeader("Content-Type", "text/html")
              .setBody(
                """<meta name="viewport" content="width=device-width,initial-scale=1">
          <style>html,body{margin:0;height:100%}button{position:fixed;inset:0;width:100%;height:100%;background:#217a39;color:white;border:0;font-size:24px}</style>
          <button onclick="fetch('/manual.m3u8')">Play test video</button>"""
              )
          }
        }
      server.start()
      val browser = withContext(Dispatchers.Main) { BrowserResolver(context) }
      val model = MainScreenViewModel()
      model.uiState.value =
        MainScreenUiState(ready = true, castId = "waiting-fixture", phase = CastPhase.Extracting)
      compose.setContent { CastorTheme { MainScreen(model, browser, "", {}) } }
      val request = ResolveRequest.newBuilder().addUrls(server.url("/page").toString()).build()
      try {
        val failure =
          withTimeout(40_000) { runCatching { browser.resolve(request) }.exceptionOrNull() }
        assertNotNull(failure)
        assertFalse(browser.active.value)
        assertNotNull(
          "Keep the browser available after reporting no playable stream",
          browser.page.value,
        )
        compose.runOnIdle {
          model.uiState.value =
            model.uiState.value.copy(
              castId = null,
              phase = CastPhase.Failed,
              error = failure!!.message,
            )
          assertEquals(server.url("/page").toString(), browser.page.value?.url)
        }
        compose.onNodeWithTag("page-browser").performScrollTo().assertExists()
        java.io
          .File(context.getExternalFilesDir(null), "page-player-retry-fixture.png")
          .outputStream()
          .use {
            InstrumentationRegistry.getInstrumentation()
              .uiAutomation
              .takeScreenshot()
              .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
          }
        val location = IntArray(2)
        compose.runOnIdle { browser.view.getLocationOnScreen(location) }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val now = android.os.SystemClock.uptimeMillis()
        for ((action, time) in
          listOf(MotionEvent.ACTION_DOWN to now, MotionEvent.ACTION_UP to now + 50)) {
          MotionEvent.obtain(
              now,
              time,
              action,
              (location[0] + 40).toFloat(),
              (location[1] + 40).toFloat(),
              0,
            )
            .let {
              automation.injectInputEvent(it, true)
              it.recycle()
            }
        }
        val result = withTimeout(10_000) { browser.resolve(request) }
        assertTrue(result.streamsList.any { it.stream.url.endsWith("/manual.m3u8") })
        assertEquals(
          "Retry must not reload the page and lose the user's interaction",
          1,
          loads.get(),
        )
        assertNull(browser.page.value)
        assertFalse(browser.active.value)
      } finally {
        withContext(Dispatchers.Main) { browser.close() }
        server.shutdown()
      }
    }

  @Test
  fun mainFrameFailureIsExplainedAndThePageCanBeDismissed() =
    runBlocking<Unit> {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(404).setBody("Page missing"))
        server.start()
        val browser = withContext(Dispatchers.Main) { BrowserResolver(context) }
        try {
          val request =
            ResolveRequest.newBuilder().addUrls(server.url("/missing").toString()).build()
          val error =
            withTimeout(10_000) { runCatching { browser.resolve(request) }.exceptionOrNull() }
          assertTrue(error?.message.orEmpty().contains("HTTP 404"))
          assertTrue(
            "Retained page: ${browser.page.value}",
            browser.page.value?.error.orEmpty().contains("HTTP 404"),
          )
          assertFalse(browser.active.value)
          withContext(Dispatchers.Main) { browser.dismissPage() }
          assertNull(browser.page.value)
        } finally {
          withContext(Dispatchers.Main) { browser.close() }
        }
      }
    }

  @Test
  fun failedPageFallsBackToTheNextRequestedPage() =
    runBlocking<Unit> {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      MockWebServer().use { server ->
        server.dispatcher =
          object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
              if (request.path == "/missing") MockResponse().setResponseCode(404)
              else
                MockResponse()
                  .setHeader("Content-Type", "text/html")
                  .setBody("<script>fetch('/available.m3u8')</script>")
          }
        server.start()
        val browser = withContext(Dispatchers.Main) { BrowserResolver(context) }
        try {
          val request =
            ResolveRequest.newBuilder()
              .addUrls(server.url("/missing").toString())
              .addUrls(server.url("/available").toString())
              .build()
          val result = withTimeout(15_000) { browser.resolve(request) }
          assertTrue(result.streamsList.any { it.stream.url.endsWith("/available.m3u8") })
          assertEquals(server.url("/available").toString(), result.getStreams(0).sourcePage)
          assertNull(browser.page.value)
        } finally {
          withContext(Dispatchers.Main) { browser.close() }
        }
      }
    }
}
