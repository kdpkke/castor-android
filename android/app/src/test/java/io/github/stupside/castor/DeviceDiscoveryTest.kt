package io.github.stupside.castor

import castor.v1.DeviceOuterClass.Device
import castor.v1.DeviceOuterClass.DeviceType
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class DeviceDiscoveryTest {
  private val id = "11223344556677889900aabbccddeeff"

  private fun remembered(port: Int) =
    Device.newBuilder()
      .setId("chromecast:$id")
      .setName("Saved TV")
      .setAddress("127.0.0.1:$port")
      .setType(DeviceType.DEVICE_TYPE_CHROMECAST)
      .build()

  private fun client(server: MockWebServer) =
    DeviceDiscovery.knownClient.newBuilder()
      .addInterceptor { chain ->
        assertEquals(8008, chain.request().url.port)
        assertEquals("/setup/eureka_info", chain.request().url.encodedPath)
        chain.proceed(chain.request().newBuilder().url(server.url("/setup/eureka_info")).build())
      }
      .build()

  @Test(timeout = 15_000)
  fun aKnownTvIsConfirmedByUuidAndCastPortWithoutAnMdnsAnnouncement() = runBlocking {
    MockWebServer().use { info ->
      ServerSocket(0).use { cast ->
        info.enqueue(
          MockResponse().setHeadersDelay(1200, TimeUnit.MILLISECONDS)
            .setBody("""{"name":"Current TV","ssdp_udn":"11223344-5566-7788-9900-aabbccddeeff"}""")
        )
        val saved = remembered(cast.localPort)
        val found = DeviceDiscovery.rememberedChromecast(saved, client(info))
        assertNotNull("An online, identity-verified TV must survive a missing multicast announcement", found)
        assertEquals(id, found!!.id)
        assertEquals(saved.address, found.address)
        assertEquals("Current TV", found.name)
        assertEquals(DeviceType.DEVICE_TYPE_CHROMECAST, found.type)
        assertEquals("chromecast:$id", saved.id)
      }
    }
  }

  @Test(timeout = 15_000)
  fun aReusedAddressOrInvalidDescriptionCannotImpersonateTheSavedTv() = runBlocking {
    for (body in listOf(
      """{"name":"Saved TV","ssdp_udn":"ffeeddcc-bbaa-0099-8877-665544332211"}""",
      """{"name":"Saved TV"}""",
      "<html>not a receiver</html>",
      "x".repeat(65_537),
    )) {
      MockWebServer().use { info ->
        info.enqueue(MockResponse().setBody(body))
        assertNull(DeviceDiscovery.rememberedChromecast(remembered(8009), client(info)))
      }
    }
  }

  @Test(timeout = 15_000)
  fun aTypedAddressIsNamedByTheIdentityItsChromecastReports() = runBlocking {
    MockWebServer().use { info ->
      ServerSocket(0).use { cast ->
        info.enqueue(
          MockResponse().setBody("""{"name":"Living room","ssdp_udn":"A1B2C3D4-0000-4000-8000-00000000C0DE"}""")
        )
        val found = DeviceDiscovery.chromecastAt("127.0.0.1", cast.localPort, client(info))!!
        assertEquals("a1b2c3d400004000800000000000c0de", found.id)
        assertEquals("Living room", found.name)
        assertEquals("127.0.0.1:${cast.localPort}", found.address)
        assertEquals(DeviceType.DEVICE_TYPE_CHROMECAST, found.type)
        info.enqueue(MockResponse().setBody("""{"name":"No identity"}"""))
        assertNull(DeviceDiscovery.chromecastAt("127.0.0.1", cast.localPort, client(info)))
      }
    }
  }

  @Test(timeout = 15_000)
  fun aClosedCastPortAndHttpRefusalsKeepTheTvOffline() = runBlocking {
    val closedPort = ServerSocket(0).use { it.localPort }
    MockWebServer().use { info ->
      info.enqueue(MockResponse().setBody("""{"ssdp_udn":"$id"}"""))
      assertNull(DeviceDiscovery.rememberedChromecast(remembered(closedPort), client(info)))
      info.enqueue(MockResponse().setResponseCode(503))
      assertNull(DeviceDiscovery.rememberedChromecast(remembered(8009), client(info)))
    }
  }

  @Test(timeout = 15_000)
  fun aRedirectIsNotFollowedAndCancellingDiscoveryCancelsTheProbe() = runBlocking {
    MockWebServer().use { info ->
      val http = client(info)
      info.enqueue(MockResponse().setResponseCode(302).setHeader("Location", info.url("/untrusted")))
      assertNull(DeviceDiscovery.rememberedChromecast(remembered(8009), http))
      assertEquals(1, info.requestCount)
      info.takeRequest()
      info.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
      val pending = async { DeviceDiscovery.rememberedChromecast(remembered(8009), http) }
      withContext(Dispatchers.IO) { assertNotNull(info.takeRequest(3, TimeUnit.SECONDS)) }
      withTimeout(1000) { pending.cancelAndJoin() }
      assertTrue(pending.isCancelled)
    }
  }

  @Test(timeout = 15_000)
  fun manualAndOtherReceiverTypesAreNotProbed() = runBlocking {
    MockWebServer().use { info ->
      val http = client(info)
      val saved = remembered(8009)
      assertNull(DeviceDiscovery.rememberedChromecast(null, http))
      assertNull(DeviceDiscovery.rememberedChromecast(saved.toBuilder().clearId().build(), http))
      assertNull(DeviceDiscovery.rememberedChromecast(saved.toBuilder().setType(DeviceType.DEVICE_TYPE_DLNA).build(), http))
      assertEquals(0, info.requestCount)
    }
  }
}
