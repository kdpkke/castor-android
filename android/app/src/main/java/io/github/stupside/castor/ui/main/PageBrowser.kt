package io.github.stupside.castor.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.stupside.castor.BrowserPage
import io.github.stupside.castor.BrowserResolver
import io.github.stupside.castor.R

@Composable
internal fun PageBrowser(
  browser: BrowserResolver,
  page: BrowserPage,
  extracting: Boolean,
  compact: Boolean,
  casting: Boolean,
) {
  val bringIntoView = remember { BringIntoViewRequester() }
  LaunchedEffect(browser, extracting, casting) {
    withFrameNanos {}
    bringIntoView.bringIntoView()
  }
  Column(
    Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        stringResource(R.string.page_browser),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.weight(1f),
      )
      if (!extracting)
        IconButton(onClick = browser::dismissPage) {
          CastIcon(R.drawable.ic_close, stringResource(R.string.close))
        }
    }
    Text(
      page.url,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val help =
      when {
        page.loading -> stringResource(R.string.page_loading)
        extracting -> stringResource(R.string.page_play_help)
        else -> stringResource(R.string.page_retry_help, stringResource(R.string.cast_video))
      }
    Text(
      help,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (page.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    page.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    AndroidView(
      factory = { browser.webView.also { (it.parent as? android.view.ViewGroup)?.removeView(it) } },
      modifier =
        Modifier.fillMaxWidth().height(if (compact) 220.dp else 360.dp).testTag("page-browser"),
      onRelease = { (it.parent as? android.view.ViewGroup)?.removeView(it) },
    )
  }
}
