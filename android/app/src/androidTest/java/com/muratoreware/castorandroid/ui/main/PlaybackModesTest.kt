package com.muratoreware.castorandroid.ui.main

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.muratoreware.castorandroid.R
import com.muratoreware.castorandroid.theme.CastorTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaybackModesTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test fun englishChoicesExplainWhatEachModeDoesWithoutClosingTheSheet() = choices(Locale.ENGLISH)

  @Test fun italianChoicesExplainWhatEachModeDoesWithoutClosingTheSheet() = choices(Locale.ITALIAN)

  private fun choices(locale: Locale) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
    val localized = context.createConfigurationContext(configuration)
    val resources = localized.resources
    var saved: String? = null
    var savedDirect: Boolean? = null
    compose.setContent {
      CompositionLocalProvider(
        LocalContext provides localized,
        LocalResources provides resources,
        LocalConfiguration provides configuration,
      ) {
        CastorTheme {
          PlaybackSettings("native", "off", false, {}) { quality, _, direct ->
            saved = quality
            savedDirect = direct
          }
        }
      }
    }
    compose.onNodeWithText(resources.getString(R.string.quality_native)).assertIsSelected()
    compose.onNodeWithText(resources.getString(R.string.quality_native_help)).assertExists()
    val direct = compose.onNodeWithText(resources.getString(R.string.dlna_direct_download))
    direct.performScrollTo().assertIsOff().performClick().assertIsOn()
    compose
      .onNodeWithText(resources.getString(R.string.quality_optimized))
      .performScrollTo()
      .performClick()
      .assertIsSelected()
    compose.onNodeWithText(resources.getString(R.string.quality_optimized_help)).assertExists()
    direct.assertIsOn().assertIsNotEnabled()
    compose.onNodeWithContentDescription(resources.getString(R.string.close)).assertExists()
    compose.onNodeWithText("720p").assertDoesNotExist()
    compose.onNodeWithText(resources.getString(R.string.save)).performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals("optimized", saved)
      assertEquals(true, savedDirect)
    }
  }
}
