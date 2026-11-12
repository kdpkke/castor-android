package io.github.stupside.castor.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stupside.castor.R
import io.github.stupside.castor.theme.GlassButton
import io.github.stupside.castor.theme.glassMaterial

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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        stringResource(R.string.active_transmissions),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.weight(1f),
      )
      GlassButton(
        onClick = newCast,
        enabled = !state.submitting,
        modifier = Modifier.testTag("new-cast"),
      ) {
        Text(stringResource(R.string.new_cast))
      }
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      state.sessions.forEach { session ->
        val cast = session.cast
        val chosen = state.selectedCastId == cast.id
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
        Row(
          Modifier.fillMaxWidth()
            .glassMaterial(MaterialTheme.shapes.medium)
            .selectable(chosen, role = Role.RadioButton, onClick = { select(cast.id) })
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("session-${cast.id}"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          CastIcon(R.drawable.ic_tv)
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              receiverName(cast.device)
                .ifBlank { cast.device.address }
                .ifBlank { stringResource(R.string.need_tv) },
              style = MaterialTheme.typography.titleMedium,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              listOf(status, label).filter { it.isNotBlank() }.joinToString(" · "),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (chosen) CastIcon(R.drawable.ic_check)
        }
      }
    }
  }
}
