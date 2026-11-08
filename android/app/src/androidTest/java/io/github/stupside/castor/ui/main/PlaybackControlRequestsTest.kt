package io.github.stupside.castor.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import castor.v1.CastOuterClass.*
import castor.v1.DeviceOuterClass.*
import com.google.protobuf.Duration
import io.github.stupside.castor.data.Rpc
import io.github.stupside.castor.R
import io.github.stupside.castor.theme.CastorTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher as ServerDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaybackControlRequestsTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun liveOptimizationIsReportedWithoutChangingTheRequestedQuality() {
    val savedQuality = CastOptions(compose.activity).height()
    val fixture = Fixture(optimizedInWatch = true)
    try {
      compose.setContent { CastorTheme { MainScreen(fixture.model, null, "", {}) } }
      fixture.ready()
      compose.waitUntil(5000) {
        fixture.model.uiState.value.activeCast?.status?.casting?.optimizedLive == true
      }
      compose.onNodeWithText(compose.activity.getString(R.string.live_optimized_automatically)).assertExists()
      assertEquals(savedQuality, CastOptions(compose.activity).height())
      assertEquals("existing-player", fixture.model.uiState.value.castId)
      assertEquals(0, fixture.submissions.get())
      assertEquals(0, fixture.stops.get())
    } finally {
      fixture.close()
    }
  }

  @Test
  fun rapidSeekKeepsOnlyTheLatestPendingTargetOnTheSameCast() {
    val fixture = Fixture(blockFirstSeek = true)
    try {
      fixture.ready()
      compose.runOnIdle { fixture.model.seekBy(10_000) }
      assertTrue(fixture.firstSeek.await(5, TimeUnit.SECONDS))
      compose.runOnIdle { repeat(8) { fixture.model.seekBy(10_000) } }
      assertEquals(listOf(40_000L), fixture.seeks.toList())
      fixture.releaseSeek.countDown()
      compose.waitUntil(5000) {
        fixture.seeks.size == 2 && fixture.model.uiState.value.playback.seekTargetMs == null
      }
      assertEquals(listOf(40_000L, 120_000L), fixture.seeks.toList())
      assertEquals(120_000L, fixture.model.uiState.value.playback.positionMs)
      assertEquals("existing-player", fixture.model.uiState.value.castId)
      assertEquals(0, fixture.submissions.get())
      assertEquals(0, fixture.stops.get())
    } finally {
      fixture.close()
    }
  }

  @Test
  fun pauseDoesNotCancelAnInFlightSeekAndWorksAfterItCompletes() {
    val fixture = Fixture(blockFirstSeek = true)
    try {
      fixture.ready()
      compose.runOnIdle { fixture.model.seekBy(10_000) }
      assertTrue(fixture.firstSeek.await(5, TimeUnit.SECONDS))
      compose.runOnIdle { fixture.model.togglePlaybackPause() }
      assertEquals(40_000L, fixture.model.uiState.value.playback.seekTargetMs)
      assertFalse(fixture.model.uiState.value.playback.changingPause)
      assertEquals(1, fixture.controls.get())
      fixture.releaseSeek.countDown()
      compose.waitUntil(5000) { fixture.model.uiState.value.playback.seekTargetMs == null }
      assertEquals(40_000L, fixture.model.uiState.value.playback.positionMs)
      compose.runOnIdle { fixture.model.togglePlaybackPause() }
      compose.waitUntil(5000) {
        fixture.model.uiState.value.playback.paused &&
          !fixture.model.uiState.value.playback.changingPause
      }
      assertEquals(2, fixture.controls.get())
      assertEquals(0, fixture.stops.get())
      assertEquals("existing-player", fixture.model.uiState.value.castId)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun aFailedControlLeavesTheCastActiveAndCanBeRetried() {
    val fixture = Fixture()
    try {
      fixture.ready()
      fixture.failNext.set(true)
      compose.runOnIdle { fixture.model.togglePlaybackPause() }
      compose.waitUntil(5000) { fixture.model.uiState.value.playback.error != null }
      assertEquals("existing-player", fixture.model.uiState.value.castId)
      assertEquals(CastPhase.Casting, fixture.model.uiState.value.phase)
      assertEquals(0, fixture.stops.get())
      compose.runOnIdle { fixture.model.dismissPlaybackError() }
      fixture.refresh()
      compose.runOnIdle { fixture.model.togglePlaybackPause() }
      compose.waitUntil(5000) {
        fixture.model.uiState.value.playback.paused &&
          !fixture.model.uiState.value.playback.changingPause
      }
      assertNull(fixture.model.uiState.value.playback.error)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun aFailedSeekRecoversControlsWithoutEndingTheCast() {
    val fixture = Fixture()
    try {
      fixture.ready()
      fixture.failNext.set(true)
      compose.runOnIdle { fixture.model.seekBy(10_000) }
      compose.waitUntil(5000) { fixture.model.uiState.value.playback.error != null }
      fixture.refresh()
      assertTrue(fixture.model.uiState.value.playback.available)
      assertEquals(1, fixture.controls.get())
      assertEquals("existing-player", fixture.model.uiState.value.castId)
      assertEquals(0, fixture.stops.get())
      compose.runOnIdle { fixture.model.seekBy(10_000) }
      compose.waitUntil(5000) { fixture.model.uiState.value.playback.seekTargetMs == null }
      assertEquals(2, fixture.controls.get())
    } finally {
      fixture.close()
    }
  }

  private inner class Fixture(
    private val blockFirstSeek: Boolean = false,
    optimizedInWatch: Boolean = false,
  ) {
    val server = MockWebServer()
    val model = MainScreenViewModel()
    val seeks = CopyOnWriteArrayList<Long>()
    val firstSeek = CountDownLatch(1)
    val releaseSeek = CountDownLatch(1)
    val failNext = AtomicBoolean()
    val submissions = AtomicInteger()
    val stops = AtomicInteger()
    val controls = AtomicInteger()
    private val position = AtomicLong(30_000)
    private val paused = AtomicBoolean()
    private val active =
      Cast.newBuilder()
        .setId("existing-player")
        .setDevice(
          Device.newBuilder()
            .setName("Fixture TV")
            .setAddress("192.0.2.65:8009")
            .setType(DeviceType.DEVICE_TYPE_CHROMECAST)
        )
        .setStatus(CastStatus.newBuilder().setCasting(CastingStatus.newBuilder().setAttempt(1)))
        .build()

    init {
      val status =
        WatchResponse.newBuilder()
          .setStatus(
            active.status.toBuilder().setCasting(active.status.casting.toBuilder().setOptimizedLive(optimizedInWatch))
          )
          .build()
          .toByteArray()
      val frame = Buffer().writeByte(0).writeInt(status.size).write(status).readByteArray()
      server.dispatcher =
        object : ServerDispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.getHeader("Authorization") != "Bearer fixture")
              return MockResponse().setResponseCode(401)
            return when (request.path) {
              "/castor.v1.CastService/ListCasts" ->
                proto(ListCastsResponse.newBuilder().addCasts(active).build().toByteArray())
              "/castor.v1.CastService/Playback" ->
                proto(PlaybackResponse.newBuilder().setPlayback(state()).build().toByteArray())
              "/castor.v1.CastService/Control" -> {
                val control = ControlRequest.parseFrom(request.body.readByteArray())
                assertEquals(active.id, control.castId)
                controls.incrementAndGet()
                if (failNext.getAndSet(false))
                  return MockResponse()
                    .setResponseCode(503)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"code\":\"unavailable\",\"message\":\"receiver busy\"}")
                when {
                  control.hasSeek() -> {
                    val at = control.seek.seconds * 1000 + control.seek.nanos / 1_000_000
                    seeks.add(at)
                    if (blockFirstSeek && seeks.size == 1) {
                      firstSeek.countDown()
                      check(releaseSeek.await(10, TimeUnit.SECONDS))
                    }
                    position.set(at)
                  }
                  control.hasPause() -> paused.set(true)
                  control.hasResume() -> paused.set(false)
                }
                proto(ControlResponse.newBuilder().setPlayback(state()).build().toByteArray())
              }
              "/castor.v1.CastService/Stop" -> {
                stops.incrementAndGet()
                proto(byteArrayOf())
              }
              "/castor.v1.CastService/Cast" -> {
                submissions.incrementAndGet()
                proto(
                  CastResponse.newBuilder().setCastId("unwanted-new-cast").build().toByteArray()
                )
              }
              "/castor.v1.CastService/Watch" ->
                MockResponse()
                  .setHeader("Content-Type", "application/connect+proto")
                  .setBody(Buffer().apply { repeat(100) { write(frame) } })
                  .throttleBody(frame.size.toLong(), 1, TimeUnit.SECONDS)
              else -> MockResponse().setResponseCode(404)
            }
          }
        }
      server.start()
    }

    fun ready() {
      compose.runOnIdle {
        model.connect(Rpc(server.url("/").toString().removeSuffix("/"), "fixture"))
      }
      compose.waitUntil(5000) { model.uiState.value.castId == active.id }
      refresh()
      assertTrue(model.uiState.value.playback.available)
    }

    fun refresh() = runBlocking {
      withContext(Dispatchers.Main) { model.refreshPlayback(active.id) }
    }

    private fun state() =
      PlaybackState.newBuilder()
        .setPosition(Duration.newBuilder().setSeconds(position.get() / 1000))
        .setDuration(Duration.newBuilder().setSeconds(600))
        .setPaused(paused.get())
        .setCanPause(true)
        .setCanSeek(true)
        .build()

    fun close() {
      releaseSeek.countDown()
      compose.runOnIdle { model.connect(null) }
      server.shutdown()
    }
  }

  private fun proto(bytes: ByteArray) =
    MockResponse().setHeader("Content-Type", "application/proto").setBody(Buffer().write(bytes))
}
