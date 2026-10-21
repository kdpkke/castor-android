package io.github.stupside.castor.ui.main

import castor.v1.DeviceOuterClass.Device
import castor.v1.DeviceOuterClass.DeviceType
import castor.v1.DeviceOuterClass.Target
import org.junit.Assert.*
import org.junit.Test

class MainScreenViewModelTest {
  private val target = Target.newBuilder().setDeviceId("living-room").build()

  @Test
  fun savedReceiverRequiresCurrentEvidenceAndRefreshesItsAddressByIdentity() {
    val device =
      Device.newBuilder()
        .setId("dlna:living-room")
        .setName("Living room")
        .setType(DeviceType.DEVICE_TYPE_DLNA)
        .setAddress("http://192.0.2.10:1938/")
        .build()
    val saved = SavedTv(device).copy(address = "http://192.0.2.10:1487/")
    assertNull(saved.available(emptyList()))
    assertEquals(SavedTv(device), saved.available(listOf(device)))
    assertNull(
      saved.available(
        listOf(device.toBuilder().setId("dlna:another-tv").setAddress(saved.address).build())
      )
    )
    assertNull(
      saved.available(listOf(device.toBuilder().setType(DeviceType.DEVICE_TYPE_ROKU).build()))
    )
  }

  @Test
  fun legacyAndManualEntriesAreConfirmedOnlyAtTheirSavedAddress() {
    val device =
      Device.newBuilder()
        .setId("dlna:living-room")
        .setName("Living room")
        .setType(DeviceType.DEVICE_TYPE_DLNA)
        .setAddress("http://192.0.2.10:1938/")
        .build()
    val legacy = SavedTv("Old preference", device.address, device.type)
    assertEquals(SavedTv(device), legacy.available(listOf(device)))
    assertNull(
      legacy.available(listOf(device.toBuilder().setAddress("http://192.0.2.10:1487/").build()))
    )
  }

  @Test
  fun discoveredTvKeepsItsIdentityWhenItsDescriptionPortChanges() {
    val first =
      Device.newBuilder()
        .setId("dlna:uuid:living-room")
        .setName("Living room")
        .setType(DeviceType.DEVICE_TYPE_DLNA)
        .setAddress("http://192.0.2.10:1487/")
        .build()
    val moved = first.toBuilder().setAddress("http://192.0.2.10:1938/").build()
    for (device in listOf(first, moved)) {
      val request =
        MainScreenViewModel.request(
          "https://example.org/video.mp4",
          false,
          SavedTv(device).target(),
          "1080",
        )
      assertEquals(first.id, request.target.deviceId)
      assertFalse(request.target.hasPinned())
    }
  }

  @Test
  fun manuallyEnteredTvStillUsesItsExactAddress() {
    val address = "http://192.0.2.10:1487/custom/description.xml"
    val saved = SavedTv("Manual receiver", address, DeviceType.DEVICE_TYPE_DLNA)
    val request =
      MainScreenViewModel.request("https://example.org/video.mp4", false, saved.target(), "1080")
    assertEquals(address, request.target.pinned.address)
    assertEquals(DeviceType.DEVICE_TYPE_DLNA, request.target.pinned.type)
    assertFalse(request.target.hasDeviceId())
  }

  @Test
  fun automaticSourceDetectionIgnoresQueryStringsAndHonorsOverrides() {
    for (url in
      listOf(
        "https://example.org/live.M3U8?token=private",
        "https://example.org/movie.mp4",
        "https://example.org/live.mpd#player",
      )) {
      assertFalse(VideoSourceMode.Automatic.isPage(url))
      assertTrue(VideoSourceMode.WebPage.isPage(url))
    }
    for (url in
      listOf(
        "https://dlive.sx/watch.php?id=877",
        "https://example.org/watch?file=movie.mp4",
        "https://example.org/player",
      )) {
      assertTrue(VideoSourceMode.Automatic.isPage(url))
      assertFalse(VideoSourceMode.DirectStream.isPage(url))
    }
  }

  @Test
  fun pageAndDirectStreamHaveDifferentContractSources() {
    val page = MainScreenViewModel.request("https://example.org/watch", true, target, "720")
    val direct =
      MainScreenViewModel.request("https://example.org/video.m3u8", false, target, "2160")
    assertEquals(listOf("https://example.org/watch"), page.source.pages.urlsList)
    assertFalse(page.source.hasStream())
    assertEquals("https://example.org/video.m3u8", direct.source.stream.url)
    assertFalse(direct.source.hasPages())
    assertEquals(2160, direct.preferences.maxHeight)
    assertEquals(target, direct.target)
  }

  @Test
  fun nativeQualityOverridesTheServers1080DefaultWithoutChangingTheSource() {
    val url = "https://example.org/selected-1440p.m3u8"
    val native = MainScreenViewModel.request(url, false, target, "native")
    assertTrue(native.preferences.hasMaxHeight())
    assertEquals(Int.MAX_VALUE, native.preferences.maxHeight)
    assertEquals(url, native.source.stream.url)
    assertTrue(native.preferences.subtitles.hasDisabled())
    val limited = MainScreenViewModel.request(url, false, target, "720")
    assertEquals(720, limited.preferences.maxHeight)
  }

  @Test
  fun invalidInputsAreRejectedWithoutBeingRewritten() {
    for (url in
      listOf(
        "file:///sdcard/movie.mp4",
        "javascript:alert(1)",
        "https://u:p@example.org/video",
        "example.org",
      )) {
      assertThrows(IllegalArgumentException::class.java) {
        MainScreenViewModel.request(url, false, target, "1080")
      }
    }
    for (height in listOf("", "0", "1", "1080.5", "overflow")) {
      assertThrows(IllegalArgumentException::class.java) {
        MainScreenViewModel.request("https://example.org", false, target, height)
      }
    }
    assertThrows(IllegalArgumentException::class.java) {
      MainScreenViewModel.request("https://example.org", true, Target.getDefaultInstance(), "1080")
    }
  }

  @Test
  fun subtitleModesStayDistinctAtTheContract() {
    fun selection(value: String) =
      MainScreenViewModel.request("https://example.org/watch", true, target, "720", value)
        .preferences
        .subtitles
    assertTrue(selection("off").hasDisabled())
    assertEquals(listOf("en"), selection("auto").preferences.languagesList)
    assertEquals(listOf("it"), selection("it").preferences.languagesList)
    assertTrue(selection("it").preferences.transcribeIfMissing)
    val italian =
      MainScreenViewModel.request("https://example.org/watch", true, target, "720", "auto", "it")
    assertEquals(listOf("it"), italian.preferences.subtitles.preferences.languagesList)
    assertTrue(italian.preferences.subtitles.preferences.transcribeIfMissing)
    assertThrows(IllegalArgumentException::class.java) { selection("italian") }
  }
}
