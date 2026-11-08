package io.github.stupside.castor

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/** The engine's FFmpeg encoders backed by this device's hardware video encoders; the engine still proves each one. */
internal fun hardwareEncoders(): List<String> =
  ffmpegEncodersFor(
    MediaCodecList(MediaCodecList.REGULAR_CODECS)
      .codecInfos
      .filter { it.isEncoder && it.isHardware() }
      .flatMap { it.supportedTypes.asList() }
  )

/** Maps the MIME types hardware encoders accept to the FFmpeg encoders that drive them, HEVC first. */
internal fun ffmpegEncodersFor(hardwareTypes: Collection<String>): List<String> = buildList {
  if (hardwareTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }) add("hevc_mediacodec")
  if (hardwareTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) add("h264_mediacodec")
}

// Before Android 10 the platform does not say; its software codecs carry these prefixes.
private fun MediaCodecInfo.isHardware(): Boolean =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isHardwareAccelerated
  else !name.startsWith("OMX.google.") && !name.startsWith("c2.android.")
