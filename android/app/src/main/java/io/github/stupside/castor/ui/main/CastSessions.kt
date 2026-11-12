package io.github.stupside.castor.ui.main

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stupside.castor.R
import io.github.stupside.castor.theme.glassMaterial

// The casts running, one row per TV, and a last row that prepares a cast to another TV. One row is chosen at a
// time: the screen below and the buttons at the bottom act on it, while every other cast keeps playing.
@Composable
internal fun CastSessions(
  state: MainScreenUiState,
  videoName: (String) -> String?,
  select: (String) -> Unit,
  newCast: () -> Unit,
  // The name of the TV a cast goes to, the same whichever protocol it uses.
  receiverName: (castor.v1.DeviceOuterClass.Device) -> String = { it.name },
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      stringResource(R.string.active_transmissions),
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.semantics { heading() },
    )
    Text(
      stringResource(
        if (state.sessions.size == 1) R.string.transmissions_help_one
        else R.string.transmissions_help_many
      ),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      state.sessions.forEach { session ->
        val cast = session.cast
        val source =
          when {
            cast.source.hasPages() -> cast.source.pages.urlsList.firstOrNull().orEmpty()
            cast.source.hasStream() -> cast.source.stream.url
            else -> ""
          }
        val label =
          videoName(source) ?: runCatching { java.net.URI(source).host }.getOrNull().orEmpty()
        val status =
          stringResource(
            when (session.phase) {
              CastPhase.Casting -> R.string.phase_casting
              CastPhase.Restarting -> R.string.restarting_casting
              CastPhase.Failed -> R.string.phase_failed
              else -> R.string.connecting
            }
          )
        TransmissionRow(
          state.selectedCastId == cast.id,
          R.drawable.ic_tv,
          receiverName(cast.device)
            .ifBlank { cast.device.address }
            .ifBlank { stringResource(R.string.need_tv) },
          listOf(status, label).filter { it.isNotBlank() }.joinToString(" · "),
          Modifier.testTag("session-${cast.id}"),
        ) {
          select(cast.id)
        }
      }
      // Chosen while nothing else is: the screen then prepares the new cast.
      TransmissionRow(
        state.selectedCastId == null,
        R.drawable.ic_cast,
        stringResource(R.string.cast_to_another_tv),
        stringResource(R.string.cast_to_another_tv_help),
        Modifier.testTag("new-cast"),
        enabled = !state.submitting,
        select = newCast,
      )
    }
  }
}

@Composable
private fun TransmissionRow(
  chosen: Boolean,
  @DrawableRes icon: Int,
  title: String,
  detail: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  select: () -> Unit,
) {
  Row(
    modifier
      .fillMaxWidth()
      .glassMaterial(MaterialTheme.shapes.medium, chosen)
      .selectable(chosen, enabled = enabled, role = Role.RadioButton, onClick = select)
      .heightIn(min = 72.dp)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    CompositionLocalProvider(
      LocalContentColor provides
        if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    ) {
      CastIcon(icon)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          title,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          detail,
          style = MaterialTheme.typography.bodySmall,
          color =
            if (chosen) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (chosen) CastIcon(R.drawable.ic_check)
    }
  }
}
