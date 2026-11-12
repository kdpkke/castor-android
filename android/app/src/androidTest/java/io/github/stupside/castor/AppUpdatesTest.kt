package io.github.stupside.castor

import android.app.NotificationManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stupside.castor.ui.main.CastOptions
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// The update path from Android's download manager to the checks before Android's installer, with a file that is
// not this app: it has to stop at the package check, before anything is handed to the installer.
class AppUpdatesTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val preferences = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
  private var saved: Map<String, *> = emptyMap<String, Any>()
  private val server = MockWebServer()
  private val served = java.util.concurrent.CopyOnWriteArrayList<String>()

  @Before
  fun setAside() {
    saved = preferences.all
    preferences.edit().clear().commit()
  }

  @After
  fun restore() {
    AppUpdates.cancel(context)
    AppUpdates.release.value = null
    AppUpdates.status.value = UpdateStatus.Idle
    context.getSystemService(NotificationManager::class.java).cancel(2)
    val edit = preferences.edit().clear()
    for ((key, value) in saved) {
      when (value) {
        is String -> edit.putString(key, value)
        is Boolean -> edit.putBoolean(key, value)
        is Long -> edit.putLong(key, value)
        is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
      }
    }
    edit.commit()
    server.shutdown()
  }

  @Test
  fun aDownloadedFileOfAnotherAppIsRefusedBeforeTheInstaller() = runBlocking {
    // The test APK: a real, readable package, but not this app.
    val apk = File(InstrumentationRegistry.getInstrumentation().context.packageCodePath).readBytes()
    val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          when (request.requestUrl!!.encodedPath.also { served += it }) {
            "/castor-android.apk" -> MockResponse().setBody(Buffer().write(apk))
            "/SHA256SUMS.txt" -> MockResponse().setBody("$sha  castor-android.apk\n")
            else -> MockResponse().setResponseCode(404)
          }
      }
    server.start()
    AppUpdates.load(context)
    AppUpdates.status.value = UpdateStatus.Idle
    AppUpdates.release.value =
      Release(
        "android-v99.0.0",
        "99.0.0",
        "Castor Android 99.0.0",
        "",
        server.url("/").toString(),
        server.url("/castor-android.apk").toString(),
        apk.size.toLong(),
        server.url("/SHA256SUMS.txt").toString(),
      )
    AppUpdates.update(context)
    val ended = withTimeoutOrNull(90_000) {
      while (AppUpdates.status.value !is UpdateStatus.Failed) delay(200)
    }
    assertNotNull("The update never ended: ${AppUpdates.status.value}", ended)
    assertEquals(
      UpdateStatus.Failed(CastOptions(context).localizedContext().getString(R.string.update_wrong_package)),
      AppUpdates.status.value,
    )
    assertTrue("The download manager fetched the file: $served", "/castor-android.apk" in served)
    assertFalse("A refused file is not kept", preferences.contains("download-sha256"))
    assertTrue(
      "Nothing was left for the installer",
      context.packageManager.packageInstaller.mySessions.isEmpty(),
    )
  }
}
