package io.github.stupside.castor.ui.main

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import castor.scraping.v1.Scraping.ResolveRequest
import castor.v1.CastOuterClass.Cast
import castor.v1.CastOuterClass.Source
import io.github.stupside.castor.BrowserResolver
import io.github.stupside.castor.theme.CastorTheme
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

// Manual diagnostics only: never contact external websites in the fixture suite.
class BrowserSiteDiagnosticTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun keepDiagnosticVisible() {
    compose.runOnUiThread {
      compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        File(context.getExternalFilesDir(null), "browser-diagnostic-${System.currentTimeMillis()}")
          .apply { mkdirs() }
      Log.i("CastorBrowserTest", "Report: ${output.name}")
      val browser = withContext(Dispatchers.Main) { BrowserResolver(context) }
      browser.prepare()
      val firstPaint = CompletableDeferred<Unit>()
      compose.runOnIdle {
        browser.view.session!!.contentDelegate =
          object : org.mozilla.geckoview.GeckoSession.ContentDelegate {
            override fun onFirstContentfulPaint(session: org.mozilla.geckoview.GeckoSession) {
              firstPaint.complete(Unit)
            }
          }
      }
      val locations = mutableListOf<String>()
      val observing =
        launch(start = CoroutineStart.UNDISPATCHED) {
          browser.page.collect { current ->
            current?.url?.let { if (locations.lastOrNull() != it) locations += it }
          }
        }
      val model = MainScreenViewModel()
      model.uiState.value =
        MainScreenUiState(
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
        // A page can extract successfully before all iframes finish loading. Snapshot on first
        // actual paint, before resolution closes the session, rather than waiting for network idle.
        withTimeout(30_000) {
          kotlinx.coroutines.selects.select<Unit> {
            firstPaint.onAwait {}
            pending.onAwait {}
          }
        }
        if (browser.page.value != null) {
          val before = browser.page.value
          val screenshot =
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
          File(output, "snapshot-state.txt")
            .writeText(
              "Before: $before\nAfter: ${browser.page.value}\nPainted: ${firstPaint.isCompleted}"
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
              "JSON.stringify({title:document.title,href:location.href,width:innerWidth,height:innerHeight,ready:document.readyState,scrollY:scrollY,bodyOpacity:getComputedStyle(document.body).opacity,stack:document.elementsFromPoint(innerWidth/2,innerHeight/2).slice(0,8).map(e=>({tag:e.tagName,id:e.id,cls:e.className,src:e.src,opacity:getComputedStyle(e).opacity,visibility:getComputedStyle(e).visibility,z:getComputedStyle(e).zIndex})),text:document.body.innerText.substring(0,1500),frames:Array.from(document.querySelectorAll('iframe')).map(f=>({src:f.src,width:f.clientWidth,height:f.clientHeight}))})"
            ) {
              Log.i("CastorBrowserTest", "Page: $it")
              document.complete(it)
            }
          }
          File(output, "document.json").writeText(withTimeout(5000) { document.await() })
        }
        val result = pending.await()
        File(output, "result.txt")
          .writeText(
            result.fold(
              { "Captured ${it.streamsCount} candidate(s)" },
              { "Extraction failed: ${it.message}" },
            )
          )
      } finally {
        observing.cancelAndJoin()
        File(output, "locations.json").writeText(org.json.JSONArray(locations).toString())
        withContext(Dispatchers.Main) { browser.close() }
      }
    }
}
