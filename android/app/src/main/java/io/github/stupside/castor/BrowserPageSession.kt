package io.github.stupside.castor

import android.content.Context
import android.content.MutableContextWrapper
import castor.scraping.v1.Scraping.ResolveRequest
import castor.scraping.v1.Scraping.ResolveResponse
import castor.v1.StreamOuterClass.Stream
import castor.v1.StreamOuterClass.StreamCandidate
import io.github.stupside.castor.ui.main.CastOptions
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.*

// One page and its isolated extension port, owned by the viewer or a retained source.
internal class BrowserPageSession(
  private val context: Context,
  var active: MutableStateFlow<Boolean>,
  var page: MutableStateFlow<BrowserPage?>,
) {
  val id = UUID.randomUUID().toString()
  private val viewContext = MutableContextWrapper(context)
  // TextureView participates in Compose scrolling/clipping; SurfaceView uses a separate surface.
  val view =
    object : GeckoView(viewContext) {
        override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
          // A gesture that begins in the webpage belongs to Gecko, including its pan/zoom handling.
          // The enclosing Compose scrollable still handles gestures started outside this view.
          val finished =
            event.actionMasked == android.view.MotionEvent.ACTION_UP ||
              event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
          if (!finished) parent?.requestDisallowInterceptTouchEvent(true)
          return super.dispatchTouchEvent(event).also {
            if (finished) parent?.requestDisallowInterceptTouchEvent(false)
          }
        }
      }
      .apply { setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW) }

  internal fun attachView(context: Context): GeckoView {
    detachView()
    // Gecko needs the visible Activity's window for IME insets and input coordinates. The
    // service keeps the session, but must not retain that Activity after the view is released.
    viewContext.baseContext = context
    return view
  }

  internal fun detachView() {
    (view.parent as? android.view.ViewGroup)?.removeView(view)
    viewContext.baseContext = context
  }

  private val engine by lazy { BrowserEngine.get(context) }
  private val session =
    GeckoSession(GeckoSessionSettings.Builder().suspendMediaWhenInactive(false).build())
  private val mutex = Mutex()
  private val candidates = ConcurrentHashMap<String, StreamCandidate>()
  private val playerSources = ConcurrentHashMap<Pair<String, String>, PlayerSource>()
  private val playerReads = Mutex()
  internal val requestErrors = mutableMapOf<String, String>()
  private var opened = false
  private var closed = false
  private var sourcePage = ""
  private var capturedRequest: ResolveRequest? = null
  // When this resolver last started a navigation; earlier requests belong to another document.
  private var navigationStarted = 0L
  // Until when a navigation of the tab is refused because Castor, not the user, just tapped the page: a tap that
  // lands on an advertisement's layer must not take the page elsewhere. Read and written on the main thread.
  private var automaticUntil = 0L
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
  // Gecko runs clearPage's about:blank asynchronously. A load issued before that blank page
  // starts is replaced by it, so the next navigation waits for this to complete.
  private var blankPage: CompletableDeferred<Unit>? = null
  private var mainPort: WebExtension.Port? = null
  // The extension's id for this session's tab, learned from its content script; -1 until then.
  private var tabId = -1
  private var evaluationId = 0
  private val evaluations = ConcurrentHashMap<Int, (String) -> Unit>()
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
                request.target != GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW &&
                (request.isDirectNavigation ||
                  android.os.SystemClock.elapsedRealtime() >= automaticUntil)
            )
              AllowOrDeny.ALLOW
            else AllowOrDeny.DENY
          )

        override fun onSubframeLoadRequest(
          session: GeckoSession,
          request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny> =
          GeckoResult.fromValue(
            // Turnstile and other embedded widgets use inline documents. These inherit the
            // embedding page's origin; top-level navigation still accepts only HTTP(S)/blank.
            if (isHttp(request.uri) || request.uri in setOf("about:blank", "about:srcdoc"))
              AllowOrDeny.ALLOW
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
          if (loadingUrl == "about:blank") blankPage?.complete(Unit)
          else if (loadingUrl == page.value?.url) page.value = page.value?.copy(loading = false)
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

  internal fun playerSource(candidate: StreamCandidate): PlayerSource? =
    playerSources[candidate.sourcePage to candidate.stream.url]

  internal fun retainedResponse(request: ResolveRequest): ResolveResponse? {
    if (closed || !opened || mainPort == null || loadFailure != null || capturedRequest != request)
      return null
    val streams = candidates.values.filter { playerSource(it) != null }
    if (streams.isEmpty()) return null
    return ResolveResponse.newBuilder().addAllStreams(streams).build()
  }

  private suspend fun player(
    operation: String,
    source: String = "",
    url: String = "",
    type: String = "player",
  ): JSONArray =
    withContext(Dispatchers.Main) {
      val port = mainPort ?: return@withContext JSONArray()
      val raw =
        withTimeout(17_000) {
          suspendCancellableCoroutine<String> { pending ->
            val id = ++evaluationId
            evaluations[id] = { value -> if (pending.isActive) pending.resume(value) }
            port.postMessage(
              JSONObject()
                .put("type", type)
                .put("id", id)
                .put("operation", operation)
                .put("source", source)
                .put("url", url)
            )
            pending.invokeOnCancellation { evaluations.remove(id) }
          }
        }
      runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

  private suspend fun discoverPlayers() {
    val documents = player("discover")
    val sources =
      (0 until documents.length())
        .mapNotNull { index ->
          val document = documents.optJSONObject(index) ?: return@mapNotNull null
          val source = document.optString("source")
          val url = document.optString("url")
          if (!isHttp(source) || !isHttp(url) || source.length > 8192 || url.length > 8192) null
          else PlayerSource(source, url, id)
        }
        .distinct()
    for (candidate in candidates.values) {
      for (exact in listOf(true, false)) {
        fun matches(value: String) =
          candidate.stream.url == value || !exact && samePath(candidate.stream.url, value)
        val selected =
          sources
            .mapNotNull {
              when {
                matches(it.source) -> it.copy(url = it.source)
                matches(it.url) -> it
                else -> null
              }
            }
            .distinct()
        if (selected.size == 1 && playerSources.size < 1600) {
          playerSources[candidate.sourcePage to candidate.stream.url] = selected.single()
        }
        if (selected.isNotEmpty()) break
      }
    }
  }

  // Starts a player that waits for a person's tap, as that person would: a tap in the middle of the page's largest
  // player while the page is on screen, otherwise a click on each frame's play control. Attempts alternate, since a
  // page may take a tap and ignore a click or the other way round. False once a video plays: another tap could
  // pause it.
  private suspend fun startPlayer(attempt: Int): Boolean {
    // A page that does not answer in time is left as it is; the search goes on.
    val frames =
      try {
        player("probe", type = "autoplay")
      } catch (_: TimeoutCancellationException) {
        return true
      }
    val reports = (0 until frames.length()).mapNotNull(frames::optJSONObject)
    if (reports.any { it.optBoolean("playing") }) return false
    val target = reports.firstOrNull { it.optBoolean("top") }?.optJSONObject("target")
    withContext(Dispatchers.Main) {
      automaticUntil = android.os.SystemClock.elapsedRealtime() + AUTOMATIC_GUARD
    }
    // The page has just scrolled the player into view; the tap waits for the screen to follow.
    delay(300)
    val tapped =
      attempt % 2 == 1 &&
        target != null &&
        withContext(Dispatchers.Main) {
          tap(target.optDouble("x", -1.0).toFloat(), target.optDouble("y", -1.0).toFloat())
        }
    if (!tapped)
      try {
        player("click", type = "autoplay")
      } catch (_: TimeoutCancellationException) {}
    return true
  }

  // A touch where the page shows, given to Gecko as a person's tap. Only while the page is on screen.
  private fun tap(x: Float, y: Float): Boolean {
    if (!view.isAttachedToWindow || !view.isShown || view.windowVisibility != android.view.View.VISIBLE)
      return false
    if (x < 0 || y < 0 || x >= view.width || y >= view.height) return false
    val down = android.os.SystemClock.uptimeMillis()
    for ((action, time) in
      listOf(android.view.MotionEvent.ACTION_DOWN to down, android.view.MotionEvent.ACTION_UP to down + 60)) {
      val event = android.view.MotionEvent.obtain(down, time, action, x, y, 0)
      event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
      view.dispatchTouchEvent(event)
      event.recycle()
    }
    return true
  }

  internal suspend fun readPlayer(source: PlayerSource): BrowserPlaylists.Document =
    playerReads.withLock {
      // executeScript keeps running after its caller is cancelled. Keep the loader's
      // access state exclusive until its bounded page-side request has finished.
      val documents = withContext(NonCancellable) { player("read", source.source, source.url) }
      currentCoroutineContext().ensureActive()
      check(documents.length() == 1) { "The player could not read the selected playlist" }
      val document = documents.getJSONObject(0)
      val url = document.getString("url")
      val body = document.getString("body")
      check(
        isHttp(url) &&
          url.length <= 8192 &&
          body.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024
      ) {
        "Invalid player playlist"
      }
      BrowserPlaylists.Document(200, body, url)
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

  suspend fun resolve(
    request: ResolveRequest,
    interactive: Boolean = true,
    retainPlayer: Boolean = false,
  ): ResolveResponse = mutex.withLock {
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
            blankPage?.let { withTimeoutOrNull(5_000) { it.await() } }
            navigationStarted = System.currentTimeMillis()
            session.loadUri(address)
          }
        }
        var firstSeen = 0L
        val started = android.os.SystemClock.elapsedRealtime()
        // A page whose player waits for a tap gets one after a while without a stream, and again later.
        var starts = 0
        var nextStart = started + PLAY_AFTER
        while (android.os.SystemClock.elapsedRealtime() - started < 30_000) {
          delay(500)
          if (loadFailure != null) break
          val now = android.os.SystemClock.elapsedRealtime()
          if (candidates.isNotEmpty()) {
            if (firstSeen == 0L) firstSeen = now
            if (now - firstSeen >= 3000) break
          } else if (starts < PLAY_ATTEMPTS && now >= nextStart) {
            starts++
            nextStart = now + PLAY_EVERY
            if (!startPlayer(starts)) starts = PLAY_ATTEMPTS
          }
        }
        streams += candidates.values
        if (streams.isNotEmpty()) break
      }
      if (streams.isEmpty()) {
        keepPage = interactive
        throw java.io.IOException(loadFailure ?: text(R.string.page_no_stream))
      }
      discoverPlayers()
      if (retainPlayer) player("suspend")
      capturedRequest = request
      ResolveResponse.newBuilder().addAllStreams(streams).build()
    } finally {
      val retain = keepPage && currentCoroutineContext().isActive
      withContext(NonCancellable + Dispatchers.Main) {
        active.value = false
        // A retained page keeps capturing the user's Play, but its videos are no longer started.
        extracting = false
        if (!retain && !retainPlayer) clearPage()
      }
    }
  }

  fun dismissPage() {
    if (!mutex.isLocked) clearPage()
  }

  internal fun park() {
    active.value = false
    page.value = null
    active = MutableStateFlow(false)
    page = MutableStateFlow(null)
    capturing = false
    detachView()
  }

  // Opens address for the viewer to play by hand, as a page kept after a failed search is.
  suspend fun showPage(address: String) {
    if (!isHttp(address) || address.length > 8192 || !mutex.tryLock()) return
    try {
      withContext(Dispatchers.Main) {
        if (page.value != null) return@withContext
        page.value = BrowserPage(address)
        try {
          prepare()
        } catch (failure: Exception) {
          if (failure is CancellationException) throw failure
          page.value = BrowserPage(address, loading = false, error = failure.message)
          return@withContext
        }
        candidates.clear()
        requestErrors.clear()
        mainPort = null
        finishEvaluations()
        sourcePage = address
        loadFailure = null
        capturing = true
        page.value = BrowserPage(address, protectionReady = true)
        blankPage?.let { withTimeoutOrNull(5_000) { it.await() } }
        navigationStarted = System.currentTimeMillis()
        session.loadUri(address)
      }
    } finally {
      mutex.unlock()
    }
  }

  private fun finishEvaluations() {
    val callbacks = evaluations.values.toList()
    evaluations.clear()
    callbacks.forEach { it("null") }
  }

  private fun clearPage() {
    page.value = null
    capturedRequest = null
    sourcePage = ""
    capturing = false
    extracting = false
    loadFailure = null
    mainPort = null
    finishEvaluations()
    if (opened) {
      session.stop()
      blankPage = CompletableDeferred()
      session.loadUri("about:blank")
    }
    candidates.clear()
  }

  fun close() {
    if (closed) return
    active.value = false
    clearPage()
    closed = true
    playerSources.clear()
    detachView()
    if (listening) engine.pageErrors -= pageError
    if (opened) {
      view.releaseSession()
      session.close()
    }
  }

  companion object {
    internal fun samePath(left: String, right: String): Boolean = runCatching {
      val a = URI(left)
      val b = URI(right)
      a.scheme == b.scheme && a.rawAuthority == b.rawAuthority && a.rawPath == b.rawPath
    }
      .getOrDefault(false)

    internal const val HLS = "application/x-mpegURL"
    // A page's own player has this long to start before Castor starts it, then gets a few more attempts.
    private const val PLAY_AFTER = 4_000L
    private const val PLAY_EVERY = 6_000L
    private const val PLAY_ATTEMPTS = 3
    // How long after an automatic tap the page may not take the tab elsewhere.
    private const val AUTOMATIC_GUARD = 2_000L
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

    internal fun isHttp(value: String): Boolean = runCatching {
      val uri = URI(value)
      uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }
      .getOrDefault(false)
  }
}
