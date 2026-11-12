package io.github.stupside.castor.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.stupside.castor.R
import io.github.stupside.castor.Release
import io.github.stupside.castor.theme.GlassPanel
import io.github.stupside.castor.theme.GlassSheet
import io.github.stupside.castor.theme.LocalWarningColors
import io.github.stupside.castor.theme.glassMaterial

// Something Castor tells the user through the bell. Each kind opens a page of its own.
internal sealed interface Notice {
  val id: String

  data class Update(val release: Release) : Notice {
    override val id: String
      get() = "update:${release.tag}"
  }
}

// The bell beside the menu: a red dot while a notice is unread, and a shake when one is waiting as the app opens or
// a new one arrives.
@Composable
internal fun NoticeBell(unread: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val swing = remember { Animatable(0f) }
  // How many unread notices the bell has already shaken for in this screen; a rotation keeps it.
  var shakenFor by rememberSaveable { mutableIntStateOf(0) }
  LaunchedEffect(unread) {
    if (unread <= shakenFor) {
      shakenFor = unread
      return@LaunchedEffect
    }
    shakenFor = unread
    if (animationsOff(context)) return@LaunchedEffect
    swing.animateTo(
      0f,
      keyframes {
        durationMillis = 900
        18f at 90
        -16f at 210
        12f at 330
        -9f at 450
        5f at 570
        -2f at 690
      },
    )
  }
  val description =
    if (unread > 0) stringResource(R.string.notices_unread, unread) else stringResource(R.string.notices)
  IconButton(
    onClick = onClick,
    modifier = modifier.glassMaterial(RoundedCornerShape(50)).testTag("notice-bell"),
  ) {
    BadgedBox(
      badge = {
        if (unread > 0) Badge(containerColor = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("notice-dot"))
      }
    ) {
      Icon(
        painterResource(R.drawable.ic_bell),
        description,
        Modifier.size(24.dp).graphicsLayer {
          rotationZ = swing.value
          // A bell swings from where it hangs.
          transformOrigin = TransformOrigin(0.5f, 0.08f)
        },
      )
    }
  }
}

private fun animationsOff(context: Context): Boolean =
  Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
internal fun NoticesSheet(
  notices: List<Notice>,
  read: Set<String>,
  open: (Notice) -> Unit,
  dismiss: () -> Unit,
) {
  GlassSheet(dismiss) {
    Column(
      Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp)
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          stringResource(R.string.notices),
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = dismiss, modifier = Modifier.glassMaterial(RoundedCornerShape(50))) {
          CastIcon(R.drawable.ic_close, stringResource(R.string.close))
        }
      }
      if (notices.isEmpty())
        Text(
          stringResource(R.string.notices_empty),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      notices.forEach { notice ->
        val (icon, title, detail) =
          when (notice) {
            is Notice.Update ->
              Triple(
                R.drawable.ic_download,
                stringResource(R.string.update_notice_title),
                stringResource(R.string.update_notice_detail, notice.release.version),
              )
          }
        GlassPanel(Modifier.fillMaxWidth().testTag("notice-${notice.id}"), onClick = { open(notice) }) {
          Row(
            Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            CastIcon(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(title, style = MaterialTheme.typography.titleMedium)
              Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            if (notice.id !in read) {
              val unread = stringResource(R.string.notice_unread)
              Box(
                Modifier.size(10.dp)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.error)
                  .semantics { contentDescription = unread }
              )
            }
            CastIcon(R.drawable.ic_next)
          }
        }
      }
    }
  }
}

// Whether Android leaves Castor out of battery optimization, which can otherwise hold it back in the background.
internal fun ignoresBatteryOptimizations(context: Context): Boolean =
  context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) != false

// Android's own question for Castor; where a device lacks it, its battery list, then Castor's app settings.
private fun openBatterySettings(context: Context) {
  val app = Uri.parse("package:${context.packageName}")
  listOf(
      Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app),
      Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app),
    )
    .firstOrNull { runCatching { context.startActivity(it) }.isSuccess }
}

// A standing warning under the header while Android optimizes Castor's battery use. It leaves as soon as Castor
// is let off, which is checked each time the screen comes back.
@Composable
internal fun BatteryOptimizationBanner(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var optimized by remember { mutableStateOf(!ignoresBatteryOptimizations(context)) }
  LifecycleResumeEffect(context) {
    optimized = !ignoresBatteryOptimizations(context)
    onPauseOrDispose {}
  }
  if (!optimized) return
  val colors = LocalWarningColors.current
  Surface(
    onClick = { openBatterySettings(context) },
    modifier = modifier.testTag("battery-warning"),
    shape = MaterialTheme.shapes.medium,
    color = colors.container,
    contentColor = colors.content,
    border = BorderStroke(1.dp, colors.content.copy(alpha = .28f)),
  ) {
    Row(
      Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      CastIcon(R.drawable.ic_warning)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.battery_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.battery_body), style = MaterialTheme.typography.bodySmall)
      }
      CastIcon(R.drawable.ic_next)
    }
  }
}
