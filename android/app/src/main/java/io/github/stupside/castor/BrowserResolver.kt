package io.github.stupside.castor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import castor.scraping.v1.Scraping.ResolveRequest
import castor.scraping.v1.Scraping.ResolveResponse
import castor.v1.StreamOuterClass.Stream
import castor.v1.StreamOuterClass.StreamCandidate
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class BrowserPage(val url: String, val loading: Boolean = true, val error: String? = null)

// A private WebView profile captures requests; no JavaScript interface is exposed to websites.
@SuppressLint("SetJavaScriptEnabled")
class BrowserResolver internal constructor(context: Context, private val adBlock: BrowserAdBlock) {
  constructor(context: Context) : this(context, BrowserAdBlock.bundled(context))

  val active = MutableStateFlow(false)
  val page = MutableStateFlow<BrowserPage?>(null)
  val webView = WebView(context)
  private val mutex = Mutex()
  private val userAgent = webView.settings.userAgentString
  private val candidates = ConcurrentHashMap<String, StreamCandidate>()
  @Volatile private var sourcePage = ""
  @Volatile private var capturing = false
  @Volatile private var loadFailure: String? = null

  init {
    with(webView.settings) {
      javaScriptEnabled = true
      domStorageEnabled = true
      mediaPlaybackRequiresUserGesture = false
      allowFileAccess = false
      allowContentAccess = false
      mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
      // With false, target=_blank/window.open can replace the current page in this WebView.
      // Handle new-window requests explicitly and refuse them instead.
      setSupportMultipleWindows(true)
      javaScriptCanOpenWindowsAutomatically = false
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    webView.webChromeClient =
      object : WebChromeClient() {
        override fun onCreateWindow(
          view: WebView,
          isDialog: Boolean,
          isUserGesture: Boolean,
          resultMsg: android.os.Message,
        ): Boolean = false

        override fun onPermissionRequest(request: PermissionRequest) {
          request.deny()
        }
      }
    // Service-worker network requests bypass WebViewClient. The app has a single browser profile;
    // install the same immutable rules there, without retaining the WebView or its context.
    val serviceWorkerRules = adBlock
    ServiceWorkerController.getInstance()
      .setServiceWorkerClient(
        object : ServiceWorkerClient() {
          override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
            serviceWorkerRules.intercept(request.url.toString())
        }
      )
    webView.webViewClient =
      object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
          if (url != "about:blank") {
            // Some WebView versions deliver an HTTP failure before this callback.
            // Only an actual navigation to a different page clears that failure.
            if (page.value?.url != url) loadFailure = null
            page.value =
              page.value?.copy(url = url, loading = loadFailure == null, error = loadFailure)
          }
        }

        override fun onPageFinished(view: WebView, url: String) {
          if (url != "about:blank" && page.value?.url == url)
            page.value = page.value?.copy(loading = false)
        }

        override fun onReceivedError(
          view: WebView,
          request: WebResourceRequest,
          error: WebResourceError,
        ) {
          if (request.isForMainFrame) failedLoading("The page could not load: ${error.description}")
        }

        override fun onReceivedHttpError(
          view: WebView,
          request: WebResourceRequest,
          response: WebResourceResponse,
        ) {
          if (request.isForMainFrame)
            failedLoading("The page returned HTTP ${response.statusCode}.")
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
          !isHttp(request.url.toString()) || adBlock.blocks(request.url.toString())

        override fun shouldInterceptRequest(
          view: WebView,
          request: WebResourceRequest,
        ): android.webkit.WebResourceResponse? {
          adBlock.intercept(request.url.toString())?.let {
            return it
          }
          capture(request.url.toString(), request.requestHeaders)
          return null
        }
      }
  }

  private fun failedLoading(message: String) {
    loadFailure = message
    page.value = page.value?.copy(loading = false, error = message)
  }

  private fun capture(
    url: String,
    headers: Map<String, String> = emptyMap(),
    htmlVideo: Boolean = false,
  ) {
    if (!capturing || !isHttp(url) || url.length > 8192 || candidates.size >= 1600) return
    // DOM currentSrc/src can expose blocked media even when its request never reached the network.
    if (adBlock.blocks(url)) return
    val type =
      when (URI(url).path.orEmpty().substringAfterLast('.').lowercase()) {
        // Use Castor's canonical identity so inspection selects its HLS decoder.
        "m3u8" -> "application/x-mpegURL"
        "mpd" -> "application/dash+xml"
        "mp4",
        "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> if (htmlVideo) "" else return
      }
    val replay =
      headers
        .filterKeys { it.lowercase() !in setOf("host", "connection", "content-length", "range") }
        .toMutableMap()
    CookieManager.getInstance().getCookie(url)?.let { replay["Cookie"] = it }
    replay.putIfAbsent("User-Agent", userAgent)
    replay.putIfAbsent("Referer", sourcePage)
    val stream = Stream.newBuilder().setUrl(url).setContentType(type).putAllHeaders(replay).build()
    candidates[url] =
      StreamCandidate.newBuilder().setStream(stream).setSourcePage(sourcePage).build()
  }

  suspend fun resolve(request: ResolveRequest, interactive: Boolean = true): ResolveResponse =
    mutex.withLock {
      require(
        request.urlsCount in 1..16 && request.urlsList.all { isHttp(it) && it.length <= 8192 }
      ) {
        "Invalid page URL"
      }
      val streams = mutableListOf<StreamCandidate>()
      var keepPage = false
      try {
        for (address in request.urlsList) {
          withContext(Dispatchers.Main) {
            // Retry a failed interactive page without losing the user's Play click.
            val reuse =
              interactive && page.value != null && sourcePage == address && loadFailure == null
            if (!reuse) candidates.clear()
            sourcePage = address
            loadFailure = null
            capturing = true
            active.value = interactive
            if (!reuse) {
              page.value = if (interactive) BrowserPage(address) else null
              if (adBlock.blocks(address)) {
                capturing = false
                webView.stopLoading()
                webView.loadUrl("about:blank")
                failedLoading("The page address was blocked by the browser's ad filter.")
              } else webView.loadUrl(address)
            }
          }
          var firstSeen = 0L
          val started = android.os.SystemClock.elapsedRealtime()
          while (android.os.SystemClock.elapsedRealtime() - started < 30_000) {
            delay(500)
            if (loadFailure != null) break
            withContext(Dispatchers.Main) {
              webView.evaluateJavascript(
                """
                JSON.stringify(Array.from(document.querySelectorAll('video,video source')).map(v => {
                  if (v.tagName === 'VIDEO') v.play().catch(() => {});
                  return v.currentSrc || v.src;
                }).filter(Boolean))
                """
                  .trimIndent()
              ) { encoded ->
                runCatching {
                  val urls = JSONArray(JSONArray("[$encoded]").getString(0))
                  for (i in 0 until urls.length()) capture(urls.getString(i), htmlVideo = true)
                }
              }
            }
            if (candidates.isNotEmpty()) {
              val now = android.os.SystemClock.elapsedRealtime()
              if (firstSeen == 0L) firstSeen = now
              if (now - firstSeen >= 3000) break
            }
          }
          streams += candidates.values
          if (streams.isNotEmpty()) break
        }
        if (streams.isEmpty()) {
          keepPage = interactive
          throw java.io.IOException(
            loadFailure
              ?: "No playable stream was found on this page. The channel may be offline. Start the video in the page player and try casting again, or use a direct video URL. DRM-protected streams cannot be cast."
          )
        }
        ResolveResponse.newBuilder().addAllStreams(streams).build()
      } finally {
        val retain = keepPage && currentCoroutineContext().isActive
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
          active.value = false
          if (!retain) clearPage()
        }
      }
    }

  fun dismissPage() {
    // Background playlist renewal also owns the WebView, without presenting interactive UI.
    if (!mutex.isLocked) clearPage()
  }

  private fun clearPage() {
    page.value = null
    capturing = false
    loadFailure = null
    webView.stopLoading()
    webView.loadUrl("about:blank")
    candidates.clear()
  }

  fun close() {
    active.value = false
    page.value = null
    capturing = false
    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
    webView.stopLoading()
    webView.destroy()
  }

  companion object {
    internal fun isHttp(value: String): Boolean =
      runCatching {
          val uri = URI(value)
          uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }
        .getOrDefault(false)
  }
}
