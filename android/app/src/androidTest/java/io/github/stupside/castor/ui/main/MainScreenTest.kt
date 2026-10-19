package io.github.stupside.castor.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import castor.v1.CastOuterClass.*
import castor.v1.DeviceOuterClass.*
import io.github.stupside.castor.data.Rpc
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  fun clearPreferences() {
    context.getSharedPreferences("casting-options", 0).edit().clear().commit()
  }

  @After
  fun resetPreferences() {
    clearPreferences()
  }

  @Test
  fun reconnectingTheSameEngineKeepsTheActiveSubscriptionAndState() {
    val active =
      Cast.newBuilder()
        .setId("already-playing")
        .setStatus(CastStatus.newBuilder().setCasting(CastingStatus.newBuilder().setAttempt(1)))
        .build()
    val message = WatchResponse.newBuilder().setStatus(active.status).build().toByteArray()
    val frame = Buffer().writeByte(0).writeInt(message.size).write(message).readByteArray()
    val server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          if (request.path == "/castor.v1.CastService/ListCasts")
            MockResponse()
              .setHeader("Content-Type", "application/proto")
              .setBody(
                Buffer()
                  .write(ListCastsResponse.newBuilder().addCasts(active).build().toByteArray())
              )
          else
            MockResponse()
              .setHeader("Content-Type", "application/proto")
              .setHeader("Content-Type", "application/connect+proto")
              .setBody(Buffer().apply { repeat(100) { write(frame) } })
              .throttleBody(frame.size.toLong(), 1, java.util.concurrent.TimeUnit.SECONDS)
      }
    server.start()
    val api = Rpc(server.url("/").toString().removeSuffix("/"), "fixture")
    val model = MainScreenViewModel()
    try {
      compose.runOnIdle { model.connect(api) }
      compose.waitUntil(5000) {
        model.uiState.value.castId == active.id && server.requestCount == 2
      }
      compose.runOnIdle {
        val before = model.uiState.value
        model.connect(api)
        org.junit.Assert.assertSame(
          "Rebinding the same engine must leave the cast alone",
          before,
          model.uiState.value,
        )
      }
      assertEquals(2, server.requestCount)
    } finally {
      compose.runOnIdle { model.connect(null) }
      server.shutdown()
    }
  }

  @Test
  fun resumedCastShowsActualSourceAndTvInsteadOfIncomingShareAndSavedPreference() {
    CastOptions(context)
      .television(SavedTv("Bedroom TV", "192.0.2.99", DeviceType.DEVICE_TYPE_DLNA))
    val actual =
      Cast.newBuilder()
        .setId("already-active")
        .setDevice(
          Device.newBuilder()
            .setName("Living room renderer")
            .setAddress("192.0.2.65")
            .setType(DeviceType.DEVICE_TYPE_DLNA)
        )
        .setSource(
          Source.newBuilder()
            .setPages(Source.Pages.newBuilder().addUrls("https://active.example.org/old-video"))
        )
        .build()
    val server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          if (request.path == "/castor.v1.CastService/ListCasts")
            MockResponse()
              .setHeader("Content-Type", "application/proto")
              .setBody(
                Buffer()
                  .write(ListCastsResponse.newBuilder().addCasts(actual).build().toByteArray())
              )
          else MockResponse().setHeader("Content-Type", "application/proto").setBody("")
      }
    server.start()
    val model = MainScreenViewModel()
    try {
      compose.setContent {
        io.github.stupside.castor.theme.CastorTheme {
          MainScreen(model, null, "https://incoming.example.org/new-video", {})
        }
      }
      compose.runOnIdle {
        model.connect(Rpc(server.url("/").toString().removeSuffix("/"), "fixture"))
      }
      compose.waitUntil(5000) { model.uiState.value.castId == "already-active" }
      compose.onNodeWithText("Living room renderer").assertExists()
      compose.onNodeWithText("active.example.org").assertExists()
      compose.onNodeWithText("Bedroom TV").assertDoesNotExist()
      compose.onNodeWithText("incoming.example.org").assertDoesNotExist()
    } finally {
      compose.runOnIdle { model.connect(null) }
      server.shutdown()
    }
  }

  @Test
  fun newCastSnapshotsSubmittedSourceAndPinnedReceiver() {
    CastOptions(context)
      .television(SavedTv("Chosen receiver", "192.0.2.88", DeviceType.DEVICE_TYPE_DLNA))
    val server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          MockResponse()
            .setHeader("Content-Type", "application/proto")
            .setBody(
              Buffer()
                .write(
                  if (request.path == "/castor.v1.CastService/Cast")
                    CastResponse.newBuilder().setCastId("new-cast").build().toByteArray()
                  else byteArrayOf()
                )
            )
      }
    server.start()
    val model = MainScreenViewModel()
    try {
      compose.setContent {
        io.github.stupside.castor.theme.CastorTheme {
          MainScreen(model, null, "https://submitted.example.org/movie.mp4", {})
        }
      }
      compose.runOnIdle {
        model.connect(Rpc(server.url("/").toString().removeSuffix("/"), "fixture"))
      }
      compose.onNodeWithTag("cast-action").performClick()
      compose.waitUntil(5000) { model.uiState.value.castId == "new-cast" }
      compose.onNodeWithText("Chosen receiver").assertExists()
      compose.onNodeWithText("submitted.example.org").assertExists()
      compose.runOnIdle {
        assertEquals("192.0.2.88", model.uiState.value.activeCast?.device?.address)
        assertEquals(
          "https://submitted.example.org/movie.mp4",
          model.uiState.value.activeCast?.source?.stream?.url,
        )
      }
    } finally {
      compose.runOnIdle { model.connect(null) }
      server.shutdown()
    }
  }

  @Test
  fun manualTvSelectionEnablesCastAndRemembersReceiver() {
    val model = MainScreenViewModel()
    model.uiState.value = MainScreenUiState(ready = true, phase = CastPhase.Ready)
    compose.setContent {
      io.github.stupside.castor.theme.CastorTheme {
        MainScreen(model, null, "https://example.org/watch", {})
      }
    }
    compose.onNodeWithTag("cast-action").assertIsNotEnabled()
    compose.onNodeWithText("Choose your TV").performClick()
    compose.onNodeWithText("Enter a TV address").performClick()
    compose.onNodeWithText("TV IP or description URL").performTextInput("192.0.2.65")
    compose.onNodeWithText("Use this TV").performClick()
    compose.onNodeWithTag("cast-action").assertIsEnabled()
    compose.runOnIdle { assertEquals("192.0.2.65", CastOptions(context).television()?.address) }
  }

  @Test
  fun playbackSettingsValidateCustomHeightAndLanguageBeforeSaving() {
    val model = MainScreenViewModel()
    compose.setContent {
      io.github.stupside.castor.theme.CastorTheme { MainScreen(model, null, "", {}) }
    }
    compose.onNodeWithText("Playback settings").performScrollTo().performClick()
    compose.onNodeWithText("720p").performClick().assertIsSelected()
    compose.onNodeWithText("Language", useUnmergedTree = true).performClick()
    compose.onNodeWithText("Done").performScrollTo().assertIsNotEnabled()
    compose.onNodeWithText("Language code, e.g. it or en").performScrollTo().performTextInput("it")
    compose.onNodeWithText("Done").performScrollTo().performClick()
    compose.onNodeWithText("720p · Subtitles: IT").assertExists()
    compose.runOnIdle {
      assertEquals("720", CastOptions(context).height())
      assertEquals("it", CastOptions(context).subtitles())
    }
  }

  @Test
  fun invalidLinkShowsRecoveryAndDisablesAnOtherwiseReadyCast() {
    CastOptions(context)
      .television(SavedTv("Fixture TV", "192.0.2.65", DeviceType.DEVICE_TYPE_DLNA))
    val model = MainScreenViewModel()
    model.uiState.value = MainScreenUiState(ready = true, phase = CastPhase.Ready)
    compose.setContent {
      io.github.stupside.castor.theme.CastorTheme {
        MainScreen(model, null, "https://example.org/watch", {})
      }
    }
    compose.onNodeWithTag("cast-action").assertIsEnabled()
    compose.onNodeWithContentDescription("Clear link").performClick()
    compose.onNodeWithTag("video-url").performTextInput("invalid")
    androidx.test.espresso.Espresso.closeSoftKeyboard()
    compose.onNodeWithText("Enter a complete http:// or https:// link.").assertIsDisplayed()
    compose.onNodeWithTag("cast-action").assertIsNotEnabled()
  }

  @Test
  fun nativeQualityIsSavedAndCustomStillRequiresAValidHeight() {
    val model = MainScreenViewModel()
    compose.setContent {
      io.github.stupside.castor.theme.CastorTheme { MainScreen(model, null, "", {}) }
    }
    compose.onNodeWithText("Playback settings").performScrollTo().performClick()
    compose.onNodeWithText("Native quality").performClick().assertIsSelected()
    compose.onNodeWithText("Done").performScrollTo().performClick()
    compose.onNodeWithText("Native quality · Subtitles: Off").assertExists()
    compose.runOnIdle { assertEquals("native", CastOptions(context).height()) }
    compose.onNodeWithText("Playback settings").performScrollTo().performClick()
    compose.onNodeWithText("Native quality").assertIsSelected()
    compose.onNodeWithText("Custom").performClick()
    compose.onNodeWithText("Done").performScrollTo().assertIsNotEnabled()
    compose.onNodeWithText("1080p").performScrollTo().performClick()
    compose.onNodeWithText("Done").performScrollTo().performClick()
    compose.onNodeWithText("1080p · Subtitles: Off").assertExists()
  }

  @Test
  fun pasteUsesClipboardOnlyWhenRequestedAndClearRemovesLink() {
    val model = MainScreenViewModel()
    compose.runOnUiThread {
      context
        .getSystemService(android.content.ClipboardManager::class.java)
        .setPrimaryClip(
          android.content.ClipData.newPlainText(
            "fixture",
            "https://example.org/live.m3u8?token=test",
          )
        )
    }
    compose.setContent {
      io.github.stupside.castor.theme.CastorTheme {
        MainScreen(model, null, "", {})
      }
    }
    compose.onNodeWithText("Paste link").performClick()
    compose.onNodeWithText("Stream link").assertExists()
    compose.onNodeWithContentDescription("Clear link").performClick()
    compose.onNodeWithText("https://example.org/live.m3u8?token=test").assertDoesNotExist()
  }

  @Test
  fun sourceSheetAppliesExplicitModesAndDismissalKeepsTheChoice() {
    val model = MainScreenViewModel()
    compose.setContent {
      io.github.stupside.castor.theme.CastorTheme {
        MainScreen(model, null, "https://example.org/watch", {})
      }
    }
    compose.onNodeWithText("Automatic").performClick()
    compose.onNodeWithText("Direct stream").performClick()
    compose.onNodeWithText("Stream link").assertExists()
    compose.onNodeWithText("Direct stream").performClick()
    compose.onNodeWithText("Web page").performClick()
    compose.onNodeWithText("Page link").assertExists()
    compose.onNodeWithText("Web page").performClick()
    compose.onNodeWithContentDescription("Close").performClick()
    compose.onNodeWithText("Web page").assertExists()
    compose.onNodeWithText("Page link").assertExists()
  }

  @Test
  fun stoppedEngineDisablesCastingAndShowsStatus() {
    val model = MainScreenViewModel()
    model.connect(null)
    compose.setContent {
      io.github.stupside.castor.theme.CastorTheme {
        MainScreen(model, null, "https://example.org/watch", {})
      }
    }
    compose.onNodeWithText("Casting engine unavailable").assertExists()
    compose.onNodeWithText("https://example.org/watch").assertExists()
    compose.onNodeWithText("Cast video").assertIsNotEnabled()
  }

  @Test
  fun copiesTheEntireErrorToTheClipboard() {
    val error = "SSL routines::certificate verify failed\n" + "Long diagnostic details. ".repeat(80)
    val model = MainScreenViewModel()
    model.fail(IllegalStateException(error))
    compose.setContent {
      io.github.stupside.castor.theme.CastorTheme {
        MainScreen(model, null, "", {})
      }
    }
    compose.onNodeWithText("Copy error").performScrollTo().performClick()
    compose.waitForIdle()
    compose.runOnIdle {
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
      assertEquals(error, clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
    }
  }
}
