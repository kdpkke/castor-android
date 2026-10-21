package io.github.stupside.castor.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import castor.v1.CastOuterClass.*
import castor.v1.DeviceOuterClass.*
import io.github.stupside.castor.R
import io.github.stupside.castor.data.Rpc
import io.github.stupside.castor.theme.CastorTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// These fixtures do not change the phone's saved preferences or contact its TV.
class ReceiverRecoveryTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val device =
    Device.newBuilder()
      .setId("dlna:living-room")
      .setName("Living room")
      .setAddress("http://192.0.2.10:1938/")
      .setType(DeviceType.DEVICE_TYPE_DLNA)
      .build()

  @Test
  fun lastUsedIsHiddenUntilDiscoveryConfirmsItAndDisappearsWhenOffline() {
    val state = mutableStateOf(MainScreenUiState(ready = true))
    val lastUsed =
      InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.last_tv) +
        " · DLNA"
    compose.setContent { CastorTheme { TvPicker(state.value, SavedTv(device), {}, {}, {}) } }
    compose.onNodeWithText(lastUsed).assertDoesNotExist()
    compose.runOnIdle {
      state.value =
        state.value.copy(
          devices =
            listOf(
              device,
              device
                .toBuilder()
                .setId("dlna:bedroom")
                .setName("Bedroom")
                .setAddress("http://192.0.2.20/")
                .build(),
            ),
          discoveryComplete = true,
        )
    }
    compose.onNodeWithText(lastUsed).assertExists()
    compose.onAllNodesWithText(device.name).assertCountEquals(1)
    compose
      .onNodeWithText(
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.other_tvs)
      )
      .assertExists()
    compose.onNodeWithText("Bedroom").assertExists()
    val evidence =
      java.io.File(
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        "receiver-groups-fixture.png",
      )
    evidence.outputStream().use {
      InstrumentationRegistry.getInstrumentation()
        .uiAutomation
        .takeScreenshot()
        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
    }
    compose.runOnIdle { state.value = state.value.copy(discovering = true) }
    compose.onNodeWithText(lastUsed).assertDoesNotExist()
    compose.runOnIdle { state.value = state.value.copy(devices = emptyList(), discovering = false) }
    compose.onNodeWithText(lastUsed).assertDoesNotExist()
  }

  @Test
  fun reopeningWaitsForTheExistingCastAndStopTargetsThatCast() {
    val received = CountDownLatch(1)
    val release = CountDownLatch(1)
    val submissions = AtomicInteger()
    val stopped = CountDownLatch(1)
    val active = Cast.newBuilder().setId("existing-cast").setDevice(device).build()
    val status =
      WatchResponse.newBuilder()
        .setStatus(CastStatus.newBuilder().setCasting(CastingStatus.newBuilder().setAttempt(1)))
        .build()
        .toByteArray()
    val frame = Buffer().writeByte(0).writeInt(status.size).write(status).readByteArray()
    val server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          when (request.path) {
            "/castor.v1.CastService/ListCasts" -> {
              received.countDown()
              check(release.await(10, TimeUnit.SECONDS))
              proto(ListCastsResponse.newBuilder().addCasts(active).build().toByteArray())
            }
            "/castor.v1.CastService/Cast" -> {
              submissions.incrementAndGet()
              proto(CastResponse.newBuilder().setCastId("duplicate").build().toByteArray())
            }
            "/castor.v1.CastService/Stop" -> {
              assertEquals(active.id, StopRequest.parseFrom(request.body.readByteArray()).castId)
              stopped.countDown()
              proto(byteArrayOf())
            }
            else ->
              MockResponse()
                .setHeader("Content-Type", "application/connect+proto")
                .setBody(Buffer().apply { repeat(100) { write(frame) } })
                .throttleBody(frame.size.toLong(), 1, TimeUnit.SECONDS)
          }
      }
    server.start()
    val model = MainScreenViewModel()
    try {
      compose.runOnIdle {
        model.connect(Rpc(server.url("/").toString().removeSuffix("/"), "fixture"))
      }
      assertTrue(received.await(5, TimeUnit.SECONDS))
      compose.runOnIdle {
        assertFalse(
          "Do not allow a new cast before recovering the engine's active cast",
          model.uiState.value.ready,
        )
        model.cast("https://example.org/video.mp4", false, SavedTv(device).target(), "1080", "off")
      }
      release.countDown()
      compose.waitUntil(5000) { model.uiState.value.castId == active.id }
      compose.runOnIdle { model.stop() }
      assertTrue(stopped.await(5, TimeUnit.SECONDS))
      assertEquals(
        "A restored session must not start another cast on the same TV",
        0,
        submissions.get(),
      )
    } finally {
      release.countDown()
      compose.runOnIdle { model.connect(null) }
      server.shutdown()
    }
  }

  private fun proto(bytes: ByteArray) =
    MockResponse().setHeader("Content-Type", "application/proto").setBody(Buffer().write(bytes))

  @Test
  fun busyFailureRecoversTheOwningCastSoItCanBeStopped() = recoverUncertainSubmission(busy = true)

  @Test
  fun lostStartResponseRecoversTheCastAcceptedByTheEngine() =
    recoverUncertainSubmission(busy = false)

  private fun recoverUncertainSubmission(busy: Boolean) {
    val lists = AtomicInteger()
    val stopped = CountDownLatch(1)
    val active = Cast.newBuilder().setId("owner").setDevice(device).build()
    val status =
      WatchResponse.newBuilder()
        .setStatus(CastStatus.newBuilder().setCasting(CastingStatus.newBuilder().setAttempt(1)))
        .build()
    fun frame(response: WatchResponse): ByteArray {
      val bytes = response.toByteArray()
      return Buffer().writeByte(0).writeInt(bytes.size).write(bytes).readByteArray()
    }
    val server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          when (request.path) {
            "/castor.v1.CastService/ListCasts" ->
              proto(
                ListCastsResponse.newBuilder()
                  .apply { if (lists.getAndIncrement() > 0) addCasts(active) }
                  .build()
                  .toByteArray()
              )
            "/castor.v1.CastService/Cast" ->
              if (busy) proto(CastResponse.newBuilder().setCastId("rejected").build().toByteArray())
              else
                MockResponse()
                  .setResponseCode(503)
                  .setHeader("Content-Type", "application/json")
                  .setBody(
                    "{\"code\":\"unavailable\",\"message\":\"response lost after acceptance\"}"
                  )
            "/castor.v1.CastService/Stop" -> {
              assertEquals(active.id, StopRequest.parseFrom(request.body.readByteArray()).castId)
              stopped.countDown()
              proto(byteArrayOf())
            }
            else -> {
              // Connect request frames have a flag byte and a four-byte message size.
              request.body.skip(5)
              val id = WatchRequest.parseFrom(request.body.readByteArray()).castId
              val response = MockResponse().setHeader("Content-Type", "application/connect+proto")
              if (id == "rejected")
                response.setBody(
                  Buffer()
                    .write(
                      frame(
                        WatchResponse.newBuilder()
                          .setEnded(
                            Ended.newBuilder()
                              .setFailed(
                                Failure.newBuilder()
                                  .setCode(FailureCode.FAILURE_CODE_PLAYBACK_FAILED)
                                  .setMessage(
                                    "device is busy; stop its current cast before starting another"
                                  )
                              )
                          )
                          .build()
                      )
                    )
                )
              else {
                val bytes = frame(status)
                response
                  .setBody(Buffer().apply { repeat(100) { write(bytes) } })
                  .throttleBody(bytes.size.toLong(), 1, TimeUnit.SECONDS)
              }
            }
          }
      }
    server.start()
    val model = MainScreenViewModel()
    try {
      compose.runOnIdle {
        model.connect(Rpc(server.url("/").toString().removeSuffix("/"), "fixture"))
      }
      compose.waitUntil(5000) { model.uiState.value.ready }
      compose.runOnIdle {
        model.cast("https://example.org/video.mp4", false, SavedTv(device).target(), "1080", "off")
      }
      compose.waitUntil(5000) {
        model.uiState.value.castId == active.id && model.uiState.value.ready
      }
      compose.runOnIdle {
        assertEquals(active, model.uiState.value.activeCast)
        assertNotNull("Keep the original diagnostic visible", model.uiState.value.error)
        model.stop()
      }
      assertTrue(stopped.await(5, TimeUnit.SECONDS))
      assertEquals(2, lists.get())
    } finally {
      compose.runOnIdle { model.connect(null) }
      server.shutdown()
    }
  }
}
