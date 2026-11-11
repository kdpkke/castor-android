package io.github.stupside.castor.ui.main

import castor.v1.DeviceOuterClass.Device
import castor.v1.DeviceOuterClass.DeviceType
import castor.v1.DeviceOuterClass.Fidelity
import io.github.stupside.castor.R
import io.github.stupside.castor.ReceiverAnnouncements
import io.github.stupside.castor.ipLiteralBytes
import java.net.URI
import java.util.Locale

// A bare IP literal is its own host. A bare IPv6 address, which is how a receiver on its protocol's default port is
// saved, is not a URI authority and would read as no host at all. Its brackets are dropped; an IPv6 zone stays, and
// sameHost ignores it.
internal fun receiverHost(address: String): String? {
  val bare = address.trim().trim('[', ']')
  if (ipLiteralBytes(bare.substringBefore('%')) != null) return bare.lowercase(Locale.ROOT)
  return runCatching {
    URI(if ("://" in address) address else "http://$address")
      .host
      ?.trim('[', ']')
      ?.lowercase(Locale.ROOT)
  }
    .getOrNull()
}

// Whether two hosts are the same place: IP literals compare as numbers, so one address written two ways is one host;
// anything else compares as text without case. Brackets and an IPv6 zone are not part of the address.
internal fun sameHost(a: String, b: String): Boolean {
  val left = hostText(a)
  val right = hostText(b)
  val leftBytes = ipLiteralBytes(left)
  val rightBytes = ipLiteralBytes(right)
  if (leftBytes != null && rightBytes != null) return leftBytes.contentEquals(rightBytes)
  return left == right
}

private fun hostText(host: String): String =
  host.trim().trim('[', ']').substringBefore('%').lowercase(Locale.ROOT)

// A typed IPv4 address alone, which Castor can ask on every protocol's own port.
internal fun bareHost(input: String): String? =
  input.trim().takeIf { value ->
    val parts = value.split('.')
    parts.size == 4 && parts.all { it.length in 1..3 && it.all(Char::isDigit) && it.toInt() <= 255 }
  }

internal fun receiverEndpoint(tv: SavedTv): String = runCatching {
  val uri = URI(if ("://" in tv.address) tv.address else "http://${tv.address}")
  val host = receiverHost(tv.address) ?: return@runCatching tv.address
  val port =
    uri.port.takeIf { it >= 0 }
      ?: when (tv.type) {
        DeviceType.DEVICE_TYPE_CHROMECAST -> 8009
        DeviceType.DEVICE_TYPE_ROKU -> 8060
        DeviceType.DEVICE_TYPE_AIRPLAY -> ReceiverAnnouncements.AIRPLAY_PORT
        DeviceType.DEVICE_TYPE_FCAST -> ReceiverAnnouncements.FCAST_PORT
        else -> if (uri.scheme.equals("https", true)) 443 else 80
      }
  "${if (':' in host) "[$host]" else host}:$port"
}
  .getOrDefault(tv.address)

// The more of the source a protocol keeps, the higher; unknown verdicts last.
internal fun fidelityRank(fidelity: Fidelity?): Int =
  when (fidelity) {
    Fidelity.FIDELITY_ORIGINAL_FILE -> 3
    Fidelity.FIDELITY_ORIGINAL_TRACKS -> 2
    Fidelity.FIDELITY_CONVERTED -> 1
    else -> 0
  }

// A TV's protocols, the one keeping most of a Native cast first; ties keep discovery's order.
internal fun rankedProtocols(protocols: List<SavedTv>, verdicts: Map<String, Fidelity>): List<SavedTv> =
  protocols.sortedByDescending { fidelityRank(verdicts[verdictKey(it)]) }

// The protocol to recommend: only one that keeps strictly more than every other.
internal fun recommendedProtocol(ranked: List<SavedTv>, verdicts: Map<String, Fidelity>): SavedTv? {
  val best = ranked.firstOrNull() ?: return null
  val rank = fidelityRank(verdicts[verdictKey(best)])
  if (ranked.size < 2 || rank == 0) return null
  return best.takeIf { ranked.drop(1).all { fidelityRank(verdicts[verdictKey(it)]) < rank } }
}

internal fun verdictLabel(fidelity: Fidelity): Int =
  when (fidelity) {
    Fidelity.FIDELITY_ORIGINAL_FILE -> R.string.verdict_original_file
    Fidelity.FIDELITY_ORIGINAL_TRACKS -> R.string.verdict_original_tracks
    else -> R.string.verdict_converted
  }

internal data class DiscoveredTv(val host: String, val name: String, val protocols: List<SavedTv>) {
  // One address for the TV, then every protocol it answers on; each protocol's port shows once it is chosen.
  val summary: String
    get() = "$host · ${protocols.map { protocolName(it.type) }.distinct().joinToString(", ")}"
}

// Discovery keeps protocol identities separate. Only the picker groups services at the same host.
internal fun discoveredTvs(devices: List<Device>, saved: SavedTv?): List<DiscoveredTv> {
  val confirmed = saved?.available(devices)
  return devices
    .filter { it.address.isNotBlank() && it.type in receiverTypes }
    .map(::SavedTv)
    .distinctBy { it.type to it.address }
    .groupBy { receiverHost(it.address) ?: it.address }
    .map { (host, protocols) ->
      val preferred =
        confirmed?.takeIf { it in protocols }
          ?: protocols.firstOrNull { it.type == DeviceType.DEVICE_TYPE_CHROMECAST }
          ?: protocols.first()
      DiscoveredTv(host, preferred.name, protocols)
    }
    .sortedBy { if (confirmed != null && it.protocols.contains(confirmed)) 0 else 1 }
}
