package com.muratoreware.castorandroid

import castor.v1.CastOuterClass.Cast
import castor.v1.CastOuterClass.CastStatus
import castor.v1.CastOuterClass.CastingStatus
import castor.v1.CastOuterClass.Playhead
import castor.v1.CastOuterClass.Source
import castor.v1.DeviceOuterClass.Device
import castor.v1.DeviceOuterClass.DeviceType
import castor.v1.StreamOuterClass.Stream
import com.google.protobuf.Duration
import com.google.protobuf.Empty
import com.muratoreware.castorandroid.ui.main.SavedTv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastMediaSessionTest {
  private fun seconds(value: Long) = Duration.newBuilder().setSeconds(value).build()

  private fun cast(id: String, tv: String, casting: Boolean, position: Long? = null, paused: Boolean = false): Cast {
    val status =
      if (!casting) CastStatus.newBuilder().setConnecting(Empty.getDefaultInstance())
      else
        CastStatus.newBuilder()
          .setCasting(
            CastingStatus.newBuilder().setAttempt(1).apply {
              if (position != null)
                setPlayhead(Playhead.newBuilder().setPosition(seconds(position)).setDuration(seconds(600)).setPaused(paused))
            }
          )
    return Cast.newBuilder()
      .setId(id)
      .setDevice(Device.newBuilder().setName(tv).setType(DeviceType.DEVICE_TYPE_DLNA).setAddress("http://192.0.2.10:1234/d.xml"))
      .setSource(Source.newBuilder().setStream(Stream.newBuilder().setUrl("https://video.example/clip.mp4")))
      .setStatus(status)
      .build()
  }

  @Test
  fun theNewestCastThatIsCastingIsControlled() {
    val controls =
      MediaControls.of(
        listOf(cast("old", "Bedroom", true, 30), cast("new", "Living room", true, 90, paused = true), cast("next", "Kitchen", false)),
        { null },
      )!!
    assertEquals("new", controls.castId)
    assertEquals("Living room", controls.tv)
    assertEquals("video.example", controls.video)
    assertTrue(controls.paused)
    assertEquals(90_000L, controls.positionMs)
    assertTrue(controls.canPause && controls.canSeek)
  }

  @Test
  fun nothingIsControlledWhileNoCastIsCasting() {
    assertNull(MediaControls.of(listOf(cast("starting", "Living room", false)), { null }))
    assertNull(MediaControls.of(emptyList(), { null }))
  }

  @Test
  fun aCastWithoutAPlayheadCanNeitherPauseNorStep() {
    val controls = MediaControls.of(listOf(cast("new", "Living room", true)), { "Holiday.mp4" })!!
    assertEquals("Holiday.mp4", controls.video)
    assertFalse(controls.canPause)
    assertFalse(controls.canSeek)
    assertNull(controls.stepped(MediaControls.STEP_MS))
  }

  @Test
  fun aStepStaysWithinTheVideo() {
    val controls = MediaControls.of(listOf(cast("new", "Living room", true, 5)), { null })!!
    assertEquals(0L, controls.stepped(-MediaControls.STEP_MS))
    assertEquals(15_000L, controls.stepped(MediaControls.STEP_MS))
    val nearEnd = controls.copy(positionMs = 595_000)
    assertEquals(600_000L, nearEnd.stepped(MediaControls.STEP_MS))
  }

  @Test
  fun aTvPinnedByAddressIsNamedAsTheScreenNamesIt() {
    val pinned = Device.newBuilder().setType(DeviceType.DEVICE_TYPE_DLNA).setAddress("http://192.0.2.10:1234/description.xml").build()
    val saved = SavedTv("Living room", "http://192.0.2.10:1234/description.xml", DeviceType.DEVICE_TYPE_DLNA, manual = true)
    assertEquals("Living room", CastorService.tvName(pinned, saved))
    assertEquals("192.0.2.10", CastorService.tvName(pinned, saved.copy(address = "http://192.0.2.99:1234/description.xml")))
    assertEquals("192.0.2.10", CastorService.tvName(pinned, null))
    assertEquals("Bedroom", CastorService.tvName(pinned.toBuilder().setName("Bedroom").build(), saved))
  }
}
