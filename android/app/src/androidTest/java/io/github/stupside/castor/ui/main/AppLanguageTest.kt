package io.github.stupside.castor.ui.main

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.stupside.castor.CastorService
import io.github.stupside.castor.MainActivity
import io.github.stupside.castor.R
import io.github.stupside.castor.theme.AppTheme
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class AppLanguageTest {
  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  @get:Rule(order = 0)
  val setup =
    object : ExternalResource() {
      override fun before() {
        context.getSharedPreferences("casting-options", 0).edit().clear().commit()
        for (permission in
          buildList {
            if (android.os.Build.VERSION.SDK_INT >= 33) add("android.permission.POST_NOTIFICATIONS")
            if (android.os.Build.VERSION.SDK_INT >= 37)
              add("android.permission.ACCESS_LOCAL_NETWORK")
          }) {
          val descriptor =
            InstrumentationRegistry.getInstrumentation()
              .uiAutomation
              .executeShellCommand("pm grant ${context.packageName} $permission")
          ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
      }

      override fun after() {
        context.stopService(Intent(context, CastorService::class.java))
        context.getSharedPreferences("casting-options", 0).edit().clear().commit()
      }
    }

  @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun appearanceDefaultsToDarkAndPersistsWithoutLosingTheDraftOrCast() {
    fun resumed(): Activity? {
      var activity: Activity? = null
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        activity =
          ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .firstOrNull()
      }
      return activity
    }
    fun nightActive(activity: Activity) =
      (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    fun night(mode: String) {
      val before = resumed()
      val target =
        when (mode) {
          "yes" -> true
          "no" -> false
          else -> null
        }
      val descriptor =
        InstrumentationRegistry.getInstrumentation()
          .uiAutomation
          .executeShellCommand("cmd uimode night $mode")
      ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
      // The system recreates the activity after the command returns. waitForIdle during that
      // hand-over can wait forever on a frame posted to the view being detached, so it waits for
      // the replacement first.
      if (before != null && target != nightActive(before)) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (true) {
          val current = resumed()
          if (
            current != null &&
              current !== before &&
              (target == null || nightActive(current) == target)
          )
            break
          if (SystemClock.uptimeMillis() > deadline) {
            // "auto" can resolve to the mode already shown, which recreates nothing.
            if (target != null) fail("MainActivity was not recreated for night mode $mode")
            break
          }
          SystemClock.sleep(50)
        }
      }
      compose.waitForIdle()
    }
    fun assertDark(expected: Boolean) {
      compose.waitUntil(10_000) {
        var dark = false
        compose.activityRule.scenario.onActivity {
          dark =
            !WindowCompat.getInsetsController(it.window, it.window.decorView)
              .isAppearanceLightStatusBars
        }
        dark == expected
      }
    }
    fun openAppearance() {
      compose.onNodeWithContentDescription("More options").performClick()
      compose.onNodeWithText("Appearance").performClick()
    }
    val previousNight = context.getSystemService(android.app.UiModeManager::class.java).nightMode
    try {
      night("no")
      compose.waitUntil(20_000) {
        compose.onAllNodesWithText("Ready").fetchSemanticsNodes().isNotEmpty()
      }
      assertEquals(AppTheme.Dark, CastOptions(context).theme())
      assertDark(true)
      compose.onNodeWithTag("video-url").performTextInput("https://example.org/theme-draft")
      Espresso.closeSoftKeyboard()
      capture("theme-home-dark")
      openAppearance()
      compose.onNodeWithText("Dark").assertIsSelected()
      capture("theme-picker-dark")
      compose.onNodeWithText("System").performClick()
      compose.onNodeWithText("System").assertIsSelected()
      assertDark(false)
      capture("theme-picker-system-light")
      night("yes")
      assertDark(true)
      compose.onNodeWithText("System").assertIsSelected()
      compose.onNodeWithText("Light").performClick()
      compose.onNodeWithText("Light").assertIsSelected()
      assertDark(false)
      compose.activityRule.scenario.recreate()
      assertDark(false)
      assertEquals(AppTheme.Light, CastOptions(context).theme())
      compose.onNodeWithText("Light").assertIsSelected()
      compose.onNodeWithContentDescription("Close").performClick()
      compose.onNodeWithText("https://example.org/theme-draft").assertExists()
      openAppearance()
      compose.onNodeWithText("Light").assertIsSelected()
      capture("theme-picker-light")
      compose.onNodeWithText("Dark").performClick()
      compose.onNodeWithText("Dark").assertIsSelected()
      assertDark(true)
      lateinit var model: MainScreenViewModel
      compose.activityRule.scenario.onActivity {
        model = ViewModelProvider(it)[MainScreenViewModel::class.java]
        // Explicit synthetic active state: theme selection must leave the live model untouched.
        model.uiState.value =
          model.uiState.value.copy(castId = "theme-fixture", phase = CastPhase.Casting)
      }
      compose.onNodeWithText("Light").performClick()
      compose.onNodeWithText("Light").assertIsSelected()
      assertDark(false)
      compose.activityRule.scenario.onActivity {
        assertSame(model, ViewModelProvider(it)[MainScreenViewModel::class.java])
        assertEquals("theme-fixture", model.uiState.value.castId)
        assertEquals(CastPhase.Casting, model.uiState.value.phase)
        assertNull(model.uiState.value.error)
      }
      compose.onNodeWithContentDescription("Close").performClick()
      compose.onNodeWithText("Appearance").assertDoesNotExist()
      openAppearance()
      compose.onNodeWithText("Light").assertIsSelected()
      Espresso.pressBack()
      compose.onNodeWithText("Appearance").assertDoesNotExist()
    } finally {
      night(
        when (previousNight) {
          android.app.UiModeManager.MODE_NIGHT_YES -> "yes"
          android.app.UiModeManager.MODE_NIGHT_NO -> "no"
          else -> "auto"
        }
      )
    }
  }

  @Test
  fun englishDefaultAndSavedLanguageChoicePreserveDraftAndRunningEngine() {
    val italianContext =
      context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.ITALIAN) }
      )
    assertEquals(AppLanguage.English, CastOptions(italianContext).language())
    assertEquals(
      "App language",
      CastOptions(italianContext).localizedContext().getString(R.string.app_language),
    )
    compose.waitUntil(20_000) {
      compose.onAllNodesWithText("Ready").fetchSemanticsNodes().isNotEmpty()
    }
    val connected = CountDownLatch(1)
    var service: CastorService? = null
    val connection =
      object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
          service = (binder as CastorService.LocalBinder).service
          connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {}
      }
    assertTrue(context.bindService(Intent(context, CastorService::class.java), connection, 0))
    try {
      assertTrue(connected.await(5, TimeUnit.SECONDS))
      val endpoint = service!!.endpoint.value
      assertNotNull(endpoint)
      compose.onNodeWithTag("video-url").performTextInput("https://example.org/shared-video")
      Espresso.closeSoftKeyboard()
      compose.onNodeWithContentDescription("More options").performClick()
      compose.onNodeWithText("App language").performClick()
      compose.onNodeWithText("English").assertExists()
      capture("language-en")
      compose.onNodeWithText("Italiano").performClick()
      compose.waitUntil(10_000) {
        compose.onAllNodesWithText("Lingua dell’app").fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithText("Italiano").assertIsSelected()
      compose.activityRule.scenario.onActivity {
        assertEquals("it", it.resources.configuration.locales[0].language)
      }
      assertEquals(AppLanguage.Italian, CastOptions(context).language())
      capture("language-it")
      compose.activityRule.scenario.recreate()
      compose.onNodeWithText("Italiano").assertIsSelected()
      compose.onNodeWithText("English").performClick()
      compose.waitUntil(10_000) {
        compose.onAllNodesWithText("App language").fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithText("English").assertIsSelected().performClick().assertIsSelected()
      compose.onNodeWithText("Italiano").performClick()
      compose.waitUntil(10_000) {
        compose.onAllNodesWithText("Lingua dell’app").fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithText("Italiano").assertIsSelected()
      compose.onNodeWithContentDescription("Chiudi").performClick()
      compose.onNodeWithText("Qualità nativa · Sottotitoli: Disattivati").assertExists()
      capture("home-it")
      compose.onNodeWithText("https://example.org/shared-video").assertExists()
      compose.onNodeWithContentDescription("Altre opzioni").performClick()
      compose.onNodeWithText("Lingua dell’app").performClick()
      compose.onNodeWithText("Italiano").assertIsSelected()
      compose.onNodeWithText("English").performClick()
      compose.waitUntil(10_000) {
        compose.onAllNodesWithText("App language").fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithText("English").assertIsSelected()
      assertEquals(AppLanguage.English, CastOptions(context).language())
      assertSame(
        "Changing language must keep the running engine",
        endpoint,
        service!!.endpoint.value,
      )
      assertFalse(service!!.stopped.value)
      compose.onNodeWithContentDescription("Close").performClick()
      compose.onNodeWithText("https://example.org/shared-video").assertExists()
      capture("home-en")
      compose.activityRule.scenario.recreate()
      compose.onNodeWithText("App language").assertDoesNotExist()
      compose.onNodeWithText("Paste link").assertExists()
      compose.onNodeWithText("Native quality · Subtitles: Off").assertExists()
    } finally {
      context.unbindService(connection)
    }
  }

  private fun capture(name: String) {
    compose.waitForIdle()
    android.os.SystemClock.sleep(250)
    val file = File(context.getExternalFilesDir(null), "design-review/$name.png")
    file.parentFile!!.mkdirs()
    val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
    try {
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } finally {
      bitmap.recycle()
    }
  }
}
