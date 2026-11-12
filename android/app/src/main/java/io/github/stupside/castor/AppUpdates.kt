package io.github.stupside.castor

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import io.github.stupside.castor.ui.main.CastOptions
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

// A Castor Android release on GitHub, as much of it as the app offers and installs.
internal data class Release(
  val tag: String,
  val version: String,
  val title: String,
  val notes: String,
  val page: String,
  val apk: String,
  val size: Long,
  val checksums: String,
)

// What GitHub says about releases, read without Android so the rules are tested on their own.
internal object Releases {
  const val REPOSITORY = "kdpkke/castor-android"
  const val LATEST = "https://api.github.com/repos/$REPOSITORY/releases/latest"
  const val APK = "castor-android.apk"
  const val CHECKSUMS = "SHA256SUMS.txt"
  private const val PAGES = "https://github.com/$REPOSITORY/releases/tag/"
  private const val DOWNLOADS = "https://github.com/$REPOSITORY/releases/download/"
  private val tagPattern = Regex("^android-v(\\d{1,6}\\.\\d{1,6}\\.\\d{1,6})$")
  private val versionPattern = Regex("^(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})(-[0-9A-Za-z.-]+)?$")
  private val checksumLine = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")
  private const val ITALIAN = "## Italiano"

  // The release GitHub describes, or null when it is not one this app installs: a draft or pre-release, a tag
  // that is not a version, or an app and checksum list that are not that release's own files on this repository.
  fun parse(json: String): Release? =
    runCatching {
        val release = JSONObject(json)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.getString("tag_name")
        val version = tagPattern.matchEntire(tag)?.groupValues?.get(1) ?: return null
        val assets = release.getJSONArray("assets")
        fun asset(name: String): JSONObject? =
          (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.optString("name") == name }
        val apk = asset(APK) ?: return null
        val checksums = asset(CHECKSUMS) ?: return null
        val files = "$DOWNLOADS$tag/"
        if (apk.optString("browser_download_url") != files + APK) return null
        if (checksums.optString("browser_download_url") != files + CHECKSUMS) return null
        val size = apk.optLong("size", 0)
        if (size <= 0) return null
        Release(
          tag,
          version,
          release.optString("name").ifBlank { tag },
          release.optString("body"),
          PAGES + tag,
          files + APK,
          size,
          files + CHECKSUMS,
        )
      }
      .getOrNull()

  // Whether version comes after installed. A test build of a version comes before that version itself; anything
  // that is not a version is never newer.
  fun newer(version: String, installed: String): Boolean {
    val next = versionPattern.matchEntire(version) ?: return false
    val current = versionPattern.matchEntire(installed) ?: return false
    for (part in 1..3) {
      val a = next.groupValues[part].toInt()
      val b = current.groupValues[part].toInt()
      if (a != b) return a > b
    }
    return next.groupValues[4].isEmpty() && current.groupValues[4].isNotEmpty()
  }

  // The notes in the app's language. Releases carry English first and Italian under "## Italiano"; notes without
  // that heading are shown whole.
  fun notes(text: String, language: String): String {
    val lines = text.replace("\r\n", "\n").lines()
    val italian = lines.indexOfFirst { it.trim().equals(ITALIAN, ignoreCase = true) }
    if (italian < 0) return text.trim()
    if (language == "it") return lines.drop(italian + 1).joinToString("\n").trim()
    return lines
      .take(italian)
      // The pointer to the Italian half, which is not shown here.
      .filterNot { it.trim().startsWith("*Versione italiana") }
      .joinToString("\n")
      .trim()
      .removeSuffix("---")
      .trim()
  }

  // The SHA-256 a checksum list gives for name, as sha256sum writes it; null when it gives none.
  fun checksum(list: String, name: String = APK): String? =
    list.lines().firstNotNullOfOrNull { line ->
      checksumLine
        .matchEntire(line.trim())
        ?.takeIf { it.groupValues[2].trim() == name }
        ?.groupValues
        ?.get(1)
        ?.lowercase()
    }
}

// Where an update stands. Only releases of this app, installed outside Google Play, are offered.
internal sealed interface UpdateStatus {
  data object Idle : UpdateStatus

  data class Downloading(val downloaded: Long, val total: Long) : UpdateStatus

  // Being written to Android's installer, or waiting for the user to confirm it there.
  data object Installing : UpdateStatus

  // Downloaded while the engine was running. Installing closes Castor, so it waits until the engine stops.
  data object Waiting : UpdateStatus

  data class Failed(val message: String) : UpdateStatus
}

// Finds new releases on GitHub, downloads one through Android's download manager and hands it to Android's
// package installer. The download and the installation go on while Castor is in the background.
internal object AppUpdates {
  private const val RELEASE_PACKAGE = "io.github.stupside.castor"
  // SHA-256 of the release signing certificate, android/docs/signing/release-cert.pem.
  private const val RELEASE_CERTIFICATE = "8b0859493ccab43565a719d2e867be815d651c49603e707f1e8e82309acd9dbb"
  private const val PLAY_STORE = "com.android.vending"
  private const val CHECK_EVERY = 6 * 60 * 60 * 1000L
  private const val CHANNEL = "updates"
  private const val NOTIFICATION = 2
  private const val UPDATES = "updates"
  internal const val INSTALL_STATUS = "io.github.stupside.castor.action.UPDATE_INSTALL_STATUS"

  val release = MutableStateFlow<Release?>(null)
  // The notices the user has opened, by notice id.
  val read = MutableStateFlow<Set<String>>(emptySet())
  val status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)

  // How many Castor screens are in front: only then may Android's confirmation open by itself.
  private val screens = AtomicInteger()
  val visible: Boolean
    get() = screens.get() > 0

  fun shown(front: Boolean) {
    if (front) screens.incrementAndGet() else screens.decrementAndGet()
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val http by lazy {
    OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followRedirects(true).build()
  }
  private var loaded = false
  private val committing = AtomicBoolean()

  private fun preferences(context: Context) =
    context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)

  private fun text(context: Context, id: Int, vararg args: Any): String =
    CastOptions(context).localizedContext().getString(id, *args)

  fun installedVersion(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

  // Restores what an earlier run of the app knew: the release on offer, what was read and where an update stands.
  @Synchronized
  fun load(context: Context) {
    if (loaded) return
    loaded = true
    val preferences = preferences(context)
    read.value = preferences.getStringSet("read", emptySet()).orEmpty().toSet()
    release.value =
      preferences
        .getString("release", null)
        ?.let(Releases::parse)
        ?.takeIf { Releases.newer(it.version, installedVersion(context)) }
    status.value =
      when {
        release.value == null -> UpdateStatus.Idle
        preferences.contains("download-id") -> UpdateStatus.Downloading(0, release.value?.size ?: 0)
        preferences.getBoolean("waiting", false) -> UpdateStatus.Waiting
        else -> preferences.getString("error", null)?.let(UpdateStatus::Failed) ?: UpdateStatus.Idle
      }
  }

  // Releases are offered only to the release build signed with the release key, and not when Google Play installed
  // it: Play would deliver its own updates. Castor cannot tell from Android that its APK came from GitHub; a release
  // build installed by anything other than Play is the closest Android lets it know.
  fun eligible(context: Context): Boolean {
    if (context.packageName != RELEASE_PACKAGE) return false
    val manager = context.packageManager
    val installer =
      runCatching {
          if (Build.VERSION.SDK_INT >= 30)
            manager.getInstallSourceInfo(context.packageName).installingPackageName
          else @Suppress("DEPRECATION") manager.getInstallerPackageName(context.packageName)
        }
        .getOrNull()
    if (installer == PLAY_STORE) return false
    val signing =
      manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        ?: return false
    if (signing.hasMultipleSigners()) return false
    return signing.signingCertificateHistory.orEmpty().any { sha256(it.toByteArray()) == RELEASE_CERTIFICATE }
  }

  // Asks GitHub for the latest release, at most every few hours. A failed check is tried again at the next start.
  fun check(context: Context) {
    load(context)
    if (!eligible(context)) return
    val preferences = preferences(context)
    val now = System.currentTimeMillis()
    if (now - preferences.getLong("checked-at", 0) in 0 until CHECK_EVERY) return
    scope.launch {
      val json = runCatching { fetch(Releases.LATEST, 1 shl 20) }.getOrNull() ?: return@launch
      val found =
        Releases.parse(json)?.takeIf { Releases.newer(it.version, installedVersion(context)) }
      preferences
        .edit()
        .putLong("checked-at", now)
        .apply { if (found != null) putString("release", json) else remove("release") }
        .apply()
      // An update already under way keeps its release.
      if (found != null || status.value == UpdateStatus.Idle) release.value = found
    }
  }

  fun markRead(context: Context, id: String) {
    if (id in read.value) return
    read.value = read.value + id
    preferences(context).edit().putStringSet("read", read.value).apply()
  }

  // Whether Android lets Castor install apps; when not, Android asks the user before installing.
  fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

  fun installPermissionIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

  // Updates to the release on offer: installs the file already downloaded for it, or downloads it first. Retrying
  // after a cancelled installation does not download the release again.
  fun update(context: Context) {
    val preferences = preferences(context)
    val ready =
      file(context)?.isFile == true &&
        !preferences.contains("download-id") &&
        preferences.contains("download-sha256") &&
        preferences.getString("download-tag", null) == release.value?.tag
    if (ready) install(context) else download(context)
  }

  // Starts downloading the release on offer, after reading its checksum list.
  private fun download(context: Context) {
    val release = release.value ?: return
    if (status.value is UpdateStatus.Downloading || status.value == UpdateStatus.Installing) return
    status.value = UpdateStatus.Downloading(0, release.size)
    scope.launch {
      try {
        val expected =
          Releases.checksum(fetch(release.checksums, 64 * 1024))
            ?: error(text(context, R.string.update_no_checksum))
        val file = file(context) ?: error(text(context, R.string.update_storage))
        file.delete()
        val manager = context.getSystemService(DownloadManager::class.java)
        val id =
          manager.enqueue(
            DownloadManager.Request(Uri.parse(release.apk))
              .setTitle(release.title)
              .setMimeType("application/vnd.android.package-archive")
              .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
              .setDestinationInExternalFilesDir(context, UPDATES, Releases.APK)
          )
        preferences(context)
          .edit()
          .putLong("download-id", id)
          .putString("download-sha256", expected)
          .putString("download-tag", release.tag)
          .remove("waiting")
          .remove("error")
          .apply()
      } catch (failure: Exception) {
        fail(context, failure.message ?: failure.toString())
      }
    }
  }

  // Follows the download while its page is open. A finished download is installed from here as well, in case the
  // download manager's broadcast never comes.
  fun refresh(context: Context) {
    val id = preferences(context).getLong("download-id", -1)
    if (id < 0) return
    val manager = context.getSystemService(DownloadManager::class.java)
    manager.query(DownloadManager.Query().setFilterById(id)).use { row ->
      if (!row.moveToFirst()) {
        preferences(context).edit().remove("download-id").apply()
        fail(context, text(context, R.string.update_download_failed, -1))
        return
      }
      when (row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
        DownloadManager.STATUS_SUCCESSFUL -> downloaded(context, id)
        DownloadManager.STATUS_FAILED -> {
          preferences(context).edit().remove("download-id").apply()
          fail(
            context,
            text(
              context,
              R.string.update_download_failed,
              row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            ),
          )
        }
        else -> {
          val total = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
          status.value =
            UpdateStatus.Downloading(
              row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
              if (total > 0) total else release.value?.size ?: 0,
            )
        }
      }
    }
  }

  fun cancel(context: Context) {
    val preferences = preferences(context)
    val id = preferences.getLong("download-id", -1)
    // Forgotten first, so the broadcast that the removal sends finds nothing to install.
    preferences.edit().remove("download-id").remove("waiting").remove("error").commit()
    if (id >= 0) context.getSystemService(DownloadManager::class.java).remove(id)
    forgetFile(context)
    status.value = UpdateStatus.Idle
  }

  // The download with this id has ended. Whoever learns it first installs it; finished runs once that is decided.
  fun downloaded(context: Context, id: Long, finished: () -> Unit = {}) {
    val claimed =
      synchronized(this) {
        val preferences = preferences(context)
        if (id < 0 || preferences.getLong("download-id", -1) != id) false
        else preferences.edit().remove("download-id").commit()
      }
    if (!claimed) return finished()
    val manager = context.getSystemService(DownloadManager::class.java)
    val succeeded =
      manager.query(DownloadManager.Query().setFilterById(id)).use { row ->
        row.moveToFirst() &&
          row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
            DownloadManager.STATUS_SUCCESSFUL
      }
    if (!succeeded) {
      fail(context, text(context, R.string.update_download_failed, -1))
      return finished()
    }
    install(context, finished = finished)
  }

  // The engine has stopped: an update that waited for it is installed now.
  fun engineStopped(context: Context) {
    if (preferences(context).getBoolean("waiting", false)) install(context)
  }

  // Hands the downloaded file to Android's installer. While the engine runs a cast it waits, unless force: the
  // installation closes Castor and with it every cast.
  fun install(context: Context, force: Boolean = false, finished: () -> Unit = {}) {
    if (!force && EngineHost.phase != EngineLifecycle.Phase.Stopped) {
      preferences(context).edit().putBoolean("waiting", true).apply()
      status.value = UpdateStatus.Waiting
      if (!visible)
        notify(
          context,
          text(context, R.string.update_ready_title),
          text(context, R.string.update_waiting_body, release.value?.version.orEmpty()),
          launch(context),
        )
      return finished()
    }
    // One file is written to Android's installer at a time.
    if (!committing.compareAndSet(false, true)) return finished()
    preferences(context).edit().remove("waiting").remove("error").apply()
    status.value = UpdateStatus.Installing
    scope.launch {
      try {
        commit(context)
      } catch (failure: Exception) {
        fail(context, failure.message ?: failure.toString())
      } finally {
        committing.set(false)
        finished()
      }
    }
  }

  // The downloaded file and what was learnt about it are gone: the next update downloads again.
  private fun forgetFile(context: Context) {
    preferences(context).edit().remove("download-sha256").remove("download-tag").apply()
    file(context)?.delete()
  }

  // A file that is not the update it should be is dropped, so a retry downloads it again.
  private fun refuse(context: Context, message: Int): Nothing {
    forgetFile(context)
    error(text(context, message))
  }

  private fun commit(context: Context) {
    val file = file(context)?.takeIf { it.isFile } ?: refuse(context, R.string.update_storage)
    val expected = preferences(context).getString("download-sha256", null) ?: refuse(context, R.string.update_no_checksum)
    val manager = context.packageManager
    val archive = manager.getPackageArchiveInfo(file.path, 0) ?: refuse(context, R.string.update_wrong_package)
    if (archive.packageName != context.packageName) refuse(context, R.string.update_wrong_package)
    if (archive.longVersionCode <= manager.getPackageInfo(context.packageName, 0).longVersionCode)
      refuse(context, R.string.update_not_newer)
    val installer = manager.packageInstaller
    val parameters =
      PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(context.packageName)
        setSize(file.length())
        // Android installs without asking once Castor installed the version it updates; the first time it asks.
        if (Build.VERSION.SDK_INT >= 31)
          setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
      }
    val sessionId = installer.createSession(parameters)
    installer.openSession(sessionId).use { session ->
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        session.openWrite(Releases.APK, 0, file.length()).use { output ->
          val buffer = ByteArray(1 shl 16)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
          }
          session.fsync(output)
        }
      }
      if (hex(digest.digest()) != expected) {
        session.abandon()
        refuse(context, R.string.update_bad_checksum)
      }
      val status =
        PendingIntent.getBroadcast(
          context,
          sessionId,
          Intent(context, UpdateInstallReceiver::class.java).setAction(INSTALL_STATUS),
          // Android adds the result to this intent, so it has to stay mutable.
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
      session.commit(status.intentSender)
    }
    // The file stays until Android reports the update installed: a cancelled confirmation is retried from it.
  }

  // What Android's installer says about the update.
  fun installed(context: Context, result: Intent) {
    when (val code = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
      PackageInstaller.STATUS_PENDING_USER_ACTION -> {
        val confirm =
          IntentCompat.getParcelableExtra(result, Intent.EXTRA_INTENT, Intent::class.java)
            ?: return fail(context, text(context, R.string.update_cancelled))
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Android blocks activities started from the background; a notification asks for the tap instead.
        val opened = visible && runCatching { context.startActivity(confirm) }.isSuccess
        if (!opened)
          notify(
            context,
            text(context, R.string.update_ready_title),
            text(context, R.string.update_ready_body, release.value?.version.orEmpty()),
            PendingIntent.getActivity(
              context,
              1,
              confirm,
              PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
          )
      }
      PackageInstaller.STATUS_SUCCESS -> {
        forgetFile(context)
        preferences(context).edit().remove("release").remove("error").remove("waiting").apply()
        release.value = null
        status.value = UpdateStatus.Idle
        // An update installed without a prompt closes Castor silently; this says what happened.
        notify(
          context,
          text(context, R.string.update_done_title),
          text(context, R.string.update_done_body, installedVersion(context)),
          launch(context),
        )
      }
      PackageInstaller.STATUS_FAILURE_ABORTED -> fail(context, text(context, R.string.update_cancelled))
      else ->
        fail(
          context,
          result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.takeIf { it.isNotBlank() }
            ?: "PackageInstaller status $code",
        )
    }
  }

  private fun fail(context: Context, message: String) {
    preferences(context).edit().putString("error", message).remove("waiting").apply()
    status.value = UpdateStatus.Failed(message)
    if (!visible)
      notify(context, text(context, R.string.update_failed_title), message, launch(context))
  }

  // Where the download manager writes the release: Castor's own external files, which need no permission.
  private fun file(context: Context): File? =
    context.getExternalFilesDir(UPDATES)?.let { File(it, Releases.APK) }

  private fun launch(context: Context): PendingIntent =
    PendingIntent.getActivity(
      context,
      0,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  private fun notify(context: Context, title: String, body: String, tap: PendingIntent) {
    val notifications = context.getSystemService(NotificationManager::class.java)
    notifications.createNotificationChannel(
      NotificationChannel(CHANNEL, text(context, R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT)
    )
    runCatching {
      notifications.notify(
        NOTIFICATION,
        Notification.Builder(context, CHANNEL)
          .setSmallIcon(android.R.drawable.stat_sys_download_done)
          .setContentTitle(title)
          .setContentText(body)
          .setStyle(Notification.BigTextStyle().bigText(body))
          .setContentIntent(tap)
          .setAutoCancel(true)
          .build(),
      )
    }
  }

  private fun fetch(url: String, limit: Int): String =
    http
      .newCall(
        Request.Builder()
          .url(url)
          .header("Accept", "application/vnd.github+json")
          .header("User-Agent", "Castor-Android")
          .build()
      )
      .execute()
      .use { response ->
        check(response.isSuccessful) { "HTTP ${response.code}" }
        val source = response.body.source()
        source.request(limit + 1L)
        check(source.buffer.size <= limit) { "The answer is too large" }
        source.buffer.readUtf8()
      }

  private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

// The download manager's end of a download. Exported for the download manager, so any app can send it: it only
// names a download id, which counts only when it is the download Castor started and the manager says it finished.
class UpdateReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
    val application = context.applicationContext
    AppUpdates.load(application)
    // The file is copied into Android's installer before the broadcast is let go.
    val pending = goAsync()
    AppUpdates.downloaded(application, intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)) {
      pending.finish()
    }
  }
}

// The package installer's result. Not exported: its confirmation intent is started as Castor, so only the
// installer, through Castor's own pending intent, may deliver it.
class UpdateInstallReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != AppUpdates.INSTALL_STATUS) return
    val application = context.applicationContext
    AppUpdates.load(application)
    AppUpdates.installed(application, intent)
  }
}
