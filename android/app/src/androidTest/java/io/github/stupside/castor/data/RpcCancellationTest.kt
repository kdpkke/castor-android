package io.github.stupside.castor.data

import castor.v1.CastOuterClass.*
import com.google.protobuf.Empty
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class RpcCancellationTest {
  private fun statusFrame(): ByteArray {
    val bytes =
      WatchResponse.newBuilder()
        .setStatus(CastStatus.newBuilder().setConnecting(Empty.getDefaultInstance()))
        .build()
        .toByteArray()
    return ByteArrayOutputStream()
      .apply {
        DataOutputStream(this).apply {
          writeByte(0)
          writeInt(bytes.size)
          write(bytes)
        }
      }
      .toByteArray()
  }

  @Test
  fun cancellingAnOpenStatusStreamRemainsCancellation() = runBlocking {
    val server = MockWebServer()
    val frame = statusFrame()
    val body = Buffer().apply { repeat(100) { write(frame) } }
    server.enqueue(
      MockResponse()
        .setHeader("Content-Type", "application/connect+proto")
        .setBody(body)
        .throttleBody(frame.size.toLong(), 1, TimeUnit.SECONDS)
    )
    server.start()
    try {
      val api = Rpc(server.url("/").toString().removeSuffix("/"), "fixture")
      val received = CompletableDeferred<Unit>()
      val failure = CompletableDeferred<Throwable>()
      val watcher = launch {
        try {
          api.watch(WatchRequest.newBuilder().setCastId("active").build()) {
            received.complete(Unit)
          }
        } catch (error: Exception) {
          failure.complete(error)
        }
      }
      withTimeout(5000) { received.await() }
      withTimeout(5000) { watcher.cancelAndJoin() }
      assertTrue(
        "Closing our own socket must not become a cast failure: ${failure.await()}",
        failure.await() is CancellationException,
      )
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun unexpectedStatusStreamClosureIsStillReported() = runBlocking {
    val server = MockWebServer()
    val body = Buffer().write(statusFrame()).writeByte(2).writeInt(2).writeUtf8("{}")
    server.enqueue(
      MockResponse().setHeader("Content-Type", "application/connect+proto").setBody(body)
    )
    server.start()
    try {
      val api = Rpc(server.url("/").toString().removeSuffix("/"), "fixture")
      var received = false
      try {
        withTimeout(5000) {
          api.watch(WatchRequest.newBuilder().setCastId("active").build()) { received = true }
        }
        fail("An unexpected close was hidden")
      } catch (error: IOException) {
        assertTrue(received)
        assertEquals("The status stream closed before the cast ended.", error.message)
      }
    } finally {
      server.shutdown()
    }
  }
}
