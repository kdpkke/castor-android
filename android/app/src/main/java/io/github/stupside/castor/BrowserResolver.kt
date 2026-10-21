package io.github.stupside.castor

import android.content.Context
import castor.scraping.v1.Scraping.ResolveRequest
import castor.scraping.v1.Scraping.ResolveResponse
import castor.v1.StreamOuterClass.Stream
import castor.v1.StreamOuterClass.StreamCandidate
import io.github.stupside.castor.ui.main.CastOptions
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.mozilla.geckoview.*

data class BrowserPage(
  val url: String,
  val loading: Boolean = true,
  val error: String? = null,
  val protectionReady: Boolean = false,
)

// The service owns a single page. Only the app's isolated extension can report captured responses.
class BrowserResolver(private val context: Context) {
  val active = MutableStateFlow(false)
  val page = MutableStateFlow<BrowserPage?>(null)
  // TextureView participates in Compose scrolling/clipping; SurfaceView uses a separate surface.
  val view = GeckoView(context).apply { setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW) }
  private val engine by lazy { BrowserEngine.get(context) }
  private val session =
    GeckoSession(GeckoSessionSettings.Builder().suspendMediaWhenInactive(false).build())
  private val mutex = Mutex()
  private val candidates = ConcurrentHashMap<String, StreamCandidate>()
  internal val requestErrors = mutableMapOf<String, String>()
  private var opened = false
  private var closed = false
  private var sourcePage = ""
  // When this resolver last started a navigation; earlier requests belong to another document.
  private var navigationStarted = 0L
  // Responses become candidates, including after a retained page's late Play.
  private var capturing = false
  // The extension starts and reports page videos only while a resolution is waiting for them.
  private var extracting = false
    set(value) {
      if (field == value) return
      field = value
      announceExtracting()
    }

  private var listening = false

  @Volatile private var loadFailure: String? = null
  private var loadingUrl: String? = null
  private var mainPort: WebExtension.Port? = null
  // The extension's id for this session's tab, learned from its content script; -1 until then.
  private var tabId = -1
  private var evaluationId = 0
  private val evaluations = mutableMapOf<Int, (String) -> Unit>()
  private val pageError: (Int, String, Int) -> Unit = { tab, url, status ->
    if (
      !closed && capturing && (tabId < 0 || tab == tabId) && url == (page.value?.url ?: sourcePage)
    )
      failedLoading(text(R.string.page_http_error, status))
  }

  init {
    session.navigationDelegate =
      object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(
          session: GeckoSession,
          request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny> =
          GeckoResult.fromValue(
            if (
              (isHttp(request.uri) || request.uri == "about:blank" && request.isDirectNavigation) &&
                request.target != GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW
            )
              AllowOrDeny.ALLOW
            else AllowOrDeny.DENY
          )

        override fun onSubframeLoadRequest(
          session: GeckoSession,
          request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny> =
          GeckoResult.fromValue(
            if (isHttp(request.uri) || request.uri == "about:blank") AllowOrDeny.ALLOW
            else AllowOrDeny.DENY
          )

        override fun onLocationChange(
          session: GeckoSession,
          url: String?,
          permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
          hasUserGesture: Boolean,
        ) {
          if (url != null && isHttp(url)) page.value = page.value?.copy(url = url)
        }

        override fun onLoadError(
          session: GeckoSession,
          uri: String?,
          error: WebRequestError,
        ): GeckoResult<String>? {
          if (uri != "about:blank") failedLoading(text(R.string.page_load_error, error.toString()))
          return null
        }
      }
    session.progressDelegate =
      object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
          loadingUrl = url
          if (isHttp(url)) {
            // The new document's content script can connect before this callback arrives; only a
            // port from another document is stale.
            if (mainPort?.sender?.url != url) {
              mainPort = null
              finishEvaluations()
            }
            if (page.value?.url != url) loadFailure = null
            page.value =
              page.value?.copy(url = url, loading = loadFailure == null, error = loadFailure)
          }
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
          if (loadingUrl != "about:blank" && loadingUrl == page.value?.url)
            page.value = page.value?.copy(loading = false)
        }
      }
    session.contentDelegate =
      object : GeckoSession.ContentDelegate {
        override fun onCrash(session: GeckoSession) = contentStopped()

        override fun onKill(session: GeckoSession) = contentStopped()
      }
    session.permissionDelegate =
      object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(
          session: GeckoSession,
          perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int> =
          GeckoResult.fromValue(
            if (
              perm.permission in
                setOf(
                  GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                  GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                )
            )
              GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
            else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
          )
      }
  }

  // GeckoView closed the session with its content process. Reopen it on the next resolution; the
  // visible page explains the failure and a retry loads it again.
  private fun contentStopped() {
    opened = false
    mainPort = null
    tabId = -1
    finishEvaluations()
    view.releaseSession()
    failedLoading(text(R.string.page_player_stopped))
  }

  internal suspend fun prepare() =
    withContext(Dispatchers.Main) {
      check(!closed) { "The browser is closed" }
      val extension =
        try {
          withTimeoutOrNull(90_000) { engine.ready().await() }
        } catch (failure: Exception) {
          if (failure is CancellationException) throw failure
          throw java.io.IOException(
            text(R.string.browser_protection_failed, failure.message.orEmpty()),
            failure,
          )
        } ?: throw java.io.IOException(text(R.string.browser_protection_failed, ""))
      val enabled = engine.runtime.webExtensionController.list().await().associateBy { it.id }
      if (!BrowserEngine.blockers.keys.all { enabled[it]?.metaData?.disabledFlags == 0 })
        throw IllegalStateException(text(R.string.browser_protection_unavailable))
      if (!listening) {
        engine.pageErrors += pageError
        listening = true
      }
      if (!opened) {
        session.open(engine.runtime)
        view.setSession(session)
        opened = true
        session.webExtensionController.setMessageDelegate(
          extension,
          object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
              if (
                port.sender.session !== session ||
                  !port.sender.isTopLevel ||
                  !isHttp(port.sender.url)
              ) {
                port.disconnect()
                return
              }
              mainPort = port
              port.setDelegate(
                object : WebExtension.PortDelegate {
                  override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    if (closed || port !== mainPort || message !is JSONObject) return
                    when (message.optString("type")) {
                      "candidate" -> capture(message)
                      "tab" -> tabId = message.optInt("id", -1)
                      "page" ->
                        if (
                          message.optInt("status") >= 400 &&
                            message.optString("url") == page.value?.url
                        )
                          failedLoading(text(R.string.page_http_error, message.optInt("status")))
                      "requestError" ->
                        if (requestErrors.size < 100)
                          requestErrors[message.optString("url")] = message.optString("error")
                      "evaluation" ->
                        evaluations
                          .remove(message.optInt("id"))
                          ?.invoke(message.optString("value", "null"))
                    }
                  }

                  override fun onDisconnect(port: WebExtension.Port) {
                    if (mainPort === port) {
                      mainPort = null
                      finishEvaluations()
                    }
                  }
                }
              )
              announceExtracting()
            }
          },
          "castor",
        )
      }
      session.setActive(true)
    }

  // App-to-extension diagnostics only. No method is exposed to page JavaScript.
  internal fun evaluateJavascript(code: String, callback: (String) -> Unit) {
    val port =
      mainPort
        ?: run {
          callback("null")
          return
        }
    val id = ++evaluationId
    evaluations[id] = callback
    port.postMessage(JSONObject().put("type", "evaluate").put("id", id).put("code", code))
  }

  private fun announceExtracting() {
    runCatching {
      mainPort?.postMessage(JSONObject().put("type", "extracting").put("value", extracting))
    }
  }

  private fun failedLoading(message: String) {
    loadFailure = message
    page.value = page.value?.copy(loading = false, error = message)
  }

  private fun text(id: Int, vararg args: Any): String =
    CastOptions(context).localizedContext().getString(id, *args)

  private fun capture(message: JSONObject) {
    val url = message.optString("url")
    if (!capturing || !isHttp(url) || url.length > 8192 || candidates.size >= 1600) return
    // Both clocks are the device's wall clock; allow for their rounding only.
    val started = message.optLong("started", 0L)
    if (started > 0 && started < navigationStarted - 50) return
    val mime = message.optString("mime").substringBefore(';').trim().lowercase()
    val type =
      when {
        mime in hlsTypes -> HLS
        mime == "application/dash+xml" || mime.startsWith("video/") -> mime
        else ->
          when (URI(url).path.orEmpty().substringAfterLast('.').lowercase()) {
            "m3u8" -> HLS
            "mpd" -> "application/dash+xml"
            "mp4",
            "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> if (message.optBoolean("htmlVideo")) "" else return
          }
      }
    val raw = message.optJSONObject("headers") ?: JSONObject()
    if (raw.length() > 100 || raw.toString().length > 65536) return
    val headers = replayable(raw.keys().asSequence().associateWith { raw.optString(it) })
    headers.putIfAbsent("Referer", sourcePage)
    val stream = Stream.newBuilder().setUrl(url).setContentType(type).putAllHeaders(headers).build()
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
        withContext(Dispatchers.Main) {
          active.value = interactive
          if (interactive && page.value == null) page.value = BrowserPage(request.getUrls(0))
        }
        prepare()
        for (address in request.urlsList) {
          withContext(Dispatchers.Main) {
            val reuse =
              interactive && page.value != null && sourcePage == address && loadFailure == null
            if (!reuse) {
              candidates.clear()
              requestErrors.clear()
              // Any connected port belongs to the document this navigation replaces.
              mainPort = null
              finishEvaluations()
            }
            sourcePage = address
            loadFailure = null
            capturing = true
            extracting = true
            if (!reuse) {
              page.value = if (interactive) BrowserPage(address, protectionReady = true) else null
              navigationStarted = System.currentTimeMillis()
              session.loadUri(address)
            }
          }
          var firstSeen = 0L
          val started = android.os.SystemClock.elapsedRealtime()
          while (android.os.SystemClock.elapsedRealtime() - started < 30_000) {
            delay(500)
            if (loadFailure != null) break
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
          throw java.io.IOException(loadFailure ?: text(R.string.page_no_stream))
        }
        ResolveResponse.newBuilder().addAllStreams(streams).build()
      } finally {
        val retain = keepPage && currentCoroutineContext().isActive
        withContext(NonCancellable + Dispatchers.Main) {
          active.value = false
          // A retained page keeps capturing the user's Play, but its videos are no longer started.
          extracting = false
          if (!retain) clearPage()
        }
      }
    }

  fun dismissPage() {
    if (!mutex.isLocked) clearPage()
  }

  private fun finishEvaluations() {
    val callbacks = evaluations.values.toList()
    evaluations.clear()
    callbacks.forEach { it("null") }
  }

  private fun clearPage() {
    page.value = null
    capturing = false
    extracting = false
    loadFailure = null
    mainPort = null
    finishEvaluations()
    if (opened) {
      session.stop()
      session.loadUri("about:blank")
    }
    candidates.clear()
  }

  fun close() {
    active.value = false
    clearPage()
    closed = true
    (view.parent as? android.view.ViewGroup)?.removeView(view)
    if (listening) engine.pageErrors -= pageError
    if (opened) {
      view.releaseSession()
      session.close()
    }
  }

  companion object {
    internal const val HLS = "application/x-mpegURL"
    private val hlsTypes =
      setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
      )
    // Connection, framing, range and cache-validation state belong to the browser's own request.
    // Replaying them would make another reader receive compressed bytes or a 304 without a body.
    private val browserOnly =
      setOf(
        "host",
        "connection",
        "proxy-connection",
        "keep-alive",
        "transfer-encoding",
        "te",
        "trailer",
        "upgrade",
        "content-length",
        "range",
        "accept-encoding",
        "if-range",
        "if-none-match",
        "if-modified-since",
        "if-match",
        "if-unmodified-since",
      )

    internal fun replayable(headers: Map<String, String>): MutableMap<String, String> {
      val connection =
        headers.entries
          .filter { it.key.equals("connection", ignoreCase = true) }
          .flatMap { it.value.split(',') }
          .map { it.trim().lowercase() }
          .toSet()
      val kept = mutableMapOf<String, String>()
      for ((name, value) in headers) {
        val key = name.lowercase()
        if (key in browserOnly || key in connection) continue
        val canonical =
          when (key) {
            "cookie" -> "Cookie"
            "user-agent" -> "User-Agent"
            "referer" -> "Referer"
            else -> name
          }
        kept[canonical] = value
      }
      return kept
    }

    internal fun isHttp(value: String): Boolean =
      runCatching {
          val uri = URI(value)
          uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }
        .getOrDefault(false)
  }
}
