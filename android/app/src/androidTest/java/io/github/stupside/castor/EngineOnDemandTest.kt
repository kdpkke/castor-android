package io.github.stupside.castor

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import castor.v1.CastOuterClass.*
import castor.v1.DeviceOuterClass.DeviceType
import castor.v1.DeviceOuterClass.Target
import castor.v1.StreamOuterClass.Stream
import io.github.stupside.castor.data.DataRepository
import io.github.stupside.castor.ui.main.CastOptions
import io.github.stupside.castor.ui.main.MainScreenViewModel
import io.github.stupside.castor.ui.main.SavedTv
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EngineOnDemandTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private var saved: Map<String, Any?> = emptyMap()

  // Every entry of the preferences is set aside, so a test leaves no choice behind and loses none of the user's.
  @Before
  fun prepare() = runBlocking<Unit> {
    saved = preferences().all
    preferences().edit().clear().commit()
    grantPermissions()
    EngineHost.lingerMillis = 1_000
    stopEngine()
  }

  // The preferences come back even when stopping the engine fails, so the user's choices are never lost.
  @After
  fun restorePreferences() {
    try {
      runBlocking<Unit> { stopEngine() }
    } finally {
      EngineHost.lingerMillis = 15_000
      val edit = preferences().edit().clear()
      for ((key, value) in saved) {
        when (value) {
          is String -> edit.putString(key, value)
          is Boolean -> edit.putBoolean(key, value)
          is Int -> edit.putInt(key, value)
          is Long -> edit.putLong(key, value)
          is Float -> edit.putFloat(key, value)
          is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
      }
      edit.commit()
    }
  }

  // With nothing to do, the screen is ready without any engine running.
  @Test
  fun withNothingToDoThereIsNoEngine() = runBlocking<Unit> {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      delay(3_000)
      assertNoEngine()
      val screen = model(scenario)
      assertTrue(screen.uiState.value.ready)
      assertNull(screen.uiState.value.error)
    }
  }

  // A request starts an engine, and the engine stops by itself once the request is over.
  @Test
  fun aRequestStartsTheEngineAndItStopsByItselfAfterwards() = runBlocking<Unit> {
    ActivityScenario.launch(MainActivity::class.java).use {
      val hold = EngineHost.hold()
      try {
        until(30_000, "The engine did not start for a request") { running() }
        assertEngine()
        val firstEndpoint = EngineHost.endpoint.value!!
        val firstPid = pidList().single()
        assertTrue(
          "A new engine has no casts",
          DataRepository(firstEndpoint).casts().isEmpty(),
        )
        hold.close()
        awaitNoEngine()

        // A later request starts another engine, not the one that stopped.
        val second = EngineHost.hold()
        try {
          until(30_000, "The second request did not start an engine") { running() }
          assertEngine()
          assertNotSame(firstEndpoint, EngineHost.endpoint.value)
          assertNotEquals(firstPid, pidList().single())
        } finally {
          second.close()
        }
        awaitNoEngine()
      } finally {
        hold.close()
      }
    }
  }

  // A request that arrives while the engine is stopping is served by the next engine, not lost.
  @Test
  fun aRequestMadeWhileTheEngineStopsIsServedByTheNextOne() = runBlocking<Unit> {
    ActivityScenario.launch(MainActivity::class.java).use {
      val first = EngineHost.hold()
      var second: AutoCloseable? = null
      try {
        until(30_000, "The engine did not start for a request") { running() }
        first.close()
        withTimeout(15_000) {
          while (EngineHost.phase != EngineLifecycle.Phase.Stopping) delay(1)
        }
        second = EngineHost.hold()
        until(30_000, "The request made while stopping was not served") { running() }
        assertEngine()
        assertTrue(
          "The next engine must answer casts",
          DataRepository(EngineHost.endpoint.value!!).casts().isEmpty(),
        )
        assertNull(EngineHost.failure.value)
        second.close()
        awaitNoEngine()
      } finally {
        first.close()
        second?.close()
      }
    }
  }

  // While either cast is live the engine stays, with the same process, until the last one has ended.
  @Test
  fun twoCastsKeepTheEngineUntilBothHaveEndedOldestFirst() =
    twoCastsKeepTheEngineUntilBothHaveEnded(oldestFirst = true)

  // The same guarantee when the newer cast ends first.
  @Test
  fun twoCastsKeepTheEngineUntilBothHaveEndedNewestFirst() =
    twoCastsKeepTheEngineUntilBothHaveEnded(oldestFirst = false)

  private fun twoCastsKeepTheEngineUntilBothHaveEnded(oldestFirst: Boolean) = runBlocking<Unit> {
    val sample = encodeLongSample()
    val media = mediaServer(sample.readBytes())
    val firstTv = receiver("Fixture TV one", "uuid:castor-android-fixture-one")
    val secondTv = receiver("Fixture TV two", "uuid:castor-android-fixture-two")
    try {
      ActivityScenario.launch(MainActivity::class.java).use {
        val hold = EngineHost.hold()
        try {
          until(30_000, "The engine did not start for the casts") { running() }
          val pid = pidList().single()
          val api = DataRepository(EngineHost.endpoint.value!!)
          val oldest = api.cast(castRequest(firstTv, media.url("/first.mp4").toString()))
          val newest = api.cast(castRequest(secondTv, media.url("/second.mp4").toString()))
          val both = setOf(oldest, newest)
          until(30_000, "The engine did not list both casts") {
            api.casts().map { it.id }.toSet() == both
          }
          delay(3_000)
          hold.close()
          delay(4_000)
          assertEngine()
          assertEquals(pid, pidList().single())

          val (firstStop, lastStop) =
            if (oldestFirst) Pair(oldest, newest) else Pair(newest, oldest)
          api.stop(firstStop)
          until(15_000, "The first stopped cast is still listed") {
            api.casts().map { it.id }.toSet() == setOf(lastStop)
          }
          delay(4_000)
          assertEngine()
          assertEquals(pid, pidList().single())

          api.stop(lastStop)
          awaitNoEngine()
        } finally {
          hold.close()
        }
      }
    } finally {
      firstTv.shutdown()
      secondTv.shutdown()
      media.shutdown()
      sample.delete()
    }
  }

  // An engine that dies is reported, and it is not restarted by the request still held; only a new request starts one.
  @Test
  fun anEngineThatDiesIsReportedAndOnlyANewRequestStartsAnother() = runBlocking<Unit> {
    ActivityScenario.launch(MainActivity::class.java).use {
      val first = EngineHost.hold()
      var second: AutoCloseable? = null
      try {
        until(30_000, "The engine did not start for a request") { running() }
        val pid = pidList().single()
        android.os.Process.killProcess(pid.toInt())
        // Stopped, reported and with no process, service or notification left, all within 10 s.
        until(10_000, "The dead engine was not reported and cleaned up") {
          noEngine() && EngineHost.failure.value != null
        }
        assertNoEngine()
        assertNotNull(EngineHost.failure.value)

        delay(3_000)
        assertEquals(EngineLifecycle.Phase.Stopped, EngineHost.phase)
        assertEquals("", enginePids())

        second = EngineHost.hold()
        assertNull(EngineHost.failure.value)
        until(30_000, "The new request did not start an engine") { running() }
        assertEngine()
        assertNotEquals(pid, pidList().single())
        second.close()
        first.close()
        awaitNoEngine()
      } finally {
        first.close()
        second?.close()
      }
    }
  }

  // A stop asked by the user holds even while a request is open, until a new request asks again.
  @Test
  fun stoppedByTheUserItStaysStoppedUntilAskedAgain() = runBlocking<Unit> {
    ActivityScenario.launch(MainActivity::class.java).use {
      val first = EngineHost.hold()
      var second: AutoCloseable? = null
      try {
        until(30_000, "The engine did not start for a request") { running() }
        EngineHost.stopNow()
        until(10_000, "The engine did not stop when the user asked") {
          EngineHost.phase == EngineLifecycle.Phase.Stopped
        }
        assertNull(EngineHost.failure.value)

        delay(3_000)
        assertNoEngine()
        assertNull(EngineHost.failure.value)

        second = EngineHost.hold()
        until(30_000, "The new request did not start an engine") { running() }
        assertEngine()
        second.close()
        first.close()
        awaitNoEngine()
      } finally {
        first.close()
        second?.close()
      }
    }
  }

  // The screen searches for its saved TV by itself, without the engine: that runs only for casts.
  @Test
  fun theScreenSearchesForItsTvWithoutTheEngine() = runBlocking<Unit> {
    CastOptions(context)
      .television(
        SavedTv(
          "Chromecast fixture",
          "192.0.2.10:8009",
          DeviceType.DEVICE_TYPE_CHROMECAST,
          id = "chromecast:fixture",
        )
      )
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      val screen = model(scenario)
      until(30_000, "The screen did not search for its TV") {
        screen.uiState.value.let { it.discovering || it.discoveryComplete }
      }
      // No engine at any moment of the search, nor after it.
      until(90_000, "The search neither ended nor failed") {
        assertNoEngine()
        screen.uiState.value.let { it.discoveryComplete || it.discoveryError != null }
      }
      delay(3_000)
      assertNoEngine()
      screen.uiState.value.let {
        assertTrue(it.ready)
        assertNull(it.failure)
        assertNotEquals("Casting engine unavailable", it.idleStatus)
      }

      // The screen keeps the finished search: recreating it does not start the engine again.
      scenario.recreate()
      delay(3_000)
      assertNoEngine()
      assertTrue(model(scenario).uiState.value.discoveryComplete)
    }
  }

  // A live cast keeps the engine after its screen has gone to the background and been closed, and a new screen finds it.
  @Test
  fun aLiveCastOutlivesTheScreenInTheBackgroundAndIsFoundAgain() = runBlocking<Unit> {
    val sample = encodeLongSample()
    val media = mediaServer(sample.readBytes())
    val tv = receiver("Fixture TV", "uuid:castor-android-fixture-background")
    val screens = mutableListOf<ActivityScenario<MainActivity>>()
    try {
      val scenario = ActivityScenario.launch(MainActivity::class.java).also { screens += it }
      until(10_000, "The screen is not in front") { scenario.state == Lifecycle.State.RESUMED }
      val screen = model(scenario)
      // The cast is asked the way the screen asks it; nothing else in this test holds the engine.
      scenario.onActivity {
        screen.cast(
          url = media.url("/long.mp4").toString(),
          page = false,
          target = receiverTarget(tv),
          maxHeight = "90",
          subtitles = "off",
          receiverName = "Fixture TV",
        )
      }
      until(60_000, "The live cast did not start an engine and a session") {
        val sessions = screen.uiState.value.sessions
        sessions.size == 1 && running() &&
          listedCastIds() == listOf(sessions.single().cast.id)
      }
      val castId = screen.uiState.value.sessions.single().cast.id
      val pid = pidList().single()
      assertEngine()

      // The app goes to the background for real: the activity is stopped, and the engine's cast goes on.
      assertTrue(
        "The system refused to go home",
        instrumentation.uiAutomation.performGlobalAction(
          android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
        ),
      )
      until(10_000, "The screen did not go to the background") {
        scenario.state == Lifecycle.State.CREATED
      }
      delay(6_000)
      assertEngine()
      assertEquals(pid, pidList().single())
      assertEquals(listOf(castId), listedCastIds())

      // Closing the screen leaves only the engine's own cast to keep the engine running.
      scenario.close()
      delay(4_000)
      assertEngine()
      assertEquals(pid, pidList().single())
      assertEquals(listOf(castId), listedCastIds())

      // A new screen finds the same cast, with the same engine.
      val again = ActivityScenario.launch(MainActivity::class.java).also { screens += it }
      until(10_000, "The new screen is not in front") { again.state == Lifecycle.State.RESUMED }
      val reopened = model(again)
      assertNotSame(screen, reopened)
      until(30_000, "The new screen did not find the live cast") {
        reopened.uiState.value.sessions.any { it.cast.id == castId } &&
          reopened.uiState.value.castId == castId
      }
      assertEngine()
      assertEquals(pid, pidList().single())

      // Stopping the cast from the new screen lets the engine go.
      again.onActivity { reopened.stop() }
      until(30_000, "The stopped cast is still listed") {
        val endpoint = EngineHost.endpoint.value ?: return@until true
        runCatching { DataRepository(endpoint).casts().isEmpty() }.getOrDefault(true)
      }
      awaitNoEngine()
    } finally {
      screens.forEach { screen -> runCatching { screen.close() } }
      tv.shutdown()
      media.shutdown()
      sample.delete()
    }
  }

  // Two screens can exist at once; when one closes, the browser the other still uses keeps working.
  @Test
  fun aScreenThatClosesLeavesTheBrowserOfAnotherScreenWorking() = runBlocking<Unit> {
    // The browser needs its protection before any page is shown, as in BrowserHostTest.
    withContext(Dispatchers.Main) { BrowserEngine.get(context).ready().await() }
    val page = MockWebServer()
    page.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          MockResponse().setHeader("Content-Type", "text/html").setBody("<p>Fixture</p>")
      }
    page.start()
    val address = page.url("/page").toString()
    val screens = mutableListOf<ActivityScenario<MainActivity>>()
    var second: MainActivity? = null
    try {
      val a = ActivityScenario.launch(MainActivity::class.java).also { screens += it }
      // A link shared to the app while it is open starts a second screen in its own task, as it does for a user;
      // the first screen stays.
      val shareLink =
        Intent(context, MainActivity::class.java)
          .setAction(Intent.ACTION_SEND)
          .setType("text/plain")
          .putExtra(Intent.EXTRA_TEXT, "https://example.org/watch")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
      val started = instrumentation.startActivitySync(shareLink) as MainActivity
      second = started
      until(10_000, "The second screen is not in front") {
        started.lifecycle.currentState == Lifecycle.State.RESUMED
      }
      var first: MainActivity? = null
      a.onActivity { first = it }
      assertNotSame(first, started)
      assertFalse("The first screen must stay alive", checkNotNull(first).isDestroyed)
      var firstModel: MainScreenViewModel? = null
      var secondModel: MainScreenViewModel? = null
      instrumentation.runOnMainSync {
        firstModel = ViewModelProvider(checkNotNull(first))[MainScreenViewModel::class.java]
        secondModel = ViewModelProvider(started)[MainScreenViewModel::class.java]
      }
      assertNotSame(firstModel, secondModel)

      // Learns which browser the screens use, without staying as an owner of it.
      val probe = Any()
      val shared =
        withContext(Dispatchers.Main) {
          try {
            BrowserHost.acquire(probe, context)
          } finally {
            BrowserHost.release(probe)
          }
        }

      // The second screen closes; the first one is in front again.
      instrumentation.runOnMainSync { started.finish() }
      until(10_000, "The second screen did not close") { started.isDestroyed }
      a.moveToState(Lifecycle.State.RESUMED)
      until(10_000, "The first screen is not in front again") { a.state == Lifecycle.State.RESUMED }

      // The first screen's browser still shows a page.
      withContext(Dispatchers.Main) { shared.showPage(address) }
      until(30_000, "The first screen's browser did not show the page") {
        shared.page.value?.let { it.url == address && !it.loading } == true
      }
      assertEquals(address, shared.page.value?.url)
      val request = withContext(Dispatchers.IO) { page.takeRequest(5, TimeUnit.SECONDS) }
      assertEquals("/page", request?.path)
      assertNoEngine()

      // A recreated screen keeps the same browser.
      a.recreate()
      val probeAgain = Any()
      val again =
        withContext(Dispatchers.Main) {
          try {
            BrowserHost.acquire(probeAgain, context)
          } finally {
            BrowserHost.release(probeAgain)
          }
        }
      assertSame(shared, again)

      // The last screen closes; a browser used afterwards is a new one.
      a.close()
      val probeFresh = Any()
      val fresh =
        withContext(Dispatchers.Main) {
          try {
            BrowserHost.acquire(probeFresh, context)
          } finally {
            BrowserHost.release(probeFresh)
          }
        }
      assertNotSame(shared, fresh)
    } finally {
      runCatching {
        instrumentation.runOnMainSync { second?.let { if (!it.isDestroyed) it.finish() } }
      }
      screens.forEach { screen -> runCatching { screen.close() } }
      page.shutdown()
    }
  }

  // The ids of the casts the running engine lists.
  private suspend fun listedCastIds(): List<String> =
    DataRepository(checkNotNull(EngineHost.endpoint.value) { "No engine answers" })
      .casts()
      .map { it.id }

  private fun model(scenario: ActivityScenario<MainActivity>): MainScreenViewModel {
    var model: MainScreenViewModel? = null
    scenario.onActivity { model = ViewModelProvider(it)[MainScreenViewModel::class.java] }
    return checkNotNull(model)
  }

  private fun preferences() = context.getSharedPreferences("casting-options", 0)

  private fun grantPermissions() {
    if (Build.VERSION.SDK_INT >= 33)
      instrumentation.uiAutomation
        .executeShellCommand(
          "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
        )
        .close()
    if (Build.VERSION.SDK_INT >= 37)
      instrumentation.uiAutomation
        .executeShellCommand(
          "pm grant ${context.packageName} android.permission.ACCESS_LOCAL_NETWORK"
        )
        .close()
  }

  private suspend fun stopEngine() {
    context.stopService(Intent(context, CastorService::class.java))
    until(20_000, "The engine did not stop") { EngineHost.phase == EngineLifecycle.Phase.Stopped }
  }

  // Polls every 20 ms until the condition holds; a timeout fails with the engine's state.
  private suspend fun until(timeoutMillis: Long, message: String, condition: suspend () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
      if (System.currentTimeMillis() >= deadline) fail("$message; ${state()}")
      delay(20)
    }
  }

  private fun running(): Boolean =
    EngineHost.phase == EngineLifecycle.Phase.Running && EngineHost.endpoint.value != null

  private suspend fun awaitNoEngine(timeoutMillis: Long = 15_000) {
    until(timeoutMillis, "The engine did not stop") { noEngine() }
    assertNoEngine()
  }

  private fun noEngine(): Boolean = runCatching { assertNoEngine() }.isSuccess

  private fun assertNoEngine() {
    assertEquals("The engine phase must be Stopped", EngineLifecycle.Phase.Stopped, EngineHost.phase)
    assertNull("The engine must have no endpoint", EngineHost.endpoint.value)
    assertEquals("No engine process may run", "", enginePids())
    assertFalse("The engine service must not run in the foreground", serviceRuns())
    assertFalse("The engine notification must be gone", notified())
  }

  private fun assertEngine() {
    assertEquals("The engine phase must be Running", EngineLifecycle.Phase.Running, EngineHost.phase)
    assertNotNull("The engine must have an endpoint", EngineHost.endpoint.value)
    assertEquals("Exactly one engine process must run", 1, pidList().size)
    assertTrue("The engine service must run in the foreground", serviceRuns())
    assertTrue("The engine notification must be shown", notified())
  }

  private fun state(): String =
    "phase=${EngineHost.phase}, endpoint=${EngineHost.endpoint.value != null}, " +
      "failure=${EngineHost.failure.value}, pids=[${enginePids()}], " +
      "service=${serviceRuns()}, notification=${notified()}"

  private fun enginePids(): String =
    ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("pidof libcastor.so")
      )
      .bufferedReader()
      .use { it.readText() }
      .trim()

  private fun pidList(): List<String> = enginePids().split(Regex("\\s+")).filter { it.isNotEmpty() }

  @Suppress("DEPRECATION")
  private fun serviceRuns(): Boolean =
    context
      .getSystemService(ActivityManager::class.java)
      .getRunningServices(Int.MAX_VALUE)
      .any { it.service.className == CastorService::class.java.name && it.foreground }

  private fun notified(): Boolean =
    context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 1 }

  // A lavfi clip made by the bundled ffmpeg; long enough that the casts do not reach its end during the test.
  private fun encodeLongSample(): File {
    val sample = File.createTempFile("castor-long", ".mp4", context.cacheDir)
    val encoder =
      ProcessBuilder(
          "${context.applicationInfo.nativeLibraryDir}/libffmpeg.so",
          "-y",
          "-hide_banner",
          "-f",
          "lavfi",
          "-i",
          "testsrc2=size=320x180:rate=25",
          "-f",
          "lavfi",
          "-i",
          "sine=frequency=1000:sample_rate=48000",
          "-t",
          "120",
          "-c:v",
          "libx264",
          "-preset",
          "ultrafast",
          "-profile:v",
          "main",
          "-pix_fmt",
          "yuv420p",
          "-c:a",
          "aac",
          "-movflags",
          "+faststart",
          sample.path,
        )
        .apply { environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir }
        .redirectErrorStream(true)
        .start()
    val log = encoder.inputStream.bufferedReader().readText()
    assertEquals(log, 0, encoder.waitFor())
    assertTrue(sample.length() > 0)
    return sample
  }

  // Serves the clip to the engine, honouring range requests like the origin of a real stream.
  private fun mediaServer(bytes: ByteArray): MockWebServer =
    MockWebServer().apply {
      dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range")
            val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            return MockResponse()
              .setHeader("Content-Type", "video/mp4")
              .setHeader("Accept-Ranges", "bytes")
              .setResponseCode(if (range != null) 206 else 200)
              .apply {
                if (range != null)
                  setHeader("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
              }
              .setBody(Buffer().write(bytes, start, bytes.size - start))
          }
        }
      start()
    }

  // A DLNA receiver with its own identity. It plays for as long as it is asked to, and reads the media it is given.
  private fun receiver(name: String, udn: String): MockWebServer {
    val server = MockWebServer()
    var uri = ""
    val playing = AtomicLong()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          if (request.path == "/description.xml") return xml(description(name, udn))
          val soap = request.getHeader("SOAPAction").orEmpty().trim('"')
          val action = soap.substringAfter('#')
          val type = soap.substringBefore('#')
          val body =
            when (action) {
              "GetProtocolInfo" ->
                "<Source/><Sink>http-get:*:video/mp2t:DLNA.ORG_PN=AVC_TS_HD_50_AC3_ISO,http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_MP_HD_AAC</Sink>"
              "SetAVTransportURI" -> {
                uri =
                  Regex("<CurrentURI>(.*?)</CurrentURI>")
                    .find(request.body.readUtf8())!!
                    .groupValues[1]
                    .replace("&amp;", "&")
                ""
              }
              "Play" -> {
                playing.set(SystemClock.elapsedRealtime())
                thread(isDaemon = true) { pull(uri) }
                ""
              }
              "GetTransportInfo" ->
                "<CurrentTransportState>PLAYING</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>"
              "GetPositionInfo" -> {
                val seconds =
                  if (playing.get() == 0L) 0L
                  else (SystemClock.elapsedRealtime() - playing.get()) / 1000
                val position = "00:%02d:%02d".format(seconds / 60, seconds % 60)
                "<Track>1</Track><TrackDuration>00:02:00</TrackDuration><TrackMetaData/><TrackURI>${uri.replace("&", "&amp;")}</TrackURI><RelTime>$position</RelTime><AbsTime>$position</AbsTime><RelCount>0</RelCount><AbsCount>0</AbsCount>"
              }
              "GetMediaInfo" -> "<CurrentURI>${uri.replace("&", "&amp;")}</CurrentURI>"
              "Stop",
              "Pause" -> ""
              else -> return MockResponse().setResponseCode(500)
            }
          return xml(
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response xmlns:u="$type">$body</u:${action}Response></s:Body></s:Envelope>"""
          )
        }
      }
    server.start()
    return server
  }

  // Reads the media the way a receiver does, a little at a time, and ends when the engine closes it.
  private fun pull(address: String) {
    try {
      val connection = URL(address).openConnection() as HttpURLConnection
      connection.readTimeout = 30_000
      try {
        connection.inputStream.use { input ->
          val buffer = ByteArray(4096)
          while (input.read(buffer) >= 0) Thread.sleep(20)
        }
      } finally {
        connection.disconnect()
      }
    } catch (_: java.io.IOException) {}
  }

  // The DLNA target of a receiver, pinned by the address of its description.
  private fun receiverTarget(receiver: MockWebServer): Target =
    Target.newBuilder()
      .setPinned(
        Target.Pinned.newBuilder()
          .setType(DeviceType.DEVICE_TYPE_DLNA)
          .setAddress(receiver.url("/description.xml").toString())
      )
      .build()

  private fun castRequest(receiver: MockWebServer, source: String): CastRequest =
    CastRequest.newBuilder()
      .setTarget(receiverTarget(receiver))
      .setSource(Source.newBuilder().setStream(Stream.newBuilder().setUrl(source)))
      .setPreferences(Preferences.newBuilder().setMaxHeight(90))
      .build()

  private fun description(name: String, udn: String) =
    """<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>$name</friendlyName><UDN>$udn</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><SCPDURL>/cm.xml</SCPDURL><controlURL>/cm</controlURL><eventSubURL>/events</eventSubURL></service><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/av.xml</SCPDURL><controlURL>/av</controlURL><eventSubURL>/events</eventSubURL></service></serviceList></device></root>"""

  private fun xml(body: String) =
    MockResponse().setHeader("Content-Type", "text/xml; charset=utf-8").setBody(body)
}
