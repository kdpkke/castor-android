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
            if (language == AppLanguage.English) stringResource(R.string.language_default)
            else null,
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
  describe: (List<SavedTv>) -> Unit,
  select: (SavedTv) -> Unit,
) {
  var manual by rememberSaveable { mutableStateOf(false) }
  var selectedHost by rememberSaveable { mutableStateOf<String?>(null) }
  var address by rememberSaveable { mutableStateOf("") }
  var type by rememberSaveable { mutableStateOf(DeviceType.DEVICE_TYPE_DLNA) }
  val televisions =
    if (state.discoveryComplete && !state.discovering) discoveredTvs(state.devices, saved)
    else emptyList()
  val selected = televisions.firstOrNull { it.host == selectedHost }
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
        if (manual) {
          IconButton(onClick = { manual = false }) {
            CastIcon(R.drawable.ic_back, stringResource(R.string.back))
          }
        }
        Text(
          if (manual) stringResource(R.string.manual_title)
          else selected?.name ?: stringResource(R.string.choose_tv),
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = dismiss, modifier = Modifier.glassMaterial(RoundedCornerShape(50))) {
          CastIcon(R.drawable.ic_close, stringResource(R.string.close))
        }
      }
      when {
        manual -> {
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
          Text(
            stringResource(R.string.device_protocol),
            style = MaterialTheme.typography.titleMedium,
          )
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
        }
        selected != null -> {
          LaunchedEffect(selected.host) { describe(selected.protocols) }
          val ranked = rankedProtocols(selected.protocols, state.verdicts)
          val recommended = recommendedProtocol(ranked, state.verdicts)
          Text(
            stringResource(R.string.available_protocols),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          ranked.forEach { tv ->
            GlassPanel(
              Modifier.fillMaxWidth().testTag("receiver-protocol-${tv.type.number}"),
              onClick = { select(tv) },
            ) {
              ListItem(
                headlineContent = { Text(protocolName(tv.type)) },
                supportingContent = {
                  Column {
                    Text(receiverEndpoint(tv))
                    state.verdicts[verdictKey(tv)]?.let { Text(stringResource(verdictLabel(it))) }
                    if (tv == recommended)
                      Text(stringResource(R.string.recommended), color = MaterialTheme.colorScheme.primary)
                    if (state.occupying(tv) != null) Text(stringResource(R.string.tv_in_use))
                  }
                },
                leadingContent = { CastIcon(R.drawable.ic_cast) },
                trailingContent = { CastIcon(R.drawable.ic_next) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
              )
            }
          }
          GlassButton(
            onClick = { selectedHost = null },
            modifier = Modifier.fillMaxWidth().testTag("receiver-back"),
          ) {
            CastIcon(R.drawable.ic_back)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.back))
          }
        }
        else -> {
          if (state.discovering) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodyMedium)
          }
          televisions.forEach { tv ->
            GlassPanel(
              Modifier.fillMaxWidth().testTag("receiver-tv-${tv.host}"),
              onClick = { selectedHost = tv.host },
            ) {
              ListItem(
                headlineContent = { Text(tv.name) },
                supportingContent = { Text(tv.summary) },
                leadingContent = { CastIcon(R.drawable.ic_tv) },
                trailingContent = { CastIcon(R.drawable.ic_next) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
              )
            }
          }
          if (!state.discovering && televisions.isEmpty()) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackSettings(
  initialHeight: String,
  initialSubtitles: String,
  dismiss: () -> Unit,
  save: (String, String) -> Unit,
) {
  var height by rememberSaveable { mutableStateOf(playbackQuality(initialHeight)) }
  var subtitleMode by rememberSaveable {
    mutableStateOf(if (initialSubtitles in listOf("off", "auto")) initialSubtitles else "language")
  }
  var language by rememberSaveable {
    mutableStateOf(if (subtitleMode == "language") initialSubtitles else "")
  }
  val validLanguage =
    subtitleMode != "language" ||
      Regex("^[a-z]{2,3}$").matches(language.trim().lowercase(Locale.ROOT))
  val subtitles =
    if (subtitleMode == "language") language.trim().lowercase(Locale.ROOT) else subtitleMode
  val changed = height != playbackQuality(initialHeight) || subtitles != initialSubtitles
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
        canSave = validLanguage,
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
      FlowRow(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        listOf(
            NATIVE_QUALITY to R.string.quality_native,
            OPTIMIZED_QUALITY to R.string.quality_optimized,
          )
          .forEach { (value, label) ->
            GlassChoice(
              height == value,
              { height = value },
              label = { Text(stringResource(label)) },
            )
          }
      }
      Text(
        stringResource(
          if (height == NATIVE_QUALITY) R.string.quality_native_help
          else R.string.quality_optimized_help
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
          stringResource(
            if (height == NATIVE_QUALITY) R.string.subtitles_native_help
            else R.string.subtitles_help
          ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      GlassButton(
        prominent = true,
        onClick = { save(height, subtitles) },
        enabled = validLanguage,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
      ) {
        Text(stringResource(R.string.save))
      }
    }
  }
}

@Composable
internal fun PairingDialog(
  pairing: PairingUiState,
  retry: () -> Unit,
  finish: (String) -> Unit,
  cancel: () -> Unit,
) {
  var pin by rememberSaveable(pairing.pairingId) { mutableStateOf("") }
  GlassDialog(cancel) {
    Text(
      stringResource(R.string.pair_title),
      style = MaterialTheme.typography.headlineSmall,
      modifier = Modifier.semantics { heading() },
    )
    Text(
      stringResource(R.string.pair_message, pairing.tv),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when {
      pairing.pairingId != null ->
        OutlinedTextField(
          pin,
          { value -> pin = value.filter(Char::isDigit).take(8) },
          // A placeholder, not a label: a floating label moves the outline below the glass surface.
          placeholder = { Text(stringResource(R.string.pair_pin)) },
          modifier = Modifier.fillMaxWidth().glassMaterial(MaterialTheme.shapes.medium).testTag("pairing-pin"),
          shape = MaterialTheme.shapes.medium,
          colors = glassFieldColors(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
          singleLine = true,
          enabled = !pairing.busy,
        )
      pairing.busy -> {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.pair_asking), style = MaterialTheme.typography.bodyMedium)
      }
    }
    if (pairing.wrongPin)
      Text(stringResource(R.string.pair_wrong_pin), color = MaterialTheme.colorScheme.error)
    pairing.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (pairing.pairingId != null)
      GlassButton(
        prominent = true,
        onClick = { finish(pin) },
        enabled = pin.isNotBlank() && !pairing.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pairing-confirm"),
      ) {
        Text(stringResource(R.string.pair_confirm))
      }
    else if (!pairing.busy)
      GlassButton(onClick = retry, modifier = Modifier.fillMaxWidth().testTag("pairing-retry")) {
        Text(stringResource(R.string.pair_retry))
      }
    GlassButton(onClick = cancel, modifier = Modifier.fillMaxWidth().testTag("pairing-cancel")) {
      Text(stringResource(R.string.pair_cancel))
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
