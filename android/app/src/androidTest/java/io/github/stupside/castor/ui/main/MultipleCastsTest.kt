package io.github.stupside.castor.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import castor.v1.CastOuterClass.*
import castor.v1.DeviceOuterClass.*
import castor.v1.StreamOuterClass.Stream
import com.google.protobuf.Duration
import io.github.stupside.castor.R
import io.github.stupside.castor.data.Rpc
import io.github.stupside.castor.theme.CastorTheme
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MultipleCastsTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun recoveringAllTVsKeepsRestartAndControlsOnTheSelectedTransmission() {
    Fixture().use { fixture ->
      fixture.open()
      compose.onNodeWithTag("session-bedroom").performClick()
      compose.waitUntil(5000) { fixture.model.uiState.value.castId == "bedroom" }
      runBlocking { withContext(Dispatchers.Main) { fixture.model.refreshPlayback("bedroom") } }
      compose.runOnIdle { fixture.model.togglePlaybackPause() }
      compose.waitUntil(5000) { fixture.controls.isNotEmpty() }
      assertEquals(listOf("bedroom"), fixture.controls.toList())
      compose.onNodeWithTag("restart-casting").performClick()
      compose.waitUntil(5000) {
        fixture.restarts.size == 1 && !fixture.model.uiState.value.restarting
      }
      assertEquals(listOf("bedroom"), fixture.restarts.toList())
      assertEquals(2, fixture.model.uiState.value.sessions.size)
      assertTrue(fixture.submissions.isEmpty())
      assertTrue(fixture.stops.isEmpty())
      compose.onNodeWithTag("session-living-room").performClick()
      assertEquals(
        "https://example.test/living-room.mp4",
        fixture.model.uiState.value.activeCast?.source?.stream?.url,
      )
      compose.runOnIdle { fixture.model.stop() }
      compose.waitUntil(5000) { fixture.stops.size == 1 }
      assertEquals(listOf("living-room"), fixture.stops.toList())
      assertFalse(fixture.model.uiState.value.sessions.first { it.cast.id == "bedroom" }.stopping)
    }
  }

  @Test
  fun anotherVideoCanStartOnAThirdTVWithoutStoppingEitherExistingCast() {
    val options = CastOptions(compose.activity)
    val previous = options.television()
    options.television(
      SavedTv("Kitchen TV", "192.0.2.30:8009", DeviceType.DEVICE_TYPE_CHROMECAST, manual = true)
    )
    try {
      Fixture().use { fixture ->
        fixture.open()
        compose.onNodeWithTag("new-cast").performClick()
        compose.onNodeWithTag("video-url").assertExists()
        assertEquals(2, fixture.model.uiState.value.sessions.size)
        compose.onNodeWithTag("cast-action").assertIsEnabled().performClick()
        // The new session is listed, then selected: wait for both, not the moment between them.
        compose.waitUntil(5000) {
          fixture.model.uiState.value.sessions.size == 3 &&
            fixture.model.uiState.value.castId == "new-transmission"
        }
        val request = fixture.submissions.single()
        assertEquals("192.0.2.30:8009", request.target.pinned.address)
        assertEquals("https://example.test/new-video.mp4", request.source.stream.url)
        assertTrue(fixture.stops.isEmpty())
        assertTrue(fixture.restarts.isEmpty())
        assertEquals(
          setOf("living-room", "bedroom", "new-transmission"),
          fixture.model.uiState.value.sessions.map { it.cast.id }.toSet(),
        )
        compose.onNodeWithTag("session-bedroom").performClick()
        assertEquals(
          "https://example.test/bedroom.mp4",
          fixture.model.uiState.value.activeCast?.source?.stream?.url,
        )
      }
    } finally {
      if (previous != null) options.television(previous)
      else
        compose.activity
          .getSharedPreferences("casting-options", 0)
          .edit()
          .remove("tv-name")
          .remove("tv-address")
          .remove("tv-id")
          .remove("tv-type")
          .remove("tv-manual")
          .apply()
    }
  }

  @Test
  fun aRestartFailureLeavesBothCastsAndAllowsAnotherAttempt() {
    Fixture().use { fixture ->
      fixture.open()
      fixture.rejectRestart.set(true)
      compose.onNodeWithTag("restart-casting").performClick()
      compose.waitUntil(5000) {
        fixture.model.uiState.value.error != null && !fixture.model.uiState.value.restarting
      }
      assertEquals(2, fixture.model.uiState.value.sessions.size)
      assertTrue(fixture.stops.isEmpty())
      compose.onNodeWithTag("restart-casting").assertIsEnabled().performClick()
      compose.waitUntil(5000) {
        fixture.restarts.size == 2 && !fixture.model.uiState.value.restarting
      }
      assertEquals(listOf("living-room", "living-room"), fixture.restarts.toList())
      assertEquals(2, fixture.model.uiState.value.sessions.size)
    }
  }

  // One row is chosen at a time, the buttons name the TV they act on, and preparing a cast to another TV is a row
  // of its own whose cast is added to the others.
  @Test
  fun theChosenRowIsTheOneTheControlsActOnAndANewCastIsAnAddition() {
    val resources = compose.activity
    Fixture().use { fixture ->
      fixture.open()
      compose.onNodeWithTag("session-living-room").assertIsSelected()
      compose.onNodeWithTag("session-bedroom").assertIsNotSelected()
      compose.onNodeWithTag("new-cast").assertIsNotSelected()
      compose
        .onNodeWithTag("controls-for")
        .assertTextEquals(resources.getString(R.string.controls_for, "Living room TV"))
      compose.onNodeWithTag("session-bedroom").performClick()
      compose.onNodeWithTag("session-bedroom").assertIsSelected()
      compose.onNodeWithTag("session-living-room").assertIsNotSelected()
      compose
        .onNodeWithTag("controls-for")
        .assertTextEquals(resources.getString(R.string.controls_for, "Bedroom TV"))
      compose.onNodeWithTag("new-cast").performScrollTo().performClick()
      compose.onNodeWithTag("new-cast").assertIsSelected()
      compose.onNodeWithTag("session-bedroom").assertIsNotSelected()
      compose.onNodeWithTag("controls-for").assertDoesNotExist()
      compose
        .onNodeWithTag("cast-action")
        .assert(hasText(resources.getString(R.string.cast_video_too)))
      assertEquals(2, fixture.model.uiState.value.sessions.size)
      assertTrue(fixture.stops.isEmpty())
    }
  }

  // The TV list marks a TV that is already showing a cast, whichever of its protocols the cast came by.
  @Test
  fun theTvListMarksATvAlreadyCasting() {
    fun device(id: String, type: DeviceType, address: String) =
      Device.newBuilder().setId(id).setName("TV $id").setType(type).setAddress(address).build()
    val busy = device("busy", DeviceType.DEVICE_TYPE_DLNA, "http://192.0.2.10:1400/description.xml")
    val free = device("free", DeviceType.DEVICE_TYPE_CHROMECAST, "192.0.2.40:8009")
    val cast =
      Cast.newBuilder()
        .setId("running")
        .setDevice(device("running", DeviceType.DEVICE_TYPE_CHROMECAST, "192.0.2.10:8009"))
        .build()
    val state =
      MainScreenUiState(
        ready = true,
        discoveryComplete = true,
        devices = listOf(busy, free),
        sessions = listOf(CastSessionUiState(cast, CastPhase.Casting)),
        selectedCastId = "running",
      )
    compose.setContent { CastorTheme { TvPicker(state, null, {}, {}, {}, {}) } }
    val inUse = compose.activity.getString(R.string.tv_in_use)
    compose
      .onNode(hasTestTag("receiver-tv-192.0.2.10") and hasAnyDescendant(hasText(inUse)))
      .assertExists()
    compose
      .onNode(hasTestTag("receiver-tv-192.0.2.40") and hasAnyDescendant(hasText(inUse)))
      .assertDoesNotExist()
  }

  @Test
  fun aCompletedCastDoesNotForgetOrStopTheOtherTV() {
    Fixture(endBedroom = true).use { fixture ->
      fixture.open()
      compose.waitUntil(5000) { fixture.model.uiState.value.sessions.size == 1 }
      assertEquals("living-room", fixture.model.uiState.value.sessions.single().cast.id)
      assertEquals("living-room", fixture.model.uiState.value.castId)
      assertTrue(fixture.stops.isEmpty())
      assertTrue(fixture.restarts.isEmpty())
    }
  }

  private inner class Fixture(val endBedroom: Boolean = false) : AutoCloseable {
    val model = MainScreenViewModel()
    val server = MockWebServer()
    val casts = ConcurrentHashMap<String, Cast>()
    val controls = CopyOnWriteArrayList<String>()
    val restarts = CopyOnWriteArrayList<String>()
    val stops = CopyOnWriteArrayList<String>()
    val submissions = CopyOnWriteArrayList<CastRequest>()
    val rejectRestart = AtomicBoolean()

    init {
      for ((id, name, address) in
        listOf(
          Triple("living-room", "Living room TV", "192.0.2.10:8009"),
          Triple("bedroom", "Bedroom TV", "192.0.2.20:8009"),
        )) {
        casts[id] =
          Cast.newBuilder()
            .setId(id)
            .setDevice(
              Device.newBuilder()
                .setId(id)
                .setName(name)
                .setAddress(address)
                .setType(DeviceType.DEVICE_TYPE_CHROMECAST)
            )
            .setSource(
              Source.newBuilder()
                .setStream(Stream.newBuilder().setUrl("https://example.test/$id.mp4"))
            )
            .setStatus(
              CastStatus.newBuilder()
                .setCasting(
                  CastingStatus.newBuilder()
                    .setAttempt(1)
                    .setPlayhead(
                      Playhead.newBuilder()
                        .setPosition(Duration.newBuilder().setSeconds(45))
                        .setDuration(Duration.newBuilder().setSeconds(600))
                    )
                )
            )
            .build()
      }
      server.dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse =
            when (request.path) {
              "/castor.v1.CastService/ListCasts" ->
                proto(
                  ListCastsResponse.newBuilder()
                    .addCasts(casts["living-room"])
                    .addCasts(casts["bedroom"])
                    .build()
                    .toByteArray()
                )
              "/castor.v1.CastService/Cast" -> {
                val cast = CastRequest.parseFrom(request.body.readByteArray())
                submissions += cast
                casts["new-transmission"] =
                  Cast.newBuilder()
                    .setId("new-transmission")
                    .setSource(cast.source)
                    .setDevice(
                      Device.newBuilder()
                        .setName("Kitchen TV")
                        .setAddress(cast.target.pinned.address)
                        .setType(cast.target.pinned.type)
                    )
                    .setStatus(casts["living-room"]!!.status)
                    .build()
                proto(CastResponse.newBuilder().setCastId("new-transmission").build().toByteArray())
              }
              "/castor.v1.CastService/Restart" -> {
                restarts += RestartRequest.parseFrom(request.body.readByteArray()).castId
                if (rejectRestart.getAndSet(false))
                  MockResponse()
                    .setResponseCode(503)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"code\":\"unavailable\",\"message\":\"Receiver is busy\"}")
                else proto(byteArrayOf())
              }
              "/castor.v1.CastService/Stop" -> {
                stops += StopRequest.parseFrom(request.body.readByteArray()).castId
                proto(byteArrayOf())
              }
              "/castor.v1.CastService/Pause" -> {
                controls += PauseRequest.parseFrom(request.body.readByteArray()).castId
                proto(byteArrayOf())
              }
              "/castor.v1.CastService/Resume" -> {
                controls += ResumeRequest.parseFrom(request.body.readByteArray()).castId
                proto(byteArrayOf())
              }
              "/castor.v1.CastService/Seek" -> {
                controls += SeekRequest.parseFrom(request.body.readByteArray()).castId
                proto(byteArrayOf())
              }
              "/castor.v1.CastService/Watch" -> {
                request.body.skip(5)
                val id = WatchRequest.parseFrom(request.body.readByteArray()).castId
                val status =
                  WatchResponse.newBuilder().setStatus(casts[id]!!.status).build().toByteArray()
                val frame =
                  Buffer().writeByte(0).writeInt(status.size).write(status).readByteArray()
                val body =
                  Buffer().apply {
                    if (endBedroom && id == "bedroom") {
                      write(frame)
                      val ended =
                        WatchResponse.newBuilder()
                          .setEnded(
                            Ended.newBuilder()
                              .setCompleted(com.google.protobuf.Empty.getDefaultInstance())
                          )
                          .build()
                          .toByteArray()
                      writeByte(0).writeInt(ended.size).write(ended)
                    } else repeat(100) { write(frame) }
                  }
                MockResponse()
                  .setHeader("Content-Type", "application/connect+proto")
                  .setBody(body)
                  .throttleBody(frame.size.toLong(), 1, TimeUnit.SECONDS)
              }
              else -> MockResponse().setResponseCode(404)
            }
        }
      server.start()
    }

    fun open() {
      compose.runOnUiThread {
        compose.activity.window.addFlags(
          android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
      }
      compose.setContent {
        CastorTheme { MainScreen(model, null, "https://example.test/new-video.mp4", {}) }
      }
      compose.runOnIdle {
        model.connect(Rpc(server.url("/").toString().removeSuffix("/"), "fixture"))
      }
      compose.waitUntil(5000) {
        model.uiState.value.ready && model.uiState.value.sessions.size == 2
      }
      assertEquals("living-room", model.uiState.value.castId)
    }

    override fun close() {
      compose.runOnIdle { model.connect(null) }
      server.shutdown()
    }
  }

  private fun proto(bytes: ByteArray) =
    MockResponse().setHeader("Content-Type", "application/proto").setBody(Buffer().write(bytes))
}
