package io.github.stupside.castor

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Lets the Castor browser extension on this phone cast through this app's engine, once the owner
 * allows it from a notification. Loopback alone does not isolate apps: every casting request needs
 * a granted token. Requests from web pages (an http(s) or null Origin) and requests naming another
 * host (DNS rebinding) are refused before anything else is read.
 */
class ExtensionGateway(
  private val api: String,
  private val apiToken: String,
  private val version: String,
  private val allowed: (String) -> Boolean,
  private val grant: () -> String,
  /** Asks the owner; true to allow, false to refuse, null when nobody answered in time. */
  private val approve: suspend () -> Boolean?,
  port: Int = PORT,
  /** Receives failures that end a connection without an answer, for the app's log. */
  private val report: (Throwable) -> Unit = {},
) : AutoCloseable {
  /** Thrown by approve when the owner cannot be asked, with the reason to show the browser. */
  class Unavailable(message: String) : Exception(message)

  // A failure inside a connection is reported, never allowed to stop the app.
  private val scope =
    CoroutineScope(
      SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> report(error) }
    )
  private val server = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
  private val pairing = Mutex()
  private val client =
    OkHttpClient.Builder()
      .followRedirects(false)
      .retryOnConnectionFailure(false)
      .connectTimeout(10, TimeUnit.SECONDS)
      // Watch streams last as long as the cast; the browser's disconnection ends them instead.
      .readTimeout(0, TimeUnit.SECONDS)
      .build()
  val port: Int = server.localPort

  init {
    scope.launch {
      while (!server.isClosed) {
        val socket =
          try {
            server.accept()
          } catch (_: java.io.IOException) {
            break
          }
        scope.launch { socket.use { runCatching { serve(it) }.onFailure(report) } }
      }
    }
  }

  private class Exchange(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val input: BufferedInputStream,
  )

  private fun read(socket: Socket): Exchange {
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
    val request = line().split(' ')
    require(request.size == 3 && request[2] == "HTTP/1.1") { "Invalid request line" }
    val headers = mutableMapOf<String, String>()
    while (true) {
      val line = line()
      if (line.isEmpty()) break
      require(':' in line)
      headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
    }
    return Exchange(request[0], request[1], headers, input)
  }

  private fun head(output: OutputStream, status: Int, type: String, length: Int? = null) {
    val size = length?.let { "Content-Length: $it\r\n" }.orEmpty()
    output.write(
      "HTTP/1.1 $status Castor\r\nContent-Type: $type\r\n${size}Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        .toByteArray()
    )
  }

  private fun json(output: OutputStream, status: Int, body: JSONObject) {
    val bytes = body.toString().toByteArray()
    head(output, status, "application/json", bytes.size)
    output.write(bytes)
    output.flush()
  }

  // Connect's error shape, so the extension's generated client reads the code and message.
  private fun failure(output: OutputStream, status: Int, code: String, message: String) =
    json(output, status, JSONObject().put("code", code).put("message", message))

  private suspend fun serve(socket: Socket) {
    socket.soTimeout = 10_000
    val exchange = read(socket)
    val output = socket.getOutputStream()
    val origin = exchange.headers["origin"]
    if (
      exchange.headers["host"] != "127.0.0.1:$port" ||
        (origin != null && !origin.startsWith("moz-extension://"))
    ) {
      failure(
        output,
        403,
        "permission_denied",
        "Only the Castor extension on this phone may connect",
      )
      return
    }
    when {
      exchange.method == "GET" && exchange.path == "/castor/extension" ->
        json(output, 200, JSONObject().put("app", "castor-android").put("version", version))
      exchange.method == "POST" && exchange.path == "/castor/extension/pair" -> pair(output)
      exchange.method == "POST" && exchange.path in methods -> forward(socket, exchange, output)
      else -> failure(output, 404, "unimplemented", "Unknown request")
    }
  }

  private suspend fun pair(output: OutputStream) {
    if (!pairing.tryLock()) {
      failure(output, 409, "aborted", "Another browser is waiting for an answer")
      return
    }
    try {
      when (approve()) {
        true -> json(output, 200, JSONObject().put("token", grant()))
        false -> failure(output, 403, "permission_denied", "The owner of this phone refused")
        null -> failure(output, 408, "deadline_exceeded", "Nobody answered the notification")
      }
    } catch (unavailable: Unavailable) {
      failure(output, 503, "unavailable", unavailable.message.orEmpty())
    } finally {
      pairing.unlock()
    }
  }

  private suspend fun forward(socket: Socket, exchange: Exchange, output: OutputStream) {
    val token = exchange.headers["authorization"].orEmpty().removePrefix("Bearer ")
    if (!allowed(token)) {
      failure(output, 401, "unauthenticated", "Allow this browser in Castor first")
      return
    }
    val length = exchange.headers["content-length"]?.toIntOrNull()
    if (
      length == null || length !in 0..maxBody || exchange.headers.containsKey("transfer-encoding")
    ) {
      failure(output, 400, "invalid_argument", "Invalid request length")
      return
    }
    val body = ByteArray(length)
    java.io.DataInputStream(exchange.input).readFully(body)
    val type = exchange.headers["content-type"].orEmpty()
    // Casting a page would open it in this app's own browser; the extension sends the streams
    // its browser found, so a cast request must name streams, in binary, to be read here.
    if (exchange.path == "/castor.v1.CastService/Cast") {
      val pages =
        runCatching { castor.v1.CastOuterClass.CastRequest.parseFrom(body).source.hasPages() }
          .getOrNull()
      if (type != "application/proto" || pages != false) {
        failure(output, 400, "invalid_argument", "Cast the streams the browser found")
        return
      }
    }
    val request =
      Request.Builder()
        .url(api + exchange.path)
        .post(body.toRequestBody(null))
        .header("Content-Type", type)
        .header("Authorization", "Bearer $apiToken")
        .header("Accept-Encoding", "identity")
        .apply {
          for (name in forwarded) exchange.headers[name.lowercase()]?.let { header(name, it) }
        }
        .build()
    val call = client.newCall(request)
    socket.soTimeout = 0
    // The browser closing its connection, or a panel going away, ends the engine request too. The
    // socket also closes under this read once the answer is sent: either way the request is over.
    val watcher =
      scope.launch {
        try {
          exchange.input.read()
        } catch (_: java.io.IOException) {}
        call.cancel()
      }
    try {
      call.execute().use { response ->
        head(output, response.code, response.header("Content-Type") ?: "application/octet-stream")
        val source = response.body.byteStream()
        val buffer = ByteArray(16_384)
        while (true) {
          val read = source.read(buffer)
          if (read < 0) break
          output.write(buffer, 0, read)
          output.flush()
        }
      }
    } catch (error: java.io.IOException) {
      // The engine or the browser went away; closing the socket tells the other side.
      report(error)
    } finally {
      watcher.cancel()
    }
  }

  override fun close() {
    server.close()
    scope.cancel()
  }

  companion object {
    const val PORT = 8411
    private const val maxBody = 1 shl 20
    // Casting, following, stopping and finding TVs.
    private val methods =
      setOf(
        "/castor.v1.CastService/Cast",
        "/castor.v1.CastService/Watch",
        "/castor.v1.CastService/Stop",
        "/castor.v1.CastService/ListCasts",
        "/castor.v1.DeviceService/ListDevices",
      )
    private val forwarded = listOf("Connect-Protocol-Version", "Connect-Timeout-Ms")
  }
}
