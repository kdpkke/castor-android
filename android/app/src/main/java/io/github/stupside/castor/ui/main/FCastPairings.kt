package io.github.stupside.castor.ui.main

import castor.v1.DeviceOuterClass.Device
import castor.v1.DeviceOuterClass.DeviceType
import io.github.stupside.castor.FCastQr
import io.github.stupside.castor.ReceiverAnnouncements
import org.json.JSONArray
import org.json.JSONObject

// One TV's FCast receiver as its QR code gave it. The TV is known by the discovery identities of its
// other protocols; the host is only where the TV answered when the code was scanned.
internal data class FCastPairing(
  val identities: Set<String>,
  val name: String,
  val port: Int,
  val version: Int?,
  val fingerprint: String?,
  val host: String,
) {
  val encrypted: Boolean
    get() = (version ?: 0) >= 4 || fingerprint != null
}

// What choosing FCast on a TV leads to.
internal sealed interface FCastChoice {
  // A receiver to cast to: announced at the TV's address, or paired by its code.
  data class Ready(val tv: SavedTv, val paired: Boolean) : FCastChoice

  // No receiver is known for this TV: its code has to be scanned.
  data object Scan : FCastChoice

  // The TV's receiver asks for an encrypted FCast link, which Castor does not speak.
  data class Unsupported(val name: String) : FCastChoice
}

// The identities a TV keeps when its address changes. FCast's own id carries its address, so it is not one.
internal fun stableIdentities(tv: DiscoveredTv): Set<String> =
  tv.protocols
    .filter { it.type != DeviceType.DEVICE_TYPE_FCAST && it.id.isNotBlank() }
    .map { "${it.type.number}|${it.id}" }
    .toSet()

// The code scanned at this address while its TV had no identity, when it said the receiver needs encryption.
// It keeps refusing the address whatever identity the TV shows later: only a new scan of that TV settles it.
internal fun refusedAt(host: String, pairings: List<FCastPairing>): FCastPairing? =
  pairings.lastOrNull { it.identities.isEmpty() && it.encrypted && sameHost(it.host, host) }

// The pairing that belongs to tv: one sharing an identity with it. A TV without any is paired only by a
// pairing that has no identity either, made at the same host.
internal fun pairingFor(tv: DiscoveredTv, pairings: List<FCastPairing>): FCastPairing? {
  refusedAt(tv.host, pairings)?.let { return it }
  val identities = stableIdentities(tv)
  if (identities.isNotEmpty()) {
    return pairings.lastOrNull { p -> p.identities.any(identities::contains) }
  }
  return pairings.lastOrNull { it.identities.isEmpty() && sameHost(it.host, tv.host) }
}

internal fun fcastChoice(tv: DiscoveredTv, pairings: List<FCastPairing>): FCastChoice {
  val pairing = pairingFor(tv, pairings)
  // An encrypted receiver wins even over an announced FCast protocol: Castor cannot speak it.
  if (pairing != null && pairing.encrypted) return FCastChoice.Unsupported(pairing.name)
  tv.protocols.firstOrNull { it.type == DeviceType.DEVICE_TYPE_FCAST }?.let { announced ->
    // The announced receiver of a paired TV is anchored to the TV's identity, so the choice follows the TV and
    // not a name at an address.
    if (pairing != null && pairing.identities.isNotEmpty())
      return FCastChoice.Ready(announced.copy(pairedFor = pairing.identities), paired = false)
    return FCastChoice.Ready(announced, paired = false)
  }
  if (pairing != null) return FCastChoice.Ready(chosen(tv, pairing), paired = true)
  return FCastChoice.Scan
}

// The choice a pairing gives tv. A TV with an identity is found again by it; one without is used where it was
// scanned, and only for now.
internal fun chosen(tv: DiscoveredTv, pairing: FCastPairing): SavedTv {
  val address = ReceiverAnnouncements.fCastAddress(tv.host, pairing.port)
  if (pairing.identities.isNotEmpty()) {
    return SavedTv(
      pairing.name,
      address,
      DeviceType.DEVICE_TYPE_FCAST,
      manual = false,
      pairedFor = pairing.identities,
    )
  }
  return SavedTv(pairing.name, address, DeviceType.DEVICE_TYPE_FCAST, manual = true, once = true)
}

// The pairing a scanned receiver gives tv.
internal fun paired(tv: DiscoveredTv, receiver: FCastQr.Receiver): FCastPairing =
  FCastPairing(
    stableIdentities(tv),
    receiver.name,
    receiver.port,
    receiver.version,
    receiver.fingerprint,
    tv.host,
  )

// The pairings that remain once added is made. A TV without identity replaces only those without identity at its
// host. A TV with identities also replaces what was read at its host without identity: its new scan settles that
// address. What is kept on disk is decided by CastOptions.pairFCast.
internal fun withPairing(pairings: List<FCastPairing>, added: FCastPairing): List<FCastPairing> {
  if (added.identities.isEmpty()) {
    return pairings.filterNot { p -> p.identities.isEmpty() && sameHost(p.host, added.host) } + added
  }
  return pairings.filterNot { p ->
    p.identities.any(added.identities::contains) || (p.identities.isEmpty() && sameHost(p.host, added.host))
  } + added
}

internal fun encodePairings(pairings: List<FCastPairing>): String {
  val array = JSONArray()
  for (pairing in pairings) {
    val item =
      JSONObject()
        .put("identities", JSONArray(pairing.identities.sorted()))
        .put("name", pairing.name)
        .put("port", pairing.port)
        .put("host", pairing.host)
    pairing.version?.let { item.put("version", it) }
    pairing.fingerprint?.let { item.put("fingerprint", it) }
    array.put(item)
  }
  return array.toString()
}

// What was saved, without the entries that cannot be read. An entry without identities is read only when
// anonymous, which is the list of this app session: the disk never holds one.
internal fun decodePairings(text: String?, anonymous: Boolean = false): List<FCastPairing> {
  if (text.isNullOrBlank()) return emptyList()
  return runCatching {
      val array = JSONArray(text)
      (0 until array.length()).mapNotNull { pairingOf(array.optJSONObject(it), anonymous) }
    }
    .getOrDefault(emptyList())
}

// One saved entry, or null when any of its fields is missing or out of shape.
private fun pairingOf(item: JSONObject?, anonymous: Boolean): FCastPairing? {
  if (item == null) return null
  val list = item.optJSONArray("identities") ?: return null
  val identities = ArrayList<String>()
  for (i in 0 until list.length()) identities.add(list.opt(i) as? String ?: return null)
  if (identities.isEmpty() && !anonymous) return null
  val name = item.opt("name") as? String ?: return null
  val host = item.opt("host") as? String ?: return null
  val port = (item.opt("port") as? Int)?.takeIf { it in 1..65535 } ?: return null
  val version = if (item.has("version") && item.opt("version") is Int) item.optInt("version") else null
  val fingerprint = if (item.has("fingerprint")) item.opt("fingerprint") as? String else null
  return FCastPairing(identities.toSet(), name, port, version, fingerprint, host)
}

// The FCast receiver of a paired TV, as choosing FCast on that TV gives it where the TV answers now. The TV is found
// among what discovery returned by its identities, never by the address it had when its code was scanned. Null while
// the TV is not there, when its pairing is gone, and when its receiver asks for encryption. Otherwise the TV's
// announced receiver, anchored to the TV, or else the paired endpoint at its current address.
internal fun pairedReceiver(tv: SavedTv, devices: List<Device>, pairings: List<FCastPairing>): SavedTv? {
  if (tv.pairedFor.isEmpty()) return null
  val at =
    discoveredTvs(devices, null).firstOrNull { found ->
      stableIdentities(found).any(tv.pairedFor::contains)
    } ?: return null
  pairingFor(at, pairings) ?: return null
  return (fcastChoice(at, pairings) as? FCastChoice.Ready)?.tv
}

// Whether a chosen FCast receiver must not be cast to. The TVs listed at its address decide by their identities, so
// another TV that took over an address is not held back by the first one's code; where none has an identity, the
// codes scanned at that address decide. Nothing known to need encryption is reached in the clear.
internal fun fcastBlocked(tv: SavedTv, tvs: List<DiscoveredTv>, pairings: List<FCastPairing>): Boolean {
  if (tv.type != DeviceType.DEVICE_TYPE_FCAST) return false
  // An address that cannot be read is not shown to be safe: with any receiver known to need encryption it is refused.
  val host = receiverHost(tv.address) ?: return pairings.any { it.encrypted }
  // Discovery can list one TV under two spellings of its address, so every entry at this host counts.
  val here = tvs.filter { sameHost(it.host, host) }
  if (here.any { stableIdentities(it).isNotEmpty() }) {
    return here.any { pairingFor(it, pairings)?.encrypted == true }
  }
  return pairings.any { it.encrypted && sameHost(it.host, host) }
}
