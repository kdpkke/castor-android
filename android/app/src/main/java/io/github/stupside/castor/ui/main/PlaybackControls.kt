package io.github.stupside.castor.ui.main

import androidx.annotation.DrawableRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.stupside.castor.R
import io.github.stupside.castor.theme.GlassButton
import io.github.stupside.castor.theme.glassMaterial
import kotlin.math.roundToLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun PlaybackControls(
  state: PlaybackUiState,
  enabled: Boolean,
  togglePause: () -> Unit,
  seekBy: (Long) -> Unit,
  seekTo: (Long) -> Unit,
) {
  val duration = state.durationMs?.takeIf { it > 0 }
  var draggedPosition by remember(duration) { mutableStateOf<Long?>(null) }
  val sliderInteraction = remember { MutableInteractionSource() }
  LaunchedEffect(sliderInteraction) {
    sliderInteraction.interactions.collect {
      if (it is DragInteraction.Cancel) draggedPosition = null
    }
  }
  val position = draggedPosition ?: state.seekTargetMs ?: state.positionMs
  val canSeek =
    enabled && state.available && state.canSeek && duration != null && !state.changingPause
  val positionLabel = stringResource(R.string.playback_position)
  val spokenPosition =
    stringResource(R.string.playback_position_value, playbackTime(position), playbackTime(duration))
  Column(
    Modifier.fillMaxWidth().testTag("playback-controls"),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (duration != null) {
      Slider(
        value =
          if (position == null) 0f else (position.toDouble() / duration).toFloat().coerceIn(0f, 1f),
        onValueChange = { draggedPosition = (it.toDouble() * duration).roundToLong() },
        onValueChangeFinished = {
          draggedPosition?.let(seekTo)
          draggedPosition = null
        },
        enabled = canSeek,
        interactionSource = sliderInteraction,
        modifier =
          Modifier.fillMaxWidth().testTag("playback-timeline").semantics {
            contentDescription = positionLabel
            stateDescription = spokenPosition
          },
      )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        playbackTime(position),
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("playback-position"),
      )
      Text(
        playbackTime(duration),
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val compact = maxWidth < 232.dp
      Row(
        Modifier.fillMaxWidth().heightIn(min = 80.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        RepeatingSeekButton(
          R.drawable.ic_replay_10,
          stringResource(R.string.playback_back_ten),
          stringResource(R.string.playback_hold_back),
          canSeek && (position ?: 0) > 0,
          state.error,
          Modifier.size(if (compact) 48.dp else 56.dp).testTag("playback-back"),
        ) {
          seekBy(-10_000)
        }
        GlassButton(
          onClick = togglePause,
          prominent = true,
          enabled = enabled && state.canTogglePause,
          contentPadding = PaddingValues(0.dp),
          modifier = Modifier.size(if (compact) 64.dp else 72.dp).testTag("playback-pause"),
        ) {
          if (state.changingPause) {
            CircularProgressIndicator(
              Modifier.size(28.dp),
              strokeWidth = 2.dp,
              color = LocalContentColor.current,
            )
          } else {
            Icon(
              painterResource(if (state.paused) R.drawable.ic_play else R.drawable.ic_pause),
              stringResource(
                if (state.paused) R.string.playback_resume else R.string.playback_pause
              ),
              modifier = Modifier.size(32.dp),
            )
          }
        }
        RepeatingSeekButton(
          R.drawable.ic_forward_10,
          stringResource(R.string.playback_forward_ten),
          stringResource(R.string.playback_hold_forward),
          canSeek && (position ?: 0) < (duration ?: 0),
          state.error,
          Modifier.size(if (compact) 48.dp else 56.dp).testTag("playback-forward"),
        ) {
          seekBy(10_000)
        }
      }
    }
    Text(
      stringResource(
        when {
          !state.available -> R.string.playback_waiting
          state.seekTargetMs != null -> R.string.playback_seeking
          !state.canSeek || duration == null -> R.string.playback_seek_unavailable
          else -> R.string.playback_hold_hint
        }
      ),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun RepeatingSeekButton(
  @DrawableRes icon: Int,
  description: String,
  holdDescription: String,
  enabled: Boolean,
  error: String?,
  modifier: Modifier,
  step: () -> Unit,
) {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val currentStep by rememberUpdatedState(step)
  val scope = rememberCoroutineScope()
  var repeating by remember { mutableStateOf<Job?>(null) }
  val owner = LocalLifecycleOwner.current
  LaunchedEffect(enabled) { if (!enabled) repeating?.cancel() }
  LaunchedEffect(error) { if (error != null) repeating?.cancel() }
  LaunchedEffect(interaction) {
    interaction.interactions.collect {
      if (it is PressInteraction.Release || it is PressInteraction.Cancel) repeating?.cancel()
    }
  }
  DisposableEffect(owner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP) repeating?.cancel()
    }
    owner.lifecycle.addObserver(observer)
    onDispose {
      owner.lifecycle.removeObserver(observer)
      repeating?.cancel()
    }
  }
  Box(
    modifier
      .alpha(if (enabled) 1f else .45f)
      .glassMaterial(CircleShape)
      .combinedClickable(
        interactionSource = interaction,
        indication = LocalIndication.current,
        enabled = enabled,
        role = Role.Button,
        onLongClickLabel = holdDescription,
        onLongClick = {
          currentStep()
          repeating?.cancel()
          if (pressed)
            repeating = scope.launch {
              while (isActive) {
                delay(300)
                currentStep()
              }
            }
        },
        onClick = { currentStep() },
      )
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      painterResource(icon),
      null,
      tint = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.size(36.dp),
    )
  }
}

internal fun playbackTime(milliseconds: Long?): String {
  if (milliseconds == null) return "--:--"
  val seconds = milliseconds.coerceAtLeast(0) / 1000
  return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
  else "%d:%02d".format(seconds / 60, seconds % 60)
}
