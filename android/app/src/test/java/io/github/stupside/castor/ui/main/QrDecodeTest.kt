package io.github.stupside.castor.ui.main

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

class QrDecodeTest {
  @Test
  fun aCodeDrawnAsAnImageIsReadBack() {
    val text = "fcast://r/eyJuYW1lIjoiVFYifQ"
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300)
    val luminance =
      ByteArray(300 * 300) { i ->
        if (matrix.get(i % 300, i / 300)) 0.toByte() else 255.toByte()
      }
    assertEquals(text, decodeQr(luminance, 300, 300))
  }

  @Test
  fun anEmptyPictureHasNoCode() {
    assertNull(decodeQr(ByteArray(300 * 300) { 255.toByte() }, 300, 300))
    assertNull(decodeQr(ByteArray(300 * 300) { i -> (i * 31 % 251).toByte() }, 300, 300))
  }
}
