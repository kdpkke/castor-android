package io.github.stupside.castor.data

import castor.scraping.v1.ScrapingServiceClient
import castor.v1.CastOuterClass.WatchRequest
import castor.v1.CastOuterClass.WatchResponse
import castor.v1.CastServiceClient
import castor.v1.DeviceServiceClient
import com.connectrpc.ConnectException
import com.connectrpc.ProtocolClientConfig
import com.connectrpc.ResponseMessage
import com.connectrpc.extensions.GoogleJavaProtobufStrategy
import com.connectrpc.getOrThrow
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.connectrpc.simpleTimeouts
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

// Generated clients own the wire protocol; this endpoint owns credentials and cast lifecycle.
class Rpc(baseUrl: String, token: String) {
  private val client =
    ProtocolClient(
      ConnectOkHttpClient(
        transport
          .newBuilder()
          .addInterceptor { chain ->
            val request =
              chain
                .request()
                .newBuilder()
                .header("Authorization", "Bearer $token")
                .header("Accept-Encoding", "identity")
                .build()
            val response = chain.proceed(request)
            if (
              response.header("Content-Encoding").orEmpty().let {
                it.isNotEmpty() && it != "identity"
              }
            ) {
              response.close()
              throw IOException("Compressed RPC responses are not supported")
            }
            response
              .newBuilder()
              .body(
                RpcResponseBody(
                  response.body,
                  response.header("Content-Type").orEmpty().startsWith("application/connect+"),
                )
              )
              .build()
          }
          .build()
      ),
      ProtocolClientConfig(
        host = baseUrl,
        serializationStrategy = GoogleJavaProtobufStrategy(),
        compressionPools = emptyList(),
        ioCoroutineContext = Dispatchers.IO,
        timeoutOracle = simpleTimeouts(90.seconds, null),
      ),
    )

  internal val casts = CastServiceClient(client)
  internal val devices = DeviceServiceClient(client)
  internal val scraping = ScrapingServiceClient(client)

  internal suspend fun <T> result(call: suspend () -> ResponseMessage<T>): T =
    try {
      call().getOrThrow()
    } catch (error: ConnectException) {
      currentCoroutineContext().ensureActive()
      throw ioFailure(error)
    }

  suspend fun watch(request: WatchRequest, update: suspend (WatchResponse) -> Unit) {
    val stream = casts.watch()
    try {
      stream.sendAndClose(request).getOrThrow()
      for (response in stream.responseChannel()) {
        require(response.hasStatus() || response.hasEnded()) { "Empty status update" }
        update(response)
        if (response.hasEnded()) return
      }
      throw EOFException("The status stream closed before the cast ended.")
    } catch (error: ConnectException) {
      currentCoroutineContext().ensureActive()
      throw ioFailure(error)
    } finally {
      withContext(NonCancellable) { stream.receiveClose() }
    }
  }

  companion object {
    private fun ioFailure(error: ConnectException) =
      IOException(
        "${error.code.name.lowercase()}: ${error.message ?: error.cause?.message ?: "Castor request failed"}",
        error,
      )

    internal fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
      val output = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      while (true) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (read < 0) return output.toByteArray()
        check(output.size() + read <= limit) { "Castor response exceeds the message limit" }
        output.write(buffer, 0, read)
      }
    }

    private val transport =
      ConnectOkHttpClient.configureClient(
          OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
        )
        .build()
  }
}
