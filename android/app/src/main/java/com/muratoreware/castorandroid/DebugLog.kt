package com.muratoreware.castorandroid

import android.app.ActivityManager
import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.muratoreware.castorandroid.ui.main.CastOptions
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// Debug mode: while it is on, what Castor logs goes to a text file the user can find and send.
// Android lets an app read only its own processes' log, so no other app's lines reach the file. The engine's lines
// are written straight from its output, so a phone that drops app log lines still keeps them.
internal object DebugLog {
  const val ENGINE_TAG = "CastorEngine"
  private const val TAG = "CastorDebug"
  private const val NAME = "castor-debug.txt"
  // Android 10 and later: Download/Castor, which every file manager shows and no permission is needed for.
  private val folder = "${Environment.DIRECTORY_DOWNLOADS}/Castor/"
  // Past this size the file starts over, keeping the latest lines without filling the phone.
  private const val LIMIT = 32L * 1024 * 1024

  // Opening and closing the file take this lock, so they happen one after the other.
  private val files = Any()

  // The open file, its writer and the reader of Android's log; all guarded by this object.
  private var stream: FileOutputStream? = null
  private var out: Writer? = null
  private var logcat: java.lang.Process? = null
  private var header = ""
  private var written = 0L

  // Runs once, as Castor's main process starts.
  fun start(context: Context) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      crash(thread, error)
      previous?.uncaughtException(thread, error)
    }
    if (enabled(context)) thread(name = "castor-debug-open") { synchronized(files) { open(context, fresh = false) } }
  }

  fun enabled(context: Context): Boolean = CastOptions(context).debugMode()

  // Turning it on starts the file again; turning it off keeps the file for sending. It waits for the file, so it is
  // called away from the main thread.
  fun enable(context: Context, on: Boolean) {
    val app = context.applicationContext
    CastOptions(app).debugMode(on)
    synchronized(files) {
      close()
      if (on) open(app, fresh = true)
    }
  }

  // A line of the engine's output.
  fun engine(line: String) = synchronized(this) { write("${prefix('I', ENGINE_TAG)}$line\n", flush = true) }

  // Where the user finds the file; null while there is none. It asks Android's storage, so not on the main thread.
  fun location(context: Context): String? =
    if (Build.VERSION.SDK_INT >= 29) find(context)?.second
    else privateFile(context).takeIf { it.isFile }?.path

  // The file as other apps can read it once Castor lets them; null while there is none.
  fun uri(context: Context): Uri? {
    synchronized(this) { runCatching { out?.flush() } }
    return if (Build.VERSION.SDK_INT >= 29) find(context)?.first
    else
      privateFile(context).takeIf { it.isFile }?.let {
        FileProvider.getUriForFile(context, "${context.packageName}.logs", it)
      }
  }

  // The share sheet that sends the log, or null while there is none.
  fun share(context: Context): Intent? {
    val uri = uri(context) ?: return null
    val send =
      Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Castor debug log")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri(NAME, uri) }
    return Intent.createChooser(send, null)
  }

  private fun open(context: Context, fresh: Boolean) {
    try {
      val output =
        if (Build.VERSION.SDK_INT >= 29) {
          val uri = find(context)?.first ?: create(context)
          ParcelFileDescriptor.AutoCloseOutputStream(
            context.contentResolver.openFileDescriptor(uri, "wa") ?: error("Android did not open $uri")
          )
        } else FileOutputStream(privateFile(context).apply { parentFile?.mkdirs() }, true)
      // From when this process started, so what happened before debug mode was turned on is kept too.
      val started = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
      val child =
        ProcessBuilder(
            "logcat",
            "-v",
            "threadtime",
            "-T",
            String.format(Locale.ROOT, "%d.%03d", started / 1000, started % 1000),
            // The engine's lines are written straight from its output.
            "$ENGINE_TAG:S",
            "*:V",
          )
          .redirectErrorStream(true)
          .start()
      synchronized(this) {
        if (fresh) output.channel.truncate(0)
        stream = output
        out = output.bufferedWriter()
        written = output.channel.size()
        logcat = child
        header = header(context, started)
        write(header, flush = true)
      }
      thread(name = "castor-debug-log", isDaemon = true) {
        try {
          child.inputStream.bufferedReader().use { reader ->
            while (true) {
              val line = reader.readLine() ?: break
              synchronized(this) { if (logcat === child) write("$line\n", flush = !reader.ready()) }
            }
          }
        } catch (_: IOException) {
          // The reader closes when debug mode is turned off.
        }
      }
    } catch (error: Exception) {
      Log.w(TAG, "The debug log could not be opened", error)
      close()
    }
  }

  private fun close() =
    synchronized(this) {
      logcat?.destroy()
      logcat = null
      runCatching { out?.close() }
      out = null
      stream = null
    }

  // Runs on the thread that failed, before Android ends Castor: the log keeps why.
  private fun crash(thread: Thread, error: Throwable) {
    runCatching {
      synchronized(this) {
        write(
          "${prefix('E', "CastorCrash")}Castor stopped on an error in thread ${thread.name}\n${error.stackTraceToString()}\n",
          flush = true,
        )
      }
    }
  }

  // Holds this object's lock.
  private fun write(text: String, flush: Boolean) {
    val out = out ?: return
    try {
      if (written + text.length > LIMIT) {
        out.flush()
        stream?.channel?.truncate(0)
        written = 0
        val note = "Earlier lines were removed: the log reached ${LIMIT / (1024 * 1024)} MB.\n"
        out.write(header + note)
        written += header.length + note.length
      }
      out.write(text)
      written += text.length
      if (flush) out.flush()
    } catch (error: IOException) {
      // The file was removed or the storage is full: debug mode stops writing until it is turned on again.
      Log.w(TAG, "The debug log could not be written", error)
      close()
    }
  }

  // The start of a line as Android's log writes it, for lines Castor writes itself.
  private fun prefix(level: Char, tag: String): String =
    String.format(
      Locale.ROOT,
      "%s %5d %5d %c %s: ",
      SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date()),
      Process.myPid(),
      Process.myTid(),
      level,
      tag,
    )

  // The phone, its Android and this Castor, at the top of each run's lines.
  private fun header(context: Context, started: Long): String {
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
    val app = context.packageManager.getPackageInfo(context.packageName, 0)
    val memory =
      ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
    val chip =
      if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
      else "${Build.HARDWARE} (${Build.BOARD})"
    val encoders =
      runCatching {
          hardwareCodecEncoders()
            .filter { codec -> codec.supportedTypes.any { it.startsWith("video/") } }
            .joinToString { it.name }
            .ifEmpty { "none" }
        }
        .getOrDefault("unknown")
    return """
      |
      |===== Castor debug log =====
      |Written from:   ${time.format(Date())}
      |Castor opened:  ${time.format(Date(started))}, lines below start there
      |App:            Castor ${app.versionName} (${app.longVersionCode}), ${context.packageName}
      |Device:         ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})
      |Chip:           $chip, ${Runtime.getRuntime().availableProcessors()} cores, ${Build.SUPPORTED_ABIS.joinToString()}
      |Memory:         ${memory.totalMem / (1024 * 1024)} MB
      |Video encoders: $encoders
      |Android:        ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), security patch ${Build.VERSION.SECURITY_PATCH}
      |Build:          ${Build.FINGERPRINT}
      |============================
      |
      """
      .trimMargin()
  }

  // Android 10 and later only show an app the files it made itself, so any log found here is Castor's own.
  @RequiresApi(29)
  private fun find(context: Context): Pair<Uri, String>? {
    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    context.contentResolver
      .query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
        arrayOf(folder, "castor-debug%"),
        "${MediaStore.MediaColumns._ID} DESC",
      )
      ?.use { cursor ->
        if (cursor.moveToFirst())
          return ContentUris.withAppendedId(collection, cursor.getLong(0)) to folder + cursor.getString(1)
      }
    return null
  }

  @RequiresApi(29)
  private fun create(context: Context): Uri =
    context.contentResolver.insert(
      MediaStore.Downloads.EXTERNAL_CONTENT_URI,
      ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
      },
    ) ?: error("Android did not create the debug log in ${folder}")

  // Android 9: the app's own folder on the shared storage, which file managers still show there.
  private fun privateFile(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs/$NAME")
}
