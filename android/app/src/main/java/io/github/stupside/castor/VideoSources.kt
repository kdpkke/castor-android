package io.github.stupside.castor

import castor.v1.CastOuterClass.Source
import castor.v1.StreamOuterClass.Stream
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

// A download endpoint need not have a video extension; ask for headers, never its whole body.
internal object VideoSources {
  private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()

  suspend fun automatic(url: String): Source {
    try {
      val head = client.newCall(Request.Builder().url(url).head().build()).response()
      head.use { response ->
        if (response.isSuccessful) {
          detectedType(response.header("Content-Type"), response.header("Content-Disposition"))
            .takeIf { it.isNotBlank() }
            ?.let {
              return direct(url, it)
            }
          if (response.header("Content-Type").orEmpty().startsWith("text/html", true))
            return page(url)
        }
      }
      client
        .newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build())
        .response()
        .use {
          if (it.isSuccessful) {
            detectedType(it.header("Content-Type"), it.header("Content-Disposition"))
              .takeIf { type -> type.isNotBlank() }
              ?.let { type ->
                return direct(url, type)
              }
          }
        }
    } catch (_: IOException) {
      currentCoroutineContext().ensureActive()
    }
    return page(url)
  }

  fun direct(url: String, type: String = ""): Source =
    Source.newBuilder().setStream(Stream.newBuilder().setUrl(url).setContentType(type)).build()

  private fun page(url: String): Source =
    Source.newBuilder().setPages(Source.Pages.newBuilder().addUrls(url)).build()

  internal fun detectedType(mime: String?, disposition: String?): String {
    val type = mime.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    if (type == "text/html" || type == "application/xhtml+xml") return ""
    if (type == "application/x-mpegurl") return "application/vnd.apple.mpegurl"
    if (
      type.matches(Regex("video/[a-z0-9.+-]+")) ||
        type in listOf("application/vnd.apple.mpegurl", "application/dash+xml")
    )
      return type
    val extended =
      Regex("(?:^|;)\\s*filename\\*\\s*=\\s*UTF-8'[^']*'([^;]*)", RegexOption.IGNORE_CASE)
        .find(disposition.orEmpty())
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.let {
          runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull()
        }
    val filename =
      extended
        ?: Regex("(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))", RegexOption.IGNORE_CASE)
          .find(disposition.orEmpty())
          ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] }.trim() }
    return filename?.let(::contentType).orEmpty()
  }

  fun contentType(name: String): String =
    when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
      "mp4",
      "m4v" -> "video/mp4"
      "mkv" -> "video/x-matroska"
      "webm" -> "video/webm"
      "mov" -> "video/quicktime"
      "avi" -> "video/x-msvideo"
      "ts" -> "video/mp2t"
      "m3u8" -> "application/vnd.apple.mpegurl"
      "mpd" -> "application/dash+xml"
      else -> ""
    }

  private suspend fun Call.response(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
      object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
          continuation.resume(response) { _, value, _ -> value.close() }
        }
      }
    )
  }
}
