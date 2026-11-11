package io.github.stupside.castor.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.stupside.castor.FCastQr
import io.github.stupside.castor.R
import io.github.stupside.castor.theme.CastorTheme
import java.util.Base64
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FCastScannerTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val paired = mutableListOf<FCastQr.Receiver>()
  private val encrypted = mutableListOf<FCastQr.Receiver>()
  private var cancelled = 0
  private var feed: ((String) -> Unit)? = null

  @Test
  fun theScannerShowsWhereToAimWhatToAimAtAndAWayBack() {
    show()
    compose.onNodeWithTag("fake-camera").assertExists()
    compose.onNodeWithTag("fcast-frame").assertIsDisplayed()
    compose
      .onNodeWithTag("fcast-instruction")
      .assertIsDisplayed()
      .assertTextEquals(compose.activity.getString(R.string.fcast_scan_title))
    compose.onNodeWithTag("fcast-cancel").assertIsDisplayed()
    compose
      .onNodeWithContentDescription(compose.activity.getString(R.string.back))
      .assertExists()
    compose
      .onNodeWithTag("fcast-cancel")
      .assertWidthIsAtLeast(48.dp)
      .assertHeightIsAtLeast(48.dp)
    compose.onNodeWithTag("fcast-wrong").assertDoesNotExist()
  }

  @Test
  fun theXGoesBackWithoutPairing() {
    show()
    compose.onNodeWithTag("fcast-cancel").performClick()
    compose.runOnIdle { assertEquals(1, cancelled) }
    assertTrue(paired.isEmpty())
    assertTrue(encrypted.isEmpty())
  }

  @Test
  fun backGoesBackWithoutPairing() {
    show()
    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.runOnIdle { assertEquals(1, cancelled) }
    assertTrue(paired.isEmpty())
    assertTrue(encrypted.isEmpty())
  }

  @Test
  fun aCodeThatIsNotFCastIsSaidAtOnceAndScanningGoesOn() {
    show()
    feedText("not-an-fcast-code")
    compose
      .onNodeWithTag("fcast-wrong")
      .assertIsDisplayed()
      .assertTextEquals(compose.activity.getString(R.string.fcast_wrong_code))
    assertTrue(paired.isEmpty())
    compose.runOnIdle { repeat(20) { feed!!.invoke("not-an-fcast-code") } }
    compose.onAllNodesWithTag("fcast-wrong").assertCountEquals(1)
    assertTrue(paired.isEmpty())
    feedText(code(plain("192.0.2.10")))
    assertEquals(1, paired.size)
    assertEquals("Receiver", paired.single().name)
    assertEquals(46899, paired.single().port)
    compose.onNodeWithTag("fcast-wrong").assertDoesNotExist()
  }

  @Test
  fun aCodeOfAnotherDeviceIsSaidAndNotPaired() {
    show()
    feedText(code(plain("198.51.100.7")))
    compose
      .onNodeWithTag("fcast-other")
      .assertIsDisplayed()
      .assertTextEquals(compose.activity.getString(R.string.fcast_other_device, "Receiver"))
    assertTrue(paired.isEmpty())
  }

  @Test
  fun aReceiverThatNeedsEncryptionIsSaidAndNeverPaired() {
    show()
    val pin = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    val json =
      """{"name":"Receiver","addresses":["192.0.2.10"],"services":[{"port":46899,"type":0}],"txt":{"v":"4","fp":"$pin"}}"""
    feedText(code(json))
    compose
      .onNodeWithTag("fcast-encrypted")
      .assertIsDisplayed()
      .assertTextEquals(compose.activity.getString(R.string.fcast_encrypted, "Receiver"))
    assertEquals(1, encrypted.size)
    assertTrue(paired.isEmpty())
    feedText(code(json))
    assertEquals(1, encrypted.size)
    assertTrue(paired.isEmpty())
  }

  @Test
  fun withoutPermissionTheScannerExplainsAndAsks() {
    var asked = 0
    show(CameraAccess(available = true, granted = false, refused = false, ask = { asked++ }, settings = {}))
    compose.onNodeWithTag("fcast-permission").assertIsDisplayed()
    compose.onNodeWithTag("fake-camera").assertDoesNotExist()
    compose.runOnIdle { assertTrue(asked >= 1) }
    val before = asked
    compose.onNodeWithTag("fcast-allow").performClick()
    compose.runOnIdle { assertTrue(asked > before) }
    compose.onNodeWithTag("fcast-cancel").assertIsDisplayed()
  }

  @Test
  fun aRefusedPermissionLeadsToTheSettings() {
    var opened = 0
    show(
      CameraAccess(
        available = true,
        granted = false,
        refused = true,
        ask = { fail("A refused permission is not asked again") },
        settings = { opened++ },
      )
    )
    compose
      .onNodeWithTag("fcast-allow")
      .assertTextEquals(compose.activity.getString(R.string.open_settings))
    compose.onNodeWithTag("fcast-allow").performClick()
    compose.runOnIdle { assertEquals(1, opened) }
  }

  @Test
  fun withoutACameraTheScannerSaysSo() {
    show(CameraAccess(available = false, granted = false, refused = false, ask = {}, settings = {}))
    compose.onNodeWithTag("fcast-no-camera").assertIsDisplayed()
    compose.onNodeWithTag("fake-camera").assertDoesNotExist()
    compose.onNodeWithTag("fcast-cancel").assertIsDisplayed().performClick()
    compose.runOnIdle { assertEquals(1, cancelled) }
  }

  private fun code(json: String): String =
    "fcast://r/" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

  private fun plain(address: String): String =
    """{"name":"Receiver","addresses":["$address"],"services":[{"port":46899,"type":0}]}"""

  private fun feedText(text: String) = compose.runOnIdle { feed!!.invoke(text) }

  private fun show(
    access: CameraAccess =
      CameraAccess(available = true, granted = true, refused = false, ask = {}, settings = {})
  ) {
    compose.setContent {
      CastorTheme {
        FCastScanner(
          "192.0.2.10",
          { paired += it },
          { encrypted += it },
          { cancelled++ },
          access,
        ) { onText ->
          feed = onText
          Box(Modifier.fillMaxSize().testTag("fake-camera"))
        }
      }
    }
  }
}
