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

  @Test
  fun aTypedAddressIsAskedOnEveryProtocolAndOpensWhatAnswers() {
    val context = compose.activity
    val state = androidx.compose.runtime.mutableStateOf(MainScreenUiState(ready = true, discoveryComplete = true))
    val searched = mutableListOf<String?>()
    compose.setContent { CastorTheme { TvPicker(state.value, null, {}, { searched += it }, {}, {}) } }
    compose.onNodeWithText(context.getString(R.string.add_manually)).performScrollTo().performClick()
    val find = compose.onNodeWithText(context.getString(R.string.find_address))
    compose.onNode(hasSetTextAction()).performTextInput("192.0.2.21:7000")
    find.assertIsNotEnabled()
    compose.onNode(hasSetTextAction()).performTextClearance()
    compose.onNode(hasSetTextAction()).performTextInput("192.0.2.21")
    find.assertIsEnabled().performClick()
    assertEquals(listOf<String?>("192.0.2.21"), searched)
    fun device(id: String, address: String, type: DeviceType) =
      Device.newBuilder().setId(id).setName("Living room").setAddress(address).setType(type).build()
    compose.runOnIdle {
      state.value =
        state.value.copy(
          devices =
            listOf(
              device("AA:BB:CC:DD:EE:01", "192.0.2.21:7000", DeviceType.DEVICE_TYPE_AIRPLAY),
              device("a1b2c3d400004000800000000000c0de", "192.0.2.21:8009", DeviceType.DEVICE_TYPE_CHROMECAST),
            )
        )
    }
    // The answering TV opens with its protocols, as if chosen from the list.
    compose.onNodeWithTag("receiver-protocol-${DeviceType.DEVICE_TYPE_AIRPLAY_VALUE}").assertExists()
    compose.onNodeWithTag("receiver-protocol-${DeviceType.DEVICE_TYPE_CHROMECAST_VALUE}").assertExists()
    compose.runOnIdle { state.value = state.value.copy(devices = emptyList()) }
    compose.onNodeWithText(context.getString(R.string.address_not_found, "192.0.2.21")).assertExists()
  }

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
        CastorTheme { TvPicker(state, null, {}, {}, {}, { selected = it }) }
      }
    }
    compose.onAllNodesWithText("Living room TV").assertCountEquals(1)
    compose.onNodeWithText("Living room renderer").assertDoesNotExist()
    compose.onNodeWithText("192.0.2.10 · Chromecast, DLNA").assertExists()
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
