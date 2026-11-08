package io.github.stupside.castor.ui.main

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import castor.v1.CastOuterClass.*
import castor.v1.DeviceOuterClass.*
import castor.v1.StreamOuterClass.Stream
import io.github.stupside.castor.ManualDiagnostic
import io.github.stupside.castor.R
import io.github.stupside.castor.theme.AppTheme
import io.github.stupside.castor.theme.CastorTheme
import java.io.File
import java.util.Locale
import org.junit.Rule
import org.junit.Test

// Manual native captures with synthetic session data. No receiver or website is contacted.
@ManualDiagnostic
class MultipleCastEvidenceTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun capturesMultipleTransmissionControlsAndDraft() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    compose.runOnUiThread {
      compose.activity.enableEdgeToEdge()
      compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    fun cast(id: String, name: String, live: Boolean = false) =
      Cast.newBuilder()
        .setId(id)
        .setDevice(
          Device.newBuilder()
            .setId(id)
            .setName(name)
            .setAddress("192.0.2.${if(live)20 else 10}:8009")
            .setType(DeviceType.DEVICE_TYPE_CHROMECAST)
        )
        .setSource(
          Source.newBuilder().setStream(Stream.newBuilder().setUrl("https://example.test/$id"))
        )
        .setStatus(
          CastStatus.newBuilder()
            .setCasting(
              CastingStatus.newBuilder().setAttempt(1).setAutomaticRestarts(if (live) 1 else 0)
            )
        )
        .build()
    val finite = cast("movie", "Living room TV")
    val live = cast("live", "Bedroom TV", true)
    val model = MainScreenViewModel()
    model.uiState.value =
      MainScreenUiState(
        ready = true,
        sessions =
          listOf(
            CastSessionUiState(
              finite,
              CastPhase.Casting,
              playback =
                PlaybackUiState(
                  positionMs = 1_894_000,
                  durationMs = 7_200_000,
                  available = true,
                  canPause = true,
                  canSeek = true,
                ),
            ),
            CastSessionUiState(
              live,
              CastPhase.Casting,
              playback = PlaybackUiState(positionMs = 42_000, available = true, canPause = true),
            ),
          ),
        selectedCastId = finite.id,
      )
    var picker by mutableStateOf(false)
    var dark by mutableStateOf(true)
    var italian by mutableStateOf(false)
    var fontScale by mutableFloatStateOf(1f)
    compose.setContent {
      val observed by model.uiState.collectAsState()
      val configuration =
        Configuration(LocalConfiguration.current).apply {
          setLocale(if (italian) Locale.ITALIAN else Locale.ENGLISH)
        }
      val localized = context.createConfigurationContext(configuration)
      CompositionLocalProvider(
        LocalConfiguration provides configuration,
        LocalContext provides localized,
        LocalResources provides localized.resources,
        LocalDensity provides Density(LocalDensity.current.density, fontScale),
      ) {
        CastorTheme(if (dark) AppTheme.Dark else AppTheme.Light) {
          MainScreen(model, null, "https://example.test/next-video.mp4", {})
          if (picker) {
            val devices =
              listOf(
                finite.device,
                live.device,
                finite.device
                  .toBuilder()
                  .setId("dlna:movie")
                  .setName("Living room renderer")
                  .setType(DeviceType.DEVICE_TYPE_DLNA)
                  .setAddress("http://192.0.2.10:1676/device.xml")
                  .build(),
              )
            TvPicker(
              observed.copy(devices = devices, discoveryComplete = true),
              null,
              { picker = false },
              {},
              {},
            )
          }
        }
      }
    }
    val deviceClass =
      InstrumentationRegistry.getArguments().getString("evidenceDeviceClass") ?: "phone"
    fun capture(state: String) {
      compose.runOnUiThread {
        androidx.core.view.WindowCompat.getInsetsController(
            compose.activity.window,
            compose.activity.window.decorView,
          )
          .apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
          }
      }
      compose.waitForIdle()
      android.os.SystemClock.sleep(800)
      val file =
        File(context.getExternalFilesDir(null), "multiple-cast-review/$deviceClass-$state.png")
      file.parentFile!!.mkdirs()
      InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
      }
    }
    compose.onNodeWithTag("restart-casting").assertIsDisplayed().assertIsEnabled()
    compose.onNodeWithTag("cast-action").assertIsDisplayed().assertIsEnabled()
    capture("dark")
    compose.runOnIdle {
      dark = false
      italian = true
      fontScale = 1.5f
    }
    compose.onNodeWithTag("restart-casting").assertIsDisplayed().assertIsEnabled()
    capture("italian-large")
    compose.runOnIdle {
      dark = true
      italian = false
      fontScale = 1f
      model.selectCast(live.id)
    }
    compose
      .onNodeWithText(context.getString(R.string.live_restarted_automatically))
      .performScrollTo()
      .assertIsDisplayed()
    capture("live-recovery")
    compose.onNodeWithTag("new-cast").performScrollTo().performClick()
    compose.onNodeWithTag("video-url").performScrollTo().assertIsDisplayed()
    capture("new-cast")
    compose.runOnIdle { picker = true }
    compose.onNodeWithTag("receiver-tv-192.0.2.10").assertIsDisplayed()
    capture("tv-list")
    compose.onNodeWithTag("receiver-tv-192.0.2.10").performClick()
    compose.onNodeWithTag("receiver-back").assertIsDisplayed()
    capture("tv-protocols")
    compose.runOnIdle {
      dark = false
      italian = true
      fontScale = 1.5f
    }
    compose.onNodeWithTag("receiver-back").performScrollTo().assertIsDisplayed()
    capture("tv-protocols-italian-large")
  }
}
