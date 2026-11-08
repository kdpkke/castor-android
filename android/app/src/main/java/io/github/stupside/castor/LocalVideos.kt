package io.github.stupside.castor

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.OpenableColumns
import java.io.InputStream
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LocalVideo(val uri: Uri, val name: String, val contentType: String)

// Only files explicitly selected by the owner receive a capability on the private bridge.
class LocalVideos(private val resolver: ContentResolver, private val baseUrl: String) :
  AutoCloseable {
  private val videos = ConcurrentHashMap<String, LocalVideo>()
  private val connections = ConcurrentHashMap.newKeySet<Transfer>()

  private class Transfer(val socket: Socket) : AutoCloseable {
    val cancellation = CancellationSignal()
    private var input: InputStream? = null

    @Synchronized
    fun attach(stream: InputStream) {
      if (cancellation.isCanceled) {
        stream.close()
        throw OperationCanceledException()
      }
      input = stream
    }

    @Synchronized
    override fun close() {
      cancellation.cancel()
      runCatching { input?.close() }
      runCatching { socket.close() }
    }
  }

  fun register(video: LocalVideo): String {
    videos.entries
      .firstOrNull { it.value == video }
      ?.let {
        return baseUrl + it.key
      }
    val extension =
      video.name.substringAfterLast('.', "bin").lowercase(Locale.ROOT).takeIf {
        it.matches(Regex("[a-z0-9]{1,10}"))
      } ?: "bin"
    val path = "/files/${UUID.randomUUID()}/video.$extension"
    videos[path] = video
    return baseUrl + path
  }

  fun name(url: String): String? = videos[url.removePrefix(baseUrl)]?.name

  fun retainSelection(uri: Uri) =
    retainSelection(resolver, uri, videos.values.map { it.uri }.toSet())

  fun serve(socket: Socket, method: String, path: String, headers: Map<String, String>) {
    fun reply(status: String, extra: String = "") {
      socket
        .getOutputStream()
        .write(
          "HTTP/1.1 $status\r\n${extra}Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
        )
    }
    val video = videos[path]
    if (video == null) {
      reply("404 Not Found", "Content-Length: 0\r\n")
      return
    }
    if (method !in listOf("GET", "HEAD")) {
      reply("405 Method Not Allowed", "Allow: GET, HEAD\r\nContent-Length: 0\r\n")
      return
    }
    val transfer = Transfer(socket)
    synchronized(videos) {
      if (videos[path] != video) {
        reply("404 Not Found", "Content-Length: 0\r\n")
        return
      }
      connections.add(transfer)
    }
    var started = false
    try {
      val descriptor = resolver.openAssetFileDescriptor(video.uri, "r", transfer.cancellation)
      if (descriptor == null) {
        reply("404 Not Found", "Content-Length: 0\r\n")
        return
      }
      descriptor.use { file ->
        val size = file.length
        val range = if (size >= 0 && method == "GET") byteRange(headers["range"], size) else null
        if (range == null && size >= 0 && method == "GET" && headers.containsKey("range")) {
          reply(
            "416 Range Not Satisfiable",
            "Content-Range: bytes */$size\r\nContent-Length: 0\r\n",
          )
          return
        }
        val start = range?.first ?: 0L
        val count = range?.let { it.last - it.first + 1 } ?: size
        val partial = headers.containsKey("range") && range != null
        val responseHeaders = buildString {
          append("Content-Type: ${video.contentType}\r\n")
          if (size >= 0) append("Accept-Ranges: bytes\r\nContent-Length: $count\r\n")
          if (partial) append("Content-Range: bytes $start-${range.last}/$size\r\n")
        }
        file.createInputStream().use { input ->
          transfer.attach(input)
          if (method == "GET") input.skipFully(start)
          started = true
          reply(if (partial) "206 Partial Content" else "200 OK", responseHeaders)
          if (method == "GET") {
            val output = socket.getOutputStream()
            val buffer = ByteArray(64 * 1024)
            var remaining = count
            while (remaining != 0L && !socket.isClosed) {
              val length =
                if (remaining < 0) buffer.size else minOf(remaining, buffer.size.toLong()).toInt()
              val read = input.read(buffer, 0, length)
              if (read < 0) break
              output.write(buffer, 0, read)
              if (remaining > 0) remaining -= read
            }
            output.flush()
          }
        }
      }
    } catch (_: SecurityException) {
      if (!started) reply("403 Forbidden", "Content-Length: 0\r\n")
    } catch (_: java.io.FileNotFoundException) {
      if (!started) reply("404 Not Found", "Content-Length: 0\r\n")
    } finally {
      connections.remove(transfer)
      transfer.close()
    }
  }

  fun clear() {
    synchronized(videos) {
      videos.clear()
      connections.forEach { runCatching { it.close() } }
      connections.clear()
    }
  }

  override fun close() {
    clear()
    releaseUnused(resolver, emptySet())
  }

  companion object {
    // Documents selected with OpenDocument can outlive an Activity's result grant. Keep only
    // the current selection and files still backing a cast; this app uses no other persisted URIs.
    fun retainSelection(resolver: ContentResolver, uri: Uri, active: Set<Uri> = emptySet()) {
      require(uri.scheme == "content") { "Choose a video using Android's file picker." }
      try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      } catch (_: SecurityException) {
        // Share intents may carry a temporary, non-persistable read grant instead.
      }
      releaseUnused(resolver, active + uri)
    }

    private fun releaseUnused(resolver: ContentResolver, keep: Set<Uri>) {
      for (grant in resolver.persistedUriPermissions) {
        if (grant.uri !in keep && grant.isReadPermission) {
          runCatching {
            resolver.releasePersistableUriPermission(
              grant.uri,
              Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
          }
        }
      }
    }

    fun describe(resolver: ContentResolver, uri: Uri): LocalVideo {
      require(uri.scheme == "content") { "Choose a video using Android's file picker." }
      val name =
        resolver
          .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
          ?.use {
            if (it.moveToFirst()) it.getString(0) else null
          }
          ?.takeIf { it.isNotBlank() } ?: "Video"
      val mime =
        resolver.getType(uri)?.takeIf {
          it != "application/octet-stream" && it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+"))
        } ?: VideoSources.contentType(name).ifBlank { "application/octet-stream" }
      return LocalVideo(uri, name, mime)
    }

    internal fun byteRange(header: String?, size: Long): LongRange? {
      if (header == null) return if (size > 0) 0L..(size - 1) else null
      val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header) ?: return null
      if (size <= 0) return null
      val (first, last) = match.destructured
      if (first.isEmpty()) {
        val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return maxOf(0, size - suffix)..(size - 1)
      }
      val start = first.toLongOrNull()?.takeIf { it in 0 until size } ?: return null
      val end = if (last.isEmpty()) size - 1 else last.toLongOrNull() ?: return null
      if (end < start) return null
      return start..minOf(end, size - 1)
    }

    private fun InputStream.skipFully(offset: Long) {
      var remaining = offset
      val buffer = ByteArray(8192)
      while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) remaining -= skipped
        else {
          val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
          check(read >= 0) { "The selected video ended before the requested position." }
          remaining -= read
        }
      }
    }
  }
}
