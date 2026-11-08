package io.github.stupside.castor

import castor.v1.DeviceOuterClass.Device
import castor.v1.DeviceOuterClass.DeviceType
import java.net.URI
import java.util.Base64
import java.util.Locale

// What AirPlay and FCast receivers announce over mDNS, read as the engine reads it.
internal object ReceiverAnnouncements {
  const val AIRPLAY_PORT = 7000
  const val FCAST_PORT = 46899

  private val featureWords = Regex("^0x([0-9A-Fa-f]{1,8})(?:,0x([0-9A-Fa-f]{1,8}))?$")
  private val mac = Regex("^Mac\\d+,\\d+$")

  // One 32-bit word, or the low word then the high word; LG drops the low word's leading zeros.
  fun features(value: String?): Long? {
    val words = featureWords.matchEntire(value?.trim().orEmpty()) ?: return null
    val low = words.groupValues[1].toLong(16)
    val high = words.groupValues[2].ifEmpty { "0" }.toLong(16)
    return (high shl 32) or low
  }

  // fex carries the same bits as a little-endian bitmap in base64, often unpadded.
  fun featuresEx(value: String?): Long? {
    val raw =
      runCatching { Base64.getDecoder().decode(value.orEmpty().trimEnd('=').let { it.padEnd((it.length + 3) / 4 * 4, '=') }) }
        .getOrNull() ?: return null
    return raw.take(8).foldIndexed(0L) { i, bits, byte -> bits or ((byte.toLong() and 0xff) shl (8 * i)) }
  }

  // Bit 0 plays a URL through /play (Apple TV), bit 49 through a remote control stream (other TVs).
  fun playsVideo(features: Long): Boolean = features and 1L != 0L || (features ushr 49) and 1L != 0L

  fun airPlay(name: String, txt: Map<String, String>, host: String, port: Int): Device? {
    val keys = txt.mapKeys { it.key.lowercase(Locale.ROOT) }
    var bits = features(keys["features"] ?: keys["ft"]) ?: return null
    keys["fex"]?.let { featuresEx(it) }?.let { bits = bits or it }
    if (!playsVideo(bits) || mac.matches(keys["model"].orEmpty())) return null
    val id = keys["deviceid"]?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    return Device.newBuilder()
      .setId(id)
      .setName(name)
      .setType(DeviceType.DEVICE_TYPE_AIRPLAY)
      .setAddress(authority(host, port))
      .build()
  }

  // FCast announces no stable identity, so its ID adds where it answers, as the engine names it.
  fun fCast(name: String, host: String, port: Int): Device {
    val address = if (port > 0 && port != FCAST_PORT) authority(host, port) else host
    return Device.newBuilder()
      .setId("$name@$address")
      .setName(name)
      .setType(DeviceType.DEVICE_TYPE_FCAST)
      .setAddress(address)
      .build()
  }

  private fun authority(host: String, port: Int): String =
    URI("http", null, host, port, null, null, null).rawAuthority
}
