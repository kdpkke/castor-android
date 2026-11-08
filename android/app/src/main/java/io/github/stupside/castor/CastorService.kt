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
import castor.v1.DeviceOuterClass.Device
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
  private var closed = false
  private var casting = false

  override fun onCreate() {
    super.onCreate()
    val notifications = getSystemService(NotificationManager::class.java)
    notifications.createNotificationChannel(
      NotificationChannel("casting", "Casting", NotificationManager.IMPORTANCE_LOW)
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
    val discovery = DeviceDiscovery(this)
    val options = CastOptions(this)
    bridge =
      BrowserServer(
        browser,
        {
          val remembered = options.television()?.takeIf { !it.manual }?.let {
            Device.newBuilder().setId(it.id).setName(it.name).setAddress(it.address).setType(it.type).build()
          }
          discovery.find(remembered)
        },
        scrapingToken,
        contentResolver,
      )
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
        hardwareEncoders()
          .takeIf { it.isNotEmpty() }
          ?.let { builder.environment()["CASTOR_SERVERS__MEDIA__FFMPEG__HARDWARE_ENCODERS"] = it.joinToString(",") }
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
    }
    return START_NOT_STICKY
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
}
