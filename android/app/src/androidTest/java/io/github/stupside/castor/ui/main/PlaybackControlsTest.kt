package io.github.stupside.castor.ui.main

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import castor.v1.CastOuterClass.*
import castor.v1.DeviceOuterClass.*
import castor.v1.StreamOuterClass.Stream
import io.github.stupside.castor.theme.AppTheme
import io.github.stupside.castor.theme.CastorTheme
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaybackControlsTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val ready =
    PlaybackUiState(
      positionMs = 30_000,
      durationMs = 600_000,
      canPause = true,
      canSeek = true,
      available = true,
    )

  @Test
  fun tapSeeksOnceAndHoldStopsAtRelease() {
    val steps = AtomicInteger()
    compose.setContent {
      CastorTheme {
        PlaybackControls(ready, true, {}, { steps.addAndGet(if (it > 0) 1 else -1) }, {})
      }
    }
    val forward = compose.onNodeWithTag("playback-forward")
    forward.performClick()
    assertEquals(1, steps.get())
    forward.performTouchInput { down(center) }
    compose.waitUntil(5000) { steps.get() >= 4 }
    forward.performTouchInput { up() }
    compose.waitForIdle()
    val released = steps.get()
    compose.mainClock.advanceTimeBy(1500)
    compose.waitForIdle()
    assertEquals("Holding stopped on release without a final extra tap", released, steps.get())
    compose.onNodeWithTag("playback-back").performClick()
    assertEquals(released - 1, steps.get())
  }

  @Test
  fun cancelledHoldAndBackgroundNeverKeepSeeking() {
    val steps = AtomicInteger()
    compose.setContent {
      CastorTheme { PlaybackControls(ready, true, {}, { steps.incrementAndGet() }, {}) }
    }
    val forward = compose.onNodeWithTag("playback-forward")
    forward.performTouchInput { down(center) }
    compose.waitUntil(5000) { steps.get() >= 2 }
    forward.performTouchInput { cancel() }
    compose.waitForIdle()
    val cancelled = steps.get()
    compose.mainClock.advanceTimeBy(1200)
    assertEquals(cancelled, steps.get())
    forward.performTouchInput { down(center) }
    compose.waitUntil(5000) { steps.get() > cancelled }
    compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
    val background = steps.get()
    compose.mainClock.advanceTimeBy(1200)
    assertEquals("A hidden app kept seeking", background, steps.get())
    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
  }

  @Test
  fun pauseIsUnavailableDuringSeekAndReturnsWhenTheSeekCompletes() {
    val state = mutableStateOf(ready.copy(seekTargetMs = 120_000))
    val pauses = AtomicInteger()
    compose.setContent {
      CastorTheme { PlaybackControls(state.value, true, { pauses.incrementAndGet() }, {}, {}) }
    }
    compose.onNodeWithTag("playback-pause").assertIsNotEnabled()
    compose.onNodeWithTag("playback-forward").assertIsEnabled()
    compose.onNodeWithTag("playback-timeline").assertIsEnabled()
    compose.runOnIdle { state.value = ready.copy(positionMs = 120_000) }
    compose.onNodeWithTag("playback-pause").assertIsEnabled().performClick()
    assertEquals(1, pauses.get())
  }

  @Test
  fun anErrorCancelsTheHeldGestureWithoutDisablingOtherControls() {
    val state = mutableStateOf(ready)
    val steps = AtomicInteger()
    compose.setContent {
      CastorTheme { PlaybackControls(state.value, true, {}, { steps.incrementAndGet() }, {}) }
    }
    val forward = compose.onNodeWithTag("playback-forward")
    forward.performTouchInput { down(center) }
    compose.waitUntil(5000) { steps.get() >= 2 }
    compose.runOnIdle { state.value = ready.copy(error = "receiver seek failed") }
    compose.waitForIdle()
    val failed = steps.get()
    compose.mainClock.advanceTimeBy(1500)
    assertEquals(failed, steps.get())
    compose.onNodeWithTag("playback-pause").assertIsEnabled()
    forward.performTouchInput { up() }
    forward.performClick()
    assertEquals(failed + 1, steps.get())
  }

  @Test
  fun unknownStateAndLiveMediaDoNotOfferFakeSeekControls() {
    val state = mutableStateOf(PlaybackUiState())
    val steps = AtomicInteger()
    compose.setContent {
      CastorTheme { PlaybackControls(state.value, true, {}, { steps.incrementAndGet() }, {}) }
    }
    compose.onNodeWithTag("playback-forward").assertIsNotEnabled()
    compose.onNodeWithTag("playback-pause").assertIsNotEnabled()
    compose.onNodeWithTag("playback-position").assertTextEquals("--:--")
    compose.runOnIdle { state.value = ready.copy(durationMs = null, canSeek = false) }
    compose.onNodeWithTag("playback-pause").assertIsEnabled()
    compose.onNodeWithTag("playback-forward").assertIsNotEnabled()
    compose.onNodeWithTag("playback-timeline").assertDoesNotExist()
    assertEquals(0, steps.get())
  }

  @Test
  fun capturesNativeControlsInBothLanguagesThemesAndLargeText() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val screen = InstrumentationRegistry.getArguments().getString("screenClass") ?: "phone"
    val model = MainScreenViewModel()
    val device =
      Device.newBuilder()
        .setName("Living room TV")
        .setAddress("192.0.2.65:8009")
        .setType(DeviceType.DEVICE_TYPE_CHROMECAST)
    val active =
      Cast.newBuilder()
        .setId("controls-visual-fixture")
        .setDevice(device)
        .setSource(
          Source.newBuilder()
            .setStream(Stream.newBuilder().setUrl("https://example.test/video.mp4"))
        )
        .build()
    model.uiState.value =
      castingScreenState(
        ready = true,
        castId = active.id,
        activeCast = active,
        phase = CastPhase.Casting,
        playback = ready.copy(positionMs = 83_000, durationMs = 683_000),
      )
    var dark by mutableStateOf(true)
    var italian by mutableStateOf(false)
    var scale by mutableFloatStateOf(1f)
    compose.setContent {
      val configuration =
        Configuration(LocalConfiguration.current).apply {
          uiMode =
            (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
              if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
          setLocale(if (italian) Locale.ITALIAN else Locale.ENGLISH)
        }
      CompositionLocalProvider(
        LocalConfiguration provides configuration,
        LocalContext provides context.createConfigurationContext(configuration),
        LocalDensity provides Density(LocalDensity.current.density, scale),
      ) {
        CastorTheme(AppTheme.System) { MainScreen(model, null, "", {}, theme = AppTheme.System) }
      }
    }
    fun capture(name: String) {
      compose.onNodeWithTag("playback-forward").assertIsDisplayed()
      compose.onNodeWithTag("cast-action").assertIsDisplayed()
      compose.runOnUiThread {
        WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
          .apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
          }
      }
      android.os.SystemClock.sleep(250)
      val file = File(context.getExternalFilesDir(null), "controls-review/$name.png")
      file.parentFile!!.mkdirs()
      val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
      try {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      } finally {
        bitmap.recycle()
      }
    }
    capture("controls-$screen-dark")
    compose.runOnIdle { dark = false }
    capture("controls-$screen-light")
    compose.runOnIdle { italian = true }
    capture("controls-$screen-italian")
    compose.runOnIdle {
      scale = 1.5f
      dark = true
    }
    compose.onNodeWithTag("playback-forward").performScrollTo()
    capture("controls-$screen-large-type")
  }
}
