package io.github.stupside.castor

import android.util.Log
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import castor.v1.DeviceOuterClass.DeviceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

// Opt in only when a DLNA TV is connected to this phone's hotspot.
@SdkSuppress(minSdkVersion = 36)
class DeviceDiscoveryHotspotTest {
  @Test
  fun findsDlnaTvOnPhoneHotspot() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("hotspot") == "true")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val devices = DeviceDiscovery(context).find()
    Log.i("CastorDiscoveryTest", "Discovered: ${devices.map { "${it.name}: ${it.address}" }}")
    assertTrue(
      "No DLNA TV found on the phone hotspot",
      devices.any { it.type == DeviceType.DEVICE_TYPE_DLNA },
    )
  }
}
