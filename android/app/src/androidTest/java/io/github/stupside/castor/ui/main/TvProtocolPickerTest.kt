package io.github.stupside.castor.ui.main

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import castor.v1.DeviceOuterClass.*
import io.github.stupside.castor.R
import io.github.stupside.castor.theme.CastorTheme
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TvProtocolPickerTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test fun englishTVListOpensProtocolsAndBackPreservesTheList() = exercise(Locale.ENGLISH)

  @Test fun italianTVListOpensProtocolsAndBackPreservesTheList() = exercise(Locale.ITALIAN)

  private fun exercise(locale: Locale) {
    fun device(id: String, name: String, address: String, type: DeviceType) =
      Device.newBuilder().setId(id).setName(name).setAddress(address).setType(type).build()
    val chromecast =
      device(
        "chromecast:living",
        "Living room TV",
        "192.0.2.10:8009",
        DeviceType.DEVICE_TYPE_CHROMECAST,
      )
    val dlna =
      device(
        "dlna:living",
        "Living room renderer",
        "http://192.0.2.10:1676/description.xml",
        DeviceType.DEVICE_TYPE_DLNA,
      )
    val bedroom =
      device(
        "chromecast:bedroom",
        "Bedroom TV",
        "192.0.2.20:8009",
        DeviceType.DEVICE_TYPE_CHROMECAST,
      )
    val state =
      MainScreenUiState(
        ready = true,
        discoveryComplete = true,
        devices = listOf(chromecast, dlna, bedroom),
      )
    var selected: SavedTv? = null
    val configuration =
      Configuration(compose.activity.resources.configuration).apply { setLocale(locale) }
    val localized = compose.activity.createConfigurationContext(configuration)
    compose.setContent {
      CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides configuration,
        LocalResources provides localized.resources,
      ) {
        CastorTheme { TvPicker(state, null, {}, {}, { selected = it }) }
      }
    }
    compose.onAllNodesWithText("Living room TV").assertCountEquals(1)
    compose.onNodeWithText("Living room renderer").assertDoesNotExist()
    compose.onNodeWithText("192.0.2.10:8009 · 192.0.2.10:1676").assertExists()
    compose.onNodeWithText("Chromecast").assertDoesNotExist()
    compose.onNodeWithText("DLNA").assertDoesNotExist()
    compose.onNodeWithText("Living room TV").performClick()
    compose.onNodeWithText("Chromecast").assertExists()
    compose.onNodeWithText("DLNA").assertExists()
    compose.onNodeWithText("Bedroom TV").assertDoesNotExist()
    compose.onNodeWithTag("receiver-back").performScrollTo().performClick()
    compose.onNodeWithText("Bedroom TV").assertExists()
    compose.onNodeWithText("DLNA").assertDoesNotExist()
    assertNull(selected)
    compose.onNodeWithText("Living room TV").performClick()
    compose.onNodeWithTag("receiver-protocol-${DeviceType.DEVICE_TYPE_DLNA_VALUE}").performClick()
    assertEquals(dlna.id, selected?.id)
    assertEquals(dlna.address, selected?.address)
    compose.onNodeWithTag("receiver-back").performScrollTo().performClick()
    compose.onNodeWithText("Bedroom TV").performClick()
    compose.onNodeWithText("Chromecast").assertExists()
    compose.onNodeWithText("DLNA").assertDoesNotExist()
    compose.onNodeWithText(localized.getString(R.string.back)).assertExists()
  }
}
