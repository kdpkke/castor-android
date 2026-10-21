package io.github.stupside.castor.ui.main

import android.content.ClipData
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import castor.v1.DeviceOuterClass.DeviceType
import io.github.stupside.castor.BrowserResolver
import io.github.stupside.castor.R
import io.github.stupside.castor.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
  viewModel: MainScreenViewModel,
  browser: BrowserResolver?,
  sharedUrl: String,
  close: () -> Unit,
  restart: () -> Unit = {},
  language: AppLanguage = AppLanguage.English,
  selectLanguage: (AppLanguage) -> Unit = {},
  theme: AppTheme = AppTheme.Dark,
  selectTheme: (AppTheme) -> Unit = {},
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val compact = LocalConfiguration.current.screenHeightDp < 480
  val options = remember(context) { CastOptions(context) }
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val clipboardEmpty = stringResource(R.string.clipboard_empty)
  val errorCopied = stringResource(R.string.error_copied)
  var url by rememberSaveable(sharedUrl) { mutableStateOf(sharedUrl) }
  var mode by rememberSaveable(sharedUrl) { mutableStateOf(VideoSourceMode.Automatic) }
  var tvName by rememberSaveable { mutableStateOf(options.television()?.name.orEmpty()) }
  var tvAddress by rememberSaveable { mutableStateOf(options.television()?.address.orEmpty()) }
  var tvType by rememberSaveable { mutableIntStateOf(options.television()?.type?.number ?: 1) }
  var tvId by rememberSaveable { mutableStateOf(options.television()?.id.orEmpty()) }
  val tv =
    if (tvAddress.isBlank()) null
    else SavedTv(tvName, tvAddress, DeviceType.forNumber(tvType), tvId)
  var height by rememberSaveable { mutableStateOf(options.height()) }
  var subtitles by rememberSaveable { mutableStateOf(options.subtitles()) }
  var sheet by rememberSaveable { mutableStateOf<String?>(null) }
  var menu by remember { mutableStateOf(false) }
  val busy = state.castId != null || state.submitting
  val validUrl = BrowserResolver.isHttp(url.trim()) && url.trim().length <= 8192
  val extracting = browser?.active?.collectAsStateWithLifecycle()?.value == true
  val browserPage = browser?.page?.collectAsStateWithLifecycle()?.value
  val subtitleLabel =
    when (subtitles) {
      "off" -> stringResource(R.string.subtitles_off)
      "auto" -> stringResource(R.string.subtitles_auto)
      else -> subtitles.uppercase(java.util.Locale.ROOT)
    }

  Scaffold(
    containerColor = Color.Transparent,
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      TopAppBar(
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(40.dp))
            Spacer(Modifier.width(6.dp))
            Text("Castor", style = MaterialTheme.typography.titleLarge)
          }
        },
        actions = {
          Text(
            stringResource(
              if (state.ready) R.string.engine_ready
              else if (state.phase == CastPhase.Preparing) R.string.engine_preparing
              else R.string.engine_unavailable
            ),
            modifier =
              Modifier.widthIn(max = 170.dp)
                .glassMaterial(RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Box {
            IconButton(
              onClick = { menu = true },
              modifier =
                Modifier.padding(start = 8.dp, end = 12.dp).glassMaterial(RoundedCornerShape(50)),
            ) {
              CastIcon(R.drawable.ic_more, stringResource(R.string.more_options))
            }
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
      )
    },
    bottomBar = {
      Surface(color = Color.Transparent) {
        Box(
          Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            Modifier.widthIn(max = 680.dp)
              .fillMaxWidth()
              .padding(horizontal = 24.dp, vertical = if (compact) 8.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            if (!busy) {
              Text(
                stringResource(
                  when {
                    !state.ready -> R.string.engine_help
                    !validUrl -> R.string.need_url
                    tv == null -> R.string.need_tv
                    else -> R.string.local_only
                  }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
              )
            }
            if (state.castId != null) {
              GlassButton(
                prominent = true,
                onClick = { viewModel.stop() },
                enabled = !state.stopping,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("cast-action"),
              ) {
                CastIcon(R.drawable.ic_stop)
                Spacer(Modifier.width(10.dp))
                Text(
                  stringResource(if (state.stopping) R.string.stopping else R.string.stop_casting)
                )
              }
            } else if (!state.ready && state.error != null) {
              GlassButton(
                prominent = true,
                onClick = restart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
              ) {
                Text(stringResource(R.string.restart_castor))
              }
            } else {
              GlassButton(
                prominent = true,
                onClick = {
                  tv?.let {
                    if (!mode.isPage(url.trim())) browser?.dismissPage()
                    viewModel.cast(
                      url.trim(),
                      mode.isPage(url.trim()),
                      it.target(),
                      height,
                      subtitles,
                      receiverName = it.name,
                      subtitleLanguage = options.language().tag,
                    )
                  }
                },
                enabled = state.ready && !state.submitting && validUrl && tv != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("cast-action"),
              ) {
                if (state.submitting)
                  CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else CastIcon(R.drawable.ic_cast)
                Spacer(Modifier.width(10.dp))
                Text(
                  stringResource(if (state.submitting) R.string.connecting else R.string.cast_video)
                )
              }
            }
          }
        }
      }
    },
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
      Column(
        Modifier.widthIn(max = 680.dp)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp)
          .padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        if (busy) {
          val active = state.activeCast
          val source = active?.source
          val activeUrl =
            when {
              source?.hasStream() == true -> source.stream.url
              source?.hasPages() == true -> source.pages.urlsList.firstOrNull().orEmpty()
              else -> ""
            }
          val receiver =
            active
              ?.device
              ?.takeIf { it.address.isNotBlank() && it.type in receiverTypes }
              ?.let { SavedTv(it) }
          ActiveCast(state.phase, activeUrl, receiver)
        } else {
          if (!compact)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
              )
              Text(
                stringResource(R.string.app_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
            Row(
              Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Text(
                stringResource(R.string.video_heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
              )
              SourceModePicker(mode) { mode = it }
            }
            val inputDescription = stringResource(R.string.video_url)
            GlassPanel(Modifier.fillMaxWidth()) {
              TextField(
                url,
                { url = it },
                placeholder = { Text(stringResource(R.string.video_hint)) },
                modifier =
                  Modifier.fillMaxWidth()
                    .heightIn(min = if (compact) 64.dp else 88.dp)
                    .testTag("video-url")
                    .semantics { contentDescription = inputDescription },
                shape = MaterialTheme.shapes.large,
                colors =
                  TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                  ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                maxLines = 3,
                isError = url.isNotBlank() && !validUrl,
                supportingText =
                  if (url.isNotBlank() && !validUrl) {
                    { Text(stringResource(R.string.video_invalid)) }
                  } else null,
                trailingIcon = {
                  if (url.isNotEmpty())
                    IconButton(onClick = { url = "" }) {
                      CastIcon(R.drawable.ic_close, stringResource(R.string.clear_link))
                    }
                },
              )
            }
            FlowRow(
              Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
              itemVerticalAlignment = Alignment.CenterVertically,
            ) {
              GlassButton(
                onClick = {
                  val clip =
                    context
                      .getSystemService(android.content.ClipboardManager::class.java)
                      .primaryClip
                  val text =
                    if (clip != null && clip.itemCount > 0)
                      clip.getItemAt(0).coerceToText(context)?.toString()
                    else null
                  if (text.isNullOrBlank()) scope.launch { snackbar.showSnackbar(clipboardEmpty) }
                  else {
                    url = text.trim()
                    mode = VideoSourceMode.Automatic
                  }
                }
              ) {
                CastIcon(R.drawable.ic_paste)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.paste_link))
              }
              if (validUrl)
                Text(
                  stringResource(
                    if (mode.isPage(url.trim())) R.string.recognized_page
                    else R.string.recognized_direct
                  ),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
          }
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionRow(
              R.drawable.ic_tv,
              tv?.name ?: stringResource(R.string.choose_tv),
              tv?.let { "${protocolName(it.type)} · ${it.address}" }
                ?: stringResource(R.string.tv_help),
              if (tv == null) R.string.choose_tv else R.string.change_tv,
            ) {
              sheet = "tv"
              if (state.ready) viewModel.discover()
            }
            ActionRow(
              R.drawable.ic_tune,
              stringResource(R.string.playback_settings),
              stringResource(
                R.string.preferences_summary,
                if (height == NATIVE_QUALITY) stringResource(R.string.quality_native)
                else "${height}p",
                subtitleLabel,
              ),
              R.string.playback_settings,
            ) {
              sheet = "settings"
            }
          }
          if (state.phase == CastPhase.Completed || state.phase == CastPhase.Stopped)
            Text(
              stringResource(
                if (state.phase == CastPhase.Stopped) R.string.cast_stopped
                else R.string.cast_completed
              ),
              style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.error?.let { error ->
          ErrorPanel(error, { viewModel.dismissError() }) {
            scope.launch {
              clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Castor error", error)))
              snackbar.showSnackbar(errorCopied)
            }
          }
        }
        if (browserPage != null && browser != null) {
          PageBrowser(browser, browserPage, extracting, compact, busy)
        }
      }
    }
  }
  if (menu)
    GlassSheet({ menu = false }) {
      Column(
        Modifier.fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp)
          .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            "Castor",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
          )
          IconButton(
            onClick = { menu = false },
            modifier = Modifier.glassMaterial(RoundedCornerShape(50)),
          ) {
            CastIcon(R.drawable.ic_close, stringResource(R.string.close))
          }
        }
        ActionRow(
          R.drawable.ic_language,
          stringResource(R.string.app_language),
          language.nativeName,
          R.string.app_language,
        ) {
          menu = false
          sheet = "language"
        }
        ActionRow(
          R.drawable.ic_theme,
          stringResource(R.string.appearance),
          stringResource(themeLabel(theme)),
          R.string.appearance,
        ) {
          menu = false
          sheet = "theme"
        }
        GlassButton(
          onClick = {
            menu = false
            restart()
          },
          enabled = !state.ready,
          modifier = Modifier.fillMaxWidth(),
        ) {
          CastIcon(R.drawable.ic_refresh)
          Spacer(Modifier.width(10.dp))
          Text(stringResource(R.string.restart_castor))
        }
        GlassButton(
          onClick = {
            menu = false
            close()
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          CastIcon(R.drawable.ic_stop)
          Spacer(Modifier.width(10.dp))
          Text(stringResource(R.string.stop_castor))
        }
      }
    }
  if (sheet == "language") AppLanguagePicker(language, { sheet = null }, selectLanguage)
  if (sheet == "theme") AppThemePicker(theme, { sheet = null }, selectTheme)
  if (sheet == "tv")
    TvPicker(state, tv, { sheet = null }, { viewModel.discover() }) { selected ->
      tvName = selected.name
      tvAddress = selected.address
      tvType = selected.type.number
      tvId = selected.id
      options.television(selected)
      sheet = null
    }
  if (sheet == "settings")
    PlaybackSettings(height, subtitles, { sheet = null }) { newHeight, newSubtitles ->
      height = newHeight
      subtitles = newSubtitles
      options.playback(height, subtitles)
      sheet = null
    }
}

@Composable
internal fun CastIcon(@DrawableRes resource: Int, description: String? = null) {
  Icon(painterResource(resource), description, Modifier.size(24.dp))
}

internal fun protocolName(type: DeviceType): String =
  when (type) {
    DeviceType.DEVICE_TYPE_CHROMECAST -> "Chromecast"
    DeviceType.DEVICE_TYPE_ROKU -> "Roku"
    else -> "DLNA"
  }

@Composable
internal fun ActionRow(
  @DrawableRes icon: Int,
  title: String,
  detail: String,
  action: Int,
  click: () -> Unit,
) {
  GlassPanel(onClick = click, modifier = Modifier.fillMaxWidth()) {
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
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      CastIcon(R.drawable.ic_next, stringResource(action))
    }
  }
}

@Composable
private fun SourceModePicker(mode: VideoSourceMode, select: (VideoSourceMode) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  fun label(value: VideoSourceMode) =
    when (value) {
      VideoSourceMode.Automatic -> R.string.mode_auto
      VideoSourceMode.WebPage -> R.string.mode_page
      VideoSourceMode.DirectStream -> R.string.mode_direct
    }
  Box {
    TextButton(onClick = { expanded = true }) {
      Text(stringResource(label(mode)))
      Spacer(Modifier.width(4.dp))
      CastIcon(R.drawable.ic_chevron_down, stringResource(R.string.mode_description))
    }
    if (expanded)
      GlassSheet({ expanded = false }) {
        Column(
          Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              stringResource(R.string.mode_description),
              style = MaterialTheme.typography.headlineMedium,
              modifier = Modifier.weight(1f),
            )
            IconButton(
              onClick = { expanded = false },
              modifier = Modifier.glassMaterial(RoundedCornerShape(50)),
            ) {
              CastIcon(R.drawable.ic_close, stringResource(R.string.close))
            }
          }
          Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VideoSourceMode.entries.forEach { value ->
              Row(
                Modifier.fillMaxWidth()
                  .glassMaterial(MaterialTheme.shapes.medium, value == mode)
                  .selectable(
                    value == mode,
                    role = Role.RadioButton,
                    onClick = {
                      select(value)
                      expanded = false
                    },
                  )
                  .heightIn(min = 60.dp)
                  .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  stringResource(label(value)),
                  modifier = Modifier.weight(1f),
                  style = MaterialTheme.typography.titleMedium,
                  color =
                    if (value == mode) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (value == mode)
                  CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onPrimary
                  ) {
                    CastIcon(R.drawable.ic_check)
                  }
              }
            }
          }
        }
      }
  }
}

@Composable
private fun ErrorPanel(error: String, dismiss: () -> Unit, copy: () -> Unit) {
  var details by rememberSaveable(error) { mutableStateOf(false) }
  GlassPanel(Modifier.testTag("cast-error")) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        stringResource(R.string.cast_error),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
      )
      Text(stringResource(R.string.error_help), style = MaterialTheme.typography.bodyMedium)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = copy) {
          Text(stringResource(R.string.copy_error), color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = { details = !details }) {
          Text(
            stringResource(if (details) R.string.hide_details else R.string.show_details),
            color = MaterialTheme.colorScheme.error,
          )
        }
        TextButton(onClick = dismiss) {
          Text(stringResource(R.string.dismiss), color = MaterialTheme.colorScheme.error)
        }
      }
      if (details) SelectionContainer { Text(error, style = MaterialTheme.typography.bodySmall) }
    }
  }
}

@Composable
private fun ActiveCast(phase: CastPhase, url: String, tv: SavedTv?) {
  val (title, help) =
    when (phase) {
      CastPhase.Extracting -> R.string.phase_extracting to R.string.phase_extracting_help
      CastPhase.Measuring -> R.string.phase_measuring to R.string.phase_measuring_help
      CastPhase.Casting -> R.string.phase_casting to R.string.phase_casting_help
      CastPhase.Failed -> R.string.phase_failed to R.string.phase_failed_help
      else -> R.string.phase_connecting to R.string.phase_connecting_help
    }
  Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
    Text(
      stringResource(title),
      style = MaterialTheme.typography.headlineLarge,
      modifier =
        Modifier.semantics {
          heading()
          liveRegion = LiveRegionMode.Polite
        },
    )
    Text(stringResource(help), color = MaterialTheme.colorScheme.onSurfaceVariant)
    GlassPanel {
      Column(
        Modifier.fillMaxWidth().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        CastIcon(R.drawable.ic_cast)
        Text(
          tv?.name ?: stringResource(R.string.current_tv),
          style = MaterialTheme.typography.headlineMedium,
        )
        tv?.let { Text(protocolName(it.type), style = MaterialTheme.typography.bodyMedium) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
          runCatching { java.net.URI(url).host }.getOrNull()
            ?: stringResource(R.string.video_source),
          style = MaterialTheme.typography.bodyLarge,
        )
      }
    }
    if (phase != CastPhase.Casting && phase != CastPhase.Failed)
      LinearProgressIndicator(Modifier.fillMaxWidth())
  }
}
