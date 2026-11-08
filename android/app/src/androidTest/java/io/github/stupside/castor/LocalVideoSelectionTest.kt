package io.github.stupside.castor

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stupside.castor.theme.AppTheme
import io.github.stupside.castor.ui.main.AppLanguage
import io.github.stupside.castor.ui.main.CastOptions
import io.github.stupside.castor.ui.main.MainScreenViewModel
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

@SdkSuppress(minSdkVersion = 29)
class LocalVideoSelectionTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  private fun shell(command: String) {
    instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
      android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
  }

  private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = buildList {
    if (node != null) {
      add(node)
      for (index in 0 until node.childCount) addAll(nodes(node.getChild(index)))
    }
  }

  private suspend fun click(label: String) {
    val clicked =
      withTimeoutOrNull(15_000) {
        while (true) {
          val matches =
            nodes(instrumentation.uiAutomation.rootInActiveWindow).filter {
              it.text?.toString() == label || it.contentDescription?.toString() == label
            }
          for (match in matches) {
            var node: AccessibilityNodeInfo? = match
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
              return@withTimeoutOrNull true
          }
          delay(100)
        }
      }
    if (clicked != true) {
      capture("picker-failure")
      error(
        "Could not tap '$label': ${nodes(instrumentation.uiAutomation.rootInActiveWindow).mapNotNull { it.text ?: it.contentDescription }}"
      )
    }
  }

  private suspend fun capture(name: String, visibleText: String = "") {
    if (visibleText.isNotEmpty())
      withTimeout(15_000) {
        while (
          nodes(instrumentation.uiAutomation.rootInActiveWindow).none {
            it.text?.toString() == visibleText
          }
        ) delay(100)
      }
    instrumentation.waitForIdleSync()
    instrumentation.uiAutomation.waitForIdle(500, 5000)
    android.os.SystemClock.sleep(250)
    val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
      val file = File(context.getExternalFilesDir(null), "video-review/$name.png")
      file.parentFile!!.mkdirs()
      file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } finally {
      screenshot.recycle()
    }
  }

  @Test
  fun documentPickerSelectionKeepsReadAccessAcrossLanguageAndAppearanceChanges() = runBlocking {
    for (permission in
      listOf("android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_LOCAL_NETWORK")) {
      shell("pm grant ${context.packageName} $permission")
    }
    val options = CastOptions(context)
    options.language(AppLanguage.English)
    options.theme(AppTheme.Dark)
    val beforeScale = context.resources.configuration.fontScale
    val sample = File.createTempFile("castor-picker", ".mp4", context.cacheDir)
    val encoder =
      ProcessBuilder(
          "${context.applicationInfo.nativeLibraryDir}/libffmpeg.so",
          "-y",
          "-hide_banner",
          "-f",
          "lavfi",
          "-i",
          "color=size=16x16:rate=1",
          "-frames:v",
          "1",
          "-c:v",
          "libx264",
          "-pix_fmt",
          "yuv420p",
          "-movflags",
          "+faststart",
          sample.path,
        )
        .apply { environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir }
        .redirectErrorStream(true)
        .start()
    val log = encoder.inputStream.bufferedReader().readText()
    assertEquals(log, 0, encoder.waitFor())
    val video = videoFixture(context, "Castor picker.mp4", sample.readBytes())
    try {
      ActivityScenario.launch(MainActivity::class.java).use { activity ->
        lateinit var model: MainScreenViewModel
        activity.onActivity { model = ViewModelProvider(it)[MainScreenViewModel::class.java] }
        withTimeout(60_000) { while (!model.uiState.value.ready) delay(100) }
        capture("video-home-dark", "Ready")
        click("Choose video")
        click("Show roots")
        click("Recent")
        click(video.name)
        withTimeout(10_000) { while (model.selectedVideo.value == null) delay(100) }
        val selected = model.selectedVideo.value!!
        assertTrue(
          "The picker must grant lasting read access",
          context.contentResolver.persistedUriPermissions.any {
            it.uri == selected.uri && it.isReadPermission
          },
        )
        capture("video-local-dark", selected.name)
        options.language(AppLanguage.Italian)
        options.theme(AppTheme.Light)
        shell("settings put system font_scale 1.3")
        activity.recreate()
        activity.onActivity {
          assertSame(model, ViewModelProvider(it)[MainScreenViewModel::class.java])
          assertEquals(selected, model.selectedVideo.value)
          assertNull(model.uiState.value.error)
        }
        context.contentResolver.openAssetFileDescriptor(selected.uri, "r")!!.use {
          assertEquals(sample.length(), it.length)
        }
        capture("video-local-light-large-it", "Su questo dispositivo")
        click("Altre opzioni")
        capture("video-menu-light-large-it", "Lingua dell’app")
      }
    } finally {
      context.stopService(Intent(context, CastorService::class.java))
      context.contentResolver.delete(video.uri, null, null)
      sample.delete()
      options.language(AppLanguage.English)
      options.theme(AppTheme.Dark)
      shell("settings put system font_scale $beforeScale")
    }
  }
}
