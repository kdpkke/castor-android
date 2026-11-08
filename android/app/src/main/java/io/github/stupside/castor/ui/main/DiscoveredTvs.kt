package io.github.stupside.castor.ui.main

import castor.v1.DeviceOuterClass.Device
import castor.v1.DeviceOuterClass.DeviceType
import java.net.URI
import java.util.Locale

internal fun receiverHost(address: String): String? = runCatching {
  URI(if ("://" in address) address else "http://$address")
    .host
    ?.trim('[', ']')
    ?.lowercase(Locale.ROOT)
}
  .getOrNull()

internal fun receiverEndpoint(tv: SavedTv): String = runCatching {
  val uri = URI(if ("://" in tv.address) tv.address else "http://${tv.address}")
  val host = receiverHost(tv.address) ?: return@runCatching tv.address
  val port =
    uri.port.takeIf { it >= 0 }
      ?: when (tv.type) {
        DeviceType.DEVICE_TYPE_CHROMECAST -> 8009
        DeviceType.DEVICE_TYPE_ROKU -> 8060
        else -> if (uri.scheme.equals("https", true)) 443 else 80
      }
  "${if (':' in host) "[$host]" else host}:$port"
}
  .getOrDefault(tv.address)

internal data class DiscoveredTv(val host: String, val name: String, val protocols: List<SavedTv>) {
  val addresses: String
    get() = protocols.map(::receiverEndpoint).distinct().joinToString(" · ")
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
