package io.github.stupside.castor

import android.content.Context
import android.net.TetheringInterface
import android.net.TetheringManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Xml
import castor.v1.DeviceOuterClass.Device
import castor.v1.DeviceOuterClass.DeviceType
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.URI
import java.net.URL
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.xmlpull.v1.XmlPullParser

// Android supplies mDNS and active hotspot interfaces; no restricted netlink enumeration is needed.
class DeviceDiscovery(private val context: Context) {
  private val nsd = context.getSystemService(NsdManager::class.java)
  // Before Android 16 an app cannot ask which interfaces tether; try the soft-AP names Android and
  // OEMs use. Each is resolved by name only, never by enumerating every interface.
  private val likelyHotspotNames = listOf("ap0", "swlan0", "softap0", "wlan1")

  // Every route is searched independently: one that fails (a cellular default route refusing
  // multicast, a hotspot that just stopped) neither hides receivers found on the others nor the
  // Chromecasts. The search fails only when nothing was found and some route failed.
  suspend fun find(): List<Device> = coroutineScope {
    val cast = async { attempt { chromecasts() } }
    val ssdp =
      async(Dispatchers.IO) {
        // The default route can be cellular while the TV sits on this phone's hotspot, and the
        // phone
        // can share a hotspot while staying on home Wi-Fi: search the route and each hotspot.
        val routes = listOf<NetworkInterface?>(null) + hotspotInterfaces()
        routes.map { async { attempt { ssdp(it) } } }.awaitAll()
      }
    val results = ssdp.await() + cast.await()
    val found = results.flatMap { it.getOrNull().orEmpty() }.distinctBy { "${it.type}:${it.id}" }
    if (found.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
    found
  }

  private suspend fun <T> attempt(search: suspend () -> T): Result<T> =
    try {
      Result.success(search())
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      Result.failure(failure)
    }

  private suspend fun hotspotInterfaces(): List<NetworkInterface> {
    val names = tetheredInterfaceNames() ?: likelyHotspotNames
    return names.mapNotNull { name ->
      runCatching { NetworkInterface.getByName(name)?.takeIf { it.isUp && it.supportsMulticast() } }
        .getOrNull()
    }
  }

  // Android 16+ reports the active hotspot interfaces; null when unknown.
  private suspend fun tetheredInterfaceNames(): List<String>? {
    if (Build.VERSION.SDK_INT < 36) return null
    val manager = context.getSystemService(TetheringManager::class.java) ?: return null
    return withTimeoutOrNull(1000) {
      callbackFlow {
          val callback =
            object : TetheringManager.TetheringEventCallback {
              override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                trySend(interfaces.map { it.getInterface() })
              }
            }
          // Delivered on the binder thread: a busy main thread must not stall discovery.
          manager.registerTetheringEventCallback({ it.run() }, callback)
          awaitClose { manager.unregisterTetheringEventCallback(callback) }
        }
        .first()
    }
  }

  private suspend fun ssdp(network: NetworkInterface?): List<Device> {
    val found = linkedMapOf<String, Pair<String, DeviceType>>()
    MulticastSocket().use { socket ->
      // The default route can be cellular even while the TV is on this phone's hotspot.
      if (network != null) socket.networkInterface = network
      socket.soTimeout = 200
      for (target in listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "roku:ecp")) {
        val message =
          "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $target\r\n\r\n"
            .toByteArray()
        repeat(2) {
          socket.send(
            DatagramPacket(message, message.size, InetAddress.getByName("239.255.255.250"), 1900)
          )
        }
      }
      val until = android.os.SystemClock.elapsedRealtime() + 2200
      while (android.os.SystemClock.elapsedRealtime() < until) {
        currentCoroutineContext().ensureActive()
        val packet = DatagramPacket(ByteArray(8192), 8192)
        try {
          socket.receive(packet)
        } catch (_: java.net.SocketTimeoutException) {
          continue
        }
        val headers =
          String(packet.data, 0, packet.length, Charsets.UTF_8)
            .lineSequence()
            .drop(1)
            .filter { ':' in it }
            .associate {
              it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
            }
        val location = headers["location"].orEmpty()
        val target = headers["st"].orEmpty()
        if (!BrowserResolver.isHttp(location)) continue
        val family =
          when {
            target == "roku:ecp" -> DeviceType.DEVICE_TYPE_ROKU
            target.contains("MediaRenderer:") -> DeviceType.DEVICE_TYPE_DLNA
            else -> continue
          }
        found.putIfAbsent(headers["usn"] ?: location, location to family)
      }
    }
    // Descriptions are read concurrently so several receivers fit the engine's listing deadline.
    return coroutineScope {
        found.map { (id, value) -> async { describe(id, value.first, value.second) } }.awaitAll()
      }
      .filterNotNull()
  }

  private fun describe(id: String, location: String, family: DeviceType): Device? {
    try {
      val connection = URL(location).openConnection() as HttpURLConnection
      connection.connectTimeout = 500
      connection.readTimeout = 500
      connection.instanceFollowRedirects = false
      val name =
        try {
          connection.inputStream.use { input ->
            val bytes = io.github.stupside.castor.data.Rpc.readLimited(input, 1_048_576)
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(bytes.inputStream(), null)
            var label = ""
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
              if (parser.eventType == XmlPullParser.START_TAG && parser.name == "friendlyName") {
                label = parser.nextText()
                break
              }
            }
            label.ifBlank { URI(location).host }
          }
        } finally {
          connection.disconnect()
        }
      return Device.newBuilder()
        .setId(id)
        .setName(name)
        .setType(family)
        .setAddress(location)
        .build()
    } catch (_: java.io.IOException) {
      return null
    } catch (_: org.xmlpull.v1.XmlPullParserException) {
      return null
    }
  }

  @Suppress("DEPRECATION")
  private suspend fun chromecasts(): List<Device> {
    val found = mutableListOf<Device>()
    val services = callbackFlow {
      val listener =
        object : NsdManager.DiscoveryListener {
          override fun onDiscoveryStarted(type: String) {}

          override fun onDiscoveryStopped(type: String) {}

          override fun onServiceFound(info: NsdServiceInfo) {
            trySend(info)
          }

          override fun onServiceLost(info: NsdServiceInfo) {}

          override fun onStartDiscoveryFailed(type: String, code: Int) {
            close(java.io.IOException("Android mDNS discovery failed ($code)"))
          }

          override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
      nsd.discoverServices("_googlecast._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
      awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
    withTimeoutOrNull(4000) {
      services.collect { service ->
        val resolved =
          withTimeoutOrNull(700) {
            suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
              nsd.resolveService(
                service,
                object : NsdManager.ResolveListener {
                  override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    if (continuation.isActive) continuation.resume(null)
                  }

                  override fun onServiceResolved(info: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(info)
                  }
                },
              )
            }
          }
        if (resolved != null) {
          val host = resolved.host?.hostAddress ?: return@collect
          val address = URI("http", null, host, resolved.port, null, null, null).rawAuthority
          val name = resolved.attributes["fn"]?.toString(Charsets.UTF_8) ?: resolved.serviceName
          val id = resolved.attributes["id"]?.toString(Charsets.UTF_8) ?: resolved.serviceName
          found +=
            Device.newBuilder()
              .setId(id)
              .setName(name)
              .setType(DeviceType.DEVICE_TYPE_CHROMECAST)
              .setAddress(address)
              .build()
        }
      }
    }
    return found
  }
}
