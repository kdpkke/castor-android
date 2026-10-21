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
import androidx.test.platform.app.InstrumentationRegistry
import castor.v1.CastOuterClass.*
import castor.v1.DeviceOuterClass.*
import io.github.stupside.castor.theme.AppTheme
import io.github.stupside.castor.theme.CastorTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

// Visual evidence uses explicitly synthetic states; no real TV or external site is contacted.
class DesignEvidenceTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun capturesNativeStatesAndAccessibilityVariants() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.getSharedPreferences("casting-options", 0).edit().clear().commit()
    val model = MainScreenViewModel()
    model.uiState.value = MainScreenUiState(ready = true, phase = CastPhase.Ready)
    var dark by mutableStateOf(false)
    var fontScale by mutableFloatStateOf(1f)
    var italian by mutableStateOf(false)
    compose.setContent {
      val configuration =
        Configuration(LocalConfiguration.current).apply {
          uiMode =
            (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
              if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
          if (italian) setLocale(java.util.Locale.ITALIAN)
        }
      CompositionLocalProvider(
        LocalConfiguration provides configuration,
        LocalContext provides context.createConfigurationContext(configuration),
        LocalDensity provides Density(LocalDensity.current.density, fontScale),
      ) {
        CastorTheme(AppTheme.System) {
          MainScreen(model, null, "https://example.org/watch", {}, theme = AppTheme.System)
        }
      }
    }
    fun capture(name: String) {
      compose.waitForIdle()
      compose.runOnUiThread {
        WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
          .apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
          }
      }
      // Compose idleness precedes the system-bar transaction reaching SurfaceFlinger.
      android.os.SystemClock.sleep(250)
      val file = File(context.getExternalFilesDir(null), "design-review/$name.png")
      file.parentFile!!.mkdirs()
      val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
      try {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      } finally {
        bitmap.recycle()
      }
    }
    capture("phone")
    compose.onNodeWithContentDescription("More options").performClick()
    capture("options")
    compose.onNodeWithText("App language").performClick()
    compose.runOnIdle { dark = true }
    capture("language-dark")
    compose.runOnIdle { fontScale = 1.5f }
    capture("language-large-type")
    compose.onNodeWithContentDescription("Close").performClick()
    compose.runOnIdle {
      dark = false
      fontScale = 1f
    }
    compose.onNodeWithText("Automatic").performClick()
    capture("source-modes")
    compose.onNodeWithContentDescription("Close").performClick()
    compose.onNodeWithText("Choose your TV").performClick()
    compose.onNodeWithText("No TVs found yet").assertExists()
    capture("receivers")
    compose.onNodeWithText("Enter a TV address").performClick()
    capture("manual")
    compose.onNodeWithContentDescription("Close").performClick()
    compose.onNodeWithText("Playback settings").performScrollTo().performClick()
    capture("settings")
    compose.onNodeWithText("Native quality").performClick().assertIsSelected()
    capture("settings-native")
    compose.onNodeWithText("720p").performClick()
    compose.onNodeWithContentDescription("Close").performClick()
    compose.onNodeWithText("Save your changes?").assertIsDisplayed()
    capture("settings-unsaved")
    compose.runOnIdle { dark = true }
    capture("settings-unsaved-dark")
    compose.runOnIdle { dark = false }
    compose.onNodeWithText("Don’t save").performClick()
    compose.onNodeWithText("Playback settings").performScrollTo().performClick()
    compose.onNodeWithText("Save").performClick()
    compose.runOnIdle { dark = true }
    capture("phone-dark")
    compose.runOnIdle {
      dark = false
      fontScale = 1.5f
    }
    compose.onNodeWithTag("cast-action").assertIsDisplayed()
    capture("phone-large-type")
    compose.runOnIdle {
      fontScale = 1f
      italian = true
    }
    capture("phone-italian")
    compose.runOnIdle {
      italian = false
      model.uiState.value =
        model.uiState.value.copy(
          castId = "synthetic-cast",
          phase = CastPhase.Casting,
          activeCast =
            Cast.newBuilder()
              .setId("synthetic-cast")
              .setSource(
                Source.newBuilder()
                  .setPages(Source.Pages.newBuilder().addUrls("https://active.example.org/fixture"))
              )
              .setDevice(
                Device.newBuilder()
                  .setName("Living room · fixture TV")
                  .setAddress("192.0.2.65")
                  .setType(DeviceType.DEVICE_TYPE_DLNA)
              )
              .build(),
        )
    }
    compose.onNodeWithText("Stop casting").assertIsEnabled()
    capture("active-fixture")
    compose.runOnIdle {
      model.uiState.value =
        model.uiState.value.copy(
          castId = null,
          phase = CastPhase.Failed,
          error =
            "device-refused: fixture receiver did not acknowledge playback\nDiagnostic details for visual review.",
        )
    }
    compose.onNodeWithTag("cast-error").performScrollTo()
    compose.onNodeWithText("Copy error").assertIsDisplayed()
    capture("error-fixture")
    context.getSharedPreferences("casting-options", 0).edit().clear().commit()
  }
}
