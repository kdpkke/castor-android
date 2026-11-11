package io.github.stupside.castor

import org.junit.Assert.*
import org.junit.Test

class FCastQrTest {
  private fun code(json: String, padded: Boolean = false): String {
    val encoder = java.util.Base64.getUrlEncoder().let { if (padded) it else it.withoutPadding() }
    return "fcast://r/" + encoder.encodeToString(json.toByteArray(Charsets.UTF_8))
  }

  private val pin = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

  private fun base64Of(bytes: Int): String = java.util.Base64.getEncoder().encodeToString(ByteArray(bytes))

  private val plain =
    """{"name":"Living room","addresses":["192.0.2.10","2001:db8::10"],"services":[{"port":46899,"type":0}]}"""

  private val plainReceiver =
    FCastQr.Receiver("Living room", listOf("192.0.2.10", "2001:db8::10"), 46899, null, null)

  // A document that carries the given txt object.
  private fun withTxt(txt: String): String =
    """{"name":"Living room","addresses":["192.0.2.10"],"services":[{"port":46899,"type":0}],"txt":$txt}"""

  // A document with the given addresses and services, and every other field valid.
  private fun document(
    addresses: String = """["192.0.2.10"]""",
    services: String = """[{"port":46899,"type":0}]""",
  ): String = """{"name":"Living room","addresses":$addresses,"services":$services}"""

  private fun assertRejected(reason: FCastQr.Reason, text: String) {
    assertEquals(text, FCastQr.Rejected(reason), FCastQr.parse(text))
  }

  @Test
  fun aPlainReceiverParsesWithItsNameAddressesAndPort() {
    val receiver = FCastQr.parse(code(plain)) as FCastQr.Receiver
    assertEquals(plainReceiver, receiver)
    assertFalse(receiver.encrypted)
  }

  @Test
  fun aPlainReceiverParsesTheSameWhenPaddedOrSurroundedByWhitespace() {
    assertEquals(plainReceiver, FCastQr.parse(code(plain, padded = true)))
    assertEquals(plainReceiver, FCastQr.parse("  ${code(plain)}\n"))
  }

  @Test
  fun theFirstTcpServiceWinsAndOtherTypesAreSkipped() {
    val json =
      """{"name":"Living room","addresses":["192.0.2.10"],"services":[{"port":1234,"type":1},{"port":40000,"type":0},{"port":46899,"type":0}]}"""
    assertEquals(40000, (FCastQr.parse(code(json)) as FCastQr.Receiver).port)
  }

  @Test
  fun aReceiverStatingV4WithAPinIsEncrypted() {
    val receiver = FCastQr.parse(code(withTxt("""{"v":"4","fp":"$pin"}"""))) as FCastQr.Receiver
    assertEquals(4, receiver.version)
    assertEquals(pin, receiver.fingerprint)
    assertTrue(receiver.encrypted)
  }

  @Test
  fun aPinAloneIsEncrypted() {
    val receiver = FCastQr.parse(code(withTxt("""{"fp":"$pin"}"""))) as FCastQr.Receiver
    assertNull(receiver.version)
    assertTrue(receiver.encrypted)
  }

  @Test
  fun v4AloneIsEncrypted() {
    val receiver = FCastQr.parse(code(withTxt("""{"v":"4"}"""))) as FCastQr.Receiver
    assertEquals(4, receiver.version)
    assertNull(receiver.fingerprint)
    assertTrue(receiver.encrypted)
  }

  @Test
  fun v3WithoutAPinIsNotEncrypted() {
    val receiver = FCastQr.parse(code(withTxt("""{"v":"3"}"""))) as FCastQr.Receiver
    assertEquals(3, receiver.version)
    assertFalse(receiver.encrypted)
  }

  @Test
  fun unknownTxtKeysAreIgnored() {
    val receiver = FCastQr.parse(code(withTxt("""{"other":"x"}"""))) as FCastQr.Receiver
    assertFalse(receiver.encrypted)
  }

  @Test
  fun aTextThatIsNotAnFCastCodeIsRejectedAsNotFCast() {
    for (text in
      listOf(
        "https://example.com/",
        "http://192.0.2.10:46899",
        "FCAST://r/abcd",
        "fcast://x/abcd",
        "fcast://r",
        "",
        "WIFI:S:home;;",
      )) assertRejected(FCastQr.Reason.NotFCast, text)
  }

  @Test
  fun aCodeLongerThanTheLimitIsRejectedAsTooLong() {
    assertRejected(FCastQr.Reason.TooLong, "fcast://r/" + "A".repeat(5000))
  }

  @Test
  fun aPayloadThatIsNotBase64UrlIsRejectedAsEncoding() {
    for (text in listOf("fcast://r/", "fcast://r/@@@@", "fcast://r/ab=cd", "fcast://r/a+b/"))
      assertRejected(FCastQr.Reason.Encoding, text)
  }

  @Test
  fun aPayloadThatIsNotADocumentIsRejectedAsDocument() {
    val notUtf8 =
      "fcast://r/" +
        java.util.Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(byteArrayOf(0xC3.toByte(), 0x28))
    for (text in listOf(code("not json"), code("[1,2]"), code("\"text\""), notUtf8))
      assertRejected(FCastQr.Reason.Document, text)
  }

  @Test
  fun aDocumentWithoutValidFieldsIsRejectedAsFields() {
    val documents =
      listOf(
        """{"addresses":["192.0.2.10"],"services":[{"port":46899,"type":0}]}""",
        """{"name":5,"addresses":["192.0.2.10"],"services":[{"port":46899,"type":0}]}""",
        """{"name":"  ","addresses":["192.0.2.10"],"services":[{"port":46899,"type":0}]}""",
        """{"name":"Living room","services":[{"port":46899,"type":0}]}""",
        """{"name":"Living room","addresses":"192.0.2.10","services":[{"port":46899,"type":0}]}""",
        document(addresses = "[]"),
        document(addresses = "[5]"),
        """{"name":"Living room","addresses":["192.0.2.10"]}""",
        document(services = """{"port":46899,"type":0}"""),
        document(services = "[5]"),
        document(services = """[{"port":"46899","type":0}]"""),
        document(services = """[{"port":70000,"type":0}]"""),
        document(services = """[{"port":-1,"type":0}]"""),
        document(services = """[{"port":1.5,"type":0}]"""),
        document(services = """[{"port":46899}]"""),
      )
    for (json in documents) assertRejected(FCastQr.Reason.Fields, code(json))
  }

  @Test
  fun anAddressThatIsNotAnIpIsRejectedAsAddress() {
    val addresses =
      listOf(
        """["tv.local"]""",
        """["192.0.2.10","example.com"]""",
        """["192.0.2.256"]""",
        """["192.0.2"]""",
        """["http://192.0.2.10"]""",
        """["192.0.2.10:46899"]""",
        """[""]""",
      )
    for (list in addresses) assertRejected(FCastQr.Reason.Address, code(document(addresses = list)))
  }

  @Test
  fun aReceiverWithoutATcpServiceIsRejectedAsNoTcpService() {
    val services =
      listOf("[]", """[{"port":46898,"type":1}]""", """[{"port":0,"type":0}]""")
    for (list in services)
      assertRejected(FCastQr.Reason.NoTcpService, code(document(services = list)))
  }

  @Test
  fun aBrokenSecurityTxtIsRejectedAsSecurity() {
    val txts =
      listOf(
        "\"v=4\"",
        """{"v":4}""",
        """{"v":"four"}""",
        """{"v":"-4"}""",
        """{"v":""}""",
        """{"fp":"short"}""",
        """{"fp":"${base64Of(31)}"}""",
        """{"fp":"${base64Of(33)}"}""",
        """{"fp":"not base64 !!"}""",
      )
    for (txt in txts) assertRejected(FCastQr.Reason.Security, code(withTxt(txt)))
  }

  @Test
  fun anAddressIsTheSameNumberHoweverItIsWritten() {
    val bytes = ipLiteralBytes("2001:db8::10")!!
    assertEquals(16, bytes.size)
    assertArrayEquals(bytes, ipLiteralBytes("2001:0db8:0:0:0:0:0:0010")!!)
    assertArrayEquals(bytes, ipLiteralBytes("2001:DB8:0:0::10")!!)
    assertFalse(bytes.contentEquals(ipLiteralBytes("2001:db8::11")!!))
    assertArrayEquals(byteArrayOf(192.toByte(), 0, 2, 10), ipLiteralBytes("192.0.2.10")!!)
    val mapped = ipLiteralBytes("::ffff:192.0.2.10")!!
    assertEquals(16, mapped.size)
    assertArrayEquals(
      ByteArray(10) + byteArrayOf(0xff.toByte(), 0xff.toByte(), 192.toByte(), 0, 2, 10),
      mapped,
    )
    for (text in listOf("tv.local", "192.0.2.256", "1::2::3", "2001:db8", ":", ""))
      assertNull(text, ipLiteralBytes(text))
  }
}
