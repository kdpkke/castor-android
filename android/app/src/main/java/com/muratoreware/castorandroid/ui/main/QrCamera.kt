package com.muratoreware.castorandroid.ui.main

import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.muratoreware.castorandroid.R
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

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

// The camera's zoom as the scanner shows and sets it, so a code across the room can fill the square. Main thread only.
@Stable
internal class CameraZoom {
  // The ratios the bound camera offers; a single one, as before any camera is bound, offers no zoom.
  var range by mutableStateOf(1f..1f)
    private set

  var ratio by mutableFloatStateOf(1f)
    private set

  private var control: CameraControl? = null
  private var observed: Pair<LiveData<ZoomState>, Observer<ZoomState>>? = null

  val zooms: Boolean
    get() = range.endInclusive > 1f

  fun set(value: Float) {
    if (control == null) return
    val next = value.coerceIn(range)
    ratio = next
    control?.setZoomRatio(next)
  }

  fun attach(camera: Camera, owner: LifecycleOwner) {
    detach()
    control = camera.cameraControl
    val state = camera.cameraInfo.zoomState
    val observer = Observer<ZoomState> {
      range = it.minZoomRatio..it.maxZoomRatio
      ratio = it.zoomRatio
    }
    state.observe(owner, observer)
    observed = state to observer
  }

  fun detach() {
    observed?.let { (state, observer) -> state.removeObserver(observer) }
    observed = null
    control = null
    range = 1f..1f
    ratio = 1f
  }
}

// The zooms offered as one tap each, as a camera app offers them: 1× and the steps the camera reaches.
internal fun zoomSteps(range: ClosedFloatingPointRange<Float>): List<Float> =
  listOf(1f, 2f, 5f, 10f).filter { it >= range.start && it <= range.endInclusive }

// The step a ratio counts as: the largest one it has reached, so a pinch to 2.7× lights 2×.
internal fun zoomStep(ratio: Float, steps: List<Float>): Float? = steps.lastOrNull { ratio >= it - 0.05f } ?: steps.firstOrNull()

// A ratio as people read it: whole ones bare, others to one decimal, in the reader's own way of writing numbers.
internal fun zoomLabel(ratio: Float, locale: Locale = Locale.getDefault()): String {
  val rounded = (ratio * 10).roundToInt() / 10f
  val number =
    if (rounded % 1f == 0f) rounded.toInt().toString() else String.format(locale, "%.1f", rounded)
  return "$number×"
}

// One button per zoom step, lit on the step the picture is at; the lit one shows the exact ratio after a pinch.
@Composable
internal fun ZoomSteps(zoom: CameraZoom, modifier: Modifier = Modifier) {
  if (!zoom.zooms) return
  val steps = zoomSteps(zoom.range)
  if (steps.size < 2) return
  val current = zoomStep(zoom.ratio, steps)
  Row(
    modifier
      .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
      .padding(4.dp)
      .testTag("camera-zoom"),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    steps.forEach { step ->
      val selected = step == current
      val label = if (selected) zoomLabel(zoom.ratio) else zoomLabel(step)
      val description = stringResource(R.string.camera_zoom, zoomLabel(step))
      Box(
        Modifier.size(48.dp)
          .background(if (selected) Color.White else Color.Transparent, CircleShape)
          .selectable(selected = selected, role = Role.RadioButton, onClick = { zoom.set(step) })
          .semantics { contentDescription = description }
          .testTag("camera-zoom-${zoomLabel(step)}"),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          label,
          color = if (selected) Color.Black else Color.White,
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
        )
      }
    }
  }
}

// The back camera, or the front one when the device has no back one. Every code it reads goes to onText on the main thread.
// Pinching the picture zooms it, as zoom's controls do.
@Composable
internal fun QrCamera(
  onText: (String) -> Unit,
  modifier: Modifier = Modifier,
  onFrame: () -> Unit = {},
  zoom: CameraZoom = remember { CameraZoom() },
) {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val currentText by rememberUpdatedState(onText)
  val currentFrame by rememberUpdatedState(onFrame)
  val preview = remember {
    PreviewView(context).apply {
      scaleType = PreviewView.ScaleType.FILL_CENTER
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      val pinch =
        ScaleGestureDetector(
          context,
          object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
              zoom.set(zoom.ratio * detector.scaleFactor)
              return true
            }
          },
        )
      setOnTouchListener { view, event ->
        pinch.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
        true
      }
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
                  // A code across the room is only a few modules wide in the default 640×480 frames.
                  .setResolutionSelector(
                    ResolutionSelector.Builder()
                      .setResolutionStrategy(
                        ResolutionStrategy(
                          Size(1280, 720),
                          ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                      )
                      .build()
                  )
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
              zoom.attach(cameras.bindToLifecycle(owner, selector, previewCase, analysis), owner)
            }
            .onFailure { Log.w("CastorScanner", "The camera could not be opened", it) }
        }
      },
      mainExecutor,
    )
    onDispose {
      active = false
      zoom.detach()
      val bound = listOfNotNull(previewUseCase, analysisUseCase)
      if (bound.isNotEmpty()) provider?.unbind(*bound.toTypedArray())
      analysisExecutor.shutdown()
    }
  }
  AndroidView(factory = { preview }, modifier = modifier)
}
