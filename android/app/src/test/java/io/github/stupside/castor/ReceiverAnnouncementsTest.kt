package io.github.stupside.castor

import castor.v1.DeviceOuterClass.DeviceType
import org.junit.Assert.*
import org.junit.Test

class ReceiverAnnouncementsTest {
  private val lg = (0xB8BCF46L shl 32) or 0x7F8AD0L

  @Test
  fun aLowWordWithoutItsLeadingZerosKeepsTheHighBitsInPlace() {
    assertEquals(lg, ReceiverAnnouncements.features("0x7F8AD0,0xB8BCF46"))
    assertEquals(lg, ReceiverAnnouncements.features("0x007F8AD0,0x0B8BCF46"))
    assertEquals(0x77L, ReceiverAnnouncements.features("0x77"))
    assertNull(ReceiverAnnouncements.features("0x1,0x2,0x3"))
  }

  @Test
  fun fexIsALittleEndianBitmapWithOrWithoutPadding() {
    assertEquals(1L or (1L shl 49), ReceiverAnnouncements.featuresEx("AQAAAAAAAg"))
    assertEquals(1L or (1L shl 49), ReceiverAnnouncements.featuresEx("AQAAAAAAAg=="))
  }

  @Test
  fun anLGOnlyWithVideoV2IsAnAirPlayReceiverAtItsPort() {
    val tv =
      ReceiverAnnouncements.airPlay(
        "[LG] webOS TV OLED42C24LA",
        mapOf("deviceid" to "dc:b9:a2:00:00:01", "features" to "0x7F8AD0,0xB8BCF46", "model" to "OLED42C24LA"),
        "192.0.2.65",
        7000,
      )!!
    assertEquals(DeviceType.DEVICE_TYPE_AIRPLAY, tv.type)
    assertEquals("DC:B9:A2:00:00:01", tv.id)
    assertEquals("192.0.2.65:7000", tv.address)
  }

  @Test
  fun speakersMacsAndReceiversWithoutAnIdentityAreLeftOut() {
    val speaker = mapOf("deviceid" to "AA:00:00:00:00:01", "features" to "0x200")
    val mac = mapOf("deviceid" to "AA:00:00:00:00:02", "features" to "0x7F8AD0,0xB8BCF46", "model" to "Mac16,8")
    val anonymous = mapOf("features" to "0x7F8AD0,0xB8BCF46")
    for (txt in listOf(speaker, mac, anonymous))
      assertNull(txt.toString(), ReceiverAnnouncements.airPlay("x", txt, "192.0.2.1", 7000))
    assertTrue(ReceiverAnnouncements.playsVideo(1L))
  }

  @Test
  fun anFCastReceiverIsNamedByItsInstanceAndWhereItAnswers() {
    assertEquals("Phone@192.0.2.5", ReceiverAnnouncements.fCast("Phone", "192.0.2.5", 46899).id)
    assertEquals("192.0.2.5:5000", ReceiverAnnouncements.fCast("Phone", "192.0.2.5", 5000).address)
  }
}
