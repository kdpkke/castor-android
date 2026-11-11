package io.github.stupside.castor

import android.content.Context
import castor.scraping.v1.Scraping.ResolveRequest
import castor.scraping.v1.Scraping.ResolveResponse
import castor.v1.StreamOuterClass.StreamCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoView

data class BrowserPage(
  val url: String,
  val loading: Boolean = true,
  val error: String? = null,
  val protectionReady: Boolean = false,
)

internal data class PlayerSource(
  val source: String,
  val url: String,
  val owner: String,
)

// The viewer can navigate independently of the pages retained by active sources.
class BrowserResolver(private val context: Context) {
  val active = MutableStateFlow(false)
  val page = MutableStateFlow<BrowserPage?>(null)
  private val mutex = Mutex()
  private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var current: BrowserPageSession? = null
  private var closed = false
  private val ownership = Any()
  private val retained = mutableMapOf<String, BrowserPageSession>()
  private val sources = mutableMapOf<Pair<String, String>, PlayerSource>()
  private val pendingClose = mutableListOf<BrowserPageSession>()

  private fun viewer(): BrowserPageSession {
    check(!closed) { "The browser is closed" }
    return current ?: BrowserPageSession(context, active, page).also { current = it }
  }

  val view
    get() = viewer().view

  internal val requestErrors: Map<String, String>
    get() = current?.requestErrors.orEmpty()

  internal fun attachView(context: Context) = viewer().attachView(context)

  internal fun detachView(view: GeckoView? = null) {
    if (view == null || current?.view === view) current?.detachView()
    else synchronized(ownership) { retained.values.firstOrNull { it.view === view } }?.detachView()
  }

  internal suspend fun prepare() = withContext(Dispatchers.Main) { viewer().prepare() }

  internal fun evaluateJavascript(code: String, callback: (String) -> Unit) {
    current?.evaluateJavascript(code, callback) ?: callback("null")
  }

  internal fun playerSource(candidate: StreamCandidate): PlayerSource? =
    synchronized(ownership) { sources[candidate.sourcePage to candidate.stream.url] }

  internal suspend fun readPlayer(source: PlayerSource): BrowserPlaylists.Document =
    withContext(Dispatchers.Main) {
      val session =
        synchronized(ownership) { retained[source.owner] }
          ?: error("The captured player was released")
      session.readPlayer(source)
    }

  suspend fun resolve(
    request: ResolveRequest,
    interactive: Boolean = true,
    decorate: (ResolveResponse) -> ResolveResponse = { it },
  ): ResolveResponse = mutex.withLock {
    withContext(Dispatchers.Main) {
      // A restarted decoder keeps the live page that renews its reader capability.
      val captured = synchronized(ownership) {
        retained.values.firstNotNullOfOrNull { it.retainedResponse(request) }
      }
      if (captured != null) return@withContext decorate(captured)
      val session = viewer()
      try {
        val response = session.resolve(request, interactive, retainPlayer = true)
        val prepared =
          response.streamsList.mapNotNull { candidate ->
            session.playerSource(candidate)?.let {
              (candidate.sourcePage to candidate.stream.url) to it
            }
          }
        if (prepared.isNotEmpty()) {
          synchronized(ownership) {
            check(retained.size < 4) { "Too many retained browser pages" }
            sources.putAll(prepared)
            retained[session.id] = session
          }
          session.park()
          current = null
        } else {
          session.dismissPage()
        }
        decorate(response)
      } catch (failure: Exception) {
        if (failure is CancellationException || !interactive) session.dismissPage()
        throw failure
      }
    }
  }

  fun dismissPage() {
    if (!mutex.isLocked) current?.dismissPage()
  }

  suspend fun showPage(address: String) {
    if (!mutex.tryLock()) return
    try {
      withContext(Dispatchers.Main) { viewer().showPage(address) }
    } finally {
      mutex.unlock()
    }
  }

  internal fun releasePlayers() {
    synchronized(ownership) {
      pendingClose.addAll(retained.values)
      retained.clear()
      sources.clear()
    }
    cleanup.launch { closeReleased() }
  }

  private fun closeReleased() {
    val released = synchronized(ownership) { pendingClose.toList().also { pendingClose.clear() } }
    released.forEach { it.close() }
  }

  fun close() {
    if (closed) return
    closed = true
    current?.close()
    current = null
    synchronized(ownership) {
      pendingClose.addAll(retained.values)
      retained.clear()
      sources.clear()
    }
    closeReleased()
    cleanup.cancel()
    active.value = false
    page.value = null
  }

  companion object {
    internal const val HLS = BrowserPageSession.HLS

    internal fun isHttp(value: String) = BrowserPageSession.isHttp(value)

    internal fun samePath(left: String, right: String) = BrowserPageSession.samePath(left, right)

    internal fun replayable(headers: Map<String, String>) = BrowserPageSession.replayable(headers)
  }
}
