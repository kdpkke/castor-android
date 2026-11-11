package io.github.stupside.castor

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

// What a scanned code says of an FCast receiver, read as the FCast connection URL defines it:
// fcast://r/ followed by the base64url of a JSON document. The text is only ever parsed, never opened.
internal sealed interface FCastQr {
  data class Receiver(
    val name: String,
    val addresses: List<String>,
    val port: Int,
    // The highest protocol version the receiver states (txt "v"), and the certificate pin it states (txt "fp").
    val version: Int?,
    val fingerprint: String?,
  ) : FCastQr {
    // A receiver that states v4 or pins a certificate must be reached encrypted, which Castor's v3 link is not.
    val encrypted: Boolean
      get() = (version ?: 0) >= 4 || fingerprint != null
  }

  data class Rejected(val reason: Reason) : FCastQr

  enum class Reason { NotFCast, TooLong, Encoding, Document, Fields, Address, NoTcpService, Security }

  companion object {
    const val PREFIX = "fcast://r/"
    const val MAX_LENGTH = 4096

    fun parse(text: String): FCastQr {
      val trimmed = text.trim()
      if (!trimmed.startsWith(PREFIX)) return Rejected(Reason.NotFCast)
      if (trimmed.length > MAX_LENGTH) return Rejected(Reason.TooLong)
      val bytes = payloadBytes(trimmed.substring(PREFIX.length)) ?: return Rejected(Reason.Encoding)
      val document = documentOf(bytes) ?: return Rejected(Reason.Document)
      val name = nameOf(document) ?: return Rejected(Reason.Fields)
      val addresses = addressesOf(document) ?: return Rejected(Reason.Fields)
      val services = servicesOf(document) ?: return Rejected(Reason.Fields)
      if (!addresses.all(::isIpLiteral)) return Rejected(Reason.Address)
      val tcp = services.firstOrNull { it.type == 0 && it.port > 0 } ?: return Rejected(Reason.NoTcpService)
      val (version, fingerprint) = txtOf(document) ?: return Rejected(Reason.Security)
      return Receiver(name, addresses, tcp.port, version, fingerprint)
    }
  }
}

// A service of the receiver as its document states it.
private data class Service(val type: Int, val port: Int)

private val VERSION = Regex("[0-9]{1,3}")

// At most two trailing '=' are padding; what remains must be non-empty base64url.
private fun payloadBytes(payload: String): ByteArray? {
  val body = payload.dropLast(payload.takeLastWhile { it == '=' }.length.coerceAtMost(2))
  val alphabet = body.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
  if (body.isEmpty() || !alphabet) return null
  return try {
    Base64.getUrlDecoder().decode(body)
  } catch (error: IllegalArgumentException) {
    null
  }
}

// The payload as strict UTF-8 JSON; a value that is not an object, or trailing text, is not a receiver.
private fun documentOf(bytes: ByteArray): JSONObject? {
  val text =
    try {
      Charsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    } catch (error: CharacterCodingException) {
      return null
    }
  return try {
    val tokener = JSONTokener(text)
    val value = tokener.nextValue()
    if (value is JSONObject && tokener.nextClean() == '\u0000') value else null
  } catch (error: JSONException) {
    null
  }
}

private fun nameOf(document: JSONObject): String? =
  (document.opt("name") as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(64)

private fun addressesOf(document: JSONObject): List<String>? {
  val array = document.optJSONArray("addresses") ?: return null
  if (array.length() !in 1..16) return null
  return stringsOf(array)
}

private fun stringsOf(array: JSONArray): List<String>? {
  val strings = ArrayList<String>()
  for (i in 0 until array.length()) strings.add(array.opt(i) as? String ?: return null)
  return strings
}

private fun servicesOf(document: JSONObject): List<Service>? {
  val array = document.optJSONArray("services") ?: return null
  if (array.length() > 16) return null
  val services = ArrayList<Service>()
  for (i in 0 until array.length()) {
    val item = array.optJSONObject(i) ?: return null
    val port = uint16(item.opt("port")) ?: return null
    val type = uint16(item.opt("type")) ?: return null
    services.add(Service(type, port))
  }
  return services
}

// An integer JSON number within 0..65535; a string, a fraction or a missing key is not one.
private fun uint16(value: Any?): Int? {
  val number =
    when (value) {
      is Int -> value.toLong()
      is Long -> value
      else -> return null
    }
  return number.takeIf { it in 0L..65535L }?.toInt()
}

// The bytes of an IP literal, 4 for IPv4 and 16 for IPv6, or null when the text is not one. No name is ever resolved.
internal fun ipLiteralBytes(text: String): ByteArray? =
  if (':' in text) ipv6Bytes(text) else ipv4Octets(text)?.map { it.toByte() }?.toByteArray()

private fun isIpLiteral(address: String): Boolean = ipLiteralBytes(address) != null

// The four decimal parts of an IPv4 address, each of 1-3 digits and at most 255.
private fun ipv4Octets(address: String): List<Int>? {
  val parts = address.split('.')
  if (parts.size != 4) return null
  return parts.map { part ->
    if (part.length !in 1..3 || !part.all { it in '0'..'9' }) return null
    part.toInt().takeIf { it <= 255 } ?: return null
  }
}

private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

// Only hex digits, ':' and '.' pass, then the groups are counted here: no name is ever looked up.
private fun ipv6Bytes(address: String): ByteArray? {
  if (!address.all { isHexDigit(it) || it == ':' || it == '.' }) return null
  val halves = address.split("::")
  if (halves.size > 2) return null
  val sides = halves.map { half -> groupsOf(half) ?: return null }
  // Only the last group of the address may be a dotted IPv4 tail, which counts as two groups.
  val tail = halves.last().isNotEmpty() && sides.last().last().contains('.')
  val tailWords =
    if (tail) {
      val octets = ipv4Octets(sides.last().last()) ?: return null
      listOf(octets[0] * 256 + octets[1], octets[2] * 256 + octets[3])
    } else {
      emptyList<Int>()
    }
  val hexSides =
    sides.mapIndexed { index, side ->
      val hex = if (tail && index == sides.lastIndex) side.dropLast(1) else side
      hex.map { group ->
        if (group.length !in 1..4 || !group.all(::isHexDigit)) return null
        group.toInt(16)
      }
    }
  // With "::" the groups before and after it are kept apart; "::" stands for at least one zero group.
  val head = if (halves.size == 2) hexSides[0] else emptyList<Int>()
  val rest = hexSides.last() + tailWords
  val count = head.size + rest.size
  if (halves.size == 2 && count > 7) return null
  if (halves.size == 1 && count != 8) return null
  val words = head + List(8 - count) { 0 } + rest
  return ByteArray(16) { i -> (words[i / 2] shr (if (i % 2 == 0) 8 else 0)).toByte() }
}

// The groups on one side of "::": none when the side is empty, and no group may be empty.
private fun groupsOf(half: String): List<String>? {
  if (half.isEmpty()) return emptyList()
  val groups = half.split(':')
  return groups.takeIf { list -> list.none { it.isEmpty() } }
}

// The version and pin the txt object states; null when it is broken. Without txt, neither is stated.
private fun txtOf(document: JSONObject): Pair<Int?, String?>? {
  if (!document.has("txt")) return Pair(null, null)
  val txt = document.opt("txt") as? JSONObject ?: return null
  if (!txt.keys().asSequence().all { txt.opt(it) is String }) return null
  val version =
    if (txt.has("v")) {
      val text = txt.opt("v") as String
      if (!VERSION.matches(text)) return null
      text.toInt()
    } else {
      null
    }
  val fingerprint = if (txt.has("fp")) txt.opt("fp") as String else null
  if (fingerprint != null && !isPin(fingerprint)) return null
  return Pair(version, fingerprint)
}

// The certificate pin is standard base64 of exactly 32 bytes.
private fun isPin(text: String): Boolean =
  runCatching { Base64.getDecoder().decode(text).size == 32 }.getOrDefault(false)
