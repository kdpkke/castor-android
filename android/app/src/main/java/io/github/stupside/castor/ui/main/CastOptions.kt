package io.github.stupside.castor.ui.main

import android.content.Context
import android.content.res.Configuration
import castor.v1.DeviceOuterClass.Device
import castor.v1.DeviceOuterClass.DeviceType
import castor.v1.DeviceOuterClass.Target
import io.github.stupside.castor.theme.AppTheme
import java.net.URI
import java.util.Locale

// The existing RPC accepts a height ceiling. Its largest signed value leaves source dimensions
// untouched without inheriting the server's default 1080p ceiling.
internal const val NATIVE_QUALITY = "native"
internal const val NATIVE_HEIGHT_CEILING = Int.MAX_VALUE

internal val receiverTypes =
  listOf(
    DeviceType.DEVICE_TYPE_DLNA,
    DeviceType.DEVICE_TYPE_CHROMECAST,
    DeviceType.DEVICE_TYPE_ROKU,
  )

enum class VideoSourceMode {
  Automatic,
  WebPage,
  DirectStream;

  fun isPage(url: String): Boolean =
    when (this) {
      WebPage -> true
      DirectStream -> false
      Automatic -> {
        val extension =
          runCatching { URI(url).path.orEmpty().substringAfterLast('.').lowercase(Locale.ROOT) }
            .getOrDefault("")
        extension !in setOf("m3u8", "mpd", "mp4", "m4v", "webm", "mkv", "ts", "mov")
      }
    }
}

data class SavedTv(
  val name: String,
  val address: String,
  val type: DeviceType,
  val id: String = "",
) {
  constructor(
    device: Device
  ) : this(device.name.ifBlank { device.address }, device.address, device.type, device.id)

  // A saved address is a hint, not proof that the receiver is still there. Prefer its stable
  // discovery identity; never substitute another receiver that happens to reuse its address.
  fun available(devices: List<Device>): SavedTv? =
    devices
      .firstOrNull {
        it.type == type &&
          it.address.isNotBlank() &&
          if (id.isNotBlank()) it.id == id else it.address == address
      }
      ?.let(::SavedTv)

  fun target(): Target =
    Target.newBuilder()
      .apply {
        if (id.isNotBlank()) deviceId = id
        else setPinned(Target.Pinned.newBuilder().setAddress(address).setType(type))
      }
      .build()
}

enum class AppLanguage(val tag: String, val nativeName: String) {
  English("en", "English"),
  Italian("it", "Italiano"),
}

// Only app preferences persist here; video URLs are not written to these preferences.
class CastOptions(private val context: Context) {
  private val preferences = context.getSharedPreferences("casting-options", Context.MODE_PRIVATE)

  fun language(): AppLanguage =
    AppLanguage.entries.firstOrNull { it.tag == preferences.getString("language", "en") }
      ?: AppLanguage.English

  fun language(value: AppLanguage) {
    preferences.edit().putString("language", value.tag).apply()
  }

  fun theme(): AppTheme =
    AppTheme.entries.firstOrNull { it.preference == preferences.getString("theme", null) }
      ?: AppTheme.Dark

  fun theme(value: AppTheme) {
    preferences.edit().putString("theme", value.preference).apply()
  }

  fun localizedContext(): Context =
    context.createConfigurationContext(
      Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(language().tag))
      }
    )

  fun television(): SavedTv? {
    val address = preferences.getString("tv-address", "").orEmpty()
    if (address.isBlank()) return null
    val type =
      DeviceType.forNumber(preferences.getInt("tv-type", DeviceType.DEVICE_TYPE_DLNA_VALUE))
    if (type !in receiverTypes) return null
    return SavedTv(
      preferences.getString("tv-name", address).orEmpty(),
      address,
      type,
      preferences.getString("tv-id", "").orEmpty(),
    )
  }

  fun television(tv: SavedTv) {
    preferences
      .edit()
      .putString("tv-name", tv.name)
      .putString("tv-address", tv.address)
      .putString("tv-id", tv.id)
      .putInt("tv-type", tv.type.number)
      .apply()
  }

  fun height(): String = preferences.getString("height", "1080").orEmpty()

  fun subtitles(): String = preferences.getString("subtitles", "off").orEmpty()

  fun playback(height: String, subtitles: String) {
    preferences.edit().putString("height", height).putString("subtitles", subtitles).apply()
  }
}
