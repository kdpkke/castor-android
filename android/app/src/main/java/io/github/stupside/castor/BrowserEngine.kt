package io.github.stupside.castor

import android.annotation.SuppressLint
import android.content.Context
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import org.json.JSONObject
import org.mozilla.geckoview.*

// One runtime per app process. Closing a page or service never destroys extension storage.
internal class BrowserEngine private constructor(private val context: Context) {
  val runtime =
    GeckoRuntime.create(
      context,
      GeckoRuntimeSettings.Builder().remoteDebuggingEnabled(false).configFilePath("").build(),
    )
  // Main-frame HTTP failures reported by the capture extension: tab id, URL and status.
  val pageErrors = mutableSetOf<(Int, String, Int) -> Unit>()
  private val setupSessions = mutableSetOf<GeckoSession>()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var startup: Deferred<WebExtension>? = null

  private val setupTabs =
    object : WebExtension.TabDelegate {
      override fun onNewTab(
        source: WebExtension,
        details: WebExtension.CreateTabDetails,
      ): GeckoResult<GeckoSession>? {
        val base = source.metaData?.baseUrl ?: return null
        if (source.id !in blockers || details.url?.startsWith(base) != true) return null
        // AdGuard awaits tabs.create before installing its default filters. GeckoView queues that
        // request indefinitely without a TabDelegate. Only the add-on's own pages may open here.
        val session = GeckoSession()
        setupSessions += session
        session.navigationDelegate =
          object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
              session: GeckoSession,
              request: GeckoSession.NavigationDelegate.LoadRequest,
            ) =
              GeckoResult.fromValue(
                if (request.uri.startsWith(base)) AllowOrDeny.ALLOW else AllowOrDeny.DENY
              )
          }
        session.progressDelegate =
          object : GeckoSession.ProgressDelegate {
            private var addonPage = false

            override fun onPageStart(session: GeckoSession, url: String) {
              addonPage = url.startsWith(base)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
              // The first stop can belong to the session's initial about:blank; closing then
              // aborted the add-on's own page before it loaded on a CI emulator.
              if (!addonPage) return
              setupSessions.remove(session)
              session.close()
            }
          }
        // The controller opens the returned session itself; it must still be unopened here.
        return GeckoResult.fromValue(session)
      }
    }

  init {
    runtime.webExtensionController.setPromptDelegate(
      object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
          extension: WebExtension,
          permissions: Array<String>,
          origins: Array<String>,
          dataCollectionPermissions: Array<String>,
        ) =
          GeckoResult.fromValue(
            WebExtension.PermissionPromptResponse(extension.id in blockers, false, false)
          )
      }
    )
  }

  // Main thread only. A failed startup is retried by the next caller instead of staying cached.
  fun ready(): Deferred<WebExtension> =
    startup
      ?: scope
        .async { start() }
        .also { started ->
          startup = started
          started.invokeOnCompletion { if (it != null && startup === started) startup = null }
        }

  private suspend fun start(): WebExtension {
    val controller = runtime.webExtensionController
    val installed = controller.list().await().associateBy { it.id }
    for ((id, bundle) in blockers) {
      val current = installed[id]
      // Installed extensions keep their storage; a newer pinned package from an app update
      // replaces older code, while filter lists keep updating through each extension.
      val extension =
        if (current == null || newerVersion(bundle.version, current.metaData.version))
          install(bundle)
        else current
      extension.setTabDelegate(setupTabs)
      // Enable both sources: a previous user/app disable must not leave an unprotected page.
      controller.enable(extension, WebExtensionController.EnableSource.USER).await()
      controller.enable(extension, WebExtensionController.EnableSource.APP).await()
    }
    val enabled = controller.list().await().associateBy { it.id }
    check(blockers.keys.all { enabled[it]?.metaData?.disabledFlags == 0 }) {
      "Both uBlock Origin and AdGuard must be enabled before opening a page"
    }
    awaitFiltering()
    val capture =
      controller
        .ensureBuiltIn("resource://android/assets/castor-browser/", "capture@castor.android")
        .await()
    capture.setMessageDelegate(
      object : WebExtension.MessageDelegate {
        override fun onMessage(
          nativeApp: String,
          message: Any,
          sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? {
          if (
            sender.environmentType == WebExtension.MessageSender.ENV_TYPE_EXTENSION &&
              sender.webExtension.id == capture.id &&
              message is JSONObject
          ) {
            val tab = message.optInt("tabId", -1)
            val url = message.optString("url")
            val status = message.optInt("status")
            if (url.length <= 8192 && BrowserResolver.isHttp(url) && status in 400..599)
              pageErrors.toList().forEach { it(tab, url, status) }
          }
          return null
        }
      },
      "castor_network",
    )
    return capture
  }

  private suspend fun install(bundle: Bundle): WebExtension {
    val file = File(context.cacheDir, bundle.file)
    try {
      withContext(Dispatchers.IO) {
        val bytes = context.assets.open("extensions/${bundle.file}").use { it.readBytes() }
        val digest =
          MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == bundle.sha256) { "The bundled ad blocker failed its integrity check" }
        file.writeBytes(bytes)
      }
      return runtime.webExtensionController
        .install(android.net.Uri.fromFile(file).toString())
        .await()
    } finally {
      // Gecko keeps its own copy in the profile; the staging file is not needed afterwards.
      withContext(NonCancellable + Dispatchers.IO) { file.delete() }
    }
  }

  // Enabled is not filtering: a newly installed or started extension enables before its engine has
  // loaded rules. Load a loopback page whose ad script both bundled lists refuse
  // (`/ads/custom_ads.js$script`) until a blocker actually stops it. No external host is contacted.
  private suspend fun awaitFiltering() {
    val probe = withContext(Dispatchers.IO) { BlockerProbe() }
    try {
      withTimeoutOrNull(60_000) {
        while (true) {
          probe.requests.clear()
          // A fresh session per attempt: Android may kill a content process under memory pressure,
          // which leaves the previous session closed.
          val session = GeckoSession().apply { open(runtime) }
          val ran =
            try {
              session.setActive(true)
              session.loadUri(probe.url(BlockerProbe.PAGE))
              withTimeoutOrNull(10_000) {
                while (BlockerProbe.CONTROL !in probe.requests) delay(100)
              }
            } finally {
              session.close()
            }
          if (ran != null && BlockerProbe.AD !in probe.requests) return@withTimeoutOrNull
          delay(250)
        }
      }
        ?: throw java.io.IOException(
          "uBlock Origin and AdGuard did not start filtering. Close the page and try again."
        )
    } finally {
      withContext(NonCancellable + Dispatchers.IO) { probe.close() }
    }
  }

  data class Bundle(val file: String, val sha256: String, val version: String)

  companion object {
    val blockers =
      linkedMapOf(
        "uBlock0@raymondhill.net" to
          Bundle(
            "ublock-origin.xpi",
            "5b74415860456370644bd80f16125e865b0e6c356bb5dfcfb84069967eaa5287",
            "1.75.0",
          ),
        "adguardadblocker@adguard.com" to
          Bundle(
            "adguard.xpi",
            "becd4d42749c57b5fbc22613ace4a93d0e47c2e646f8771a9e4f28c1c768cd89",
            "5.5.3.5",
          ),
      )
    // Holds only the application context, which lives as long as the process.
    @SuppressLint("StaticFieldLeak") private var instance: BrowserEngine? = null

    fun get(context: Context): BrowserEngine =
      instance ?: BrowserEngine(context.applicationContext).also { instance = it }

    // Numeric dotted versions only; anything else keeps the installed extension.
    internal fun newerVersion(bundled: String, installed: String?): Boolean {
      val next = bundled.split('.').map { it.toIntOrNull() ?: return false }
      val current = installed?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return true
      for (i in 0 until maxOf(next.size, current.size)) {
        val difference = next.getOrElse(i) { 0 } - current.getOrElse(i) { 0 }
        if (difference != 0) return difference > 0
      }
      return false
    }
  }
}

// A loopback page for the startup filtering check: it records which paths Gecko requested.
private class BlockerProbe : AutoCloseable {
  val requests = ConcurrentLinkedQueue<String>()
  // Android's getLoopbackAddress() is ::1; the probe URL names the IPv4 loopback.
  private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
  private val worker =
    Thread {
        while (!server.isClosed) {
          runCatching {
            server.accept().use { socket ->
              socket.soTimeout = 5000
              val reader = socket.getInputStream().bufferedReader()
              val line = reader.readLine().orEmpty()
              // Drain the headers so closing the socket cannot reset the connection mid-response.
              do {
                val header = reader.readLine()
              } while (!header.isNullOrEmpty())
              val path = line.split(' ').getOrNull(1).orEmpty().substringBefore('?')
              requests += path
              val body =
                if (path == PAGE) "<script src='$AD'></script><script>fetch('$CONTROL')</script>"
                else "ok"
              val bytes = body.toByteArray()
              socket
                .getOutputStream()
                .write(
                  ("HTTP/1.1 200 OK\r\nContent-Type: ${if (path == PAGE) "text/html" else "application/javascript"}\r\n" +
                      "Cache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray() + bytes
                )
            }
          }
        }
      }
      .apply {
        isDaemon = true
        start()
      }

  fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"

  override fun close() {
    server.close()
    worker.join(1000)
  }

  companion object {
    const val PAGE = "/castor-probe"
    const val AD = "/ads/custom_ads.js"
    const val CONTROL = "/castor-probe-control"
  }
}

internal suspend fun <T : Any> GeckoResult<T>.await(): T =
  suspendCancellableCoroutine { continuation ->
    accept(
      {
        if (continuation.isActive) {
          if (it != null) continuation.resume(it)
          else
            continuation.resumeWithException(java.io.IOException("The browser returned no result"))
        }
      },
      {
        if (continuation.isActive)
          continuation.resumeWithException(
            it ?: java.io.IOException("The browser operation failed")
          )
      },
    )
  }
