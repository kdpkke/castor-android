package io.github.stupside.castor

import castor.v1.CastOuterClass.CastRequest
import castor.v1.CastOuterClass.Source
import castor.v1.StreamOuterClass.Stream
import castor.v1.StreamOuterClass.StreamCandidate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExtensionGatewayTest {
  private val engine = MockWebServer()
  private val granted = mutableSetOf<String>()
  private val reported = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
  private var answer: suspend () -> Boolean? = { true }
  private lateinit var gateway: ExtensionGateway
  private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

  @Before
  fun start() {
    engine.start()
    gateway =
      ExtensionGateway(
        engine.url("/").toString().trimEnd('/'),
        "engine-token",
        "1.2.0",
        { it in granted },
        { "browser-token-${granted.size + 1}".also { granted += it } },
        { answer() },
        port = 0,
        report = { reported += it },
      )
  }

  @After
  fun stop() {
    gateway.close()
    engine.shutdown()
  }

  private fun call(
    path: String,
    body: ByteArray? = null,
    type: String = "application/proto",
    token: String? = null,
    origin: String? = "moz-extension://5f3c0d1e",
    host: String? = null,
  ): Response {
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${gateway.port}$path")
        .apply {
          if (body != null) post(body.toRequestBody(type.toMediaType()))
          token?.let { header("Authorization", "Bearer $it") }
          origin?.let { header("Origin", it) }
          host?.let { header("Host", it) }
        }
        .build()
    return client.newCall(request).execute()
  }

  private fun Response.json(): JSONObject = JSONObject(body.string())

  private fun streamsCast(): ByteArray =
    CastRequest.newBuilder()
      .setSource(
        Source.newBuilder()
          .setStreams(
            Source.ResolvedStreams.newBuilder()
              .addStreams(
                StreamCandidate.newBuilder()
                  .setStream(Stream.newBuilder().setUrl("https://cdn.example/master.m3u8"))
              )
          )
      )
      .build()
      .toByteArray()

  @Test
  fun answersWhoItIsWithoutAToken() {
    call("/castor/extension").use {
      assertEquals(200, it.code)
      val body = it.json()
      assertEquals("castor-android", body.getString("app"))
      assertEquals("1.2.0", body.getString("version"))
    }
  }

  @Test
  fun refusesWebPagesAndOtherHostNames() {
    for (origin in listOf("https://evil.example", "http://127.0.0.1:8080", "null")) {
      call("/castor/extension", origin = origin).use { assertEquals(origin, 403, it.code) }
    }
    // DNS rebinding reaches loopback under the attacker's host name.
    call("/castor/extension", host = "evil.example:${gateway.port}").use {
      assertEquals(403, it.code)
    }
    call("/castor/extension", origin = null).use { assertEquals(200, it.code) }
  }

  @Test
  fun castingNeedsAGrantedTokenAndNeverSeesTheEngineToken() {
    call("/castor.v1.CastService/ListCasts", ByteArray(0)).use {
      assertEquals(401, it.code)
      assertEquals("unauthenticated", it.json().getString("code"))
    }
    call("/castor.v1.CastService/ListCasts", ByteArray(0), token = "engine-token").use {
      assertEquals(401, it.code)
    }
    assertEquals(0, engine.requestCount)
  }

  @Test
  fun anAllowedBrowserCastsWithTheEngineTokenInItsPlace() {
    val token = call("/castor/extension/pair", ByteArray(0)).use { it.json().getString("token") }
    engine.enqueue(
      MockResponse()
        .setHeader("Content-Type", "application/proto")
        .setBody(Buffer().write(byteArrayOf(10, 2, 105, 100)))
    )
    call("/castor.v1.CastService/Cast", streamsCast(), token = token).use {
      assertEquals(200, it.code)
      assertEquals("application/proto", it.header("Content-Type"))
      assertTrue(it.body.bytes().contentEquals(byteArrayOf(10, 2, 105, 100)))
    }
    val forwarded = engine.takeRequest()
    assertEquals("/castor.v1.CastService/Cast", forwarded.path)
    assertEquals("Bearer engine-token", forwarded.getHeader("Authorization"))
    assertTrue(forwarded.body.readByteArray().contentEquals(streamsCast()))
  }

  // A request's end closes its socket under the reader that watches for the browser leaving; on a
  // phone an exception escaping that reader stopped the whole app.
  @Test
  fun aFinishedRequestLeavesNothingFailingBehind() {
    granted += "browser"
    repeat(3) {
      engine.enqueue(MockResponse().setHeader("Content-Type", "application/proto"))
      call("/castor.v1.CastService/ListCasts", ByteArray(0), token = "browser").use {
        assertEquals(200, it.code)
      }
    }
    Thread.sleep(500)
    assertEquals(emptyList<Throwable>(), reported.toList())
  }

  @Test
  fun anEmptyAnswerIsRelayed() {
    granted += "browser"
    engine.enqueue(MockResponse().setHeader("Content-Type", "application/proto"))
    call("/castor.v1.CastService/ListCasts", ByteArray(0), token = "browser").use {
      assertEquals(200, it.code)
      assertEquals(0, it.body.bytes().size)
    }
  }

  @Test
  fun aWatchStreamIsRelayedToItsEnd() {
    granted += "browser"
    val frames = ByteArray(40_000) { (it % 251).toByte() }
    engine.enqueue(
      MockResponse()
        .setHeader("Content-Type", "application/connect+proto")
        .setChunkedBody(Buffer().write(frames), 4096)
        .throttleBody(8192, 20, TimeUnit.MILLISECONDS)
    )
    call(
        "/castor.v1.CastService/Watch",
        ByteArray(5),
        type = "application/connect+proto",
        token = "browser",
      )
      .use {
        assertEquals("application/connect+proto", it.header("Content-Type"))
        assertTrue(it.body.bytes().contentEquals(frames))
      }
  }

  @Test
  fun aCastMustNameTheBrowsersStreamsInBinary() {
    granted += "browser"
    val page =
      CastRequest.newBuilder()
        .setSource(
          Source.newBuilder().setPages(Source.Pages.newBuilder().addUrls("https://example.org"))
        )
        .build()
        .toByteArray()
    call("/castor.v1.CastService/Cast", page, token = "browser").use { assertEquals(400, it.code) }
    call("/castor.v1.CastService/Cast", "{}".toByteArray(), "application/json", "browser").use {
      assertEquals(400, it.code)
    }
    assertEquals(0, engine.requestCount)
  }

  @Test
  fun theOwnersAnswerDecidesThePairing() {
    answer = { false }
    call("/castor/extension/pair", ByteArray(0)).use { assertEquals(403, it.code) }
    answer = { null }
    call("/castor/extension/pair", ByteArray(0)).use { assertEquals(408, it.code) }
    answer = { throw ExtensionGateway.Unavailable("Turn on notifications") }
    call("/castor/extension/pair", ByteArray(0)).use {
      assertEquals(503, it.code)
      assertEquals("Turn on notifications", it.json().getString("message"))
    }
    assertTrue(granted.isEmpty())
  }

  @Test
  fun onlyOnePairingWaitsAtATime() {
    val decision = CompletableDeferred<Boolean>()
    answer = { decision.await() }
    val first = Thread {
      call("/castor/extension/pair", ByteArray(0)).use { assertEquals(200, it.code) }
    }
    first.start()
    Thread.sleep(300)
    call("/castor/extension/pair", ByteArray(0)).use { assertEquals(409, it.code) }
    decision.complete(true)
    first.join(5000)
    assertEquals(1, granted.size)
  }

  @Test
  fun unknownRequestsAreRefused() {
    granted += "browser"
    call("/castor.scraping.v1.ScrapingService/Resolve", ByteArray(0), token = "browser").use {
      assertNotEquals(200, it.code)
    }
    assertEquals(0, engine.requestCount)
  }
}
