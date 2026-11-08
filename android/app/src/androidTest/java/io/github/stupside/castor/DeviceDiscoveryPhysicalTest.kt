package io.github.stupside.castor

import androidx.test.platform.app.InstrumentationRegistry
import castor.v1.DeviceOuterClass.Device
import castor.v1.DeviceOuterClass.DeviceType
import io.github.stupside.castor.ui.main.CastOptions
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Explicit physical diagnostic only; ordinary tests never probe the owner's LAN.
@ManualDiagnostic
class DeviceDiscoveryPhysicalTest {
  @Test
  fun aSavedChromecastIsVerifiedWithoutDependingOnItsMulticastAnnouncement() = runBlocking {
    val address = InstrumentationRegistry.getArguments().getString("expectedChromecast")
    assumeTrue("Supply the owner's receiver address explicitly", address != null)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val saved = requireNotNull(CastOptions(context).television())
    assertEquals(address, saved.address)
    assertEquals(DeviceType.DEVICE_TYPE_CHROMECAST, saved.type)
    val candidate = Device.newBuilder().setId(saved.id).setName(saved.name)
      .setAddress(saved.address).setType(saved.type).build()
    val confirmed = DeviceDiscovery.rememberedChromecast(candidate)
    assertNotNull("The saved TV must be reachable and identify itself with its saved UUID", confirmed)
    assertEquals(saved.id.removePrefix("chromecast:"), confirmed!!.id)
    assertEquals(address, confirmed.address)
    val listed = DeviceDiscovery(context).find(candidate)
    assertEquals(1, listed.count { it.id == confirmed.id && it.address == address })
    File(context.getExternalFilesDir(null), "discovery-current.json").writeText(
      JSONObject().put("name", confirmed.name).put("address", confirmed.address)
        .put("id", confirmed.id).put("uuid_verified", true).toString()
    )
  }
}
