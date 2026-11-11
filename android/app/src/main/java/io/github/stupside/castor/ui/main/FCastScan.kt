package io.github.stupside.castor.ui.main

import io.github.stupside.castor.FCastQr

// What the scanner knows of the code in view.
internal sealed interface FCastScanResult {
  data object Searching : FCastScanResult

  // Not an FCast code, or a broken one.
  data class Wrong(val reason: FCastQr.Reason) : FCastScanResult

  // A valid FCast code of a receiver that is not at this TV's address.
  data class OtherDevice(val name: String) : FCastScanResult

  // This TV's receiver, which asks for the encrypted FCast link Castor does not speak.
  data class Encrypted(val receiver: FCastQr.Receiver) : FCastScanResult

  data class Paired(val receiver: FCastQr.Receiver) : FCastScanResult
}

// Judges what the camera decodes for the TV at host. A code held in view is judged once: the same text
// again changes nothing, and nothing is read after a pairing.
internal class FCastScan(private val host: String) {
  var result: FCastScanResult = FCastScanResult.Searching
    private set

  // How many texts were judged, as opposed to seen again.
  var judged = 0
    private set

  private var last: String? = null

  fun read(text: String): FCastScanResult {
    if (result is FCastScanResult.Paired) return result
    if (text == last) return result
    last = text
    judged++
    val next =
      when (val parsed = FCastQr.parse(text)) {
        is FCastQr.Rejected -> FCastScanResult.Wrong(parsed.reason)
        is FCastQr.Receiver ->
          when {
            parsed.addresses.none { sameHost(it, host) } ->
              FCastScanResult.OtherDevice(parsed.name)
            parsed.encrypted -> FCastScanResult.Encrypted(parsed)
            else -> FCastScanResult.Paired(parsed)
          }
      }
    result = next
    return next
  }
}
