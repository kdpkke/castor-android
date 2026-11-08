package io.github.stupside.castor

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// A service instance as one receiver describes itself when asked directly.
internal data class Announced(val name: String, val port: Int, val txt: Map<String, String>)

// Asks one receiver for its DNS-SD services by unicast, which reaches TVs whose Wi-Fi drops multicast.
internal object UnicastMdns {
  const val PORT = 5353
  private const val PTR = 12
  private const val TXT = 16
  private const val SRV = 33

  fun query(service: String, id: Int = 0): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
    for (label in service.trimEnd('.').split('.')) {
      val bytes = label.toByteArray(Charsets.UTF_8)
      out.write(bytes.size)
      out.write(bytes)
    }
    out.write(byteArrayOf(0, 0, PTR.toByte(), 0, 1))
    return out.toByteArray()
  }

  // The service's instances with their port and TXT; a malformed reply names none.
  fun services(reply: ByteArray, service: String): List<Announced> =
    try {
      parse(reply, service.trimEnd('.').lowercase(Locale.ROOT))
    } catch (_: IndexOutOfBoundsException) {
      emptyList()
    } catch (_: IllegalArgumentException) {
      emptyList()
    }

  // A sender port other than 5353 asks the receiver to answer this socket directly (RFC 6762 §6.7).
  suspend fun ask(
    host: InetAddress,
    service: String,
    port: Int = PORT,
    waitMillis: Long = 1500,
  ): List<Announced> {
    DatagramSocket().use { socket ->
      socket.soTimeout = 250
      val question = query(service, (System.nanoTime() and 0xffff).toInt())
      val until = System.nanoTime() + waitMillis * 1_000_000
      var sent = 0
      while (System.nanoTime() < until) {
        currentCoroutineContext().ensureActive()
        // A second question covers one lost on the air.
        if (sent < 2) {
          socket.send(DatagramPacket(question, question.size, host, port))
          sent++
        }
        val packet = DatagramPacket(ByteArray(9000), 9000)
        try {
          socket.receive(packet)
        } catch (_: SocketTimeoutException) {
          continue
        }
        if (packet.address != host) continue
        val found = services(packet.data.copyOf(packet.length), service)
        if (found.isNotEmpty()) return found
      }
    }
    return emptyList()
  }

  private class Reader(val data: ByteArray) {
    var at = 0

    fun u8(): Int = data[at++].toInt() and 0xff

    fun u16(): Int = (u8() shl 8) or u8()

    fun name(): String {
      val labels = mutableListOf<String>()
      var position = at
      var jumped = false
      var jumps = 0
      while (true) {
        val length = data[position].toInt() and 0xff
        when {
          length == 0 -> {
            if (!jumped) at = position + 1
            return labels.joinToString(".")
          }
          length and 0xc0 == 0xc0 -> {
            require(++jumps <= 32) { "Compression loop" }
            val target = ((length and 0x3f) shl 8) or (data[position + 1].toInt() and 0xff)
            if (!jumped) at = position + 2
            jumped = true
            position = target
          }
          else -> {
            require(length <= 63) { "Invalid label" }
            labels += String(data, position + 1, length, Charsets.UTF_8)
            position += length + 1
          }
        }
      }
    }
  }

  private fun parse(reply: ByteArray, service: String): List<Announced> {
    val reader = Reader(reply)
    reader.at = 4
    val questions = reader.u16()
    val records = reader.u16() + reader.u16() + reader.u16()
    repeat(questions) {
      reader.name()
      reader.at += 4
    }
    val instances = linkedSetOf<String>()
    val ports = mutableMapOf<String, Int>()
    val texts = mutableMapOf<String, Map<String, String>>()
    repeat(records) {
      val owner = reader.name().lowercase(Locale.ROOT)
      val type = reader.u16()
      reader.at += 6
      val length = reader.u16()
      val end = reader.at + length
      require(end <= reply.size) { "Truncated record" }
      when (type) {
        PTR -> if (owner == service) instances += reader.name()
        SRV -> {
          reader.at += 4
          ports[owner] = reader.u16()
        }
        TXT -> {
          val entries = linkedMapOf<String, String>()
          while (reader.at < end) {
            val size = reader.u8()
            val entry = String(reply, reader.at, size, Charsets.UTF_8)
            reader.at += size
            if (entry.isNotEmpty()) entries.putIfAbsent(entry.substringBefore('='), entry.substringAfter('=', ""))
          }
          texts[owner] = entries
        }
      }
      reader.at = end
    }
    val suffix = ".$service"
    return instances.map { instance ->
      val key = instance.lowercase(Locale.ROOT)
      // An instance label may itself hold dots, as LG's Cast names do: strip only the service.
      val label = if (key.endsWith(suffix)) instance.dropLast(suffix.length) else instance
      Announced(label, ports[key] ?: 0, texts[key].orEmpty())
    }
  }
}
