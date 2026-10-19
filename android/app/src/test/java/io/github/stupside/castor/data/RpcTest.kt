package io.github.stupside.castor.data

import castor.v1.CastOuterClass.*
import castor.v1.DeviceOuterClass.*
import com.google.protobuf.Empty
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class RpcTest {
  private suspend fun withServer(test: suspend (MockWebServer, Rpc) -> Unit) {
    val server = MockWebServer()
    server.start()
    try {
      test(server, Rpc(server.url("/").toString().removeSuffix("/"), "fixture-secret"))
    } finally {
      server.shutdown()
    }
  }

  private fun unary(bytes: ByteArray) =
    MockResponse().setHeader("Content-Type", "application/proto").setBody(Buffer().write(bytes))

  private fun frame(bytes: ByteArray, flags: Int = 0) =
    Buffer().writeByte(flags).writeInt(bytes.size).write(bytes)

  private fun stream(body: Buffer) =
    MockResponse().setHeader("Content-Type", "application/connect+proto").setBody(body)

  private suspend inline fun <reified T : Throwable> failure(block: suspend () -> Unit): T {
    try {
      block()
    } catch (error: Throwable) {
      assertTrue("Expected ${T::class.java.simpleName}, got $error", error is T)
      return error as T
    }
    throw AssertionError("Expected ${T::class.java.simpleName}")
  }

  @Test
  fun responseLimitAcceptsExactBytesAndRejectsOverflow() {
    assertArrayEquals(
      byteArrayOf(1, 2),
      Rpc.readLimited(ByteArrayInputStream(byteArrayOf(1, 2)), 2),
    )
    assertThrows(IllegalStateException::class.java) {
      Rpc.readLimited(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2)
    }
  }

  @Test
  fun generatedDiscoveryAuthenticatesAndAcceptsAnEmptyRequest() = runBlocking {
    withServer { server, api ->
      val device = Device.newBuilder().setName("Fixture TV").setAddress("192.0.2.1").build()
      server.enqueue(
        unary(ListDevicesResponse.newBuilder().addDevices(device).build().toByteArray())
      )
      assertEquals(listOf(device), DataRepository(api).devices())
      val request = server.takeRequest(5, TimeUnit.SECONDS)!!
      assertEquals("/castor.v1.DeviceService/ListDevices", request.path)
      assertEquals("Bearer fixture-secret", request.getHeader("Authorization"))
      assertEquals("1", request.getHeader("Connect-Protocol-Version"))
      assertEquals("identity", request.getHeader("Accept-Encoding"))
      assertEquals(0L, request.body.size)
    }
  }

  @Test
  fun generatedCastAndStopUseTheTypedContracts() = runBlocking {
    withServer { server, api ->
      server.enqueue(unary(CastResponse.newBuilder().setCastId("opaque-id").build().toByteArray()))
      server.enqueue(unary(StopResponse.getDefaultInstance().toByteArray()))
      val data = DataRepository(api)
      assertEquals("opaque-id", data.cast(CastRequest.getDefaultInstance()))
      data.stop("opaque-id")
      assertEquals("/castor.v1.CastService/Cast", server.takeRequest().path)
      val stop = server.takeRequest()
      assertEquals("/castor.v1.CastService/Stop", stop.path)
      assertEquals("opaque-id", StopRequest.parseFrom(stop.body.readByteArray()).castId)
    }
  }

  @Test
  fun statusUpdatesAndTheExplicitCastEndArePreserved() = runBlocking {
    withServer { server, api ->
      val status =
        WatchResponse.newBuilder()
          .setStatus(CastStatus.newBuilder().setConnecting(Empty.getDefaultInstance()))
          .build()
      val ended =
        WatchResponse.newBuilder()
          .setEnded(Ended.newBuilder().setStopped(Empty.getDefaultInstance()))
          .build()
      val body =
        frame(status.toByteArray()).apply {
          writeAll(frame(ended.toByteArray()))
          writeAll(frame("{}".toByteArray(), 2))
        }
      server.enqueue(stream(body))
      val updates = mutableListOf<WatchResponse>()
      withTimeout(5000) { DataRepository(api).watch("active") { updates.add(it) } }
      assertEquals(listOf(status, ended), updates)
      val request = server.takeRequest()
      assertEquals("/castor.v1.CastService/Watch", request.path)
      assertEquals("Bearer fixture-secret", request.getHeader("Authorization"))
    }
  }

  @Test
  fun anEmptyStatusMessageIsNotAcceptedAsAnUpdate() = runBlocking {
    withServer { server, api ->
      server.enqueue(stream(frame(byteArrayOf()).apply { writeAll(frame("{}".toByteArray(), 2)) }))
      failure<IllegalArgumentException> {
        withTimeout(5000) { DataRepository(api).watch("active") {} }
      }
    }
  }

  @Test
  fun successfulRpcEndWithoutCastEndedIsStillAnError() = runBlocking {
    withServer { server, api ->
      server.enqueue(stream(frame("{}".toByteArray(), 2)))
      val error =
        failure<IOException> {
          withTimeout(5000) { DataRepository(api).watch("active") {} }
        }
      assertEquals("The status stream closed before the cast ended.", error.message)
    }
  }

  @Test
  fun serverStreamErrorsKeepTheirDiagnostic() = runBlocking {
    withServer { server, api ->
      server.enqueue(
        stream(
          frame(
            """{"error":{"code":"permission_denied","message":"fixture refused"}}""".toByteArray(),
            2,
          )
        )
      )
      val error =
        failure<IOException> {
          withTimeout(5000) { DataRepository(api).watch("active") {} }
        }
      assertTrue(error.message.orEmpty().contains("fixture refused"))
    }
  }

  @Test
  fun oversizedFramesAreRejectedBeforeTheirBodyIsRead() = runBlocking {
    withServer { server, api ->
      server.enqueue(stream(Buffer().writeByte(0).writeInt(Int.MAX_VALUE)))
      val error =
        failure<IOException> {
          withTimeout(5000) { DataRepository(api).watch("active") {} }
        }
      assertTrue(
        error.stackTraceToString(),
        error.message.orEmpty().contains("Invalid status message length"),
      )
    }
  }

  @Test
  fun oversizedUnaryResponsesAreRejectedBeforeProtobufParsing() = runBlocking {
    withServer { server, api ->
      server.enqueue(unary(ByteArray(8 * 1024 * 1024 + 1)))
      val error = failure<IOException> { withTimeout(5000) { DataRepository(api).devices() } }
      assertTrue(error.stackTraceToString(), error.message.orEmpty().contains("message limit"))
    }
  }

  @Test
  fun malformedAndCompressedFramesAreRejected() = runBlocking {
    for (body in
      listOf(
        Buffer().writeByte(0).writeInt(-1),
        frame(byteArrayOf(), 1),
        Buffer().writeByte(0).writeInt(3).writeByte(8),
      )) {
      withServer { server, api ->
        server.enqueue(stream(body))
        failure<IOException> { withTimeout(5000) { DataRepository(api).watch("active") {} } }
      }
    }
  }

  @Test
  fun redirectsNeverForwardTheBearerToken() = runBlocking {
    withServer { target, _ ->
      withServer { server, api ->
        server.enqueue(
          MockResponse().setResponseCode(302).setHeader("Location", target.url("/stolen"))
        )
        failure<IOException> { withTimeout(5000) { DataRepository(api).devices() } }
        assertEquals(1, server.requestCount)
        assertEquals(0, target.requestCount)
      }
    }
  }

  @Test
  fun cancellingABlockedUnaryCallRemainsCancellation() = runBlocking {
    withServer { server, api ->
      server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
      val call = async(Dispatchers.Default) { DataRepository(api).devices() }
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
      withTimeout(5000) { call.cancelAndJoin() }
      assertTrue(call.isCancelled)
    }
  }
}
