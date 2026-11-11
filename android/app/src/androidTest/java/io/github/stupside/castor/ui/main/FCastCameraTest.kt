package io.github.stupside.castor.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stupside.castor.theme.CastorTheme
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FCastCameraTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  @Before
  fun grantTheCamera() {
    assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
    instrumentation.uiAutomation.grantRuntimePermission(
      context.packageName,
      Manifest.permission.CAMERA,
    )
  }

  @Test
  fun theRealCameraFeedsFramesToTheDecoder() {
    val frames = AtomicInteger()
    compose.setContent {
      CastorTheme {
        QrCamera(
          onText = {},
          modifier = Modifier.fillMaxSize(),
          onFrame = { frames.incrementAndGet() },
        )
      }
    }
    compose.waitUntil(20_000) { frames.get() >= 10 }
    saveScreenshot("fcast-camera.png")
  }

  @Test
  fun theRealScannerOpensWithTheCameraBehindItsFrame() {
    compose.setContent { CastorTheme { FCastScanner("192.0.2.10", {}, {}, {}) } }
    compose.onNodeWithTag("fcast-frame").assertIsDisplayed()
    compose.onNodeWithTag("fcast-instruction").assertIsDisplayed()
    compose.onNodeWithTag("fcast-cancel").assertIsDisplayed()
    compose.onNodeWithTag("fcast-permission").assertDoesNotExist()
    saveScreenshot("fcast-scanner.png")
  }

  // Bound to the activity's lifecycle, the camera is released in the background and taken again on return.
  @Test
  fun theCameraStopsInTheBackgroundAndComesBack() {
    val frames = AtomicInteger()
    compose.setContent {
      CastorTheme {
        QrCamera(
          onText = {},
          modifier = Modifier.fillMaxSize(),
          onFrame = { frames.incrementAndGet() },
        )
      }
    }
    compose.waitUntil(20_000) { frames.get() >= 5 }
    compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
    Thread.sleep(1500)
    val paused = frames.get()
    Thread.sleep(2500)
    assertTrue("frames kept coming in the background: ${frames.get() - paused}", frames.get() - paused <= 1)
    compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
    val deadline = System.currentTimeMillis() + 20_000
    while (frames.get() < paused + 5 && System.currentTimeMillis() < deadline) {
      Thread.sleep(100)
    }
    assertTrue(frames.get() >= paused + 5)
  }

  // The capture is kept on the device for a reviewer to look at, and must not be empty.
  private fun saveScreenshot(name: String) {
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    val file = File(requireNotNull(context.getExternalFilesDir(null)), name)
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    assertTrue(file.length() > 0)
  }
}
