package io.github.stupside.castor.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import castor.v1.DeviceOuterClass.DeviceType
import io.github.stupside.castor.R
import io.github.stupside.castor.theme.*
import java.util.Locale

@Composable
internal fun AppLanguagePicker(
  selected: AppLanguage,
  dismiss: () -> Unit,
  select: (AppLanguage) -> Unit,
) {
  GlassSheet(dismiss) {
    Column(
      Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp)
        .padding(bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          stringResource(R.string.app_language),
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = dismiss, modifier = Modifier.glassMaterial(RoundedCornerShape(50))) {
          CastIcon(R.drawable.ic_close, stringResource(R.string.close))
        }
      }
      Text(
        stringResource(R.string.language_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppLanguage.entries.forEach { language ->
          AppChoiceRow(
            selected == language,
            language.nativeName,
            if (language == AppLanguage.English) stringResource(R.string.language_default) else null,
          ) {
            select(language)
          }
        }
      }
    }
  }
}

internal fun themeLabel(theme: AppTheme): Int =
  when (theme) {
    AppTheme.System -> R.string.theme_system
    AppTheme.Light -> R.string.theme_light
    AppTheme.Dark -> R.string.theme_dark
  }

@Composable
internal fun AppThemePicker(selected: AppTheme, dismiss: () -> Unit, select: (AppTheme) -> Unit) {
  GlassSheet(dismiss) {
    Column(
      Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp)
        .padding(bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          stringResource(R.string.appearance),
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = dismiss, modifier = Modifier.glassMaterial(RoundedCornerShape(50))) {
          CastIcon(R.drawable.ic_close, stringResource(R.string.close))
        }
      }
      Text(
        stringResource(R.string.appearance_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppTheme.entries.forEach { theme ->
          AppChoiceRow(
            selected == theme,
            stringResource(themeLabel(theme)),
            if (theme == AppTheme.Dark) stringResource(R.string.theme_default) else null,
          ) {
            select(theme)
          }
        }
      }
    }
  }
}

@Composable
private fun AppChoiceRow(chosen: Boolean, label: String, detail: String?, select: () -> Unit) {
  Row(
    Modifier.fillMaxWidth()
      .glassMaterial(MaterialTheme.shapes.medium, chosen)
      .selectable(chosen, role = Role.RadioButton, onClick = select)
      .heightIn(min = 76.dp)
      .padding(horizontal = 20.dp, vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    CompositionLocalProvider(
      LocalContentColor provides
        if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge)
        detail?.let {
          Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color =
              if (chosen) MaterialTheme.colorScheme.onPrimary
              else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (chosen) CastIcon(R.drawable.ic_check)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TvPicker(
  state: MainScreenUiState,
  saved: SavedTv?,
  dismiss: () -> Unit,
  refresh: () -> Unit,
  select: (SavedTv) -> Unit,
) {
  var manual by rememberSaveable { mutableStateOf(false) }
  var address by rememberSaveable { mutableStateOf("") }
  var type by rememberSaveable { mutableStateOf(DeviceType.DEVICE_TYPE_DLNA) }
  val lastUsed =
    saved?.available(state.devices)?.takeIf { state.discoveryComplete && !state.discovering }
  val otherTvs =
    state.devices.filter {
      it.address.isNotBlank() && it.type in receiverTypes && SavedTv(it) != lastUsed
    }
  GlassSheet(dismiss) {
    Column(
      Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .imePadding()
        .padding(horizontal = 24.dp)
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (manual)
          IconButton(onClick = { manual = false }) {
            CastIcon(R.drawable.ic_back, stringResource(R.string.back))
          }
        Text(
          stringResource(if (manual) R.string.manual_title else R.string.choose_tv),
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = dismiss, modifier = Modifier.glassMaterial(RoundedCornerShape(50))) {
          CastIcon(R.drawable.ic_close, stringResource(R.string.close))
        }
      }
      if (manual) {
        OutlinedTextField(
          address,
          { address = it },
          label = { Text(stringResource(R.string.tv_address)) },
          supportingText = { Text(stringResource(R.string.address_help)) },
          modifier = Modifier.fillMaxWidth().glassMaterial(MaterialTheme.shapes.medium),
          shape = MaterialTheme.shapes.medium,
          colors = glassFieldColors(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          singleLine = true,
        )
        Text(stringResource(R.string.device_protocol), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          receiverTypes.forEach { family ->
            GlassChoice(type == family, { type = family }, label = { Text(protocolName(family)) })
          }
        }
        GlassButton(
          prominent = true,
          onClick = { select(SavedTv(address.trim(), address.trim(), type, manual = true)) },
          enabled = address.isNotBlank(),
          modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
          Text(stringResource(R.string.use_tv))
        }
      } else {
        Text(
          stringResource(R.string.search_help),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lastUsed?.let { tv ->
          GlassPanel(Modifier.fillMaxWidth(), onClick = { select(tv) }) {
            ListItem(
              headlineContent = { Text(tv.name) },
              supportingContent = {
                Text("${stringResource(R.string.last_tv)} · ${protocolName(tv.type)}")
              },
              leadingContent = { CastIcon(R.drawable.ic_tv) },
              trailingContent = { CastIcon(R.drawable.ic_next, stringResource(R.string.use_tv)) },
              colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
          }
        }
        if (lastUsed != null) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          Text(
            stringResource(R.string.other_tvs),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
          )
          if (otherTvs.isEmpty()) {
            Text(
              stringResource(R.string.no_other_tvs),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        if (state.discovering) {
          LinearProgressIndicator(Modifier.fillMaxWidth())
          Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodyMedium)
        }
        otherTvs.forEach { device ->
          GlassPanel(onClick = { select(SavedTv(device)) }) {
            ListItem(
              headlineContent = { Text(device.name.ifBlank { device.address }) },
              supportingContent = { Text(protocolName(device.type)) },
              leadingContent = { CastIcon(R.drawable.ic_tv) },
              trailingContent = { CastIcon(R.drawable.ic_next) },
              colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
          }
        }
        if (!state.discovering && state.devices.isEmpty()) {
          Text(stringResource(R.string.no_tvs), style = MaterialTheme.typography.titleMedium)
          Text(
            stringResource(R.string.no_tvs_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        state.discoveryError?.let {
          Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        GlassButton(
          onClick = refresh,
          enabled = state.ready && !state.discovering,
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
          CastIcon(R.drawable.ic_refresh)
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.search_again))
        }
        TextButton(onClick = { manual = true }, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.add_manually))
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackSettings(
  initialHeight: String,
  initialSubtitles: String,
  dismiss: () -> Unit,
  save: (String, String) -> Unit,
) {
  val presets = listOf("720", "1080", "2160")
  var height by rememberSaveable { mutableStateOf(initialHeight) }
  var custom by rememberSaveable {
    mutableStateOf(initialHeight !in presets && initialHeight != NATIVE_QUALITY)
  }
  var subtitleMode by rememberSaveable {
    mutableStateOf(if (initialSubtitles in listOf("off", "auto")) initialSubtitles else "language")
  }
  var language by rememberSaveable {
    mutableStateOf(if (subtitleMode == "language") initialSubtitles else "")
  }
  val validHeight = (!custom && height == NATIVE_QUALITY) || (height.toIntOrNull() ?: 0) >= 2
  val validLanguage =
    subtitleMode != "language" ||
      Regex("^[a-z]{2,3}$").matches(language.trim().lowercase(Locale.ROOT))
  val subtitles =
    if (subtitleMode == "language") language.trim().lowercase(Locale.ROOT) else subtitleMode
  val changed = height != initialHeight || subtitles != initialSubtitles
  // Closing with unsaved changes asks first, whether through X, a tap outside, a swipe or Back.
  var confirmClose by rememberSaveable { mutableStateOf(false) }
  fun close() {
    if (changed) confirmClose = true else dismiss()
  }
  GlassSheet(
    dismiss,
    allowDismiss = {
      if (changed) confirmClose = true
      !changed
    },
  ) {
    if (confirmClose)
      UnsavedChangesDialog(
        canSave = validHeight && validLanguage,
        keepEditing = { confirmClose = false },
        discard = dismiss,
        save = { save(height, subtitles) },
      )
    Column(
      Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .imePadding()
        .padding(horizontal = 24.dp)
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          stringResource(R.string.playback_settings),
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = ::close, modifier = Modifier.glassMaterial(RoundedCornerShape(50))) {
          CastIcon(R.drawable.ic_close, stringResource(R.string.close))
        }
      }
      Text(stringResource(R.string.quality), style = MaterialTheme.typography.titleMedium)
      GlassChoice(
        !custom && height == NATIVE_QUALITY,
        {
          custom = false
          height = NATIVE_QUALITY
        },
        label = { Text(stringResource(R.string.quality_native)) },
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { preset ->
          GlassChoice(
            !custom && height == preset,
            {
              custom = false
              height = preset
            },
            label = { Text("${preset}p") },
          )
        }
        GlassChoice(
          custom,
          {
            custom = true
            if (height == NATIVE_QUALITY) height = ""
          },
          label = { Text(stringResource(R.string.custom)) },
        )
      }
      if (custom)
        OutlinedTextField(
          height,
          { height = it },
          label = { Text(stringResource(R.string.height_pixels)) },
          isError = !validHeight,
          supportingText = { if (!validHeight) Text(stringResource(R.string.height_invalid)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().glassMaterial(MaterialTheme.shapes.medium),
          shape = MaterialTheme.shapes.medium,
          colors = glassFieldColors(),
        )
      Text(
        stringResource(
          if (!custom && height == NATIVE_QUALITY) R.string.quality_native_help
          else R.string.quality_help
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      HorizontalDivider()
      Text(stringResource(R.string.subtitles), style = MaterialTheme.typography.titleMedium)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "off" to R.string.subtitles_off,
            "auto" to R.string.subtitles_auto,
            "language" to R.string.subtitles_language,
          )
          .forEach { (value, label) ->
            GlassChoice(
              subtitleMode == value,
              { subtitleMode = value },
              label = { Text(stringResource(label)) },
            )
          }
      }
      if (subtitleMode == "language")
        OutlinedTextField(
          language,
          { language = it },
          label = { Text(stringResource(R.string.language_code)) },
          isError = !validLanguage,
          supportingText = { if (!validLanguage) Text(stringResource(R.string.language_invalid)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().glassMaterial(MaterialTheme.shapes.medium),
          shape = MaterialTheme.shapes.medium,
          colors = glassFieldColors(),
        )
      if (subtitleMode != "off")
        Text(
          stringResource(R.string.subtitles_help),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      GlassButton(
        prominent = true,
        onClick = { save(height, subtitles) },
        enabled = validHeight && validLanguage,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
      ) {
        Text(stringResource(R.string.save))
      }
    }
  }
}

@Composable
private fun UnsavedChangesDialog(
  canSave: Boolean,
  keepEditing: () -> Unit,
  discard: () -> Unit,
  save: () -> Unit,
) {
  GlassDialog(keepEditing) {
    Text(
      stringResource(R.string.unsaved_title),
      style = MaterialTheme.typography.headlineSmall,
      modifier = Modifier.semantics { heading() },
    )
    Text(
      stringResource(if (canSave) R.string.unsaved_message else R.string.unsaved_invalid),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    GlassButton(
      prominent = true,
      onClick = save,
      enabled = canSave,
      modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("unsaved-save"),
    ) {
      Text(stringResource(R.string.save))
    }
    GlassButton(onClick = discard, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.dont_save))
    }
  }
}
