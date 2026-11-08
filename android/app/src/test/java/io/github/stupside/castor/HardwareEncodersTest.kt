package io.github.stupside.castor

import org.junit.Assert.assertEquals
import org.junit.Test

class HardwareEncodersTest {
  @Test
  fun hardwareVideoTypesNameTheEnginesMediaCodecEncoders() {
    assertEquals(
      listOf("hevc_mediacodec", "h264_mediacodec"),
      ffmpegEncodersFor(listOf("video/avc", "audio/mp4a-latm", "VIDEO/HEVC")),
    )
    assertEquals(listOf("h264_mediacodec"), ffmpegEncodersFor(listOf("video/avc", "video/x-vnd.on2.vp8")))
    assertEquals(emptyList<String>(), ffmpegEncodersFor(emptyList()))
  }
}
