package io.github.stupside.castor.ui.main

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import castor.v1.DeviceOuterClass.DeviceType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FCastPairingStoreTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val preferences = context.getSharedPreferences("casting-options", Context.MODE_PRIVATE)
  private var saved: Map<String, Any?> = emptyMap()

  // Every entry of the preferences is set aside, so a test leaves no choice behind and loses none of the user's.
  @Before
  fun clearPreferences() {
    saved = preferences.all
    preferences.edit().clear().commit()
  }

  @After
  fun restorePreferences() {
    val edit = preferences.edit().clear()
    for ((key, value) in saved) {
      when (value) {
        is String -> edit.putString(key, value)
        is Boolean -> edit.putBoolean(key, value)
        is Int -> edit.putInt(key, value)
        is Long -> edit.putLong(key, value)
        is Float -> edit.putFloat(key, value)
        is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
      }
    }
    edit.commit()
  }

  @Test
  fun aPairingIsStillThereForANewCastOptions() {
    val pairing = pairing(setOf(CHROMECAST_LIVING))
    CastOptions(context).pairFCast(pairing)
    context.getSharedPreferences("casting-options", Context.MODE_PRIVATE).edit().commit()
    assertEquals(listOf(pairing), CastOptions(context).fcastPairings())
  }

  @Test
  fun aSecondScanOfTheSameTVReplacesTheFirst() {
    CastOptions(context).pairFCast(pairing(setOf(CHROMECAST_LIVING), port = 46899))
    val second = pairing(setOf(CHROMECAST_LIVING), host = "192.0.2.11", port = 46900)
    CastOptions(context).pairFCast(second)
    context.getSharedPreferences("casting-options", Context.MODE_PRIVATE).edit().commit()
    assertEquals(listOf(second), CastOptions(context).fcastPairings())
  }

  @Test
  fun aPairingWithoutIdentitiesIsNotKept() {
    CastOptions(context).pairFCast(pairing(emptySet()))
    preferences.edit().commit()
    assertFalse(preferences.contains("fcast-pairings"))
    assertTrue(CastOptions(context).fcastPairings().isEmpty())
  }

  @Test
  fun aPairedChoiceKeepsItsTVsIdentitiesAndAChoiceForNowIsNeverSaved() {
    val paired =
      SavedTv(
        "Receiver",
        "192.0.2.10",
        DeviceType.DEVICE_TYPE_FCAST,
        pairedFor = setOf("2|chromecast:a", "1|dlna:a"),
      )
    CastOptions(context).television(paired)
    preferences.edit().commit()
    assertEquals(paired, CastOptions(context).television())

    CastOptions(context)
      .television(
        SavedTv("Other", "192.0.2.30", DeviceType.DEVICE_TYPE_FCAST, manual = true, once = true)
      )
    preferences.edit().commit()
    assertEquals(paired, CastOptions(context).television())
  }

  private fun pairing(
    identities: Set<String>,
    host: String = "192.0.2.10",
    port: Int = 46899,
  ): FCastPairing = FCastPairing(identities, "Receiver", port, null, null, host)

  private companion object {
    val CHROMECAST_LIVING = "${DeviceType.DEVICE_TYPE_CHROMECAST.number}|chromecast:living"
  }
}
