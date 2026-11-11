package io.github.stupside.castor.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import castor.v1.DeviceOuterClass.*
import io.github.stupside.castor.FCastQr
import io.github.stupside.castor.R
import io.github.stupside.castor.theme.CastorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FCastPickerTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val deviceList = mutableStateOf<List<Device>>(emptyList())
  private val pairingList = mutableStateOf<List<FCastPairing>>(emptyList())
  // Changing the round remounts the picker, so a fresh one starts with no choice open.
  private val round = mutableStateOf(0)

  private val pairs = mutableListOf<FCastPairing>()
  private var selected: SavedTv? = null
  private var scannerHost: String? = null
  private var paired: (FCastQr.Receiver) -> Unit = {}
  private var encrypted: (FCastQr.Receiver) -> Unit = {}
  private var cancel: () -> Unit = {}

  private val fcastRow = "receiver-protocol-${DeviceType.DEVICE_TYPE_FCAST_VALUE}"

  @Test
  fun everyTVOffersFCastAndATVWithoutAReceiverAsksForItsCode() {
    val chromecast = device("chromecast:living", "Living room TV", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
    val bedroom = device("dlna:bedroom", "Bedroom TV", "http://192.0.2.20:1676/description.xml", DeviceType.DEVICE_TYPE_DLNA)
    start(listOf(chromecast, bedroom))
    compose.onNodeWithText("Living room TV").performClick()
    compose.onNodeWithTag(fcastRow).performScrollTo()
    compose.onNodeWithText(compose.activity.getString(R.string.fcast_scan_hint)).assertExists()
    compose.onNodeWithTag(fcastRow).performClick()
    compose.onNodeWithTag("fake-scanner").assertExists()
    assertEquals("192.0.2.10", scannerHost)
    compose.runOnIdle { cancel() }
    compose.onNodeWithTag("fake-scanner").assertDoesNotExist()
    assertNull(selected)
    assertTrue(pairs.isEmpty())
    compose.onNodeWithTag("receiver-back").performScrollTo().performClick()
    compose.onNodeWithText("Bedroom TV").performScrollTo().performClick()
    compose.onNodeWithTag(fcastRow).assertExists()
  }

  @Test
  fun aScannedCodePairsTheTVAndChoosesFCast() {
    val chromecast = device("chromecast:living", "Living room TV", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
    start(listOf(chromecast))
    compose.onNodeWithText("Living room TV").performClick()
    compose.onNodeWithTag(fcastRow).performScrollTo().performClick()
    compose.runOnIdle {
      paired(FCastQr.Receiver("Receiver", listOf("192.0.2.10"), 46899, null, null))
    }
    val pairing = pairs.single()
    assertEquals(setOf("${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:living"), pairing.identities)
    assertEquals("192.0.2.10", pairing.host)
    assertEquals(46899, pairing.port)
    assertEquals(
      SavedTv(
        "Receiver",
        "192.0.2.10",
        DeviceType.DEVICE_TYPE_FCAST,
        pairedFor = setOf("${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:living"),
      ),
      selected,
    )
    compose.onNodeWithTag("fake-scanner").assertDoesNotExist()
  }

  @Test
  fun aPairedTVListsFCastWithoutScanning() {
    val chromecast = device("chromecast:living", "Living room TV", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
    val old =
      FCastPairing(
        setOf("${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:living"),
        "Receiver",
        46899,
        null,
        null,
        "192.0.2.99",
      )
    start(listOf(chromecast), listOf(old))
    compose.onNodeWithText("Living room TV").performClick()
    compose.onNodeWithText(compose.activity.getString(R.string.fcast_paired)).assertExists()
    compose.onNodeWithText("192.0.2.10:46899").assertExists()
    compose.onNodeWithTag(fcastRow).performScrollTo().performClick()
    assertEquals(
      SavedTv(
        "Receiver",
        "192.0.2.10",
        DeviceType.DEVICE_TYPE_FCAST,
        pairedFor = setOf("${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:living"),
      ),
      selected,
    )
    compose.onNodeWithTag("fake-scanner").assertDoesNotExist()
    compose.onNodeWithTag("fcast-rescan").performScrollTo().performClick()
    compose.onNodeWithTag("fake-scanner").assertExists()
  }

  @Test
  fun anotherTVAtTheSameAddressMustScanItsOwnCode() {
    val other = device("chromecast:other", "Living room TV", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
    val old =
      FCastPairing(
        setOf("${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:living"),
        "Receiver",
        46899,
        null,
        null,
        "192.0.2.10",
      )
    start(listOf(other), listOf(old))
    compose.onNodeWithText("Living room TV").performClick()
    compose.onNodeWithTag(fcastRow).performScrollTo()
    compose.onNodeWithText(compose.activity.getString(R.string.fcast_scan_hint)).assertExists()
    compose.onNodeWithTag(fcastRow).performClick()
    compose.onNodeWithTag("fake-scanner").assertExists()
    assertNull(selected)
  }

  @Test
  fun anAnnouncedReceiverIsChosenAsBefore() {
    val chromecast = device("chromecast:living", "Living room TV", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
    val receiver = device("Receiver@192.0.2.10", "Receiver", "192.0.2.10", DeviceType.DEVICE_TYPE_FCAST)
    start(listOf(chromecast, receiver))
    compose.onNodeWithText("Living room TV").performClick()
    compose.onAllNodesWithTag(fcastRow).assertCountEquals(1)
    compose.onNodeWithTag(fcastRow).performScrollTo().performClick()
    assertEquals(SavedTv(receiver), selected)
    compose.onNodeWithTag("fake-scanner").assertDoesNotExist()
  }

  @Test
  fun aReceiverScannedAsEncryptedIsNeverOffered() {
    val chromecast = device("chromecast:living", "Living room TV", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
    val receiver = device("Receiver@192.0.2.10", "Receiver", "192.0.2.10", DeviceType.DEVICE_TYPE_FCAST)
    val encryptedPairing =
      FCastPairing(
        setOf("${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:living"),
        "Receiver",
        46899,
        4,
        null,
        "192.0.2.10",
      )
    start(listOf(chromecast, receiver), listOf(encryptedPairing))
    compose.onNodeWithText("Living room TV").performClick()
    compose.onAllNodesWithTag(fcastRow).assertCountEquals(1)
    compose
      .onNodeWithText(compose.activity.getString(R.string.fcast_encrypted, "Receiver"))
      .assertExists()
    compose.onNodeWithTag(fcastRow).performScrollTo().performClick()
    compose.onNodeWithTag("fake-scanner").assertExists()
    assertNull(selected)
    assertTrue(pairs.isEmpty())

    // A fresh picker that has no pairing yet: the scanned code of the same TV is kept as encrypted.
    remount(listOf(chromecast))
    compose.onNodeWithText("Living room TV").performClick()
    compose.onNodeWithTag(fcastRow).performScrollTo().performClick()
    compose.onNodeWithTag("fake-scanner").assertExists()
    compose.runOnIdle {
      encrypted(FCastQr.Receiver("Receiver", listOf("192.0.2.10"), 46899, 4, null))
    }
    val pairing = pairs.single()
    assertEquals(4, pairing.version)
    assertTrue(pairing.encrypted)
    assertNull(selected)
  }

  @Test
  fun aTVWithoutIdentityIsPairedOnlyForThisChoice() {
    val old = device("", "Old renderer", "http://192.0.2.30:1000/d.xml", DeviceType.DEVICE_TYPE_DLNA)
    start(listOf(old))
    compose.onNodeWithText("Old renderer").performClick()
    compose.onNodeWithTag(fcastRow).performScrollTo().performClick()
    compose.runOnIdle {
      paired(FCastQr.Receiver("Receiver", listOf("192.0.2.30"), 46899, null, null))
    }
    assertEquals(SavedTv("Receiver", "192.0.2.30", DeviceType.DEVICE_TYPE_FCAST, manual = true, once = true), selected)
    val pairing = pairs.single()
    assertTrue(pairing.identities.isEmpty())
    assertEquals(emptyList<FCastPairing>(), decodePairings(encodePairings(listOf(pairing))))
  }

  @Test
  fun anOpenScannerComesBackAfterTheActivityIsRecreated() {
    val chromecast = device("chromecast:living", "Living room TV", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
    val state = MainScreenUiState(ready = true, discoveryComplete = true, devices = listOf(chromecast))
    val restoration = StateRestorationTester(compose)
    restoration.setContent {
      CastorTheme {
        TvPicker(
          state,
          null,
          {},
          {},
          {},
          select = { selected = it },
          pairings = emptyList(),
          pair = { pairs += it },
          scanner = { host, onPaired, onEncrypted, onCancel ->
            scannerHost = host
            paired = onPaired
            encrypted = onEncrypted
            cancel = onCancel
            Box(Modifier.fillMaxSize().testTag("fake-scanner"))
          },
        )
      }
    }
    compose.onNodeWithText("Living room TV").performClick()
    compose.onNodeWithTag(fcastRow).performScrollTo().performClick()
    compose.onNodeWithTag("fake-scanner").assertExists()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag("fake-scanner").assertExists()
    assertEquals("192.0.2.10", scannerHost)
    compose.runOnIdle { cancel() }
    compose.onNodeWithTag("fake-scanner").assertDoesNotExist()
    compose.onNodeWithTag(fcastRow).assertIsDisplayed()
    assertNull(selected)
    assertTrue(pairs.isEmpty())
  }

  @Test
  fun aSavedPairingCanBeReplacedWhileTheReceiverIsAnnounced() {
    val chromecast = device("chromecast:living", "Living room TV", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
    val receiver = device("Receiver@192.0.2.10", "Receiver", "192.0.2.10", DeviceType.DEVICE_TYPE_FCAST)
    val saved =
      FCastPairing(
        setOf("${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:living"),
        "Receiver",
        40000,
        null,
        null,
        "192.0.2.10",
      )
    start(listOf(chromecast, receiver), listOf(saved))
    compose.onNodeWithText("Living room TV").performClick()
    compose.onNodeWithTag(fcastRow).assertExists()
    compose.onNodeWithTag("fcast-rescan").performScrollTo().performClick()
    compose.onNodeWithTag("fake-scanner").assertExists()
    assertEquals("192.0.2.10", scannerHost)
    compose.runOnIdle {
      paired(FCastQr.Receiver("Receiver", listOf("192.0.2.10"), 46899, null, null))
    }
    val pairing = pairs.single()
    assertEquals(46899, pairing.port)
    assertEquals(setOf("${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:living"), pairing.identities)
    assertEquals(
      SavedTv(receiver).copy(pairedFor = setOf("${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:living")),
      selected,
    )
    compose.onNodeWithTag("fake-scanner").assertDoesNotExist()
  }

  @Test
  fun anEncryptedCodeOfATVWithoutIdentityStaysRefusedWhenThePickerIsOpenedAgain() {
    val old = device("", "Old renderer", "http://192.0.2.30:1000/d.xml", DeviceType.DEVICE_TYPE_DLNA)
    start(listOf(old))
    compose.onNodeWithText("Old renderer").performClick()
    compose.onNodeWithTag(fcastRow).performScrollTo().performClick()
    compose.onNodeWithTag("fake-scanner").assertExists()
    compose.runOnIdle {
      encrypted(FCastQr.Receiver("Receiver", listOf("192.0.2.30"), 46899, 4, "A".repeat(43) + "="))
    }
    val pairing = pairs.single()
    assertTrue(pairing.identities.isEmpty())
    assertEquals(4, pairing.version)
    assertNull(selected)

    // Opened again, the same TV also announces its receiver: the code still says it is encrypted.
    val announced = device("Receiver@192.0.2.30", "Receiver", "192.0.2.30", DeviceType.DEVICE_TYPE_FCAST)
    remount(listOf(old, announced), listOf(pairing))
    compose.onNodeWithText("Old renderer").performClick()
    compose.onAllNodesWithTag(fcastRow).assertCountEquals(1)
    compose
      .onNodeWithText(compose.activity.getString(R.string.fcast_encrypted, "Receiver"))
      .assertExists()
    // The announced receiver is not offered beside it.
    compose.onNodeWithText("192.0.2.30").assertDoesNotExist()
    compose.onNodeWithTag(fcastRow).performScrollTo().performClick()
    compose.onNodeWithTag("fake-scanner").assertExists()
    assertNull(selected)
  }

  @Test
  fun anEncryptedCodeReadBeforeTheTVHadAnIdentityIsStillRefusedOnceItsIdentityArrives() {
    val old = device("", "Old renderer", "http://192.0.2.30:1000/d.xml", DeviceType.DEVICE_TYPE_DLNA)
    start(listOf(old))
    compose.onNodeWithText("Old renderer").performClick()
    compose.onNodeWithTag(fcastRow).performScrollTo().performClick()
    compose.onNodeWithTag("fake-scanner").assertExists()
    compose.runOnIdle {
      encrypted(FCastQr.Receiver("Receiver", listOf("192.0.2.30"), 46899, 4, "A".repeat(43) + "="))
    }
    val pairing = pairs.single()
    assertTrue(pairing.identities.isEmpty())
    assertNull(selected)

    // Opened again, the same TV now has an identity and also announces its receiver: the code still says it is encrypted.
    val late = device("chromecast:late", "Old renderer TV", "192.0.2.30:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
    val announced = device("Receiver@192.0.2.30", "Receiver", "192.0.2.30", DeviceType.DEVICE_TYPE_FCAST)
    remount(listOf(old, late, announced), listOf(pairing))
    compose.onNodeWithTag("receiver-tv-192.0.2.30").performClick()
    compose.onAllNodesWithTag(fcastRow).assertCountEquals(1)
    compose
      .onNodeWithText(compose.activity.getString(R.string.fcast_encrypted, "Receiver"))
      .assertExists()
    compose.onNodeWithTag(fcastRow).performScrollTo().performClick()
    compose.onNodeWithTag("fake-scanner").assertExists()
    assertNull(selected)
  }

  @Test
  fun choosingTheAnnouncedReceiverOfAPairedTVKeepsTheTVsIdentity() {
    val identity = "${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:living"
    val chromecast = device("chromecast:living", "Living room TV", "192.0.2.10:8009", DeviceType.DEVICE_TYPE_CHROMECAST)
    val receiver = device("Receiver@192.0.2.10", "Receiver", "192.0.2.10", DeviceType.DEVICE_TYPE_FCAST)
    val pairing = FCastPairing(setOf(identity), "Receiver", 46899, null, null, "192.0.2.10")
    start(listOf(chromecast, receiver), listOf(pairing))
    compose.onNodeWithText("Living room TV").performClick()
    compose.onNodeWithTag(fcastRow).performScrollTo().performClick()
    assertEquals(SavedTv(receiver).copy(pairedFor = setOf(identity)), selected)
    compose.onNodeWithTag("fake-scanner").assertDoesNotExist()
  }

  private fun device(id: String, name: String, address: String, type: DeviceType): Device =
    Device.newBuilder().setId(id).setName(name).setAddress(address).setType(type).build()

  // Sets the TVs and pairings the picker shows, then mounts it once.
  private fun start(devices: List<Device>, pairings: List<FCastPairing> = emptyList()) {
    deviceList.value = devices
    pairingList.value = pairings
    compose.setContent {
      CastorTheme {
        key(round.value) {
          TvPicker(
            MainScreenUiState(
              ready = true,
              discoveryComplete = true,
              devices = deviceList.value,
            ),
            null,
            {},
            {},
            {},
            select = { selected = it },
            pairings = pairingList.value,
            pair = { pairs += it },
            scanner = { host, onPaired, onEncrypted, onCancel ->
              scannerHost = host
              paired = onPaired
              encrypted = onEncrypted
              cancel = onCancel
              Box(Modifier.fillMaxSize().testTag("fake-scanner"))
            },
          )
        }
      }
    }
  }

  // Shows the picker again with other TVs and pairings, as a fresh picker with no open choice.
  private fun remount(devices: List<Device>, pairings: List<FCastPairing> = emptyList()) {
    compose.runOnIdle {
      deviceList.value = devices
      pairingList.value = pairings
      round.value++
    }
  }
}
