package com.muratoreware.castorandroid

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DebugLogTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private var before = false

  @Before
  fun remember() {
    before = DebugLog.enabled(context)
  }

  @After
  fun restore() {
    DebugLog.enable(context, before)
  }

  private fun log(): String {
    val uri = DebugLog.uri(context)
    assertNotNull("No debug log was written", uri)
    return context.contentResolver.openInputStream(uri!!)!!.use { it.readBytes().decodeToString() }
  }

  @Test
  fun keepsThePhoneCastorsLinesAndTheEngineInAFileTheUserCanFind() {
    Log.i("CastorTest", "before debug mode")
    DebugLog.enable(context, true)
    val marker = "debug-log-${System.nanoTime()}"
    // As the service hands on each line of the engine's output.
    Log.i(DebugLog.ENGINE_TAG, "engine $marker")
    DebugLog.engine("engine $marker")
    Log.i("CastorTest", "app $marker")

    val deadline = System.currentTimeMillis() + 10_000
    var text = log()
    while (!text.contains("app $marker") && System.currentTimeMillis() < deadline) {
      Thread.sleep(200)
      text = log()
    }

    if (Build.VERSION.SDK_INT >= 29)
      assertTrue(DebugLog.location(context)!!, DebugLog.location(context)!!.startsWith("Download/Castor/castor-debug"))
    assertTrue(text, text.contains("===== Castor debug log ====="))
    assertTrue(text, text.contains("Device:         ${Build.MANUFACTURER} ${Build.MODEL}"))
    assertTrue(text, text.contains("Android:        ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
    // Lines logged before debug mode was turned on, in the same run of Castor, are kept too.
    assertTrue(text, text.contains("I CastorTest: before debug mode"))
    assertTrue(text, text.contains("I CastorTest: app $marker"))
    // The engine's lines are written once, straight from its output.
    assertEquals(text, 1, Regex("I ${DebugLog.ENGINE_TAG}: engine $marker").findAll(text).count())
  }

  @Test
  fun turningItOnAgainStartsANewFileAndTurningItOffKeepsIt() {
    DebugLog.enable(context, true)
    DebugLog.engine("old line")
    DebugLog.enable(context, false)
    DebugLog.engine("written while off")
    assertTrue(log().contains("old line"))
    assertTrue(!log().contains("written while off"))

    DebugLog.enable(context, true)
    assertTrue(!log().contains("old line"))
    assertNotNull(DebugLog.share(context))
  }
}
