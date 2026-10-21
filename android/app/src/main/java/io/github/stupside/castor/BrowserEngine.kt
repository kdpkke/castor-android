package io.github.stupside.castor

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import org.json.JSONObject
import org.mozilla.geckoview.*

// One runtime per app process. Closing a page or service never destroys extension storage.
internal class BrowserEngine private constructor(context: Context) {
  val runtime =
    GeckoRuntime.create(
      context,
      GeckoRuntimeSettings.Builder().remoteDebuggingEnabled(false).configFilePath("").build(),
    )
  val ready: Deferred<WebExtension>
  val pageErrors = mutableSetOf<(String, Int) -> Unit>()
  private val setupSessions = mutableSetOf<GeckoSession>()

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
            override fun onPageStop(session: GeckoSession, success: Boolean) {
              setupSessions.remove(session)
              session.close()
            }
          }
        // The controller opens the returned session itself; it must still be unopened here.
        return GeckoResult.fromValue(session)
      }
    }

  init {
    val controller = runtime.webExtensionController
    controller.setPromptDelegate(
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
    ready =
      CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).async {
        val installed = controller.list().await().associateBy { it.id }
        for ((id, bundle) in blockers) {
          val extension =
            installed[id]
              ?: run {
                val file =
                  withContext(Dispatchers.IO) {
                    val bytes =
                      context.assets.open("extensions/${bundle.first}").use { it.readBytes() }
                    val digest =
                      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                        "%02x".format(it)
                      }
                    check(digest == bundle.second) {
                      "The bundled ad blocker failed its integrity check"
                    }
                    File(context.filesDir, bundle.first).apply { writeBytes(bytes) }
                  }
                controller.install(android.net.Uri.fromFile(file).toString()).await()
              }
          // Enable both sources: a previous user/app disable must not leave an unprotected page.
          extension.setTabDelegate(setupTabs)
          controller.enable(extension, WebExtensionController.EnableSource.USER).await()
          controller.enable(extension, WebExtensionController.EnableSource.APP).await()
        }
        val enabled = controller.list().await().associateBy { it.id }
        check(blockers.keys.all { enabled[it]?.metaData?.disabledFlags == 0 }) {
          "Both uBlock Origin and AdGuard must be enabled before opening a page"
        }
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
                val url = message.optString("url")
                val status = message.optInt("status")
                if (url.length <= 8192 && BrowserResolver.isHttp(url) && status in 400..599)
                  pageErrors.toList().forEach { it(url, status) }
              }
              return null
            }
          },
          "castor_network",
        )
        capture
      }
  }

  companion object {
    val blockers =
      linkedMapOf(
        "uBlock0@raymondhill.net" to
          ("ublock-origin.xpi" to
            "5b74415860456370644bd80f16125e865b0e6c356bb5dfcfb84069967eaa5287"),
        "adguardadblocker@adguard.com" to
          ("adguard.xpi" to "becd4d42749c57b5fbc22613ace4a93d0e47c2e646f8771a9e4f28c1c768cd89"),
      )
    private var instance: BrowserEngine? = null

    fun get(context: Context): BrowserEngine =
      instance ?: BrowserEngine(context.applicationContext).also { instance = it }
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
