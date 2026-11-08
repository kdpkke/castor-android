package io.github.stupside.castor

import castor.v1.DeviceOuterClass.DeviceType
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UnicastMdnsTest {
  private val service = "_airplay._tcp.local"

  // A receiver's reply as LG TVs send it: the question, the PTR, then SRV, TXT and A, compressed.
  private fun reply(instance: String = "[LG] webOS TV", txt: List<String> = airPlayTxt): ByteArray {
    val out = ByteArrayOutputStream()
    fun u16(value: Int) {
      out.write(value shr 8)
      out.write(value)
    }
    fun labels(vararg names: String) = names.forEach {
      val bytes = it.toByteArray()
      out.write(bytes.size)
      out.write(bytes)
    }
    fun pointer(offset: Int) {
      out.write(0xc0 or (offset shr 8))
      out.write(offset)
    }
    fun record(type: Int, rdata: () -> Unit) {
      u16(type)
      u16(0x8001)
      u16(0)
      u16(120)
      val lengthAt = out.size()
      u16(0)
      val start = out.size()
      rdata()
      val bytes = out.toByteArray()
      val length = bytes.size - start
      bytes[lengthAt] = (length shr 8).toByte()
      bytes[lengthAt + 1] = length.toByte()
      out.reset()
      out.write(bytes)
    }
    out.write(byteArrayOf(0x12, 0x34, 0x84.toByte(), 0, 0, 1, 0, 1, 0, 0, 0, 3))
    val serviceAt = out.size()
    labels("_airplay", "_tcp", "local")
    out.write(0)
    u16(12)
    u16(1)
    var instanceAt = 0
    pointer(serviceAt)
    record(12) {
      instanceAt = out.size()
      labels(instance)
      pointer(serviceAt)
    }
    var targetAt = 0
    pointer(instanceAt)
    record(33) {
      u16(0)
      u16(0)
      u16(7000)
      targetAt = out.size()
      labels("LGwebOSTV")
      pointer(serviceAt + 1 + "_airplay".length + 1 + "_tcp".length)
    }
    pointer(instanceAt)
    record(16) { txt.forEach { labels(it) } }
    pointer(targetAt)
    record(1) { out.write(byteArrayOf(192.toByte(), 0, 2, 21)) }
    return out.toByteArray()
  }

  private val airPlayTxt =
    listOf("deviceid=AA:BB:CC:DD:EE:01", "features=0x7F8AD0,0xB8BCF46", "model=OLEDTEST", "flags=0x244")

  @Test
  fun aDirectReplyNamesTheInstanceItsPortAndItsTxt() {
    val found = UnicastMdns.services(reply(), service).single()
    assertEquals("[LG] webOS TV", found.name)
    assertEquals(7000, found.port)
    assertEquals("AA:BB:CC:DD:EE:01", found.txt["deviceid"])
    val device = ReceiverAnnouncements.airPlay(found.name, found.txt, "192.0.2.21", found.port)!!
    assertEquals("AA:BB:CC:DD:EE:01", device.id)
    assertEquals(DeviceType.DEVICE_TYPE_AIRPLAY, device.type)
    assertEquals("192.0.2.21:7000", device.address)
  }

  @Test
  fun aDotInsideTheInstanceLabelStaysInTheName() {
    assertEquals("Living.Room-01", UnicastMdns.services(reply("Living.Room-01"), service).single().name)
  }

  @Test
  fun anotherServiceOrAMalformedReplyNamesNothing() {
    val good = reply()
    assertTrue(UnicastMdns.services(good, "_fcast._tcp.local").isEmpty())
    assertTrue(UnicastMdns.services(good.copyOf(good.size - 9), service).isEmpty())
    val loop = good.copyOf()
    // The question's name now points at itself.
    loop[12] = 0xc0.toByte()
    loop[13] = 12
    assertTrue(UnicastMdns.services(loop, service).isEmpty())
  }

  @Test
  fun theQuestionAsksForTheServicePointer() {
    val question = UnicastMdns.query(service, 0x0102)
    assertArrayEquals(byteArrayOf(1, 2, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0), question.copyOf(12))
    assertEquals("_airplay", String(question, 13, 8))
    assertArrayEquals(byteArrayOf(0, 0, 12, 0, 1), question.copyOfRange(question.size - 5, question.size))
  }

  @Test(timeout = 10_000)
  fun aReceiverAskedDirectlyAnswersTheAskingSocket() = runBlocking {
    DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
      val answered = thread {
        val packet = DatagramPacket(ByteArray(512), 512)
        receiver.receive(packet)
        val asked = UnicastMdns.query(service)
        // The id differs per question; the rest must be the PTR question.
        assertArrayEquals(asked.copyOfRange(2, asked.size), packet.data.copyOfRange(2, packet.length))
        val answer = reply()
        receiver.send(DatagramPacket(answer, answer.size, packet.socketAddress))
      }
      val found = UnicastMdns.ask(InetAddress.getLoopbackAddress(), service, receiver.localPort, 3000)
      answered.join()
      assertEquals("AA:BB:CC:DD:EE:01", found.single().txt["deviceid"])
    }
  }

  @Test(timeout = 10_000)
  fun aSilentReceiverNamesNothingWithinTheWait() = runBlocking {
    DatagramSocket(0, InetAddress.getLoopbackAddress()).use { silent ->
      assertTrue(UnicastMdns.ask(InetAddress.getLoopbackAddress(), service, silent.localPort, 600).isEmpty())
    }
  }
}
