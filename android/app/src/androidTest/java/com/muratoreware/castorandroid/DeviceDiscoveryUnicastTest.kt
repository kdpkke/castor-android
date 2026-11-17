package com.muratoreware.castorandroid

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import castor.v1.DeviceOuterClass.DeviceType
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

// A TV announced over mDNS whose Wi-Fi drops SSDP multicast is still found as a DLNA renderer, by a search sent to it.
class DeviceDiscoveryUnicastTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext
  private val nsd = context.getSystemService(NsdManager::class.java)
  private val name = "Castor unicast fixture"
  private val udn = "uuid:castor-android-unicast-fixture"
  private lateinit var description: MockWebServer
  private var ssdp: DatagramSocket? = null
  private var registration: NsdManager.RegistrationListener? = null
  private val multicastSearches = java.util.concurrent.atomic.AtomicInteger()
  private val unicastSearches = java.util.concurrent.atomic.AtomicInteger()

  @Before
  fun grant() {
    if (Build.VERSION.SDK_INT >= 37)
      instrumentation.uiAutomation
        .executeShellCommand("pm grant ${context.packageName} android.permission.ACCESS_LOCAL_NETWORK")
        .close()
  }

  @After
  fun close() {
    registration?.let { runCatching { nsd.unregisterService(it) } }
    ssdp?.close()
    if (::description.isInitialized) description.shutdown()
  }

  @Test(timeout = 60_000)
  fun aTvAnnouncedOverMdnsIsAskedForItsRendererDirectly() = runBlocking<Unit> {
    val address = wifiAddress()
    assumeTrue("This test needs the phone on Wi-Fi", address != null)
    description =
      MockWebServer().apply {
        dispatcher =
          object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
              MockResponse()
                .setHeader("Content-Type", "text/xml; charset=utf-8")
                .setBody(
                  """<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>$name</friendlyName><UDN>$udn</UDN></device></root>"""
                )
          }
        start(InetAddress.getByName("0.0.0.0"), 0)
      }
    val location = "http://${address!!.hostAddress}:${description.port}/description.xml"
    val socket =
      runCatching {
          DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(1900))
          }
        }
        .getOrNull()
    assumeTrue("Another app holds the SSDP port on this phone", socket != null)
    ssdp = socket
    // Answers only a search sent to this phone alone, as a TV whose Wi-Fi drops multicast does.
    thread(isDaemon = true) {
      val buffer = ByteArray(2048)
      while (!socket!!.isClosed) {
        val packet = DatagramPacket(buffer, buffer.size)
        try {
          socket.receive(packet)
        } catch (_: java.io.IOException) {
          break
        }
        val search = String(packet.data, 0, packet.length)
        if (!search.startsWith("M-SEARCH")) continue
        if (search.contains("239.255.255.250")) {
          multicastSearches.incrementAndGet()
          continue
        }
        unicastSearches.incrementAndGet()
        val answer =
          "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: $location\r\nSERVER: fixture\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: $udn::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val bytes = answer.toByteArray()
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress)) }
      }
    }
    announce()

    val found = DeviceDiscovery(context).find()
    val renderer = found.firstOrNull { it.type == DeviceType.DEVICE_TYPE_DLNA && it.name == name }
    assertNotNull(
      "The renderer of a TV announced over mDNS was not found by a search sent to it; found $found " +
        "(unicast searches ${unicastSearches.get()}, multicast searches ${multicastSearches.get()})",
      renderer,
    )
    assertTrue("The renderer was found without asking it directly", unicastSearches.get() > 0)
  }

  // The fixture announces itself as an FCast receiver on this phone, as a TV announces its other protocols.
  private fun announce() {
    val registered = CountDownLatch(1)
    val listener =
      object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) = registered.countDown()

        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}

        override fun onServiceUnregistered(info: NsdServiceInfo) {}

        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
      }
    nsd.registerService(
      NsdServiceInfo().apply {
        serviceName = name
        serviceType = "_fcast._tcp"
        port = description.port
      },
      NsdManager.PROTOCOL_DNS_SD,
      listener,
    )
    registration = listener
    assertTrue("The fixture could not announce itself over mDNS", registered.await(10, TimeUnit.SECONDS))
  }

  // This phone's IPv4 address on Wi-Fi, which the announcement resolves to.
  private fun wifiAddress(): InetAddress? =
    NetworkInterface.getNetworkInterfaces()
      .toList()
      .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
      .flatMap { it.inetAddresses.toList() }
      .firstOrNull { it is Inet4Address }
}
