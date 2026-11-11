package io.github.stupside.castor.ui.main

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer
import java.util.concurrent.Executors

// The text of the QR code in a luminance picture, or null when none can be read. Only QR codes are looked for.
internal fun decodeQr(luminance: ByteArray, width: Int, height: Int): String? {
  val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
  val bitmap = BinaryBitmap(HybridBinarizer(source))
  return try {
    QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
  } catch (error: ReaderException) {
    null
  }
}

// Reads the luminance of each camera frame, then reports the frame and any code in it. Runs on the analysis thread.
internal class QrAnalyzer(
  private val onFrame: () -> Unit,
  private val onText: (String) -> Unit,
) : ImageAnalysis.Analyzer {
  // Frames are read one at a time, so one buffer serves every frame of the same size.
  private var luminance = ByteArray(0)

  override fun analyze(image: ImageProxy) {
    try {
      val plane = image.planes[0]
      val width = image.width
      val height = image.height
      if (luminance.size != width * height) luminance = ByteArray(width * height)
      copyLuminance(plane.buffer, plane.rowStride, width, height, luminance)
      onFrame()
      decodeQr(luminance, width, height)?.let(onText)
    } finally {
      // An image that stays open stalls the camera, whether or not it held a code.
      image.close()
    }
  }
}

// Copies the rows of a plane into the picture. A row may be padded to rowStride bytes, so the padding is skipped.
private fun copyLuminance(
  source: ByteBuffer,
  rowStride: Int,
  width: Int,
  height: Int,
  target: ByteArray,
) {
  val bytes = source.duplicate()
  if (rowStride == width) {
    bytes.position(0)
    bytes.get(target, 0, width * height)
  } else {
    for (row in 0 until height) {
      bytes.position(row * rowStride)
      bytes.get(target, row * width, width)
    }
  }
}

// The back camera, or the front one when the device has no back one. Every code it reads goes to onText on the main thread.
@Composable
internal fun QrCamera(
  onText: (String) -> Unit,
  modifier: Modifier = Modifier,
  onFrame: () -> Unit = {},
) {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val currentText by rememberUpdatedState(onText)
  val currentFrame by rememberUpdatedState(onFrame)
  val preview = remember {
    PreviewView(context).apply {
      scaleType = PreviewView.ScaleType.FILL_CENTER
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
  }
  DisposableEffect(owner) {
    val analysisExecutor = Executors.newSingleThreadExecutor()
    val mainExecutor = ContextCompat.getMainExecutor(context)
    val providerFuture = ProcessCameraProvider.getInstance(context)
    // Once the composable is gone, a late callback from the camera does nothing.
    var active = true
    var provider: ProcessCameraProvider? = null
    var previewUseCase: Preview? = null
    var analysisUseCase: ImageAnalysis? = null
    providerFuture.addListener(
      {
        if (active) {
          runCatching {
              val cameras = providerFuture.get()
              provider = cameras
              val previewCase =
                Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
              val analysis =
                ImageAnalysis.Builder()
                  .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                  .build()
                  .also {
                    it.setAnalyzer(
                      analysisExecutor,
                      QrAnalyzer(
                        onFrame = { mainExecutor.execute { if (active) currentFrame() } },
                        onText = { text -> mainExecutor.execute { if (active) currentText(text) } },
                      ),
                    )
                  }
              previewUseCase = previewCase
              analysisUseCase = analysis
              val selector =
                if (cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                  CameraSelector.DEFAULT_BACK_CAMERA
                else CameraSelector.DEFAULT_FRONT_CAMERA
              cameras.unbindAll()
              // Binding to the lifecycle owner is what stops the camera in the background and restarts it on return.
              cameras.bindToLifecycle(owner, selector, previewCase, analysis)
            }
            .onFailure { Log.w("CastorScanner", "The camera could not be opened", it) }
        }
      },
      mainExecutor,
    )
    onDispose {
      active = false
      val bound = listOfNotNull(previewUseCase, analysisUseCase)
      if (bound.isNotEmpty()) provider?.unbind(*bound.toTypedArray())
      analysisExecutor.shutdown()
    }
  }
  AndroidView(factory = { preview }, modifier = modifier)
}
