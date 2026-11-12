package io.github.stupside.castor

import android.content.Context
import android.net.TetheringInterface
import android.net.TetheringManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Xml
import castor.v1.DeviceOuterClass.Device
import castor.v1.DeviceOuterClass.DeviceType
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import org.xmlpull.v1.XmlPullParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONException
import io.github.stupside.castor.ui.main.CastOptions
import io.github.stupside.castor.ui.main.receiverHost

// Android supplies mDNS and active hotspot interfaces; no restricted netlink enumeration is needed.
class DeviceDiscovery(private val context: Context) {
  private val nsd = context.getSystemService(NsdManager::class.java)
  // Before Android 16 an app cannot ask which interfaces tether; try the soft-AP names Android and
  // OEMs use. Each is resolved by name only, never by enumerating every interface.
  private val likelyHotspotNames = listOf("ap0", "swlan0", "softap0", "wlan1")

  // The search the engine asks for: the saved TV and the host last typed are asked directly too.
  suspend fun find(options: CastOptions): List<Device> =
    find(
      options.television()?.let {
        Device.newBuilder().setId(it.id).setName(it.name).setAddress(it.address).setType(it.type).build()
      },
      options.searchedHost(),
    )

  // The same search run by the app itself, so that finding a TV never starts the engine. Its devices are listed
  // as the engine lists them, so a TV found here is the one the engine casts to, and it is given the time the
  // engine gives it (its network timeout, 5 s). Android may hold back multicast replies from an app without a
  // multicast lock, so the search holds one, as the engine's service does.
  suspend fun search(options: CastOptions): List<Device> {
    val lock =
      context.applicationContext
        .getSystemService(WifiManager::class.java)
        .createMulticastLock("castor-search")
        .apply { setReferenceCounted(false) }
    lock.acquire()
    try {
      return withTimeoutOrNull(5_000) { listed(find(options)) }
        ?: throw java.io.IOException("The search for TVs took too long.")
    } finally {
      lock.release()
    }
  }

  // Every route is searched independently: one that fails (a cellular default route refusing
  // multicast, a hotspot that just stopped) neither hides receivers found on the others nor the
  // Chromecasts. The search fails only when nothing was found and some route failed.
  suspend fun find(remembered: Device? = null, typed: String? = null): List<Device> = coroutineScope {
    val cast = async { attempt { chromecasts() } }
    val airplay = async { attempt { airPlays() } }
    val fcast = async { attempt { fCasts() } }
    val known = async { rememberedChromecast(remembered) }
    // Some TVs' Wi-Fi drops multicast while they answer directly: ask the saved and typed hosts too.
    val hosts = listOfNotNull(remembered?.address?.let(::receiverHost), typed?.let(::receiverHost)).distinct()
    val direct =
      async(Dispatchers.IO) { hosts.map { async { attempt { direct(it, remembered) } } }.awaitAll() }
    val ssdp =
      async(Dispatchers.IO) {
        // The default route can be cellular while the TV sits on this phone's hotspot, and the
        // phone
        // can share a hotspot while staying on home Wi-Fi: search the route and each hotspot.
        val routes = listOf<NetworkInterface?>(null) + hotspotInterfaces()
        routes.map { async { attempt { ssdp(it) } } }.awaitAll()
      }
    val results = ssdp.await() + cast.await() + airplay.await() + fcast.await() + direct.await()
    val found =
      (results.flatMap { it.getOrNull().orEmpty() } + listOfNotNull(known.await()))
        .distinctBy { "${it.type}:${it.id}" }
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
        val headers = ssdpHeaders(packet)
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

  private fun ssdpHeaders(packet: DatagramPacket): Map<String, String> =
    String(packet.data, 0, packet.length, Charsets.UTF_8)
      .lineSequence()
      .drop(1)
      .filter { ':' in it }
      .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }

  // UPnP 1.1 lets a control point ask one device by unicast; only that device's renderer counts.
  private suspend fun ssdpAt(host: InetAddress): List<Device> {
    val found = linkedMapOf<String, String>()
    java.net.DatagramSocket().use { socket ->
      socket.soTimeout = 200
      val literal = host.hostAddress.orEmpty().substringBefore('%').let { if (':' in it) "[$it]" else it }
      val message =
        "M-SEARCH * HTTP/1.1\r\nHOST: $literal:1900\r\nMAN: \"ssdp:discover\"\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
          .toByteArray()
      repeat(2) { socket.send(DatagramPacket(message, message.size, host, 1900)) }
      val until = android.os.SystemClock.elapsedRealtime() + 1500
      while (android.os.SystemClock.elapsedRealtime() < until) {
        currentCoroutineContext().ensureActive()
        val packet = DatagramPacket(ByteArray(8192), 8192)
        try {
          socket.receive(packet)
        } catch (_: java.net.SocketTimeoutException) {
          continue
        }
        if (packet.address != host) continue
        val headers = ssdpHeaders(packet)
        val location = headers["location"].orEmpty()
        if (!BrowserResolver.isHttp(location) || !headers["st"].orEmpty().contains("MediaRenderer:")) continue
        if (receiverHost(location) != receiverHost(literal)) continue
        found.putIfAbsent(headers["usn"] ?: location, location)
      }
    }
    return found.mapNotNull { (id, location) -> describe(id, location, DeviceType.DEVICE_TYPE_DLNA) }
  }

  // A saved renderer is read again at its description URL, and kept only when its UDN is the same.
  private fun savedRenderer(remembered: Device): Device? {
    if (remembered.type != DeviceType.DEVICE_TYPE_DLNA || !BrowserResolver.isHttp(remembered.address)) return null
    val usn = remembered.id.removePrefix("dlna:")
    val udn = rendererUdn(remembered.id) ?: return null
    return describe(usn, remembered.address, DeviceType.DEVICE_TYPE_DLNA, udn)
  }

  // Each protocol on its own port: AirPlay and FCast by unicast DNS-SD, Cast by its setup API, DLNA by SSDP.
  private suspend fun direct(host: String, remembered: Device?): List<Device> = coroutineScope {
    val address = InetAddress.getByName(host)
    val literal = address.hostAddress.orEmpty().substringBefore('%')
    listOf(
        async {
          attempt {
            UnicastMdns.ask(address, "_airplay._tcp.local").mapNotNull {
              val port = it.port.takeIf { port -> port > 0 } ?: ReceiverAnnouncements.AIRPLAY_PORT
              ReceiverAnnouncements.airPlay(it.name, it.txt, literal, port)
            }
          }
        },
        async {
          attempt {
            UnicastMdns.ask(address, "_fcast._tcp.local").map {
              ReceiverAnnouncements.fCast(it.name, literal, it.port)
            }
          }
        },
        async {
          attempt {
            listOfNotNull(
              chromecastAt(literal)?.let { if (it.name.isBlank()) it.toBuilder().setName(literal).build() else it }
            )
          }
        },
        async { attempt { ssdpAt(address) } },
        async {
          attempt {
            listOfNotNull(
              remembered?.takeIf { receiverHost(it.address) == host }?.let(::savedRenderer)
            )
          }
        },
      )
      .awaitAll()
      .flatMap { it.getOrNull().orEmpty() }
  }

  private fun describe(id: String, location: String, family: DeviceType, udn: String? = null): Device? {
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
            var described: String? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
              if (parser.eventType != XmlPullParser.START_TAG) continue
              if (parser.name == "friendlyName" && label.isEmpty()) label = parser.nextText()
              else if (parser.name == "UDN" && described == null) described = parser.nextText().trim()
              if (label.isNotEmpty() && (udn == null || described != null)) break
            }
            // A reused address must not stand in for the saved renderer.
            if (udn != null && !udn.equals(described, ignoreCase = true)) return null
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

  private suspend fun chromecasts(): List<Device> =
    services("_googlecast._tcp.") { resolved, host ->
      val address = URI("http", null, host, resolved.port, null, null, null).rawAuthority
      val name = resolved.attributes["fn"]?.toString(Charsets.UTF_8) ?: resolved.serviceName
      val id = resolved.attributes["id"]?.toString(Charsets.UTF_8) ?: resolved.serviceName
      Device.newBuilder()
        .setId(id)
        .setName(name)
        .setType(DeviceType.DEVICE_TYPE_CHROMECAST)
        .setAddress(address)
        .build()
    }

  // AirPlay receivers that play video; audio-only speakers and Macs are left out, as the engine refuses them.
  private suspend fun airPlays(): List<Device> =
    services("_airplay._tcp.") { resolved, host ->
      val txt = resolved.attributes.mapValues { it.value?.toString(Charsets.UTF_8).orEmpty() }
      ReceiverAnnouncements.airPlay(resolved.serviceName, txt, host, resolved.port)
    }

  private suspend fun fCasts(): List<Device> =
    services("_fcast._tcp.") { resolved, host ->
      ReceiverAnnouncements.fCast(resolved.serviceName, host, resolved.port)
    }

  // Android resolves one service at a time on older releases: every family's resolutions take turns.
  private val resolving = kotlinx.coroutines.sync.Mutex()

  @Suppress("DEPRECATION")
  private suspend fun services(type: String, device: (NsdServiceInfo, String) -> Device?): List<Device> {
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
      nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
      awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
    withTimeoutOrNull(4000) {
      services.collect { service ->
        val resolved =
          resolving.withLock {
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
          }
        if (resolved != null) {
          val host = resolved.host?.hostAddress ?: return@collect
          device(resolved, host)?.let { found += it }
        }
      }
    }
    return found
  }

  companion object {
    // What a search found as the engine lists it (its ExternalDiscovery, Directory.discover and Info.Public): a
    // device of a type the engine casts to, with an id and an address; its id is the type's name, a colon and the
    // id the search found; each id once, the first kept.
    internal fun listed(found: List<Device>): List<Device> =
      found
        .mapNotNull { device ->
          val type = engineTypes[device.type] ?: return@mapNotNull null
          if (device.id.isEmpty() || device.address.isEmpty()) return@mapNotNull null
          Device.newBuilder()
            .setId("$type:${device.id}")
            .setName(device.name)
            .setType(device.type)
            .setAddress(device.address)
            .build()
        }
        .distinctBy { it.id }

    // The engine's name for each type it casts to: the contract's name, lower case, without its prefix.
    private val engineTypes =
      listOf(
          DeviceType.DEVICE_TYPE_DLNA,
          DeviceType.DEVICE_TYPE_CHROMECAST,
          DeviceType.DEVICE_TYPE_ROKU,
          DeviceType.DEVICE_TYPE_FCAST,
          DeviceType.DEVICE_TYPE_AIRPLAY,
        )
        .associateWith { it.name.removePrefix("DEVICE_TYPE_").lowercase(Locale.ROOT) }

    // A saved renderer's id is the engine's "dlna:" before the USN the TV announced.
    internal fun rendererUdn(id: String): String? =
      id.removePrefix("dlna:").substringBefore("::").takeIf { it.startsWith("uuid:") }

    internal val knownClient =
      OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.SECONDS)
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    internal suspend fun rememberedChromecast(
      remembered: Device?,
      client: OkHttpClient = knownClient,
    ): Device? {
      if (remembered == null || remembered.type != DeviceType.DEVICE_TYPE_CHROMECAST) return null
      val rawId = remembered.id.removePrefix("chromecast:")
      val expected = castIdentity(rawId) ?: return null
      val address = runCatching {
        URI(if ("://" in remembered.address) remembered.address else "http://${remembered.address}")
      }.getOrNull() ?: return null
      val host = address.host ?: return null
      if (
        address.scheme != "http" || address.userInfo != null || address.rawQuery != null ||
          address.fragment != null || address.path !in listOf("", "/") || address.port !in 1..65535
      ) return null
      // A reused IP or friendly name is not evidence of the saved receiver's identity.
      val found = chromecastAt(host, address.port, client)?.takeIf { it.id == expected } ?: return null
      return remembered.toBuilder()
        .setId(rawId)
        .setName(found.name.ifBlank { remembered.name })
        .build()
    }

    // The Chromecast at host as its setup API names it, once its Cast port answers; blank name if unnamed.
    internal suspend fun chromecastAt(
      host: String,
      port: Int = 8009,
      client: OkHttpClient = knownClient,
    ): Device? {
      val call = runCatching {
        val endpoint = URI("http", null, host, 8008, "/setup/eureka_info", null, null).toASCIIString()
        client.newCall(Request.Builder().url(endpoint).header("Accept", "application/json").build())
      }.getOrNull() ?: return null
      return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
          override fun onFailure(call: Call, e: java.io.IOException) {
            if (continuation.isActive) continuation.resume(null)
          }

          override fun onResponse(call: Call, response: Response) {
            val found = try {
              response.use {
                if (!it.isSuccessful) return@use null
                val bytes = io.github.stupside.castor.data.Rpc.readLimited(it.body.byteStream(), 65_536)
                val info = JSONObject(bytes.toString(Charsets.UTF_8))
                val id = castIdentity(info.optString("ssdp_udn")) ?: return@use null
                Socket().use { socket -> socket.connect(InetSocketAddress(host, port), 500) }
                Device.newBuilder()
                  .setId(id)
                  .setName(info.optString("name"))
                  .setType(DeviceType.DEVICE_TYPE_CHROMECAST)
                  .setAddress(URI("http", null, host, port, null, null, null).rawAuthority)
                  .build()
              }
            } catch (_: java.io.IOException) {
              null
            } catch (_: JSONException) {
              null
            } catch (_: IllegalStateException) {
              // The bounded reader rejects oversized descriptions before JSON parsing.
              null
            }
            if (continuation.isActive) continuation.resume(found)
          }
        })
      }
    }

    private fun castIdentity(value: String): String? =
      value.removePrefix("uuid:").replace("-", "").lowercase(Locale.ROOT)
        .takeIf { it.matches(Regex("[0-9a-f]{32}")) }
  }
}
