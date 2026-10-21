package io.github.stupside.castor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import castor.v1.CastOuterClass.FailureCode
import castor.v1.DeviceOuterClass.DeviceType
import io.github.stupside.castor.data.DataRepository
import io.github.stupside.castor.ui.main.MainScreenViewModel
import io.github.stupside.castor.ui.main.SavedTv
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

// Opt in only when a DLNA TV is connected to this phone's hotspot.
@SdkSuppress(minSdkVersion = 36)
class DeviceDiscoveryHotspotTest {
  @Test
  fun nativeEngineConnectsByIdentityEvenWithAStaleSavedUrl() =
    runBlocking<Unit> {
      assumeTrue(InstrumentationRegistry.getArguments().getString("hotspot") == "true")
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      ActivityScenario.launch(MainActivity::class.java).use {
        val bound = CompletableDeferred<CastorService>()
        val connection =
          object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
              bound.complete((binder as CastorService.LocalBinder).service)
            }

            override fun onServiceDisconnected(name: ComponentName) {}
          }
        context.bindService(
          Intent(context, CastorService::class.java),
          connection,
          Context.BIND_AUTO_CREATE,
        )
        try {
          val service = withTimeout(10_000) { bound.await() }
          val api = withTimeout(30_000) { service.endpoint.filterNotNull().first() }
          val data = DataRepository(api)
          assumeTrue(
            "Stop any active cast before running the physical connection test",
            data.casts().isEmpty(),
          )
          val device = data.devices().first { it.type == DeviceType.DEVICE_TYPE_DLNA }
          val saved = SavedTv(device).copy(address = "http://127.0.0.1:1/stale-description.xml")
          MockWebServer().use { source ->
            // An invalid local source proves connection without playing media on the real TV.
            source.dispatcher =
              object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                  MockResponse().setResponseCode(404)
              }
            source.start()
            val id =
              data.cast(
                MainScreenViewModel.request(
                  source.url("/missing.mp4").toString(),
                  false,
                  saved.target(),
                  "1080",
                )
              )
            Log.i("CastorDiscoveryTest", "Native test cast: $id")
            try {
              var failedAfterConnection = false
              withTimeout(45_000) {
                data.watch(id) { update ->
                  if (update.hasEnded()) {
                    assertTrue(update.ended.hasFailed())
                    assertFalse(
                      update.ended.failed.message,
                      update.ended.failed.code == FailureCode.FAILURE_CODE_DEVICE_UNREACHABLE,
                    )
                    failedAfterConnection = true
                  }
                }
              }
              assertTrue(
                "The native cast did not deliver its terminal result",
                failedAfterConnection,
              )
              assertTrue("The native engine never reached source probing", source.requestCount > 0)
              Log.i(
                "CastorDiscoveryTest",
                "Native connection succeeded using ${device.name} at ${device.address}",
              )
            } finally {
              runCatching { data.stop(id) }
            }
          }
        } finally {
          context.unbindService(connection)
          context.stopService(Intent(context, CastorService::class.java))
        }
      }
    }

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
