package com.muratoreware.castorandroid

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
import com.muratoreware.castorandroid.data.DataRepository
import com.muratoreware.castorandroid.data.Rpc
import com.muratoreware.castorandroid.ui.main.CastOptions
import java.io.File
import java.util.UUID
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
import org.json.JSONObject

class CastorService : Service() {
  inner class LocalBinder : Binder() {
    val service
      get() = this@CastorService
  }

  val endpoint = MutableStateFlow<Rpc?>(null)
  val failure = MutableStateFlow<String?>(null)
  val stopped = MutableStateFlow(false)
  lateinit var browser: BrowserResolver
    private set

  val videos: LocalVideos?
    get() = bridge?.videos

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var process: Process? = null
  private var bridge: BrowserServer? = null
  private var multicast: WifiManager.MulticastLock? = null
  private var wake: PowerManager.WakeLock? = null
  // The newest cast's controls in the notification and on the lock screen.
  private lateinit var media: CastMediaSession
  var generation = 0
    private set
  @Volatile private var closed = false
  private var casting = false
  // Whether work still held may start another engine once this one is gone: not after a failure or a stop by the user.
  private var restart = true

  override fun onCreate() {
    super.onCreate()
    generation = EngineHost.created(this)
    val notifications = getSystemService(NotificationManager::class.java)
    notifications.createNotificationChannel(
      NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
    )
    startForeground(NOTIFICATION, engineNotification())
    val castOptions = CastOptions(this)
    media =
      CastMediaSession(
        this,
        scope,
        ::engineNotification,
        { notifications.notify(NOTIFICATION, it) },
        { videos?.name(it) },
        { device -> tvName(device, castOptions.television()) },
      )
    browser = BrowserHost.acquire(this, this)
    val scrapingToken = UUID.randomUUID().toString()
    val apiToken = UUID.randomUUID().toString()
    val mediaToken = UUID.randomUUID().toString()
    val discovery = DeviceDiscovery(this)
    val options = CastOptions(this)
    bridge =
      BrowserServer(
        browser,
        // A TV entered by hand has no identity to confirm, but its host is asked directly too.
        { discovery.find(options) },
        scrapingToken,
        contentResolver,
      )
    // A device without Wi-Fi has no Wi-Fi service, and no multicast lock to take: its wired network needs none.
    multicast =
      (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)
        ?.createMulticastLock("castor")
        ?.apply {
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
        // Debug mode asks the engine for its detailed log too.
        if (
          applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 ||
            DebugLog.enabled(this@CastorService)
        ) {
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
        hardwareEncoders()
          .takeIf { it.isNotEmpty() }
          ?.let { encoders ->
            builder.environment()["CASTOR_SERVERS__MEDIA__FFMPEG__HARDWARE_ENCODERS"] = encoders.joinToString(",")
            builder.environment()["CASTOR_SERVERS__MEDIA__FFMPEG__SOFTWARE_PRESET"] = "superfast"
          }
        val child = builder.start()
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
          if (closed) child.destroy() else process = child
        }
        currentCoroutineContext().ensureActive()
        launch {
          try {
            child.errorStream.bufferedReader().useLines { lines ->
              lines.forEach {
                android.util.Log.i(DebugLog.ENGINE_TAG, it)
                DebugLog.engine(it)
              }
            }
          } catch (error: java.io.IOException) {
            if (currentCoroutineContext().isActive)
              android.util.Log.w(DebugLog.ENGINE_TAG, "Engine log closed", error)
          }
        }
        child.inputStream.bufferedReader().use { output ->
          val line = output.readLine() ?: error("The native engine exited during startup")
          val url = JSONObject(line).getString("url")
          check(url.startsWith("http://127.0.0.1:")) { "Unexpected native engine endpoint" }
          val rpc = Rpc(url, apiToken)
          endpoint.value = rpc
          EngineHost.ready(this@CastorService, rpc)
          media.attach(DataRepository(rpc))
          // The newest cast's controls follow the engine's word on it, for the notification and the lock screen.
          launch {
            while (currentCoroutineContext().isActive) {
              media.refresh()
              kotlinx.coroutines.delay(1000)
            }
          }
          while (true) {
            val event = output.readLine() ?: break
            val active = JSONObject(event).getBoolean("active")
            EngineHost.active(this@CastorService, active)
            withContext(Dispatchers.Main) { keepAwake(active) }
          }
        }
        val status = child.waitFor()
        error("The casting engine stopped (exit $status).")
      } catch (error: Exception) {
        // An engine that was asked to stop has not failed.
        if (
          !closed &&
            currentCoroutineContext().isActive &&
            error !is kotlinx.coroutines.CancellationException
        ) {
          failure.value = error.message ?: "Unable to start Castor"
          restart = false
        }
      } finally {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { shutdown() }
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (val action = intent?.action) {
      "STOP" -> stopByUser()
      null -> {}
      else -> media.command(action)
    }
    return START_NOT_STICKY
  }

  // The engine's own notification, shown while no cast is playing.
  private fun engineNotification(): Notification {
    val stop =
      PendingIntent.getService(
        this,
        0,
        Intent(this, CastorService::class.java).setAction("STOP"),
        PendingIntent.FLAG_IMMUTABLE,
      )
    return Notification.Builder(this, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(getString(R.string.notification_engine_running))
      .setContentIntent(openApp(this))
      .setOngoing(true)
      .addAction(Notification.Action.Builder(null, getString(R.string.stop_castor), stop).build())
      .build()
  }

  // The user asked for the engine to stop: work still held does not start it again by itself.
  fun stopByUser() {
    restart = false
    shutdown()
  }

  override fun onBind(intent: Intent): IBinder = LocalBinder()

  private fun keepAwake(active: Boolean) {
    if (closed) return
    if (!active && casting) bridge?.playbackEnded()
    casting = active
    if (active) wake?.acquire(10 * 60 * 1000L) else if (wake?.isHeld == true) wake?.release()
  }

  internal fun shutdown() {
    if (closed) return
    closed = true
    EngineHost.closing(this)
    stopped.value = true
    endpoint.value = null
    bridge?.close()
    process?.destroy()
    scope.cancel()
    // The pages kept for this engine's casts are let go; the browser itself stays while the screen uses it.
    browser.releasePlayers()
    BrowserHost.release(this)
    if (multicast?.isHeld == true) multicast?.release()
    if (wake?.isHeld == true) wake?.release()
    media.release()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    shutdown()
    EngineHost.stopped(this, failure.value, restart)
    super.onDestroy()
  }

  companion object {
    internal const val CHANNEL = "casting"
    private const val NOTIFICATION = 1

    // A cast's TV as the screen names it: the engine's name, else the saved TV's at the same host, else its host.
    // A TV pinned by address reaches the engine with no name of its own.
    internal fun tvName(device: castor.v1.DeviceOuterClass.Device, saved: com.muratoreware.castorandroid.ui.main.SavedTv?): String {
      if (device.name.isNotBlank()) return device.name
      val host = com.muratoreware.castorandroid.ui.main.receiverHost(device.address)
      val savedHost = saved?.address?.let { com.muratoreware.castorandroid.ui.main.receiverHost(it) }
      if (host != null && savedHost != null && com.muratoreware.castorandroid.ui.main.sameHost(host, savedHost) && saved.name.isNotBlank())
        return saved.name
      return host ?: device.address
    }

    internal fun openApp(context: android.content.Context): PendingIntent =
      PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
      )
  }
}
