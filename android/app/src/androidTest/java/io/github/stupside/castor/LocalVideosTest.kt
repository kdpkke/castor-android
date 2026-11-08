package io.github.stupside.castor

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
class LocalVideosTest {
  @Test
  fun selectedContentIsPrivateSeekableAndRevokedWhenTheCastEnds() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File.createTempFile("castor-local", ".mp4", context.cacheDir)
    val bytes = ByteArray(262144) { (it % 251).toByte() }
    file.writeBytes(bytes)
    val resolver = context.contentResolver
    val selected = videoFixture(context, "My video.mp4", bytes)
    val browser = withContext(Dispatchers.Main) { BrowserResolver(context) }
    val bridge = BrowserServer(browser, { emptyList() }, "rpc-secret", resolver)
    val client = OkHttpClient()
    try {
      val video = LocalVideos.describe(resolver, selected.uri)
      assertEquals("My video.mp4", video.name)
      assertEquals("video/mp4", video.contentType)
      val url = bridge.videos!!.register(video)
      fun read(range: String? = null, head: Boolean = false) =
        client
          .newCall(
            Request.Builder()
              .url(url)
              .apply {
                if (head) head()
                range?.let { header("Range", it) }
              }
              .build()
          )
          .execute()
      read(head = true).use {
        assertEquals(200, it.code)
        assertEquals(bytes.size.toString(), it.header("Content-Length"))
        assertEquals(0, it.body.bytes().size)
      }
      for ((header, expected) in
        listOf(
          "bytes=4-99" to bytes.copyOfRange(4, 100),
          "bytes=-9" to bytes.takeLast(9).toByteArray(),
          "bytes=262140-" to bytes.copyOfRange(262140, bytes.size),
        )) {
        read(header).use {
          assertEquals(206, it.code)
          assertArrayEquals(expected, it.body.bytes())
        }
      }
      read().use { assertArrayEquals(bytes, it.body.bytes()) }
      read("bytes=999999-").use {
        assertEquals(416, it.code)
        assertEquals("bytes */${bytes.size}", it.header("Content-Range"))
      }
      client
        .newCall(Request.Builder().url(bridge.url + "/files/forged/video.mp4").build())
        .execute()
        .use {
          assertEquals(404, it.code)
        }
      bridge.playbackEnded()
      read().use { assertEquals(404, it.code) }
    } finally {
      bridge.close()
      withContext(Dispatchers.Main) { browser.close() }
      resolver.delete(selected.uri, null, null)
      file.delete()
    }
  }
}

// A document owned by this test app exercises the real Android ContentResolver without storage
// access.
internal fun videoFixture(
  context: android.content.Context,
  name: String,
  bytes: ByteArray,
): LocalVideo {
  val resolver = context.contentResolver
  val values =
    ContentValues().apply {
      put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name)
      put(android.provider.MediaStore.Video.Media.MIME_TYPE, VideoSources.contentType(name))
      put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
    }
  val uri =
    checkNotNull(
      resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    )
  try {
    checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
    values.clear()
    values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return LocalVideos.describe(resolver, uri)
  } catch (error: Exception) {
    resolver.delete(uri, null, null)
    throw error
  }
}
