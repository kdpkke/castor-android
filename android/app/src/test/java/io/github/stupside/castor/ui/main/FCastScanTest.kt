package io.github.stupside.castor.ui.main

import io.github.stupside.castor.FCastQr
import org.junit.Assert.*
import org.junit.Test

class FCastScanTest {
  private val pin = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

  private fun code(json: String, padded: Boolean = false): String {
    val encoder = java.util.Base64.getUrlEncoder().let { if (padded) it else it.withoutPadding() }
    return "fcast://r/" + encoder.encodeToString(json.toByteArray(Charsets.UTF_8))
  }

  private val wrong = "https://example.com/"

  private val living =
    """{"name":"Living room","addresses":["198.51.100.7","192.0.2.10"],"services":[{"port":46899,"type":0}]}"""

  @Test
  fun startsSearching() {
    val scan = FCastScan("192.0.2.10")
    assertEquals(FCastScanResult.Searching, scan.result)
    assertEquals(0, scan.judged)
  }

  @Test
  fun aCodeThatIsNotFCastIsWrongAtOnce() {
    val scan = FCastScan("192.0.2.10")
    val expected = FCastScanResult.Wrong(FCastQr.Reason.NotFCast)
    assertEquals(expected, scan.read(wrong))
    assertEquals(expected, scan.result)
    assertEquals(1, scan.judged)
  }

  @Test
  fun theSameWrongCodeHeldInViewIsJudgedOnce() {
    val scan = FCastScan("192.0.2.10")
    repeat(50) { scan.read(wrong) }
    assertEquals(1, scan.judged)
    assertEquals(FCastScanResult.Wrong(FCastQr.Reason.NotFCast), scan.result)
  }

  @Test
  fun anotherCodeIsJudgedAgain() {
    val scan = FCastScan("192.0.2.10")
    scan.read(wrong)
    val empty = code("""{"name":"Kitchen","addresses":["198.51.100.7"],"services":[]}""")
    assertEquals(FCastScanResult.Wrong(FCastQr.Reason.NoTcpService), scan.read(empty))
    assertEquals(2, scan.judged)
    assertEquals(FCastScanResult.Wrong(FCastQr.Reason.NoTcpService), scan.result)
  }

  @Test
  fun thisTVsReceiverPairs() {
    val scan = FCastScan("192.0.2.10")
    val receiver =
      FCastQr.Receiver("Living room", listOf("198.51.100.7", "192.0.2.10"), 46899, null, null)
    assertEquals(FCastScanResult.Paired(receiver), scan.read(code(living)))
    assertEquals(FCastScanResult.Paired(receiver), scan.result)
  }

  @Test
  fun theHostIsComparedWithoutCaseOrBrackets() {
    val json =
      """{"name":"Living room","addresses":["2001:db8::10"],"services":[{"port":46899,"type":0}]}"""
    val scan = FCastScan("[2001:DB8::10]")
    val receiver = FCastQr.Receiver("Living room", listOf("2001:db8::10"), 46899, null, null)
    assertEquals(FCastScanResult.Paired(receiver), scan.read(code(json)))
  }

  @Test
  fun aReceiverElsewhereIsNotPaired() {
    val json =
      """{"name":"Kitchen","addresses":["198.51.100.7"],"services":[{"port":46899,"type":0}]}"""
    val scan = FCastScan("192.0.2.10")
    assertEquals(FCastScanResult.OtherDevice("Kitchen"), scan.read(code(json)))
  }

  @Test
  fun anEncryptedReceiverOfThisTVIsReported() {
    val json =
      """{"name":"Living room","addresses":["192.0.2.10"],"services":[{"port":46899,"type":0}],"txt":{"v":"4","fp":"$pin"}}"""
    val scan = FCastScan("192.0.2.10")
    val receiver = FCastQr.Receiver("Living room", listOf("192.0.2.10"), 46899, 4, pin)
    assertEquals(FCastScanResult.Encrypted(receiver), scan.read(code(json)))
  }

  @Test
  fun anEncryptedReceiverElsewhereIsStillAnotherDevice() {
    val json =
      """{"name":"Living room","addresses":["198.51.100.7"],"services":[{"port":46899,"type":0}],"txt":{"v":"4","fp":"$pin"}}"""
    val scan = FCastScan("192.0.2.10")
    assertEquals(FCastScanResult.OtherDevice("Living room"), scan.read(code(json)))
  }

  @Test
  fun nothingIsReadAfterAPairing() {
    val scan = FCastScan("192.0.2.10")
    val paired = scan.read(code(living))
    val judged = scan.judged
    assertTrue(paired is FCastScanResult.Paired)
    assertEquals(paired, scan.read(wrong))
    assertEquals(paired, scan.result)
    assertEquals(judged, scan.judged)
  }

  @Test
  fun aRightCodeAfterAWrongOnePairs() {
    val scan = FCastScan("192.0.2.10")
    scan.read(wrong)
    val receiver =
      FCastQr.Receiver("Living room", listOf("198.51.100.7", "192.0.2.10"), 46899, null, null)
    assertEquals(FCastScanResult.Paired(receiver), scan.read(code(living)))
  }

  @Test
  fun theSameAddressWrittenAnotherWayIsThisTV() {
    val pairs =
      listOf(
        "2001:db8:0:0:0:0:0:10" to "2001:db8::10",
        "2001:db8::10" to "2001:0db8:0000:0000:0000:0000:0000:0010",
        "[2001:DB8::10]" to "2001:db8:0:0::10",
        "192.0.2.10" to "192.0.2.10",
      )
    for ((host, address) in pairs) {
      val json =
        """{"name":"Living room","addresses":["$address"],"services":[{"port":46899,"type":0}]}"""
      val scan = FCastScan(host)
      assertTrue(
        "host $host should be this TV for address $address",
        scan.read(code(json)) is FCastScanResult.Paired,
      )
    }
  }

  @Test
  fun aDifferentAddressIsStillAnotherDevice() {
    val ipv6 =
      """{"name":"Kitchen","addresses":["2001:db8::11"],"services":[{"port":46899,"type":0}]}"""
    assertEquals(
      FCastScanResult.OtherDevice("Kitchen"),
      FCastScan("2001:db8::10").read(code(ipv6)),
    )
    val ipv4 =
      """{"name":"Kitchen","addresses":["192.0.2.100"],"services":[{"port":46899,"type":0}]}"""
    assertEquals(
      FCastScanResult.OtherDevice("Kitchen"),
      FCastScan("192.0.2.10").read(code(ipv4)),
    )
  }
}
