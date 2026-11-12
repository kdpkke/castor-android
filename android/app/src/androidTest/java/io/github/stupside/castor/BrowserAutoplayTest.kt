package io.github.stupside.castor

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import castor.scraping.v1.Scraping.ResolveRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// Pages whose player loads its stream only after a tap on its play control, in a frame of another origin.
class BrowserAutoplayTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val served = CopyOnWriteArrayList<String>()
  private val top = server()
  private val frame = server()
  private val sessions = mutableListOf<BrowserPageSession>()

  @Before
  fun prepareBrowserProtection() =
    runBlocking<Unit> { withContext(Dispatchers.Main) { BrowserEngine.get(context).ready().await() } }

  @After
  fun close() =
    runBlocking<Unit> {
      withContext(Dispatchers.Main) { sessions.forEach { it.close() } }
      top.shutdown()
      frame.shutdown()
    }

  @Test
  fun aPlayerThatWaitsForAClickIsStartedWhenNoStreamCame() = runBlocking {
    val response = session().resolve(page("click"), interactive = false)
    assertEquals(frame.url("/live/index.m3u8").toString(), response.streamsList.single().stream.url)
    assertFalse("The page must stay where it was: $served", "/elsewhere" in served)
  }

  @Test
  fun aPlayerThatTakesOnlyARealTapIsTappedWhileThePageIsOnScreen() = runBlocking {
    val session = session()
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      assertEquals(
        "The page must be on screen; is the device locked?",
        Lifecycle.State.RESUMED,
        scenario.state,
      )
      scenario.onActivity { activity ->
        activity.setContentView(
          session.attachView(activity),
          ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
      }
      val response = session.resolve(page("tap"), interactive = false)
      assertEquals(frame.url("/live/index.m3u8").toString(), response.streamsList.single().stream.url)
      withContext(Dispatchers.Main) { session.detachView() }
    }
  }

  @Test
  fun aPlayerCoveredByALinkIsNeitherTappedNorLeftFor() = runBlocking {
    val session = session()
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        activity.setContentView(
          session.attachView(activity),
          ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
      }
      val failure = runCatching { session.resolve(page("tap", covered = true), interactive = false) }
      assertTrue("Nothing plays, so nothing may be found", failure.isFailure)
      withContext(Dispatchers.Main) { session.detachView() }
    }
    assertFalse("A link must never be followed: $served", "/elsewhere" in served)
    assertFalse("The player was never tapped: $served", "/live/index.m3u8" in served)
  }

  private suspend fun session(): BrowserPageSession =
    withContext(Dispatchers.Main) {
        BrowserPageSession(context, MutableStateFlow(false), MutableStateFlow<BrowserPage?>(null))
      }
      .also { sessions += it }

  // A page that embeds the player of another origin. `wants` is the gesture the player starts on: any click, or
  // only a real tap. A covered page lays a link over the middle of its player.
  private fun page(wants: String, covered: Boolean = false): ResolveRequest {
    val player = frame.url("/player").newBuilder().addQueryParameter("wants", wants).build()
    val address =
      top.url("/page").newBuilder().addQueryParameter("frame", player.toString()).apply {
        if (covered) addQueryParameter("covered", "1")
      }
    return ResolveRequest.newBuilder().addUrls(address.build().toString()).build()
  }

  private fun server(): MockWebServer =
    MockWebServer().apply {
      dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!
            served += url.encodedPath
            fun html(body: String) = MockResponse().setHeader("Content-Type", "text/html").setBody(body)
            return when (url.encodedPath) {
              "/page" -> {
                val player = url.queryParameter("frame")!!.replace("&", "&amp;")
                val cover =
                  if (url.queryParameter("covered") == null) ""
                  else
                    "<a href=\"/elsewhere\" style=\"position:absolute;left:0;top:0;width:640px;height:360px\">ad</a>"
                html(
                  "<!doctype html><meta name=viewport content='width=device-width'>" +
                    "<body style='margin:0'><iframe src=\"$player\" width=640 height=360 style='border:0'></iframe>$cover"
                )
              }
              "/player" -> html(PLAYER)
              "/live/index.m3u8" ->
                MockResponse()
                  .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                  .setBody("#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXTINF:3,\nchunk-1.ts\n")
              else -> html("<!doctype html><p>elsewhere</p>")
            }
          }
        }
      start()
    }
}

// A play overlay over the whole frame. It loads the stream only on the gesture its query asks for: "click", any
// click, or "tap", a click the browser marks as a person's.
private const val PLAYER =
  """<!doctype html><title>player</title>
<style>html,body{margin:0;height:100%}#cover{position:absolute;inset:0;background:#111}</style>
<div id="cover" class="poster"></div>
<script>
const wants = new URLSearchParams(location.search).get('wants');
document.getElementById('cover').addEventListener('click', event => {
  if (wants === 'tap' && !event.isTrusted) return;
  fetch('/live/index.m3u8').then(response => response.text());
});
</script>"""
