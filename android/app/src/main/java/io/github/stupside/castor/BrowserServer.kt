package io.github.stupside.castor

import castor.scraping.v1.Scraping.ResolveRequest
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

// Authenticated RPC opens pages; captured-playlist capabilities can renew their own page.
class BrowserServer(
  private val resolver: BrowserResolver,
  private val discovery: suspend () -> List<castor.v1.DeviceOuterClass.Device>,
  private val token: String,
  content: android.content.ContentResolver? = null,
) : AutoCloseable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
  private val playlists = BrowserPlaylists(resolver)
  val url = "http://127.0.0.1:${server.localPort}"
  val videos = content?.let { LocalVideos(it, url) }

  init {
    scope.launch {
      while (!server.isClosed) {
        val socket =
          try {
            server.accept()
          } catch (_: java.io.IOException) {
            break
          }
        scope.launch { socket.use { runCatching { serve(it) } } }
      }
    }
  }

  private suspend fun serve(socket: Socket) {
    socket.soTimeout = 10_000
    val input = socket.getInputStream().buffered()
    var headerBytes = 0
    fun line(): String {
      val text = StringBuilder()
      while (true) {
        val byte = input.read()
        require(byte >= 0 && ++headerBytes <= 16_384) { "Invalid HTTP headers" }
        if (byte == 10) return text.toString().trimEnd('\r')
        text.append(byte.toChar())
      }
    }
    val request = line()
    val headers = mutableMapOf<String, String>()
    while (true) {
      val line = line()
      if (line.isEmpty()) break
      require(':' in line)
      headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
    }
    fun reply(status: Int, type: String, bytes: ByteArray) {
      socket.getOutputStream().apply {
        write(
          "HTTP/1.1 $status Response\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            .toByteArray()
        )
        write(bytes)
        flush()
      }
    }
    fun error(status: Int, code: String, message: String) =
      reply(
        status,
        "application/json",
        JSONObject().put("code", code).put("message", message).toString().toByteArray(),
      )
    val target = request.split(' ')
    if (target.size == 3 && target[1].startsWith("/files/") && videos != null) {
      socket.soTimeout = 0
      videos.serve(socket, target[0], target[1], headers)
      return
    }
    if (request.startsWith("GET /playlists/") && request.endsWith(" HTTP/1.1")) {
      socket.soTimeout = 0
      kotlinx.coroutines.coroutineScope {
        val requestJob = coroutineContext[kotlinx.coroutines.Job]!!
        val disconnected = launch(Dispatchers.IO) { if (input.read() < 0) requestJob.cancel() }
        try {
          val document =
            withTimeout(50_000) {
              playlists.read(request.substringAfter("GET ").substringBefore(" HTTP/1.1"), url)
            }
          if (document == null) error(404, "not_found", "Unknown playlist capability")
          else reply(document.status, "application/vnd.apple.mpegurl", document.body.toByteArray())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          error(502, "unavailable", "The page could not renew or read the playlist")
        } finally {
          socket.close()
          disconnected.cancel()
        }
      }
      return
    }
    if (
      !MessageDigest.isEqual(
        headers["authorization"].orEmpty().toByteArray(),
        "Bearer $token".toByteArray(),
      )
    ) {
      error(401, "unauthenticated", "The browser service requires its bearer token")
      return
    }
    val discover = request == "POST /castor.v1.DeviceService/ListDevices HTTP/1.1"
    if (
      (!discover && request != "POST /castor.scraping.v1.ScrapingService/Resolve HTTP/1.1") ||
        headers["content-type"] != "application/proto"
    ) {
      error(404, "unimplemented", "Unsupported browser request")
      return
    }
    val length = headers["content-length"]?.toIntOrNull()
    if (length == null || length !in 0..262_144 || headers.containsKey("transfer-encoding")) {
      error(400, "invalid_argument", "Invalid request length")
      return
    }
    val bytes = ByteArray(length)
    java.io.DataInputStream(input).readFully(bytes)
    socket.soTimeout = 0
    kotlinx.coroutines.coroutineScope {
      val requestJob = coroutineContext[kotlinx.coroutines.Job]!!
      val disconnected = launch(Dispatchers.IO) { if (input.read() < 0) requestJob.cancel() }
      try {
        val response =
          if (discover) {
            castor.v1.DeviceOuterClass.ListDevicesRequest.parseFrom(bytes)
            castor.v1.DeviceOuterClass.ListDevicesResponse.newBuilder()
              .addAllDevices(discovery())
              .build()
          } else
            withTimeout(60_000) {
              playlists.retain(resolver.resolve(ResolveRequest.parseFrom(bytes)), url)
            }
        reply(200, "application/proto", response.toByteArray())
      } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
      } catch (failure: IllegalArgumentException) {
        error(400, "invalid_argument", failure.message ?: "Invalid browser request")
      } catch (failure: Exception) {
        error(404, "not_found", failure.message ?: "Page extraction failed")
      } finally {
        socket.close()
        disconnected.cancel()
      }
    }
  }

  override fun close() {
    videos?.close()
    playlists.clear()
    server.close()
    scope.cancel()
  }

  fun playbackEnded() {
    videos?.clear()
    playlists.clear()
  }
}
