package io.github.stupside.castor

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import castor.scraping.v1.Scraping.ResolveRequest
import castor.scraping.v1.Scraping.ResolveResponse
import castor.v1.StreamOuterClass.Stream
import castor.v1.StreamOuterClass.StreamCandidate
import io.github.stupside.castor.data.Rpc
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BrowserSessionTest {
  @Test
  fun playerPreparedPlaylistsReloadThroughTheirIframeLoader() = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val fixture =
      instrumentation.context.assets.open("player.html").bufferedReader().use { it.readText() }
    val page = MockWebServer()
    val frame = MockWebServer()
    val pages = AtomicInteger()
    val access = AtomicInteger()
    val refused = AtomicBoolean()
    frame.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          when (request.requestUrl!!.encodedPath) {
            "/player" -> {
              pages.incrementAndGet()
              MockResponse().setHeader("Content-Type", "text/html").setBody(fixture)
            }
            "/access" ->
              MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"token\":${access.incrementAndGet()}}")
            "/manifest" ->
              if (refused.get()) MockResponse().setResponseCode(403)
              else
                MockResponse()
                  .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                  .setBody(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\nopaque-reference\n"
                  )
            else -> MockResponse().setResponseCode(404)
          }
      }
    frame.start()
    page.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          MockResponse()
            .setHeader("Content-Type", "text/html")
            .setBody("<iframe src='${frame.url("/player")}'></iframe>")
      }
    page.start()
    val resolver = withContext(Dispatchers.Main) { BrowserResolver(context) }
    val bridge = BrowserServer(resolver, { emptyList() }, "fixture-token")
    try {
      val api = Rpc(bridge.url, "fixture-token")
      val response = api.result {
        api.scraping.resolve(
          ResolveRequest.newBuilder().addUrls(page.url("/page").toString()).build()
        )
      }
      val url = response.streamsList.single().stream.url
      suspend fun read(): String = withContext(Dispatchers.IO) { URL(url).readText() }
      val first = read()
      assertTrue(
        "The page's processed segment must reach the reader: $first",
        first.contains(frame.url("/segments/").toString()),
      )
      assertFalse(first, first.contains("opaque-reference"))
      assertTrue(first, first.contains("URI=\"${frame.url("/key.bin")}\""))
      assertTrue(first, first.contains("URI=\"${frame.url("/init.mp4")}\""))
      val second = read()
      assertNotEquals("Reload must invoke the player again", first, second)
      assertEquals("The original player page must survive extraction", 1, pages.get())
      val instance = Regex("/segments/([^/]+)/").find(first)!!.groupValues[1]
      repeat(5) {
        val retried = api.result {
          api.scraping.resolve(
            ResolveRequest.newBuilder().addUrls(page.url("/page").toString()).build()
          )
        }
        assertEquals(
          "A retry must retain the same reader capability",
          url,
          retried.streamsList.single().stream.url,
        )
        assertTrue(read().contains("/segments/$instance/"))
      }
      assertEquals("Retries must keep the original player page", 1, pages.get())
      resolver.showPage(page.url("/another-page").toString())
      withContext(Dispatchers.Main) { resolver.dismissPage() }
      assertTrue(
        "Viewer navigation must preserve the retained player's state",
        read().contains("/segments/$instance/"),
      )
      val other =
        api
          .result {
            api.scraping.resolve(
              ResolveRequest.newBuilder().addUrls(page.url("/second-source").toString()).build()
            )
          }
          .streamsList
          .single()
          .stream
          .url
      val otherBody = withContext(Dispatchers.IO) { URL(other).readText() }
      assertFalse(
        "Independent sources must own independent player state",
        otherBody.contains("/segments/$instance/"),
      )
      assertTrue(read().contains("/segments/$instance/"))
      refused.set(true)
      withContext(Dispatchers.IO) {
        val failed = URL(url).openConnection() as HttpURLConnection
        try {
          assertEquals(502, failed.responseCode)
        } finally {
          failed.disconnect()
        }
      }
      refused.set(false)
      assertTrue(read().contains("/segments/"))
      bridge.playbackEnded()
      withContext(Dispatchers.IO) {
        val expired = URL(url).openConnection() as HttpURLConnection
        try {
          assertEquals(404, expired.responseCode)
        } finally {
          expired.disconnect()
        }
      }
    } finally {
      bridge.close()
      withContext(Dispatchers.Main) { resolver.close() }
      page.shutdown()
      frame.shutdown()
    }
  }

  @Before
  fun prepareBrowserProtection() =
    runBlocking<Unit> {
      withContext(Dispatchers.Main) {
        BrowserEngine.get(InstrumentationRegistry.getInstrumentation().targetContext)
          .ready()
          .await()
      }
    }

  @Test
  fun cancellingPlayerReadWaitsForItsLoaderBeforeStartingAnother() = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val fixture =
      instrumentation.context.assets.open("player.html").bufferedReader().use { it.readText() }
    val slow = AtomicBoolean()
    val requested = java.util.concurrent.CountDownLatch(1)
    val origin = MockWebServer()
    origin.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          when (request.requestUrl!!.encodedPath) {
            "/page" -> MockResponse().setHeader("Content-Type", "text/html").setBody(fixture)
            "/access" ->
              MockResponse().setBody("{\"token\":1}").apply {
                if (slow.getAndSet(false)) {
                  requested.countDown()
                  setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS)
                }
              }
            else ->
              MockResponse()
                .setHeader("Content-Type", BrowserResolver.HLS)
                .setBody("#EXTM3U\n#EXTINF:6,\nopaque-reference\n")
          }
      }
    origin.start()
    val resolver = withContext(Dispatchers.Main) { BrowserResolver(instrumentation.targetContext) }
    val playlists = BrowserPlaylists(resolver)
    try {
      val base = "http://127.0.0.1:1"
      val response =
        resolver.resolve(
          ResolveRequest.newBuilder().addUrls(origin.url("/page").toString()).build()
        )
      val retained = playlists.retain(response, base)
      val path = java.net.URI(retained.getStreams(0).stream.url).path
      slow.set(true)
      val pending = async { playlists.read(path, base) }
      assertTrue(
        withContext(Dispatchers.IO) { requested.await(5, java.util.concurrent.TimeUnit.SECONDS) }
      )
      withTimeout(5000) { pending.cancelAndJoin() }
      assertTrue(playlists.read(path, base)!!.body.contains("/segments/"))
    } finally {
      playlists.clear()
      withContext(Dispatchers.Main) { resolver.close() }
      origin.shutdown()
    }
  }

  @Test
  fun extensionlessChildPlaylistsStayManagedAndKeysStayMediaReferences() = runBlocking {
    val resolver =
      withContext(Dispatchers.Main) {
        BrowserResolver(InstrumentationRegistry.getInstrumentation().targetContext)
      }
    val origin = MockWebServer()
    origin.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          MockResponse()
            .setBody(
              if (request.path == "/master")
                "\uFEFF#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio\"\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvideo\n"
              else
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.m3u8\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\nsegment.ts\n"
            )
      }
    origin.start()
    val playlists = BrowserPlaylists(resolver)
    val base = "http://127.0.0.1:1"
    try {
      val retained =
        playlists.retain(
          ResolveResponse.newBuilder()
            .addStreams(
              StreamCandidate.newBuilder()
                .setSourcePage(origin.url("/page").toString())
                .setStream(
                  Stream.newBuilder()
                    .setUrl(origin.url("/master").toString())
                    .setContentType(BrowserResolver.HLS)
                )
            )
            .build(),
          base,
        )
      suspend fun read(address: String) = playlists.read(java.net.URI(address).path, base)!!.body
      val master = read(retained.getStreams(0).stream.url)
      val audio = Regex("URI=\"([^\"]+)\"").find(master)!!.groupValues[1]
      val video = master.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.single()
      for (address in listOf(audio, video)) {
        assertTrue(address, address.startsWith("$base/playlists/"))
        val body = read(address)
        assertTrue(body, body.contains("URI=\"${origin.url("/key.m3u8")}\""))
        assertTrue(body, body.contains("URI=\"${origin.url("/init.mp4")}\""))
        assertTrue(body, body.contains(origin.url("/segment.ts").toString()))
      }
    } finally {
      playlists.clear()
      origin.shutdown()
      withContext(Dispatchers.Main) { resolver.close() }
    }
  }

  @Test
  fun playlistRedirectDoesNotForwardCredentialsToAnotherOrigin() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val resolver = withContext(Dispatchers.Main) { BrowserResolver(context) }
    val origin = MockWebServer()
    val edge = MockWebServer()
    edge.start()
    origin.enqueue(
      MockResponse().setResponseCode(302).setHeader("Location", edge.url("/index.m3u8"))
    )
    edge.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-ENDLIST\n"))
    origin.start()
    try {
      val playlists = BrowserPlaylists(resolver)
      val retained =
        playlists.retain(
          ResolveResponse.newBuilder()
            .addStreams(
              StreamCandidate.newBuilder()
                .setSourcePage(origin.url("/page").toString())
                .setStream(
                  Stream.newBuilder()
                    .setUrl(origin.url("/index.m3u8").toString())
                    .putHeaders("Authorization", "source-secret")
                    .putHeaders("Cookie", "source-session")
                )
            )
            .build(),
          "http://127.0.0.1:1",
        )
      assertEquals(
        200,
        playlists
          .read(java.net.URI(retained.getStreams(0).stream.url).path, "http://127.0.0.1:1")!!
          .status,
      )
      val initial = origin.takeRequest()
      val redirected = edge.takeRequest()
      assertEquals("source-secret", initial.getHeader("Authorization"))
      assertEquals("source-session", initial.getHeader("Cookie"))
      assertNull(redirected.getHeader("Authorization"))
      assertNull(redirected.getHeader("Cookie"))
    } finally {
      origin.shutdown()
      edge.shutdown()
      withContext(Dispatchers.Main) { resolver.close() }
    }
  }

  @Test
  fun cancellingPlaylistReadDisconnectsItsBlockedOrigin() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val resolver = withContext(Dispatchers.Main) { BrowserResolver(context) }
    val origin = MockWebServer()
    origin.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    origin.start()
    try {
      val playlists = BrowserPlaylists(resolver)
      val retained =
        playlists.retain(
          ResolveResponse.newBuilder()
            .addStreams(
              StreamCandidate.newBuilder()
                .setSourcePage(origin.url("/page").toString())
                .setStream(Stream.newBuilder().setUrl(origin.url("/index.m3u8").toString()))
            )
            .build(),
          "http://127.0.0.1:1",
        )
      val pending = async {
        playlists.read(java.net.URI(retained.getStreams(0).stream.url).path, "http://127.0.0.1:1")
      }
      assertNotNull(
        withContext(Dispatchers.IO) { origin.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) }
      )
      withTimeout(5000) { pending.cancelAndJoin() }
      assertTrue(pending.isCancelled)
    } finally {
      origin.shutdown()
      withContext(Dispatchers.Main) { resolver.close() }
    }
  }

  @Test
  fun capturedPlaylistRenewsExpiredAccessAndKeepsRelativeMediaReferences() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val ticket = AtomicInteger(1)
    val pages = AtomicInteger()
    val refused = AtomicBoolean()
    val origin = MockWebServer()
    origin.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val path = request.requestUrl!!
          return when (path.encodedPath) {
            "/page" -> {
              pages.incrementAndGet()
              MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<script>fetch('/live/index.m3u8?ticket=${ticket.get()}');</script>")
            }
            "/live/index.m3u8" -> {
              if (refused.get() || path.queryParameter("ticket") != ticket.get().toString())
                MockResponse().setResponseCode(403)
              else
                MockResponse()
                  .setBody(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:6,\npiece.ts\n"
                  )
            }
            else -> MockResponse().setResponseCode(404)
          }
        }
      }
    origin.start()
    val resolver = withContext(Dispatchers.Main) { BrowserResolver(context) }
    val bridge = BrowserServer(resolver, { emptyList() }, "fixture-token")
    try {
      val api = Rpc(bridge.url, "fixture-token")
      val response = api.result {
        api.scraping.resolve(
          ResolveRequest.newBuilder().addUrls(origin.url("/page").toString()).build()
        )
      }
      assertEquals("application/x-mpegURL", response.streamsList.single().stream.contentType)
      val playlist = response.streamsList.single().stream.url
      ticket.set(2)
      val simultaneous =
        async(Dispatchers.IO) {
          val other = URL(playlist).openConnection() as HttpURLConnection
          try {
            assertEquals(200, other.responseCode)
            other.inputStream.use { it.readBytes() }
          } finally {
            other.disconnect()
          }
        }
      val connection =
        withContext(Dispatchers.IO) { URL(playlist).openConnection() as HttpURLConnection }
      try {
        val body =
          withContext(Dispatchers.IO) {
            assertEquals(200, connection.responseCode)
            connection.inputStream.bufferedReader().use { it.readText() }
          }
        simultaneous.await()
        assertTrue(body, body.contains(origin.url("/live/piece.ts").toString()))
        assertTrue(body, body.contains("URI=\"${origin.url("/live/key.bin")}\""))
        assertEquals("Expired access must reopen the original page once", 2, pages.get())
        assertTrue(
          "The capability belongs to the loopback bridge",
          playlist.startsWith(bridge.url + "/"),
        )
        assertFalse(resolver.active.value)
      } finally {
        connection.disconnect()
      }
      refused.set(true)
      repeat(2) {
        withContext(Dispatchers.IO) {
          val rejected = URL(playlist).openConnection() as HttpURLConnection
          try {
            assertEquals(403, rejected.responseCode)
          } finally {
            rejected.disconnect()
          }
        }
      }
      assertEquals("A permanent refusal renews only once", 3, pages.get())
      bridge.playbackEnded()
      withContext(Dispatchers.IO) {
        val expired = URL(playlist).openConnection() as HttpURLConnection
        try {
          assertEquals(404, expired.responseCode)
        } finally {
          expired.disconnect()
        }
      }
      val missing =
        withContext(Dispatchers.IO) {
          URL(bridge.url + "/playlists/unknown.m3u8").openConnection() as HttpURLConnection
        }
      try {
        assertEquals(404, withContext(Dispatchers.IO) { missing.responseCode })
      } finally {
        missing.disconnect()
      }
    } finally {
      bridge.close()
      withContext(Dispatchers.Main) { resolver.close() }
      origin.shutdown()
    }
  }

  @Test
  fun mimeTypedPlaylistWithoutSuffixIsRenewableAndCarriesNoBrowserRequestState() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val origin = MockWebServer()
    origin.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          when (request.requestUrl!!.encodedPath) {
            "/page" ->
              MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<script>fetch('/live/stream?id=7')</script>")
            // Gecko asks for compressed bodies; that request state must not reach other readers.
            "/live/stream" ->
              MockResponse()
                .setHeader("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
                .setHeader("Cache-Control", "no-store")
                .setBody("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\npiece.ts\n")
            else -> MockResponse().setResponseCode(404)
          }
      }
    origin.start()
    val resolver = withContext(Dispatchers.Main) { BrowserResolver(context) }
    val bridge = BrowserServer(resolver, { emptyList() }, "fixture-token")
    try {
      val api = Rpc(bridge.url, "fixture-token")
      val stream =
        api
          .result {
            api.scraping.resolve(
              ResolveRequest.newBuilder().addUrls(origin.url("/page").toString()).build()
            )
          }
          .streamsList
          .single()
          .stream
      assertEquals("application/x-mpegURL", stream.contentType)
      assertTrue(
        "MIME-typed HLS must use the renewable loopback playlist: ${stream.url}",
        stream.url.startsWith(bridge.url + "/"),
      )
      val replayed = stream.headersMap.keys.map { it.lowercase() }
      for (name in
        listOf("accept-encoding", "if-none-match", "if-modified-since", "range")) assertFalse(
        "$name must not be replayed: $replayed",
        name in replayed,
      )
      val body = withContext(Dispatchers.IO) { URL(stream.url).readText() }
      assertTrue(body, body.contains(origin.url("/live/piece.ts").toString()))
    } finally {
      bridge.close()
      withContext(Dispatchers.Main) { resolver.close() }
      origin.shutdown()
    }
  }

  // Streams a page's player loads through a service worker. Synthetic local servers only.

  private fun withWorkerFixture(test: suspend WorkerFixture.() -> Unit) =
    runBlocking<Unit> {
      val fixture = WorkerFixture(InstrumentationRegistry.getInstrumentation().targetContext)
      val outcome = runCatching { fixture.test() }
      val cleanup = runCatching { fixture.close() }
      outcome.getOrThrow()
      cleanup.getOrThrow()
    }

  private suspend fun waitFor(what: String, condition: () -> Boolean) {
    if (withTimeoutOrNull(30_000) { while (!condition()) delay(100) } == null) fail(what)
  }

  @Test
  fun streamAWorkerFetchesForAPlayerIsCapturedFromThePlayersDocument() = withWorkerFixture {
    val top = server()
    val frame = server()
    val player =
      player(frame, "ch" to "a", "load" to "/live/a/index.m3u8,/live/a/chunk-1.png", "rounds" to "40")
    val response = session().resolve(page(top, player), interactive = false)
    val streams = response.streamsList.associateBy { it.stream.url }
    val playlist = streams[frame.url("/live/a/index.m3u8").toString()]
    val segment = streams[frame.url("/live/a/chunk-1.png").toString()]
    assertNotNull("The playlist the worker fetched for the player must be captured: ${streams.keys}", playlist)
    assertNotNull("So must its segment, whose name is not a video's: ${streams.keys}", segment)
    assertEquals(2, streams.size)
    assertEquals(BrowserPageSession.HLS, playlist!!.stream.contentType)
    assertEquals("video/mp2t", segment!!.stream.contentType)
    assertEquals(pageAddress(top, player), playlist.sourcePage)
    // The request really carried the player's own header; the candidate carries only what follows
    // from the player's document.
    val sent = served.first { it.path == "/live/a/index.m3u8" }
    assertEquals("a", sent.playback)
    val expected = mapOf("Referer" to player, "User-Agent" to sent.agent)
    assertEquals(expected, playlist.stream.headersMap)
    assertEquals(expected, segment.stream.headersMap)
  }

  @Test
  fun pagesSharingAWorkerAndAnAddressEachGetACandidateOfTheirOwn() = withWorkerFixture {
    val top = server()
    val frame = server()
    val media = server()
    val shared = "/live/shared/index.m3u8"
    val remote = media.url("/live/remote/index.m3u8").toString()
    val origin = frame.url("/").toString().trimEnd('/')
    fun playerOf(ch: String) =
      player(frame, "ch" to ch, "load" to "$shared,$remote,/live/$ch/index.m3u8", "rounds" to "40")
    val (a, b) =
      coroutineScope {
        val first = async { session().resolve(page(top, playerOf("a")), interactive = false) }
        val second = async { session().resolve(page(top, playerOf("b")), interactive = false) }
        first.await() to second.await()
      }
    // Both players asked for the same addresses, each with its own header, in turns, and the
    // server answered each with its own representation.
    val turns = served.filter { it.path == shared }.map { it.playback }
    assertTrue("Both players must have asked: $turns", turns.containsAll(listOf("a", "b")))
    assertTrue("Their requests must alternate: $turns", turns.zipWithNext().count { it.first != it.second } >= 2)
    val agent = served.first { it.path == shared }.agent
    for ((ch, other, response) in listOf(Triple("a", "b", a), Triple("b", "a", b))) {
      val streams = response.streamsList.associate { it.stream.url to it.stream.headersMap }
      assertEquals(
        "Page $ch must receive its own addresses only",
        setOf(frame.url(shared).toString(), remote, frame.url("/live/$ch/index.m3u8").toString()),
        streams.keys,
      )
      val own = mapOf("Referer" to playerOf(ch), "User-Agent" to agent)
      assertEquals(own, streams[frame.url(shared).toString()])
      assertEquals(own, streams[frame.url("/live/$ch/index.m3u8").toString()])
      assertEquals(
        mapOf("Referer" to "$origin/", "Origin" to origin, "User-Agent" to agent),
        streams[remote],
      )
      assertFalse(
        "Nothing of page $other may reach page $ch: $streams",
        streams.toString().contains("ch=$other"),
      )
    }
  }

  @Test
  fun lateWorkerRequestForAReplacedPageBringsNothingOfItToTheNewDocument() = withWorkerFixture {
    val release = CountDownLatch(1)
    val top = server()
    val frame = server(held = release)
    val shared = "/live/shared/index.m3u8"
    val old = player(frame, "ch" to "old", "load" to shared, "rounds" to "40", "late" to shared)
    val new = player(frame, "ch" to "new", "load" to shared, "rounds" to "40")
    val session = session()
    session.showPage(pageAddress(top, old))
    waitFor("The first page's worker must have its own request for the address pending") {
      served.any { it.path == shared && it.playback == "old" } &&
        served.any { it.path == shared && it.playback == "late-old" }
    }
    val streams =
      coroutineScope {
        val resolving = async { session.resolve(page(top, new), interactive = false) }
        waitFor("The new page must be loading the same address") {
          served.any { it.path == shared && it.playback == "new" }
        }
        // The worker's request made for the page that is gone is answered only now.
        release.countDown()
        resolving.await().streamsList
      }
    assertTrue(
      "The late request must have been answered while the new page was searched",
      served.any { it.path == "$shared#answered" && it.playback == "late-old" },
    )
    val agent = served.first { it.path == shared && it.playback == "new" }.agent
    assertEquals(listOf(frame.url(shared).toString()), streams.map { it.stream.url })
    assertEquals(mapOf("Referer" to new, "User-Agent" to agent), streams.single().stream.headersMap)
    assertFalse("Nothing of the replaced page: $streams", streams.toString().contains("old"))
  }

  @Test
  fun lateWorkerRequestForAReleasedPageReachesNoOtherPage() = withWorkerFixture {
    val release = CountDownLatch(1)
    val top = server()
    val frame = server(held = release)
    val shared = "/live/shared/index.m3u8"
    val gone = player(frame, "ch" to "gone", "load" to shared, "rounds" to "40", "late" to shared)
    val kept = player(frame, "ch" to "kept", "load" to shared, "rounds" to "40")
    val released = session()
    released.showPage(pageAddress(top, gone))
    waitFor("The first page's worker must have its own request for the address pending") {
      served.any { it.path == shared && it.playback == "gone" } &&
        served.any { it.path == shared && it.playback == "late-gone" }
    }
    // A released page is a closed browser page: nothing is left of it to receive a candidate.
    withContext(Dispatchers.Main) { released.close() }
    val streams =
      coroutineScope {
        val resolving = async { session().resolve(page(top, kept), interactive = false) }
        waitFor("The remaining page must be loading the same address") {
          served.any { it.path == shared && it.playback == "kept" }
        }
        release.countDown()
        resolving.await().streamsList
      }
    assertTrue(
      "The late request must have been answered while the remaining page was searched",
      served.any { it.path == "$shared#answered" && it.playback == "late-gone" },
    )
    val agent = served.first { it.path == shared && it.playback == "kept" }.agent
    assertEquals(listOf(frame.url(shared).toString()), streams.map { it.stream.url })
    assertEquals(mapOf("Referer" to kept, "User-Agent" to agent), streams.single().stream.headersMap)
    assertFalse("Nothing of the released page: $streams", streams.toString().contains("gone"))
  }

  @Test
  fun pageLeftDuringASlowNavigationGivesNothingToTheNextPage() = withWorkerFixture {
    val release = CountDownLatch(1)
    val top = server(held = release)
    val frame = server()
    val playlist = "/live/old/index.m3u8"
    val old = player(frame, "ch" to "old", "load" to playlist, "rounds" to "200")
    val session = session()
    session.showPage(pageAddress(top, old))
    waitFor("The first page's player must be loading through its worker") {
      served.count { it.path == playlist } >= 2
    }
    val next = ResolveRequest.newBuilder().addUrls(top.url("/slow").toString()).build()
    val outcome =
      coroutineScope {
        val resolving = async { runCatching { session.resolve(next, interactive = false) } }
        // The next page's answer is kept back: the first page stays where it is and keeps loading.
        waitFor("The navigation to the next page must be waiting for its answer") {
          served.any { it.path == "/slow" }
        }
        val asked = served.indexOfFirst { it.path == "/slow" }
        waitFor("The first page must keep loading while the navigation waits") {
          served.drop(asked).count { it.path == playlist } >= 3
        }
        release.countDown()
        resolving.await()
      }
    assertTrue("The next page must have been answered", served.any { it.path == "/slow#answered" })
    val found = outcome.getOrNull()?.streamsList?.map { it.stream.url to it.stream.headersMap }
    assertTrue(
      "A page without media may find no stream, least of all the one of the page it replaced: $found",
      outcome.exceptionOrNull() is java.io.IOException,
    )
  }

  @Test
  fun addressesAWorkerRewritesOrRequestsItselfAreNotCaptured() = withWorkerFixture {
    val top = server()
    val frame = server()
    val player =
      player(
        frame,
        "ch" to "a",
        "load" to "/rewritten/a/index.m3u8",
        "rounds" to "100",
        "own" to "/own/a/index.m3u8",
      )
    val failure =
      runCatching { session().resolve(page(top, player), interactive = false) }.exceptionOrNull()
    assertTrue("No stream may be found: $failure", failure is java.io.IOException)
    assertTrue(
      "The worker must have fetched another address than the player asked for",
      served.any { it.path == "/upstream/a/index.m3u8" } &&
        served.none { it.path == "/rewritten/a/index.m3u8" },
    )
    assertTrue(
      "The worker must have fetched an address on its own",
      served.any { it.path == "/own/a/index.m3u8" && it.playback == "own-a" },
    )
  }

  @Test
  fun aWorkerOfAnotherOriginConfirmsNothingForADocument() = withWorkerFixture {
    val top = server()
    val media = server()
    // This origin's worker answers by itself: the address never reaches the network from it.
    val silent = server(worker = ANSWERING_WORKER)
    val fetching = server()
    val remote = media.url("/live/remote/index.m3u8").toString()
    val origin = fetching.url("/").toString().trimEnd('/')
    coroutineScope {
      val starved = async {
        runCatching {
            session()
              .resolve(
                page(top, player(silent, "ch" to "silent", "load" to remote, "rounds" to "100")),
                interactive = false,
              )
          }
          .exceptionOrNull()
      }
      val fed =
        session()
          .resolve(
            page(top, player(fetching, "ch" to "fetching", "load" to remote, "rounds" to "100")),
            interactive = false,
          )
          .streamsList
      val agent = served.first { it.path == "/live/remote/index.m3u8" }.agent
      assertEquals(listOf(remote), fed.map { it.stream.url })
      assertEquals(
        mapOf("Referer" to "$origin/", "Origin" to origin, "User-Agent" to agent),
        fed.single().stream.headersMap,
      )
      val failure = starved.await()
      assertTrue("The other origin's page may find no stream: $failure", failure is java.io.IOException)
    }
    assertTrue(
      "Only the fetching origin's worker may have reached the network",
      served.any { it.path == "/live/remote/index.m3u8" && it.playback == "fetching" } &&
        served.none { it.path == "/live/remote/index.m3u8" && it.playback == "silent" },
    )
  }

  @Test
  fun tabAndDedicatedWorkerRequestsKeepTheirRealHeaders() = withWorkerFixture {
    val top = server()
    val frame = server()
    val player =
      player(
        frame,
        "ch" to "a",
        "direct" to "1",
        "load" to "/live/direct/index.m3u8",
        "rounds" to "40",
        "dedicated" to "/live/dedicated/index.m3u8",
      )
    val streams =
      session().resolve(page(top, player), interactive = false).streamsList.associate {
        it.stream.url to it.stream.headersMap
      }
    val direct = frame.url("/live/direct/index.m3u8").toString()
    val dedicated = frame.url("/live/dedicated/index.m3u8").toString()
    assertEquals(setOf(direct, dedicated), streams.keys)
    assertEquals("a", streams[direct]!!["X-Synthetic-Playback"])
    assertEquals("dedicated-a", streams[dedicated]!!["X-Synthetic-Playback"])
  }
}

private class Served(val path: String, val playback: String?, val agent: String?)

// Local servers of synthetic players whose media goes through a service worker, and the browser pages
// that open them. Playlists are served as text/html and name the requester's X-Synthetic-Playback
// header, so two requesters of one address receive different representations.
private class WorkerFixture(private val context: Context) {
  val served = CopyOnWriteArrayList<Served>()
  private val servers = mutableListOf<MockWebServer>()
  private val sessions = mutableListOf<BrowserPageSession>()

  // `held` keeps back the answers to the requests a worker makes later on its own ("late-…") and
  // the answer to the page "/slow".
  fun server(worker: String = FORWARDING_WORKER, held: CountDownLatch? = null): MockWebServer {
    val server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          val url = request.requestUrl!!
          val path = url.encodedPath
          val playback = request.getHeader("X-Synthetic-Playback")
          if (request.method != "OPTIONS")
            served += Served(path, playback, request.getHeader("User-Agent"))
          fun html(body: String) =
            MockResponse().setHeader("Content-Type", "text/html").setBody(body)
          fun script(body: String) =
            MockResponse().setHeader("Content-Type", "text/javascript").setBody(body)
          val response =
            when {
              request.method == "OPTIONS" -> MockResponse().setResponseCode(204)
              path == "/page" -> {
                val frame = url.queryParameter("frame")!!.replace("&", "&amp;")
                html("<iframe src=\"$frame\" width=\"320\" height=\"180\"></iframe>")
              }
              path == "/player" -> html(PLAYER)
              path == "/sw.js" -> script(worker)
              path == "/dedicated.js" -> script(DEDICATED_WORKER)
              path == "/cleanup" -> html(CLEANUP)
              path == "/slow" -> {
                held?.await(40, TimeUnit.SECONDS)
                served += Served("$path#answered", playback, null)
                html("<!doctype html><title>no media</title><p>Nothing to play here.</p>")
              }
              path.endsWith(".m3u8") -> {
                if (playback?.startsWith("late-") == true) {
                  held?.await(40, TimeUnit.SECONDS)
                  served += Served("$path#answered", playback, null)
                }
                html("#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXTINF:3,\nchunk-for-$playback.png\n")
                  .setHeader("Cache-Control", "no-store")
              }
              path.contains("/chunk-") ->
                MockResponse()
                  .setHeader("Content-Type", "video/mp2t")
                  .setHeader("Cache-Control", "no-store")
                  .setBody("synthetic segment")
              else -> MockResponse().setBody("ok")
            }
          return response
            .setHeader("Access-Control-Allow-Origin", "*")
            .setHeader("Access-Control-Allow-Headers", "x-synthetic-playback")
        }
      }
    server.start()
    servers += server
    return server
  }

  suspend fun session(): BrowserPageSession =
    withContext(Dispatchers.Main) {
        BrowserPageSession(context, MutableStateFlow(false), MutableStateFlow<BrowserPage?>(null))
      }
      .also { sessions += it }

  // The address of a player on `frame`, steered by its query: see PLAYER.
  fun player(frame: MockWebServer, vararg query: Pair<String, String>): String =
    frame
      .url("/player")
      .newBuilder()
      .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
      .build()
      .toString()

  // A page of another origin that embeds that player in a frame.
  fun pageAddress(top: MockWebServer, player: String): String =
    top.url("/page").newBuilder().addQueryParameter("frame", player).build().toString()

  fun page(top: MockWebServer, player: String): ResolveRequest =
    ResolveRequest.newBuilder().addUrls(pageAddress(top, player)).build()

  // Leaves no service worker registration of these origins in the app's browser, then stops everything.
  suspend fun close() {
    try {
      val janitor = session()
      for (server in servers) {
        val before = served.count { it.path == "/cleaned" }
        janitor.showPage(server.url("/cleanup").toString())
        withTimeout(20_000) { while (served.count { it.path == "/cleaned" } == before) delay(100) }
        withContext(Dispatchers.Main) { janitor.dismissPage() }
      }
    } finally {
      withContext(Dispatchers.Main) { sessions.forEach { it.close() } }
      servers.forEach { it.shutdown() }
    }
  }
}

// A player steered by its query:
//   ch         the name it sends in its X-Synthetic-Playback header
//   load       comma-separated addresses it loads again and again, each with that header
//   rounds     how many times
//   late, own  an address its service worker requests on its own, with the header "late-<ch>" or "own-<ch>"
//   dedicated  an address a dedicated worker requests, with the header "dedicated-<ch>"
//   direct     "1": no service worker, the frame's own requests reach the network
private const val PLAYER =
  """<!doctype html><title>player</title><script>
const query = new URLSearchParams(location.search);
const ch = query.get('ch');
const load = address => fetch(address, {headers: {'X-Synthetic-Playback': ch}}).then(response => response.text());
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
(async () => {
  try {
    if (query.get('dedicated')) new Worker('/dedicated.js').postMessage({address: query.get('dedicated'), playback: 'dedicated-' + ch});
    if (!query.get('direct')) {
      await navigator.serviceWorker.register('/sw.js', {scope: '/player'});
      await navigator.serviceWorker.ready;
      if (!navigator.serviceWorker.controller) await new Promise(resolve => navigator.serviceWorker.addEventListener('controllerchange', resolve, {once: true}));
      for (const name of ['late', 'own']) if (query.get(name)) navigator.serviceWorker.controller.postMessage({address: query.get(name), playback: name + '-' + ch});
    }
    const addresses = (query.get('load') || '').split(',').filter(Boolean);
    for (let round = 0; round < +query.get('rounds'); round++) {
      for (const address of addresses) await load(address).catch(() => {});
      await sleep(300);
    }
  } catch (error) { fetch('/failed', {method: 'POST', body: String(error)}); }
})();
</script>"""

// Forwards the player's media requests, rewrites one family of addresses, and requests an address on its
// own when told to.
private const val FORWARDING_WORKER =
  """self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));
self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);
  if (url.pathname.startsWith('/rewritten/')) event.respondWith(fetch(url.pathname.replace('/rewritten/', '/upstream/')));
  else if (url.pathname.endsWith('.m3u8') || url.pathname.includes('/chunk-')) event.respondWith(fetch(event.request));
});
self.addEventListener('message', event => event.waitUntil(fetch(event.data.address, {headers: {'X-Synthetic-Playback': event.data.playback}}).then(response => response.text()).catch(() => {})));
"""

// Answers the player's playlist requests by itself, without the network.
private const val ANSWERING_WORKER =
  """self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));
self.addEventListener('fetch', event => {
  if (new URL(event.request.url).pathname.endsWith('.m3u8')) event.respondWith(new Response('#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXTINF:3,\nchunk-from-worker.png\n', {headers: {'Content-Type': 'text/html'}}));
});
"""

private const val DEDICATED_WORKER =
  """onmessage = event => fetch(event.data.address, {headers: {'X-Synthetic-Playback': event.data.playback}}).then(response => response.text());
"""

private const val CLEANUP =
  """<!doctype html><title>cleanup</title><script>
navigator.serviceWorker.getRegistrations().then(all => Promise.all(all.map(one => one.unregister()))).then(() => fetch('/cleaned', {method: 'POST'}));
</script>"""
