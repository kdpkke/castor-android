package io.github.stupside.castor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import castor.scraping.v1.Scraping.ResolveRequest
import castor.scraping.v1.Scraping.ResolveResponse
import castor.v1.StreamOuterClass.Stream
import castor.v1.StreamOuterClass.StreamCandidate
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

// A private WebView profile captures requests; no JavaScript interface is exposed to websites.
@SuppressLint("SetJavaScriptEnabled")
class BrowserResolver(context: Context) {
  val active = MutableStateFlow(false)
  val webView = WebView(context)
  private val mutex = Mutex()
  private val userAgent = webView.settings.userAgentString
  private val candidates = ConcurrentHashMap<String, StreamCandidate>()
  @Volatile private var sourcePage = ""
  @Volatile private var capturing = false

  init {
    with(webView.settings) {
      javaScriptEnabled = true
      domStorageEnabled = true
      mediaPlaybackRequiresUserGesture = false
      allowFileAccess = false
      allowContentAccess = false
      mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
      setSupportMultipleWindows(false)
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    webView.webChromeClient =
      object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
          request.deny()
        }
      }
    webView.webViewClient =
      object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
          !isHttp(request.url.toString())

        override fun shouldInterceptRequest(
          view: WebView,
          request: WebResourceRequest,
        ): android.webkit.WebResourceResponse? {
          capture(request.url.toString(), request.requestHeaders)
          return null
        }
      }
  }

  private fun capture(
    url: String,
    headers: Map<String, String> = emptyMap(),
    htmlVideo: Boolean = false,
  ) {
    if (!capturing || !isHttp(url) || url.length > 8192 || candidates.size >= 1600) return
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
      try {
        for (page in request.urlsList) {
          candidates.clear()
          sourcePage = page
          withContext(Dispatchers.Main) {
            capturing = true
            active.value = interactive
            webView.loadUrl(page)
          }
          var firstSeen = 0L
          val started = android.os.SystemClock.elapsedRealtime()
          while (android.os.SystemClock.elapsedRealtime() - started < 30_000) {
            delay(500)
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
        check(streams.isNotEmpty()) {
          "No playable stream found. Start the video in the browser below, or supply its direct URL. DRM is unsupported."
        }
        ResolveResponse.newBuilder().addAllStreams(streams).build()
      } finally {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
          active.value = false
          capturing = false
          webView.stopLoading()
          webView.loadUrl("about:blank")
          candidates.clear()
        }
      }
    }

  fun close() {
    active.value = false
    capturing = false
    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
    webView.stopLoading()
    webView.destroy()
  }

  companion object {
    internal fun isHttp(value: String): Boolean = runCatching {
      val uri = URI(value)
      uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }
      .getOrDefault(false)
  }
}
