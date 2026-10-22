package io.github.stupside.castor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import io.github.stupside.castor.data.Rpc
import io.github.stupside.castor.ui.main.CastOptions
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class CastorService : Service() {
  inner class LocalBinder : Binder() {
    val service
      get() = this@CastorService
  }

  val endpoint = MutableStateFlow<Rpc?>(null)
  val failure = MutableStateFlow<String?>(null)
  val stopped = MutableStateFlow(false)
  /** True while a browser waits for the owner's answer; the app asks too when it is open. */
  val browserRequest = MutableStateFlow(false)
  lateinit var browser: BrowserResolver
    private set

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var process: Process? = null
  private var bridge: BrowserServer? = null
  private var gateway: ExtensionGateway? = null
  private var pairing: CompletableDeferred<Boolean>? = null
  private var multicast: WifiManager.MulticastLock? = null
  private var wake: PowerManager.WakeLock? = null
  private var closed = false
  private var casting = false

  override fun onCreate() {
    super.onCreate()
    val notifications = getSystemService(NotificationManager::class.java)
    notifications.createNotificationChannel(
      NotificationChannel("casting", "Casting", NotificationManager.IMPORTANCE_LOW)
    )
    notifications.createNotificationChannel(
      NotificationChannel(
        "browser-access",
        CastOptions(this).localizedContext().getString(R.string.browser_channel),
        NotificationManager.IMPORTANCE_HIGH,
      )
    )
    val stop =
      PendingIntent.getService(
        this,
        0,
        Intent(this, CastorService::class.java).setAction("STOP"),
        PendingIntent.FLAG_IMMUTABLE,
      )
    val open =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
      )
    startForeground(
      1,
      Notification.Builder(this, "casting")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("Castor")
        .setContentText("Casting engine running on this phone")
        .setContentIntent(open)
        .setOngoing(true)
        .addAction(Notification.Action.Builder(null, "Stop Castor", stop).build())
        .build(),
    )
    browser = BrowserResolver(this)
    val scrapingToken = UUID.randomUUID().toString()
    val apiToken = UUID.randomUUID().toString()
    val mediaToken = UUID.randomUUID().toString()
    bridge = BrowserServer(browser, DeviceDiscovery(this)::find, scrapingToken)
    multicast =
      (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
        .createMulticastLock("castor")
        .apply {
          setReferenceCounted(false)
          acquire()
        }
    wake =
      getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "castor:casting")
        .apply { setReferenceCounted(false) }
    scope.launch(Dispatchers.Main) {
      while (currentCoroutineContext().isActive) {
        if (casting) keepAwake(true)
        kotlinx.coroutines.delay(5 * 60 * 1000L)
      }
    }
    scope.launch {
      try {
        val native = applicationInfo.nativeLibraryDir
        val executable = File(native, "libcastor.so")
        check(executable.canExecute()) {
          "The APK does not contain the native engine for this device. Build its ABI first."
        }
        val builder = ProcessBuilder(executable.path).directory(filesDir)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
          builder.command().add("--debug")
        }
        val certificates = nativeCaBundle(cacheDir)
        builder
          .environment()
          .putAll(
            mapOf(
              "HOME" to filesDir.path,
              "TMPDIR" to cacheDir.path,
              "XDG_CACHE_HOME" to cacheDir.path,
              "CASTOR_SERVERS__API__TOKEN" to apiToken,
              "CASTOR_SERVERS__MEDIA__TOKEN" to mediaToken,
              "CASTOR_SERVERS__SCRAPING__URL" to bridge!!.url,
              "CASTOR_SERVERS__SCRAPING__TOKEN" to scrapingToken,
              "CASTOR_SERVERS__MEDIA__FFMPEG__PATH" to "$native/libffmpeg.so",
              "CASTOR_SERVERS__MEDIA__FFMPEG__PROBE_PATH" to "$native/libffprobe.so",
              "SSL_CERT_FILE" to certificates.path,
              "LD_LIBRARY_PATH" to native,
            )
          )
        val child = builder.start()
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
          if (closed) child.destroy() else process = child
        }
        currentCoroutineContext().ensureActive()
        launch {
          try {
            child.errorStream.bufferedReader().useLines { lines ->
              lines.forEach { android.util.Log.i("CastorEngine", it) }
            }
          } catch (error: java.io.IOException) {
            if (currentCoroutineContext().isActive)
              android.util.Log.w("CastorEngine", "Engine log closed", error)
          }
        }
        child.inputStream.bufferedReader().use { output ->
          val line = output.readLine() ?: error("The native engine exited during startup")
          val url = JSONObject(line).getString("url")
          check(url.startsWith("http://127.0.0.1:")) { "Unexpected native engine endpoint" }
          endpoint.value = Rpc(url, apiToken)
          openGateway(url, apiToken)
          while (true) {
            val event = output.readLine() ?: break
            val active = JSONObject(event).getBoolean("active")
            withContext(Dispatchers.Main) { keepAwake(active) }
          }
        }
        val status = child.waitFor()
        error("The casting engine stopped (exit $status). Open Castor to restart it.")
      } catch (error: Exception) {
        if (
          currentCoroutineContext().isActive && error !is kotlinx.coroutines.CancellationException
        )
          failure.value = error.message ?: "Unable to start Castor"
      } finally {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { shutdown() }
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      "STOP" -> {
        failure.value = "Castor stopped. Reopen the app to restart the engine."
        shutdown()
      }
      ALLOW_BROWSER -> pairing?.complete(true)
      REFUSE_BROWSER -> pairing?.complete(false)
    }
    return START_NOT_STICKY
  }

  fun answerBrowser(allow: Boolean) {
    pairing?.complete(allow)
  }

  // The Castor browser extension on this phone reaches the engine through a fixed local port. A
  // port another app already holds leaves the extension reporting that Castor is not running.
  private suspend fun openGateway(url: String, apiToken: String) {
    val access = ExtensionAccess(this)
    val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    val opened =
      try {
        ExtensionGateway(url, apiToken, version, access::allowed, access::grant, ::askBrowser) {
          android.util.Log.w("ExtensionGateway", "A browser request ended without an answer", it)
        }
      } catch (error: java.io.IOException) {
        android.util.Log.w("CastorService", "The browser extension port is unavailable", error)
        return
      }
    withContext(NonCancellable + Dispatchers.Main) {
      if (closed) opened.close() else gateway = opened
    }
  }

  // Asks the owner, through a notification, whether a browser on this phone may cast.
  private suspend fun askBrowser(): Boolean? {
    val text = CastOptions(this).localizedContext()
    val notifications = getSystemService(NotificationManager::class.java)
    if (!notifications.areNotificationsEnabled())
      throw ExtensionGateway.Unavailable(text.getString(R.string.browser_notifications_off))
    val decision = CompletableDeferred<Boolean>()
    fun action(name: String, label: Int, code: Int) =
      Notification.Action.Builder(
          null,
          text.getString(label),
          PendingIntent.getService(
            this,
            code,
            Intent(this, CastorService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE,
          ),
        )
        .build()
    // Tapping the notification instead of a button opens the app, which asks the same question.
    val open =
      PendingIntent.getActivity(
        this,
        3,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
      )
    withContext(Dispatchers.Main) {
      pairing = decision
      browserRequest.value = true
      notifications.notify(
        BROWSER_NOTIFICATION,
        Notification.Builder(this@CastorService, "browser-access")
          .setSmallIcon(android.R.drawable.ic_media_play)
          .setContentTitle(text.getString(R.string.browser_allow_title))
          .setContentText(text.getString(R.string.browser_allow_text))
          .setStyle(
            Notification.BigTextStyle().bigText(text.getString(R.string.browser_allow_text))
          )
          .setCategory(Notification.CATEGORY_STATUS)
          .setContentIntent(open)
          .setTimeoutAfter(PAIRING_TIMEOUT)
          .addAction(action(ALLOW_BROWSER, R.string.browser_allow, 1))
          .addAction(action(REFUSE_BROWSER, R.string.browser_refuse, 2))
          .build(),
      )
    }
    try {
      return withTimeoutOrNull(PAIRING_TIMEOUT) { decision.await() }
    } finally {
      withContext(NonCancellable + Dispatchers.Main) {
        if (pairing === decision) {
          pairing = null
          browserRequest.value = false
        }
        notifications.cancel(BROWSER_NOTIFICATION)
      }
    }
  }

  override fun onBind(intent: Intent): IBinder = LocalBinder()

  private fun keepAwake(active: Boolean) {
    if (closed) return
    if (!active && casting) bridge?.playbackEnded()
    casting = active
    if (active) wake?.acquire(10 * 60 * 1000L) else if (wake?.isHeld == true) wake?.release()
  }

  private fun shutdown() {
    if (closed) return
    closed = true
    stopped.value = true
    endpoint.value = null
    bridge?.close()
    gateway?.close()
    pairing?.complete(false)
    process?.destroy()
    scope.cancel()
    browser.close()
    if (multicast?.isHeld == true) multicast?.release()
    if (wake?.isHeld == true) wake?.release()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    shutdown()
    super.onDestroy()
  }

  private companion object {
    const val ALLOW_BROWSER = "ALLOW_BROWSER"
    const val REFUSE_BROWSER = "REFUSE_BROWSER"
    const val BROWSER_NOTIFICATION = 2
    const val PAIRING_TIMEOUT = 120_000L
  }
}
