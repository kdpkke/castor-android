package io.github.stupside.castor.ui.main

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import castor.scraping.v1.Scraping.ResolveRequest
import castor.v1.CastOuterClass.Cast
import castor.v1.CastOuterClass.Source
import io.github.stupside.castor.BrowserResolver
import io.github.stupside.castor.ManualDiagnostic
import io.github.stupside.castor.theme.CastorTheme
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

// Manual diagnostics only: never contact external websites in the fixture suite.
@ManualDiagnostic
class BrowserSiteDiagnosticTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun keepDiagnosticVisible() {
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
  fun inspectPublicPageInTheEmbeddedBrowser() =
    runBlocking<Unit> {
      val page = InstrumentationRegistry.getArguments().getString("publicPage")
      assumeTrue("Only contact a public site when explicitly requested", page != null)
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      assertFalse(
        "Unlock the device before running browser diagnostics",
        context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked,
      )
      val output =
        File(context.getExternalFilesDir(null), "browser-diagnostic-current").apply { mkdirs() }
      Log.i("CastorBrowserTest", "Report: ${output.name}")
      val browser = withContext(Dispatchers.Main) { BrowserResolver(context) }
      browser.prepare()
      val locations = mutableListOf<String>()
      val observing =
        launch(start = CoroutineStart.UNDISPATCHED) {
          browser.page.collect { current ->
            current?.url?.let { if (locations.lastOrNull() != it) locations += it }
          }
        }
      val model = MainScreenViewModel()
      model.uiState.value =
        castingScreenState(
          ready = true,
          castId = "browser-diagnostic",
          phase = CastPhase.Extracting,
          activeCast =
            Cast.newBuilder()
              .setSource(Source.newBuilder().setPages(Source.Pages.newBuilder().addUrls(page!!)))
              .build(),
        )
      compose.setContent { CastorTheme { MainScreen(model, browser, page, {}) } }
      try {
        val pending = async {
          runCatching { browser.resolve(ResolveRequest.newBuilder().addUrls(page).build()) }
        }
        withTimeout(10_000) {
          while (browser.page.value == null) delay(100)
        }
        compose.onNodeWithTag("page-browser").performScrollTo().assertIsDisplayed()
        val result = withTimeout(45_000) { pending.await() }
        // A challenge can keep running in the retained page after extraction found no stream.
        // Inspect its settled document rather than racing its first paint against extraction.
        if (browser.page.value != null) {
          delay(2000)
          if (InstrumentationRegistry.getArguments().getString("focusInput") != "true") {
            val scrolled = CompletableDeferred<Unit>()
            compose.runOnIdle {
              browser.evaluateJavascript(
                "(document.querySelector('[name=\"cf-turnstile-response\"]')?.parentElement || document.querySelector('iframe'))?.scrollIntoView({block:'center'}); true"
              ) {
                scrolled.complete(Unit)
              }
            }
            withTimeout(5000) { scrolled.await() }
          }
          delay(15_000)
        }
        if (browser.page.value != null) {
          compose.onNodeWithTag("page-browser").performScrollTo().assertIsDisplayed()
          if (InstrumentationRegistry.getArguments().getString("focusInput") == "true") {
            suspend fun inspect(): org.json.JSONObject {
              val response = CompletableDeferred<String>()
              compose.runOnIdle {
                browser.evaluateJavascript(
                  """JSON.stringify({scale:devicePixelRatio*visualViewport.scale,input:Array.from(document.querySelectorAll('input')).filter(i=>!['hidden','checkbox','radio','submit','button'].includes(i.type)).map(i=>({x:i.getBoundingClientRect().x,y:i.getBoundingClientRect().y,w:i.clientWidth,h:i.clientHeight})).find(i=>i.w>0&&i.h>0),scrollers:Array.from(document.querySelectorAll('*')).filter(e=>e.scrollHeight>e.clientHeight+10&&e.clientHeight>30).map(e=>({tag:e.tagName,id:e.id,top:e.scrollTop,height:e.clientHeight,scrollHeight:e.scrollHeight,overflow:getComputedStyle(e).overflowY})),focused:document.activeElement?.tagName})"""
                ) {
                  response.complete(it)
                }
              }
              val raw = withTimeout(5_000) { response.await() }
              return org.json.JSONObject(org.json.JSONTokener(raw).nextValue() as String)
            }
            val before = inspect()
            File(output, "focus-before.json").writeText(before.toString())
            val input = before.getJSONObject("input")
            val location = IntArray(2)
            compose.runOnIdle { browser.view.getLocationOnScreen(location) }
            val scale = before.getDouble("scale").toFloat()
            browserTouchGesture(
              location[0] + (input.getDouble("x").toFloat() + 20) * scale,
              location[1] + (input.getDouble("y").toFloat() + 15) * scale,
            )
            delay(1500)
            File(output, "focus-open.json").writeText(inspect().toString())
            val bounds = android.graphics.Rect()
            compose.runOnIdle { assertTrue(browser.view.getGlobalVisibleRect(bounds)) }
            browserTouchGesture(
              bounds.exactCenterX(),
              bounds.bottom - 20f,
              0f,
              -bounds.height() * .6f,
            )
            delay(1000)
            File(output, "focus-scrolled.json").writeText(inspect().toString())
          }
          val before = browser.page.value
          val screenshot =
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
          File(output, "snapshot-state.txt")
            .writeText(
              "Before: ${before?.copy(url = withoutQuery(before.url))}\nAfter: ${browser.page.value?.let { it.copy(url = withoutQuery(it.url)) }}"
            )
          File(output, "browser.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
          }
          val document = CompletableDeferred<String>()
          compose.runOnIdle {
            val view = browser.view
            assertTrue("The browser must be attached to the screen", view.isAttachedToWindow)
            assertTrue("The browser must have a real viewport", view.width > 0 && view.height > 0)
            Log.i(
              "CastorBrowserTest",
              "Attached=${view.isAttachedToWindow}, shown=${view.isShown}, viewport=${view.width}x${view.height}",
            )
            browser.evaluateJavascript(
              "JSON.stringify({title:document.title,href:location.origin+location.pathname,width:innerWidth,height:innerHeight,ready:document.readyState,text:document.body.innerText.substring(0,1500),turnstileResponsePresent:!!document.querySelector('[name=\"cf-turnstile-response\"]'),turnstileResponseLength:document.querySelector('[name=\"cf-turnstile-response\"]')?.value?.length??0,frames:Array.from(document.querySelectorAll('iframe')).map(f=>({src:f.src.split(/[?#]/)[0],title:f.title,width:f.clientWidth,height:f.clientHeight}))})"
            ) {
              Log.i("CastorBrowserTest", "Page: $it")
              document.complete(it)
            }
          }
          File(output, "document.json").writeText(withTimeout(5000) { document.await() })
        }
        File(output, "request-errors.json")
          .writeText(
            org.json.JSONObject(browser.requestErrors.mapKeys { withoutQuery(it.key) }).toString()
          )
        File(output, "result.txt")
          .writeText(
            result.fold(
              { "Captured ${it.streamsCount} candidate(s)" },
              { "Extraction failed: ${it.message}" },
            )
          )
      } finally {
        observing.cancelAndJoin()
        File(output, "locations.json")
          .writeText(org.json.JSONArray(locations.map(::withoutQuery)).toString())
        withContext(Dispatchers.Main) { browser.close() }
      }
    }

  private fun withoutQuery(url: String): String = url.substringBefore('?').substringBefore('#')
}
