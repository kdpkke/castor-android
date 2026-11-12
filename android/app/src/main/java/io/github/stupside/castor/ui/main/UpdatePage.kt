package io.github.stupside.castor.ui.main

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.stupside.castor.AppUpdates
import io.github.stupside.castor.R
import io.github.stupside.castor.Release
import io.github.stupside.castor.Releases
import io.github.stupside.castor.UpdateStatus
import io.github.stupside.castor.theme.GlassButton
import io.github.stupside.castor.theme.GlassPanel
import io.github.stupside.castor.theme.GlassScene
import io.github.stupside.castor.theme.glassMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

// A page of its own for a new release: what changed, and the update from download to installation. Closing it
// leaves the update as it is; a download already started goes on.
@Composable
internal fun UpdatePage(
  release: Release,
  status: UpdateStatus,
  installed: String,
  language: String,
  dismiss: () -> Unit,
) {
  val context = LocalContext.current
  val application = context.applicationContext
  val downloading = status is UpdateStatus.Downloading
  LaunchedEffect(downloading) {
    while (downloading && isActive) {
      withContext(Dispatchers.IO) { AppUpdates.refresh(application) }
      delay(500)
    }
  }
  fun start() {
    // Android asks once whether Castor may install apps. The download goes on while the user answers.
    if (!AppUpdates.canInstall(context))
      runCatching { context.startActivity(AppUpdates.installPermissionIntent(context)) }
    AppUpdates.update(application)
  }
  Dialog(
    onDismissRequest = dismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    GlassScene(Modifier.fillMaxSize().testTag("update-page")) {
      Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
          Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            stringResource(R.string.update_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f).semantics { heading() },
          )
          IconButton(
            onClick = dismiss,
            modifier = Modifier.glassMaterial(RoundedCornerShape(50)).testTag("update-close"),
          ) {
            CastIcon(R.drawable.ic_close, stringResource(R.string.close))
          }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
          Column(
            Modifier.widthIn(max = 680.dp)
              .fillMaxWidth()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 24.dp)
              .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
          ) {
            GlassPanel(Modifier.fillMaxWidth()) {
              Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CastIcon(R.drawable.ic_download)
                Text(release.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                  stringResource(R.string.update_versions, installed, release.version),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateProgress(release, status, ::start, dismiss)
              }
            }
            Text(
              stringResource(R.string.update_whats_new),
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.semantics { heading() },
            )
            GlassPanel(Modifier.fillMaxWidth()) {
              ReleaseNotes(Releases.notes(release.notes, language), Modifier.padding(24.dp))
            }
            TextButton(
              onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.page))) }
              },
              modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
              Text(stringResource(R.string.update_on_github))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun UpdateProgress(release: Release, status: UpdateStatus, start: () -> Unit, dismiss: () -> Unit) {
  val context = LocalContext.current
  when (status) {
    UpdateStatus.Idle -> {
      Text(
        stringResource(R.string.update_help, Formatter.formatShortFileSize(context, release.size)),
        style = MaterialTheme.typography.bodyMedium,
      )
      if (!AppUpdates.canInstall(context))
        Text(
          stringResource(R.string.update_permission),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      GlassButton(
        prominent = true,
        onClick = start,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("update-start"),
      ) {
        CastIcon(R.drawable.ic_download)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.update_now))
      }
      GlassButton(onClick = dismiss, modifier = Modifier.fillMaxWidth().testTag("update-later")) {
        Text(stringResource(R.string.update_later))
      }
    }
    is UpdateStatus.Downloading -> {
      val total = status.total.coerceAtLeast(1)
      val fraction = (status.downloaded.toFloat() / total).coerceIn(0f, 1f)
      LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
      Text(
        stringResource(
          R.string.update_downloading,
          (fraction * 100).toInt(),
          Formatter.formatShortFileSize(context, status.downloaded),
          Formatter.formatShortFileSize(context, status.total),
        ),
        style = MaterialTheme.typography.bodyMedium,
      )
      GlassButton(
        onClick = { AppUpdates.cancel(context.applicationContext) },
        modifier = Modifier.fillMaxWidth().testTag("update-cancel"),
      ) {
        Text(stringResource(R.string.update_cancel))
      }
    }
    UpdateStatus.Installing -> {
      LinearProgressIndicator(Modifier.fillMaxWidth())
      Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.bodyMedium)
      // Opens Android's confirmation again, for one that was dismissed.
      GlassButton(onClick = start, modifier = Modifier.fillMaxWidth().testTag("update-install")) {
        Text(stringResource(R.string.update_install))
      }
    }
    UpdateStatus.Waiting -> {
      Text(stringResource(R.string.update_waiting), style = MaterialTheme.typography.bodyMedium)
      GlassButton(
        onClick = { AppUpdates.install(context.applicationContext, force = true) },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.update_install_now))
      }
    }
    is UpdateStatus.Failed -> {
      Text(
        stringResource(R.string.update_failed),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.error,
      )
      SelectionContainer { Text(status.message, style = MaterialTheme.typography.bodySmall) }
      GlassButton(
        prominent = true,
        onClick = start,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("update-retry"),
      ) {
        Text(stringResource(R.string.update_retry))
      }
    }
  }
}

// The parts of release notes the page lays out: GitHub Markdown as the releases use it.
internal sealed interface NoteBlock {
  data class Heading(val level: Int, val text: String) : NoteBlock

  data class Paragraph(val text: String) : NoteBlock

  data class Bullet(val text: String) : NoteBlock

  data class Code(val text: String) : NoteBlock

  data object Rule : NoteBlock
}

internal fun noteBlocks(markdown: String): List<NoteBlock> {
  val blocks = mutableListOf<NoteBlock>()
  val paragraph = mutableListOf<String>()
  var code: MutableList<String>? = null
  fun closeParagraph() {
    if (paragraph.isNotEmpty()) blocks += NoteBlock.Paragraph(paragraph.joinToString(" "))
    paragraph.clear()
  }
  for (raw in markdown.replace("\r\n", "\n").lines()) {
    val line = raw.trim()
    val open = code
    if (open != null) {
      if (line.startsWith("```")) {
        blocks += NoteBlock.Code(open.joinToString("\n"))
        code = null
      } else open += raw
      continue
    }
    when {
      line.startsWith("```") -> {
        closeParagraph()
        code = mutableListOf()
      }
      line.isEmpty() -> closeParagraph()
      line.matches(Regex("^-{3,}$")) -> {
        closeParagraph()
        blocks += NoteBlock.Rule
      }
      line.startsWith("#") -> {
        closeParagraph()
        val level = line.takeWhile { it == '#' }.length
        blocks += NoteBlock.Heading(level, line.drop(level).trim())
      }
      line.startsWith("- ") || line.startsWith("* ") -> {
        closeParagraph()
        blocks += NoteBlock.Bullet(line.drop(2).trim())
      }
      else -> paragraph += line
    }
  }
  code?.let { blocks += NoteBlock.Code(it.joinToString("\n")) }
  closeParagraph()
  return blocks
}

private val inlineMarks = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\((https?://[^)\s]+)\)|\*([^*\s][^*]*)\*""")

// Bold, code, links and emphasis inside one block of text.
private fun inline(text: String, link: Color): AnnotatedString = buildAnnotatedString {
  var at = 0
  for (match in inlineMarks.findAll(text)) {
    append(text.substring(at, match.range.first))
    val (bold, code, label, url, emphasis) = match.destructured
    when {
      bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
      code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
      label.isNotEmpty() ->
        withLink(
          LinkAnnotation.Url(
            url,
            TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)),
          )
        ) {
          append(label)
        }
      else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(emphasis) }
    }
    at = match.range.last + 1
  }
  append(text.substring(at))
}

@Composable
internal fun ReleaseNotes(markdown: String, modifier: Modifier = Modifier) {
  val blocks = remember(markdown) { noteBlocks(markdown) }
  val link = MaterialTheme.colorScheme.primary
  Column(modifier.testTag("release-notes"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    blocks.forEach { block ->
      when (block) {
        is NoteBlock.Heading ->
          Text(
            inline(block.text, link),
            style =
              if (block.level <= 2) MaterialTheme.typography.titleMedium
              else MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 6.dp).semantics { heading() },
          )
        is NoteBlock.Paragraph -> Text(inline(block.text, link), style = MaterialTheme.typography.bodyMedium)
        is NoteBlock.Bullet ->
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("•", style = MaterialTheme.typography.bodyMedium)
            Text(inline(block.text, link), style = MaterialTheme.typography.bodyMedium)
          }
        is NoteBlock.Code ->
          SelectionContainer {
            Text(
              block.text,
              style = MaterialTheme.typography.bodySmall,
              fontFamily = FontFamily.Monospace,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        NoteBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      }
    }
  }
}
