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

  suspend fun find(): List<Device> = coroutineScope {
    val cast = async { chromecasts() }
    val ssdp = async(Dispatchers.IO) {
      val tethered = tetheredInterfaces()
      if (tethered.isEmpty()) ssdp(null)
      else coroutineScope { tethered.map { async { ssdp(it) } }.awaitAll().flatten() }
    }
    (ssdp.await() + cast.await()).distinctBy { "${it.type}:${it.id}" }
  }

  private suspend fun tetheredInterfaces(): List<NetworkInterface> {
    if (Build.VERSION.SDK_INT < 36) return emptyList()
    val manager = context.getSystemService(TetheringManager::class.java)
    val names = withTimeoutOrNull(2000) {
      callbackFlow {
        val callback = object : TetheringManager.TetheringEventCallback {
          override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
            trySend(interfaces.map { it.getInterface() })
          }
        }
        manager.registerTetheringEventCallback(context.mainExecutor, callback)
        awaitClose { manager.unregisterTetheringEventCallback(callback) }
      }.first()
    } ?: throw java.io.IOException("Android did not report the hotspot network. Search again.")
    return names.map { name ->
      NetworkInterface.getByName(name)
        ?: throw java.io.IOException("The hotspot network changed. Search again.")
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
            DatagramPacket(message, message.size, InetAddress.getByName("239.255.255.250"), 1900),
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
    return found.mapNotNull { (id, value) ->
      currentCoroutineContext().ensureActive()
      val (location, family) = value
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
        Device.newBuilder().setId(id).setName(name).setType(family).setAddress(location).build()
      } catch (_: java.io.IOException) {
        null
      } catch (_: org.xmlpull.v1.XmlPullParserException) {
        null
      }
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
