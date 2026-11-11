package io.github.stupside.castor.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.stupside.castor.FCastQr
import io.github.stupside.castor.R
import io.github.stupside.castor.theme.GlassButton

// Whether the scanner may use the camera, and how to ask.
internal class CameraAccess(
  val available: Boolean,
  val granted: Boolean,
  // Asked and refused: only the system settings can grant it now.
  val refused: Boolean,
  val ask: () -> Unit,
  val settings: () -> Unit,
)

@Composable
internal fun rememberCameraAccess(): CameraAccess {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val available = remember(context) {
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
  }
  var granted by remember { mutableStateOf(cameraGranted(context)) }
  var refused by rememberSaveable { mutableStateOf(false) }
  // Read again on every resume, so a permission changed in the system settings shows at once.
  DisposableEffect(owner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) granted = cameraGranted(context)
    }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer) }
  }
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
      granted = allowed
      if (!allowed) refused = true
    }
  return CameraAccess(
    available = available,
    granted = granted,
    refused = refused,
    ask = { launcher.launch(Manifest.permission.CAMERA) },
    settings = {
      context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
      )
    },
  )
}

private fun cameraGranted(context: Context): Boolean =
  ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
    PackageManager.PERMISSION_GRANTED

// The full-screen scanner for one TV's FCast code: the camera, a square to aim with, what to aim at, and an X to go back.
@Composable
internal fun FCastScanner(
  host: String,
  paired: (FCastQr.Receiver) -> Unit,
  encrypted: (FCastQr.Receiver) -> Unit,
  cancel: () -> Unit,
  access: CameraAccess = rememberCameraAccess(),
  camera: @Composable (onText: (String) -> Unit) -> Unit = { onText ->
    QrCamera(onText, Modifier.fillMaxSize())
  },
) {
  BackHandler { cancel() }
  val scan = remember(host) { FCastScan(host) }
  var result by remember(host) { mutableStateOf<FCastScanResult>(FCastScanResult.Searching) }
  // A code is judged once while it stays in view, so paired and encrypted hear of each receiver once.
  val read: (String) -> Unit = { text ->
    val next = scan.read(text)
    if (next != result) {
      result = next
      when (next) {
        is FCastScanResult.Paired -> paired(next.receiver)
        is FCastScanResult.Encrypted -> encrypted(next.receiver)
        else -> {}
      }
    }
  }
  if (access.available && !access.granted && !access.refused) {
    LaunchedEffect(Unit) { access.ask() }
  }
  Box(Modifier.fillMaxSize().background(Color.Black).testTag("fcast-scanner")) {
    when {
      !access.available ->
        Text(
          stringResource(R.string.fcast_no_camera),
          color = Color.White,
          textAlign = TextAlign.Center,
          modifier = Modifier.align(Alignment.Center).padding(24.dp).testTag("fcast-no-camera"),
        )
      !access.granted ->
        Column(
          Modifier.align(Alignment.Center).padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Text(
            stringResource(R.string.fcast_camera_permission),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("fcast-permission"),
          )
          GlassButton(
            prominent = true,
            onClick = if (access.refused) access.settings else access.ask,
            modifier = Modifier.testTag("fcast-allow"),
          ) {
            Text(
              stringResource(
                if (access.refused) R.string.open_settings else R.string.fcast_camera_allow
              )
            )
          }
        }
      else -> {
        camera(read)
        // The square to aim with. Capped in width so it stays fully visible in landscape.
        Box(
          Modifier.align(Alignment.Center)
            .fillMaxWidth(0.6f)
            .widthIn(max = 320.dp)
            .aspectRatio(1f)
            .border(3.dp, Color.White, RoundedCornerShape(16.dp))
            .testTag("fcast-frame")
        ) {}
        Surface(
          modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(24.dp),
          color = Color.Black.copy(alpha = 0.6f),
          shape = RoundedCornerShape(16.dp),
        ) {
          Text(
            stringResource(R.string.fcast_scan_title),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp).testTag("fcast-instruction"),
          )
        }
        when (val shown = result) {
          is FCastScanResult.Wrong ->
            FCastJudgement(stringResource(R.string.fcast_wrong_code), "fcast-wrong")
          is FCastScanResult.OtherDevice ->
            FCastJudgement(
              stringResource(R.string.fcast_other_device, shown.name),
              "fcast-other",
            )
          is FCastScanResult.Encrypted ->
            FCastJudgement(
              stringResource(R.string.fcast_encrypted, shown.receiver.name),
              "fcast-encrypted",
            )
          else -> {}
        }
      }
    }
    // The way back is there in every state, also without a camera or its permission.
    IconButton(
      onClick = cancel,
      modifier =
        Modifier.align(Alignment.BottomCenter)
          .safeDrawingPadding()
          .padding(bottom = 24.dp)
          .size(64.dp)
          .background(Color.Black.copy(alpha = 0.6f), CircleShape)
          .testTag("fcast-cancel"),
    ) {
      CompositionLocalProvider(LocalContentColor provides Color.White) {
        CastIcon(R.drawable.ic_close, stringResource(R.string.back))
      }
    }
  }
}

// What the camera read, said aloud as it changes; it sits between the square and the X.
@Composable
private fun BoxScope.FCastJudgement(text: String, tag: String) {
  Surface(
    modifier =
      Modifier.align(Alignment.BottomCenter)
        .safeDrawingPadding()
        .padding(bottom = 112.dp, start = 24.dp, end = 24.dp),
    color = MaterialTheme.colorScheme.errorContainer,
    shape = RoundedCornerShape(16.dp),
  ) {
    Text(
      text,
      color = MaterialTheme.colorScheme.onErrorContainer,
      modifier =
        Modifier.padding(16.dp)
          .testTag(tag)
          .semantics { liveRegion = LiveRegionMode.Polite },
    )
  }
}
