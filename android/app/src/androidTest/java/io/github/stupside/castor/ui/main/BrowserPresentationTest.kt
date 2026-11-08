package io.github.stupside.castor.ui.main

import android.graphics.Color
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
      compose.activity.enableEdgeToEdge()
      compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      val activity =
        android.content.ComponentName(
          compose.activity,
          io.github.stupside.castor.MainActivity::class.java,
        )
      @Suppress("DEPRECATION")
      val softInputMode = compose.activity.packageManager.getActivityInfo(activity, 0).softInputMode
      compose.activity.window.setSoftInputMode(softInputMode)
    }
  }

  @Test
  fun focusedInputKeepsPageGesturesAndWebButtonsUsable() =
    runBlocking<Unit> {
      val instrumentation = InstrumentationRegistry.getInstrumentation()
      val context = instrumentation.targetContext
      val server = MockWebServer()
      val state = java.util.concurrent.atomic.AtomicReference(org.json.JSONObject())
      val clicks = AtomicInteger()
      server.dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            when (request.requestUrl?.encodedPath) {
              "/state" ->
                state.set(org.json.JSONObject(request.requestUrl!!.queryParameter("value")!!))
              "/clicked" -> clicks.incrementAndGet()
              else ->
                return MockResponse()
                  .setHeader("Content-Type", "text/html")
                  .setBody(
                    """<meta name="viewport" content="width=device-width,initial-scale=1">
                <style>html,body{margin:0;width:100%;height:100%;overflow:hidden}#scroll{width:100%;height:100%;overflow:auto}main{width:2400px;height:2400px;background:#217a39}
                header{position:fixed;left:0;top:0;z-index:1;background:white;padding:8px}
                input,button{box-sizing:border-box;height:44px;font-size:16px}input{width:180px}
                button{display:block;width:180px;margin-top:8px}</style>
                <header><input id="name" aria-label="Fixture username"><button id="submit" onclick="fetch('/clicked')">Test button</button></header>
                <div id="scroll"><main>Scrollable form fixture</main></div>
                <script>const report=()=>fetch('/state?value='+encodeURIComponent(JSON.stringify({
                  x:document.querySelector('#scroll').scrollLeft,y:document.querySelector('#scroll').scrollTop,w:innerWidth,h:innerHeight,scale:devicePixelRatio*visualViewport.scale,text:document.querySelector('input').value,
                  focused:document.activeElement===document.querySelector('input')})));
                document.querySelector('#scroll').addEventListener('scroll',report);addEventListener('resize',report);
                document.querySelector('input').addEventListener('input',report);
                document.querySelector('input').addEventListener('focus',report);
                requestAnimationFrame(()=>requestAnimationFrame(report));</script>"""
                  )
            }
            return MockResponse().setHeader("Content-Type", "application/json").setBody("{}")
          }
        }
      server.start()
      val browser = withContext(Dispatchers.Main) { BrowserResolver(context) }
      val model = MainScreenViewModel()
      model.uiState.value =
        castingScreenState(ready = true, castId = "form-fixture", phase = CastPhase.Extracting)
      compose.setContent { CastorTheme { MainScreen(model, browser, "", {}) } }
      val pending = async {
        runCatching {
          browser.resolve(
            ResolveRequest.newBuilder().addUrls(server.url("/form").toString()).build()
          )
        }
      }
      fun visible() =
        android.graphics.Rect().also { rect ->
          compose.runOnIdle { assertTrue(browser.view.getGlobalVisibleRect(rect)) }
        }
      fun imeVisible(): Boolean {
        var shown = false
        compose.runOnIdle {
          shown =
            ViewCompat.getRootWindowInsets(browser.view)
              ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return shown
      }
      fun tapPage(cssX: Float, cssY: Float) {
        val location = IntArray(2)
        compose.runOnIdle { browser.view.getLocationOnScreen(location) }
        val scale = state.get().getDouble("scale").toFloat()
        browserTouchGesture(location[0] + cssX * scale, location[1] + cssY * scale)
      }
      try {
        withTimeout(10_000) {
          while (true) {
            compose.waitForIdle()
            if (browser.page.value?.loading == false && state.get().optInt("w") > 0) break
            delay(100)
          }
        }
        compose.onNodeWithTag("page-browser").performScrollTo()
        delay(500)
        tapPage(70f, 28f)
        val keyboardOpened =
          withTimeoutOrNull(10_000) {
            while (!imeVisible()) delay(100)
            true
          } == true
        java.io
          .File(context.getExternalFilesDir(null), "browser-keyboard-fixture.png")
          .outputStream()
          .use {
            instrumentation.uiAutomation
              .takeScreenshot()
              .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
          }
        assertTrue(
          "Tapping the form must open the keyboard; browser=${visible()} DOM=${state.get()}",
          keyboardOpened,
        )
        assertTrue("The web input must have focus", state.get().optBoolean("focused"))
        // Extraction can finish while the user is entering a login. Preserve interaction
        // through the real busy-to-retained-page layout change, with the IME still open.
        assertNotNull(withTimeout(40_000) { pending.await() }.exceptionOrNull())
        compose.runOnIdle {
          model.uiState.value = MainScreenUiState(ready = true, idlePhase = CastPhase.Failed)
        }
        compose.waitForIdle()
        delay(500)
        val before = visible()
        val initialY = state.get().optInt("y")
        browserTouchGesture(
          before.exactCenterX(),
          before.bottom - 12f,
          0f,
          -minOf(before.height() * .5f, 180f),
        )
        delay(500)
        assertTrue(
          "Typing must not give webpage scrolling to Castor; before=$before after=${visible()} DOM=${state.get()} IME=${imeVisible()}",
          state.get().optInt("y") > initialY + 10,
        )
        assertEquals(
          "The app must stay in place during a webpage gesture with the keyboard open",
          before.top,
          visible().top,
        )
        val horizontal = visible()
        val initialX = state.get().optInt("x")
        browserTouchGesture(
          horizontal.right - 20f,
          horizontal.bottom - 15f,
          -horizontal.width() * .6f,
          0f,
        )
        delay(500)
        assertTrue(
          "Horizontal page scrolling must also survive typing",
          state.get().optInt("x") > initialX + 10,
        )
        assertTrue("The button test must start with the real keyboard visible", imeVisible())
        tapPage(70f, 80f)
        assertTrue(
          "The webpage button must react while the keyboard is open; DOM=${state.get()} rect=${visible()}",
          withTimeoutOrNull(5_000) {
            while (clicks.get() != 1) delay(100)
            true
          } == true,
        )
        if (imeVisible())
          instrumentation.uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
          )
        assertTrue(
          "Back must hide the keyboard",
          withTimeoutOrNull(5_000) {
            while (imeVisible()) delay(100)
            true
          } == true,
        )
        delay(400)
        tapPage(70f, 80f)
        assertTrue(
          "The webpage button must react after hiding the keyboard; DOM=${state.get()} rect=${visible()}",
          withTimeoutOrNull(5_000) {
            while (clicks.get() != 2) delay(100)
            true
          } == true,
        )
        compose
          .onNodeWithContentDescription(context.getString(io.github.stupside.castor.R.string.close))
          .performScrollTo()
          .performClick()
        compose.runOnIdle {
          assertNull(
            "The native Close button must dismiss the retained browser",
            browser.page.value,
          )
        }
      } finally {
        pending.cancelAndJoin()
        withContext(Dispatchers.Main) { browser.close() }
        server.shutdown()
      }
    }

  @Test
  fun pageOwnsBothScrollAxesAndGesturesOutsideStillScrollTheApp() =
    runBlocking<Unit> {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      val server = MockWebServer()
      val domX = AtomicInteger()
      val domY = AtomicInteger()
      val painted = AtomicInteger()
      val viewport = java.util.concurrent.atomic.AtomicReference("")
      server.dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.requestUrl?.encodedPath == "/position") {
              domX.set(request.requestUrl!!.queryParameter("x")!!.toInt())
              domY.set(request.requestUrl!!.queryParameter("y")!!.toInt())
              painted.incrementAndGet()
              viewport.set(request.requestUrl.toString())
              return MockResponse().setHeader("Content-Type", "application/json").setBody("{}")
            }
            return MockResponse()
              .setHeader("Content-Type", "text/html")
              .setBody(
                """<meta name="viewport" content="width=device-width,initial-scale=1">
      <style>html,body{margin:0}main{width:2400px;height:2400px;background:#217a39}</style>
      <main>Scrollable fixture</main>
      <script>
      const report=()=>fetch('/position?x='+scrollX+'&y='+scrollY+'&w='+innerWidth+'&h='+innerHeight+'&sw='+document.scrollingElement.scrollWidth+'&sh='+document.scrollingElement.scrollHeight);
      addEventListener('scroll',report);
      requestAnimationFrame(()=>requestAnimationFrame(report));
      </script>"""
              )
          }
        }
      server.start()
      val browser = withContext(Dispatchers.Main) { BrowserResolver(context) }
      val model = MainScreenViewModel()
      model.uiState.value =
        castingScreenState(ready = true, castId = "scroll-fixture", phase = CastPhase.Extracting)
      compose.setContent { CastorTheme { MainScreen(model, browser, "", {}) } }
      val pending = async {
        browser.resolve(ResolveRequest.newBuilder().addUrls(server.url("/page").toString()).build())
      }
      val scrollX = AtomicInteger()
      val scrollY = AtomicInteger()
      fun visible(): android.graphics.Rect =
        android.graphics.Rect().also { rect ->
          compose.runOnIdle { assertTrue(browser.view.getGlobalVisibleRect(rect)) }
        }
      fun swipe(x: Float, y: Float, dx: Float, dy: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val began = android.os.SystemClock.uptimeMillis()
        for (step in 0..21) {
          val action =
            when (step) {
              0 -> MotionEvent.ACTION_DOWN
              21 -> MotionEvent.ACTION_UP
              else -> MotionEvent.ACTION_MOVE
            }
          val fraction = minOf(step, 20) / 20f
          val pointer =
            MotionEvent.PointerProperties().apply {
              id = 0
              toolType = MotionEvent.TOOL_TYPE_FINGER
            }
          val coordinates =
            MotionEvent.PointerCoords().apply {
              this.x = x + dx * fraction
              this.y = y + dy * fraction
              pressure = 1f
              size = 1f
            }
          val event =
            MotionEvent.obtain(
              began,
              android.os.SystemClock.uptimeMillis(),
              action,
              1,
              arrayOf(pointer),
              arrayOf(coordinates),
              0,
              0,
              1f,
              1f,
              0,
              0,
              android.view.InputDevice.SOURCE_TOUCHSCREEN,
              0,
            )
          try {
            assertTrue(automation.injectInputEvent(event, true))
          } finally {
            event.recycle()
          }
          android.os.SystemClock.sleep(16)
        }
      }
      try {
        withTimeout(10_000) {
          while (browser.page.value?.loading != false || painted.get() == 0) delay(100)
        }
        withContext(Dispatchers.Main) {
          browser.view.session!!.scrollDelegate =
            object : org.mozilla.geckoview.GeckoSession.ScrollDelegate {
              override fun onScrollChanged(
                session: org.mozilla.geckoview.GeckoSession,
                x: Int,
                y: Int,
              ) {
                scrollX.set(x)
                scrollY.set(y)
              }
            }
        }
        compose.waitForIdle()
        delay(500)
        val first = visible()
        swipe(first.exactCenterX(), first.top + first.height() * .8f, 0f, -first.height() * .6f)
        delay(400)
        assertTrue(
          "The webpage must scroll vertically instead of its Compose parent; browser=$first height=${browser.view.height} DOM=${domY.get()} native=${scrollY.get()} after=${visible()} viewport=${viewport.get()}",
          domY.get() > 10 || scrollY.get() > 10,
        )
        val afterVertical = visible()
        assertEquals(
          "A gesture in the browser must keep the app in place",
          first.top,
          afterVertical.top,
        )
        swipe(
          afterVertical.left + afterVertical.width() * .8f,
          afterVertical.exactCenterY(),
          -afterVertical.width() * .6f,
          0f,
        )
        delay(400)
        assertTrue("The webpage must scroll horizontally", domX.get() > 10 || scrollX.get() > 10)
        val beforeOuter = visible()
        swipe(beforeOuter.exactCenterX(), beforeOuter.top - 180f, 0f, 120f)
        delay(400)
        assertTrue(
          "Gestures outside the browser must still scroll Castor",
          visible().top > beforeOuter.top + 10,
        )
      } finally {
        pending.cancelAndJoin()
        withContext(Dispatchers.Main) { browser.close() }
        server.shutdown()
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
        castingScreenState(
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
        castingScreenState(ready = true, castId = "waiting-fixture", phase = CastPhase.Extracting)
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
              sessions = emptyList(),
              selectedCastId = null,
              idlePhase = CastPhase.Failed,
              failure = failure!!.message,
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
  fun successfulResolutionReloadsTheSamePageOnTheNextCast() =
    runBlocking<Unit> {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      val loads = AtomicInteger()
      MockWebServer().use { server ->
        server.dispatcher =
          object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
              if (request.requestUrl?.encodedPath == "/page") {
                val visit = loads.incrementAndGet()
                MockResponse()
                  .setHeader("Content-Type", "text/html")
                  .setHeader("Cache-Control", "no-store")
                  .setBody("<script>fetch('/available-$visit.m3u8')</script>")
              } else {
                MockResponse()
                  .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                  .setBody("#EXTM3U\n")
              }
          }
        server.start()
        val browser = withContext(Dispatchers.Main) { BrowserResolver(context) }
        val request = ResolveRequest.newBuilder().addUrls(server.url("/page").toString()).build()
        try {
          for (visit in 1..2) {
            val result = withTimeout(10_000) { browser.resolve(request) }
            assertEquals(
              "Each cast must load a fresh page after the previous one closed",
              visit,
              loads.get(),
            )
            assertEquals(1, result.streamsCount)
            assertTrue(result.getStreams(0).stream.url.endsWith("/available-$visit.m3u8"))
            assertNull(browser.page.value)
            assertFalse(browser.active.value)
          }
        } finally {
          withContext(Dispatchers.Main) { browser.close() }
        }
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

internal fun browserTouchGesture(x: Float, y: Float, dx: Float = 0f, dy: Float = 0f) {
  val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
  val began = android.os.SystemClock.uptimeMillis()
  val steps = if (dx == 0f && dy == 0f) listOf(0, 21) else (0..21).toList()
  for (step in steps) {
    val pointer =
      MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_FINGER
      }
    val coordinates =
      MotionEvent.PointerCoords().apply {
        this.x = x + dx * minOf(step, 20) / 20f
        this.y = y + dy * minOf(step, 20) / 20f
        pressure = 1f
        size = 1f
      }
    val event =
      MotionEvent.obtain(
        began,
        android.os.SystemClock.uptimeMillis(),
        when (step) {
          0 -> MotionEvent.ACTION_DOWN
          21 -> MotionEvent.ACTION_UP
          else -> MotionEvent.ACTION_MOVE
        },
        1,
        arrayOf(pointer),
        arrayOf(coordinates),
        0,
        0,
        1f,
        1f,
        0,
        0,
        android.view.InputDevice.SOURCE_TOUCHSCREEN,
        0,
      )
    try {
      assertTrue(automation.injectInputEvent(event, true))
    } finally {
      event.recycle()
    }
    android.os.SystemClock.sleep(if (steps.size == 2) 40 else 16)
  }
}
