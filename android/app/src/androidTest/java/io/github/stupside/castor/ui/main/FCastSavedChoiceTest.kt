package io.github.stupside.castor.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import castor.v1.CastOuterClass.*
import castor.v1.DeviceOuterClass.*
import io.github.stupside.castor.R
import io.github.stupside.castor.data.Rpc
import io.github.stupside.castor.theme.CastorTheme
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FCastSavedChoiceTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val a = "${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:a"
  private var saved: Map<String, Any?> = emptyMap()

  // Every entry of the preferences is set aside, so a test leaves no choice behind and loses none of the user's.
  @Before
  fun clearPreferences() {
    saved = preferences().all
    preferences().edit().clear().commit()
  }

  @After
  fun restorePreferences() {
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

  @Test
  fun aPairedReceiverIsCastToWhereItsTVAnswersNow() {
    CastOptions(context)
      .pairFCast(FCastPairing(setOf(a), "Receiver", 46899, null, null, "192.0.2.10"))
    CastOptions(context)
      .television(SavedTv("Receiver", "192.0.2.10", DeviceType.DEVICE_TYPE_FCAST, pairedFor = setOf(a)))
    flushPreferences()
    val casts = CopyOnWriteArrayList<CastRequest>()
    val server =
      engine(
        listOf(
          device("chromecast:a", "Living room TV", "192.0.2.77:8009", DeviceType.DEVICE_TYPE_CHROMECAST),
          device("chromecast:b", "Bedroom TV", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST),
        ),
        casts,
      )
    val model = MainScreenViewModel()
    try {
      mount(model, server)
      // The saved address moves to where the TV answers now, so the cast must go there.
      compose.waitUntil(10_000) {
        model.uiState.value.discoveryComplete &&
          CastOptions(context).television()?.address == "192.0.2.77" &&
          castEnabled()
      }
      compose.waitForIdle()
      compose.onNodeWithTag("cast-action").performClick()
      compose.waitUntil(10_000) { casts.isNotEmpty() }
      val target = casts.single().target
      assertEquals("192.0.2.77", target.pinned.address)
      assertEquals(DeviceType.DEVICE_TYPE_FCAST, target.pinned.type)
      assertEquals("192.0.2.77", CastOptions(context).television()?.address)
    } finally {
      compose.runOnIdle { model.connect(null) }
      server.shutdown()
    }
  }

  @Test
  fun aPairedReceiverWhoseTVIsGoneIsNotCastToAtItsOldAddress() {
    CastOptions(context)
      .pairFCast(FCastPairing(setOf(a), "Receiver", 46899, null, null, "192.0.2.10"))
    CastOptions(context)
      .television(SavedTv("Receiver", "192.0.2.10", DeviceType.DEVICE_TYPE_FCAST, pairedFor = setOf(a)))
    flushPreferences()
    val casts = CopyOnWriteArrayList<CastRequest>()
    val server =
      engine(
        listOf(
          device("chromecast:b", "Bedroom TV", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
        ),
        casts,
      )
    val model = MainScreenViewModel()
    try {
      mount(model, server)
      compose.waitUntil(10_000) { model.uiState.value.discoveryComplete }
      compose.onNodeWithText(context.getString(R.string.tv_not_found)).assertExists()
      compose.onNodeWithTag("cast-action").assertIsNotEnabled()
      compose.waitForIdle()
      assertTrue(casts.isEmpty())
    } finally {
      compose.runOnIdle { model.connect(null) }
      server.shutdown()
    }
  }

  @Test
  fun aReceiverKnownToNeedEncryptionIsNotCastTo() {
    val pin = "A".repeat(43) + "="
    CastOptions(context)
      .pairFCast(FCastPairing(setOf(a), "Receiver", 46899, 4, pin, "192.0.2.10"))
    CastOptions(context)
      .television(SavedTv("Receiver", "192.0.2.10", DeviceType.DEVICE_TYPE_FCAST, id = "fcast:receiver"))
    flushPreferences()
    val casts = CopyOnWriteArrayList<CastRequest>()
    val server =
      engine(
        listOf(
          device("chromecast:a", "Living room TV", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST),
          device("fcast:receiver", "Receiver", "192.0.2.10", DeviceType.DEVICE_TYPE_FCAST),
        ),
        casts,
      )
    val model = MainScreenViewModel()
    val blocked = context.getString(R.string.fcast_encrypted_blocked)
    try {
      mount(model, server)
      compose.waitUntil(10_000) { model.uiState.value.discoveryComplete && castEnabled() }
      compose.waitForIdle()
      compose.onNodeWithTag("cast-action").performClick()
      // The error text is shown once its details are opened.
      compose
        .onNodeWithText(context.getString(R.string.show_details))
        .performScrollTo()
        .performClick()
      compose.onNodeWithText(blocked).assertExists()
      compose.runOnIdle { assertEquals(blocked, model.uiState.value.error) }
      compose.waitForIdle()
      assertTrue(casts.isEmpty())
    } finally {
      compose.runOnIdle { model.connect(null) }
      server.shutdown()
    }
  }

  @Test
  fun aReceiverAtABareIPv6AddressKnownToNeedEncryptionIsNotCastToBeforeAnySearch() {
    assertBareIPv6EncryptedReceiverIsNotCastTo { model ->
      compose.waitUntil(10_000) { castEnabled() }
      compose.waitForIdle()
      assertFalse(model.uiState.value.discoveryComplete)
    }
  }

  @Test
  fun aReceiverAtABareIPv6AddressKnownToNeedEncryptionIsNotCastToAfterASearch() {
    assertBareIPv6EncryptedReceiverIsNotCastTo { model ->
      compose.waitUntil(10_000) { castEnabled() }
      compose.runOnIdle { model.discover() }
      compose.waitUntil(10_000) { model.uiState.value.discoveryComplete && castEnabled() }
      compose.waitForIdle()
    }
  }

  // Shared by the bare IPv6 tests; the caller decides how far the screen is driven before the cast click.
  private fun assertBareIPv6EncryptedReceiverIsNotCastTo(reach: (MainScreenViewModel) -> Unit) {
    val pin = "A".repeat(43) + "="
    CastOptions(context)
      .pairFCast(FCastPairing(setOf(a), "Receiver", 46899, 4, pin, "2001:db8::10"))
    CastOptions(context)
      .television(SavedTv("Receiver", "2001:db8::10", DeviceType.DEVICE_TYPE_FCAST, manual = true))
    flushPreferences()
    val casts = CopyOnWriteArrayList<CastRequest>()
    val server =
      engine(
        listOf(
          device("chromecast:a", "Living room TV", "[2001:db8::10]:8009", DeviceType.DEVICE_TYPE_CHROMECAST),
        ),
        casts,
      )
    val model = MainScreenViewModel()
    val blocked = context.getString(R.string.fcast_encrypted_blocked)
    try {
      mount(model, server)
      reach(model)
      compose.onNodeWithTag("cast-action").performClick()
      // The error text is shown once its details are opened.
      compose
        .onNodeWithText(context.getString(R.string.show_details))
        .performScrollTo()
        .performClick()
      compose.onNodeWithText(blocked).assertExists()
      compose.runOnIdle { assertEquals(blocked, model.uiState.value.error) }
      compose.waitForIdle()
      assertTrue(casts.isEmpty())
    } finally {
      compose.runOnIdle { model.connect(null) }
      server.shutdown()
    }
  }

  @Test
  fun anAnnouncedReceiverOfAPairedTVIsFollowedToItsTVsNewAddress() {
    CastOptions(context)
      .pairFCast(FCastPairing(setOf(a), "Receiver", 46899, null, null, "192.0.2.10"))
    CastOptions(context)
      .television(SavedTv("Receiver", "192.0.2.10", DeviceType.DEVICE_TYPE_FCAST, pairedFor = setOf(a)))
    flushPreferences()
    val casts = CopyOnWriteArrayList<CastRequest>()
    val server =
      engine(
        listOf(
          device("chromecast:a", "Living room TV", "192.0.2.77:8009", DeviceType.DEVICE_TYPE_CHROMECAST),
          device("Receiver@192.0.2.77", "Receiver", "192.0.2.77", DeviceType.DEVICE_TYPE_FCAST),
          device("chromecast:b", "Bedroom TV", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST),
          device("Receiver@192.0.2.10", "Receiver", "192.0.2.10", DeviceType.DEVICE_TYPE_FCAST),
        ),
        casts,
      )
    val model = MainScreenViewModel()
    try {
      mount(model, server)
      compose.waitUntil(10_000) {
        model.uiState.value.discoveryComplete &&
          CastOptions(context).television()?.id == "Receiver@192.0.2.77"
      }
      compose.waitForIdle()
      compose.onNodeWithTag("cast-action").performClick()
      compose.waitUntil(10_000) { casts.isNotEmpty() }
      assertEquals("Receiver@192.0.2.77", casts.single().target.deviceId)
      compose.waitForIdle()
      assertTrue(
        casts.none { it.target.deviceId == "Receiver@192.0.2.10" || it.target.pinned.address == "192.0.2.10" }
      )
    } finally {
      compose.runOnIdle { model.connect(null) }
      server.shutdown()
    }
  }

  private fun preferences() = context.getSharedPreferences("casting-options", 0)

  private fun flushPreferences() {
    preferences().edit().commit()
  }

  private fun device(id: String, name: String, address: String, type: DeviceType): Device =
    Device.newBuilder().setId(id).setName(name).setAddress(address).setType(type).build()

  // Discovery answers with the given devices; every Cast request it receives is recorded.
  private fun engine(devices: List<Device>, casts: CopyOnWriteArrayList<CastRequest>): MockWebServer {
    val server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val body =
            when (request.path) {
              "/castor.v1.DeviceService/ListDevices" ->
                ListDevicesResponse.newBuilder().addAllDevices(devices).build().toByteArray()
              "/castor.v1.CastService/ListCasts" -> ListCastsResponse.getDefaultInstance().toByteArray()
              "/castor.v1.CastService/Cast" -> {
                casts += CastRequest.parseFrom(request.body.readByteArray())
                CastResponse.newBuilder().setCastId("new-cast").build().toByteArray()
              }
              else -> byteArrayOf()
            }
          return MockResponse()
            .setHeader("Content-Type", "application/proto")
            .setBody(Buffer().write(body))
        }
      }
    server.start()
    return server
  }

  private fun mount(model: MainScreenViewModel, server: MockWebServer) {
    compose.setContent {
      CastorTheme { MainScreen(model, null, "https://example.org/video.mp4", {}) }
    }
    compose.runOnIdle { model.connect(Rpc(server.url("/").toString().removeSuffix("/"), "fixture")) }
  }

  private fun castEnabled(): Boolean =
    compose.onAllNodes(hasTestTag("cast-action") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
}
