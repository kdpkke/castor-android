package io.github.stupside.castor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import castor.scraping.v1.Scraping.ResolveRequest
import castor.v1.CastOuterClass.*
import castor.v1.DeviceOuterClass.DeviceType
import castor.v1.DeviceOuterClass.Target
import castor.v1.StreamOuterClass.Stream
import io.github.stupside.castor.data.DataRepository
import io.github.stupside.castor.data.Rpc
import io.github.stupside.castor.ui.main.AppLanguage
import io.github.stupside.castor.ui.main.CastOptions
import io.github.stupside.castor.ui.main.CastPhase
import io.github.stupside.castor.ui.main.MainScreenViewModel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class CastorRuntimeTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  @Test
  fun platformBridgeAuthenticatesEmptyDiscoveryRequests() = runBlocking {
    val browser = withContext(Dispatchers.Main) { BrowserResolver(context) }
    var calls = 0
    val device =
      castor.v1.DeviceOuterClass.Device.newBuilder()
        .setId("fixture")
        .setName("Fixture TV")
        .setType(DeviceType.DEVICE_TYPE_DLNA)
        .setAddress("http://127.0.0.1/description.xml")
        .build()
    val bridge =
      BrowserServer(
        browser,
        {
          calls++
          listOf(device)
        },
        "fixture-token",
      )
    try {
      val devices = DataRepository(Rpc(bridge.url, "fixture-token")).devices()
      assertEquals(listOf(device), devices)
      try {
        DataRepository(Rpc(bridge.url, "wrong-token")).devices()
        fail("An unauthorized native request succeeded")
      } catch (_: java.io.IOException) {}
      assertEquals(1, calls)
      try {
        val api = Rpc(bridge.url, "fixture-token")
        api.result { api.scraping.resolve(ResolveRequest.getDefaultInstance()) }
        fail("An empty page request was accepted")
      } catch (error: java.io.IOException) {
        assertTrue(error.message.orEmpty().contains("invalid_argument"))
      }
    } finally {
      bridge.close()
      withContext(Dispatchers.Main) { browser.close() }
    }
  }

  @Test
  fun nativeTextAndHdrFiltersEncodeAFrame() {
    val command =
      ProcessBuilder(
        "${context.applicationInfo.nativeLibraryDir}/libffmpeg.so",
        "-hide_banner",
        "-f",
        "lavfi",
        "-i",
        "testsrc2=size=256x144:rate=25:duration=0.1",
        "-vf",
        "setparams=range=limited:color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc,zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,format=yuv420p,drawtext=fontfile=/system/fonts/Roboto-Regular.ttf:text=Castor",
        "-frames:v",
        "1",
        "-c:v",
        "libx264",
        "-f",
        "null",
        "-",
      )
    command.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
    val process = command.redirectErrorStream(true).start()
    val log = process.inputStream.bufferedReader().readText()
    assertEquals(log, 0, process.waitFor())
  }

  @Test fun phoneEngineTranscodesForADlnaReceiverAndStops() = castFixture(false)

  @Test fun pageWithPixelWrappedHlsIsAdmittedAndCasts() = castFixture(true)

  @Test
  fun livePageRenewsExpiredPlaylistWhileThePhoneKeepsServing() = castFixture(true, renewing = true)

  @Test
  fun failedPlaylistRenewalIsAnErrorInsteadOfCompletion() =
    castFixture(true, renewing = true, denyRenewal = true)

  @Test
  fun nativeQualityKeeps1440pInsteadOfInheritingThe1080pDefault() =
    castFixture(false, nativeQuality = true)

  private fun castFixture(
    pixels: Boolean,
    nativeQuality: Boolean = false,
    renewing: Boolean = false,
    denyRenewal: Boolean = false,
  ) = runBlocking {
    grantPermissions()
    ActivityScenario.launch(MainActivity::class.java).use { activity ->
      val bound = CompletableDeferred<CastorService>()
      val connection =
        object : ServiceConnection {
          override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            bound.complete((binder as CastorService.LocalBinder).service)
          }

          override fun onServiceDisconnected(name: ComponentName) {}
        }
      context.bindService(
        Intent(context, CastorService::class.java),
        connection,
        Context.BIND_AUTO_CREATE,
      )
      try {
        val service = withTimeout(10_000) { bound.await() }
        val api =
          withTimeout(60_000) {
            while (service.endpoint.value == null) {
              service.failure.value?.let { error(it) }
              delay(100)
            }
            service.endpoint.value!!
          }
        val sample =
          File.createTempFile("castor-fixture", if (pixels) ".ts" else ".mp4", context.cacheDir)
        val duration = if (renewing) 4 else if (pixels) 60 else if (nativeQuality) 12 else 30
        val command =
          listOf(
            "${context.applicationInfo.nativeLibraryDir}/libffmpeg.so",
            "-y",
            "-hide_banner",
            "-f",
            "lavfi",
            "-i",
            if (nativeQuality) "testsrc2=size=256x1440:rate=2" else "testsrc2=size=320x180:rate=25",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=1000:sample_rate=48000",
            "-t",
            duration.toString(),
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
          ) +
            (if (pixels) listOf("-f", "mpegts") else listOf("-movflags", "+faststart")) +
            sample.path
        val encoder =
          ProcessBuilder(command)
            .apply { environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir }
            .redirectErrorStream(true)
            .start()
        val log = encoder.inputStream.bufferedReader().readText()
        assertEquals(log, 0, encoder.waitFor())
        assertTrue(sample.length() > 0)
        val wrapped = if (pixels) pixelSegment(sample.readBytes()) else null
        val nativeMedia = java.io.ByteArrayOutputStream()
        val received = CountDownLatch(1)
        val confirmed = CountDownLatch(1)
        val continued = CountDownLatch(1)
        val receivedBytes = java.util.concurrent.atomic.AtomicLong()
        val fetches = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = MockWebServer()
        var uri = ""
        val tickets = java.util.concurrent.atomic.AtomicInteger()
        val issued = java.util.concurrent.atomic.AtomicLong()
        val blocked = java.util.concurrent.atomic.AtomicBoolean()
        val began = android.os.SystemClock.elapsedRealtime()
        server.dispatcher =
          object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
              if (renewing && request.path == "/watch") {
                val ticket = tickets.incrementAndGet()
                issued.set(android.os.SystemClock.elapsedRealtime())
                return MockResponse()
                  .setHeader("Content-Type", "text/html")
                  .setBody("<script>fetch('/video.m3u8?ticket=$ticket');</script>")
              }
              if (renewing && request.requestUrl!!.encodedPath == "/video.m3u8") {
                if (
                  blocked.get() ||
                    request.requestUrl!!.queryParameter("ticket") != tickets.get().toString() ||
                    android.os.SystemClock.elapsedRealtime() - issued.get() >= 8000
                )
                  return MockResponse().setResponseCode(403)
                val first = (android.os.SystemClock.elapsedRealtime() - began) / 4000
                return MockResponse()
                  .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                  .setBody(
                    "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:$first\n" +
                      (first..first + 2).joinToString("") {
                        "#EXT-X-DISCONTINUITY\n#EXTINF:4,\nsegment.png?n=$it\n"
                      }
                  )
              }
              if (renewing && request.requestUrl!!.encodedPath == "/segment.png")
                return MockResponse()
                  .setHeader("Content-Type", "image/png")
                  .setBody(Buffer().write(wrapped!!))
              if (pixels && request.path == "/watch")
                return MockResponse()
                  .setHeader("Content-Type", "text/html")
                  .setBody("<video src='/video.m3u8'></video>")
              if (pixels && request.path == "/video.m3u8")
                return MockResponse()
                  .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                  .setBody(
                    "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$duration\n#EXTINF:$duration,\nsegment.png\n#EXT-X-ENDLIST\n"
                  )
              if (pixels && request.path == "/segment.png")
                return MockResponse()
                  .setHeader("Content-Type", "image/png")
                  .setBody(Buffer().write(wrapped!!))
              if (request.path == "/video.mp4") {
                val bytes = sample.readBytes()
                val start =
                  request
                    .getHeader("Range")
                    ?.substringAfter("bytes=")
                    ?.substringBefore('-')
                    ?.toIntOrNull() ?: 0
                return MockResponse()
                  .setHeader("Content-Type", "video/mp4")
                  .setHeader("Accept-Ranges", "bytes")
                  .setResponseCode(if (request.getHeader("Range") != null) 206 else 200)
                  .apply {
                    if (request.getHeader("Range") != null)
                      setHeader("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
                  }
                  .setBody(Buffer().write(bytes, start, bytes.size - start))
              }
              if (request.path == "/description.xml") return xml(description)
              val soap = request.getHeader("SOAPAction").orEmpty().trim('"')
              val action = soap.substringAfter('#')
              val type = soap.substringBefore('#')
              val body =
                when (action) {
                  "GetProtocolInfo" ->
                    "<Source/><Sink>http-get:*:video/mp2t:DLNA.ORG_PN=AVC_TS_HD_50_AC3_ISO,http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_MP_HD_AAC</Sink>"
                  "SetAVTransportURI" -> {
                    uri =
                      Regex("<CurrentURI>(.*?)</CurrentURI>")
                        .find(request.body.readUtf8())!!
                        .groupValues[1]
                        .replace("&amp;", "&")
                    ""
                  }
                  "Play" -> {
                    fetches.launch {
                      val response = URL(uri).openConnection() as HttpURLConnection
                      response.connectTimeout = 15_000
                      response.readTimeout = 30_000
                      try {
                        response.inputStream.use { input ->
                          val buffer = ByteArray(4096)
                          while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            receivedBytes.addAndGet(count.toLong())
                            if (nativeQuality)
                              synchronized(nativeMedia) {
                                if (nativeMedia.size() < 2 * 1024 * 1024)
                                  nativeMedia.write(buffer, 0, count)
                              }
                            if (confirmed.count == 0L) continued.countDown()
                            received.countDown()
                            if (pixels && confirmed.count > 0L) {
                              check(confirmed.await(20, TimeUnit.SECONDS)) {
                                "Playback was not confirmed after the missing Play acknowledgement"
                              }
                            }
                            delay(20)
                          }
                        }
                      } finally {
                        response.disconnect()
                      }
                    }
                    ""
                  }
                  "GetTransportInfo" ->
                    "<CurrentTransportState>PLAYING</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>"
                  "GetMediaInfo" -> {
                    confirmed.countDown()
                    "<CurrentURI>${uri.replace("&", "&amp;")}</CurrentURI>"
                  }
                  "Stop",
                  "Pause" -> ""
                  else -> return MockResponse().setResponseCode(500)
                }
              return xml(
                  """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response xmlns:u="$type">$body</u:${action}Response></s:Body></s:Envelope>"""
                )
                .apply {
                  // The receiver is already reading, but its Play acknowledgement misses the
                  // deadline.
                  if (pixels && action == "Play") setHeadersDelay(15, TimeUnit.SECONDS)
                }
            }
          }
        server.start()
        try {
          val data = DataRepository(api)
          val request =
            CastRequest.newBuilder()
              .setTarget(
                Target.newBuilder()
                  .setPinned(
                    Target.Pinned.newBuilder()
                      .setType(DeviceType.DEVICE_TYPE_DLNA)
                      .setAddress(server.url("/description.xml").toString())
                  )
              )
              .setSource(
                Source.newBuilder().apply {
                  if (pixels)
                    setPages(Source.Pages.newBuilder().addUrls(server.url("/watch").toString()))
                  else setStream(Stream.newBuilder().setUrl(server.url("/video.mp4").toString()))
                }
              )
              .setPreferences(Preferences.newBuilder().setMaxHeight(90))
              .build()
          val chosenRequest =
            if (nativeQuality)
              io.github.stupside.castor.ui.main.MainScreenViewModel.request(
                server.url("/video.mp4").toString(),
                false,
                request.target,
                "native",
              )
            else request
          var model: MainScreenViewModel? = null
          val id =
            if (!pixels && !nativeQuality) {
              activity.onActivity {
                model = ViewModelProvider(it)[MainScreenViewModel::class.java]
                model!!.cast(
                  server.url("/video.mp4").toString(),
                  false,
                  request.target,
                  "90",
                  "off",
                )
              }
              withTimeout(15_000) {
                while (model!!.uiState.value.castId == null) delay(50)
                model!!.uiState.value.castId!!
              }
            } else data.cast(chosenRequest)
          activity.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
          var ended: Ended? = null
          val watcher = async {
            data.watch(id) { update ->
              if (update.hasEnded()) ended = update.ended
            }
          }
          assertTrue(
            "Receiver did not receive transcoded media; ${service.failure.value}",
            withContext(Dispatchers.IO) { received.await(90, TimeUnit.SECONDS) },
          )
          assertTrue("DLNA must fetch the phone's media route", uri.contains("/media/"))
          if (model != null) {
            val running = model!!
            activity.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            withTimeout(15_000) {
              while (running.uiState.value.phase != CastPhase.Casting) delay(50)
            }
            for (language in listOf(AppLanguage.Italian, AppLanguage.English)) {
              CastOptions(context).language(language)
              activity.recreate()
              activity.onActivity {
                assertSame(running, ViewModelProvider(it)[MainScreenViewModel::class.java])
              }
              // Let the recreated activity rebind while the receiver continues reading media.
              val before = receivedBytes.get()
              withTimeout(5000) { while (receivedBytes.get() <= before) delay(50) }
              withContext(Dispatchers.Main) {
                assertEquals(id, running.uiState.value.castId)
                assertEquals(CastPhase.Casting, running.uiState.value.phase)
                assertNull(running.uiState.value.error)
                assertSame(api, service.endpoint.value)
                assertFalse(service.stopped.value)
              }
            }
          }
          if (pixels) {
            assertTrue(
              "Timed-out Play was not confirmed; $ended",
              withContext(Dispatchers.IO) { confirmed.await(20, TimeUnit.SECONDS) },
            )
            assertTrue(
              "Media stopped after the Play timeout; $ended",
              withContext(Dispatchers.IO) { continued.await(20, TimeUnit.SECONDS) },
            )
            assertNull("The playing cast ended after a missing acknowledgement", ended)
            if (renewing) {
              withTimeout(30_000) {
                while (tickets.get() < 3) delay(100)
              }
              val before = receivedBytes.get()
              withTimeout(15_000) { while (receivedBytes.get() <= before) delay(100) }
              assertNull("The live cast ended after renewed access", ended)
              if (denyRenewal) {
                blocked.set(true)
                withTimeout(45_000) { watcher.await() }
                assertTrue(
                  "Rejected renewal must fail instead of completing: $ended",
                  ended?.hasFailed() == true,
                )
                assertTrue(ended!!.failed.message, ended!!.failed.message.contains("403"))
              }
            }
          }
          if (nativeQuality) {
            withTimeout(15_000) {
              while (synchronized(nativeMedia) { nativeMedia.size() } < 128 * 1024) delay(100)
            }
            val captured = File.createTempFile("castor-native-quality", ".ts", context.cacheDir)
            try {
              captured.writeBytes(synchronized(nativeMedia) { nativeMedia.toByteArray() })
              val probe =
                ProcessBuilder(
                    "${context.applicationInfo.nativeLibraryDir}/libffprobe.so",
                    "-v",
                    "error",
                    "-select_streams",
                    "v:0",
                    "-show_entries",
                    "stream=height",
                    "-of",
                    "csv=p=0",
                    captured.path,
                  )
                  .apply {
                    environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
                  }
                  .start()
              val output = probe.inputStream.bufferedReader().readText()
              val errors = probe.errorStream.bufferedReader().readText()
              assertEquals(errors, 0, probe.waitFor())
              assertEquals(
                "Native output must retain the 1440p selected source: $output",
                setOf("1440"),
                output
                  .lineSequence()
                  .map { it.trim().trim(',') }
                  .filter { it.isNotBlank() }
                  .toSet(),
              )
            } finally {
              captured.delete()
            }
          }
          if (nativeQuality) {
            withTimeout(20_000) { watcher.await() }
            assertTrue(
              "The finite native-quality clip must complete: $ended",
              ended?.hasCompleted() == true,
            )
          } else if (!denyRenewal) {
            if (model != null) withContext(Dispatchers.Main) { model!!.stop() } else data.stop(id)
            withTimeout(20_000) { watcher.await() }
            assertTrue(
              "Stop must deliver an explicit stopped result: $ended",
              ended?.hasStopped() == true,
            )
            if (model != null) {
              withTimeout(5000) {
                while (model!!.uiState.value.phase != CastPhase.Stopped) delay(50)
              }
              assertNull(model!!.uiState.value.error)
            }
          }
        } finally {
          fetches.cancel()
          server.shutdown()
          sample.delete()
        }
      } finally {
        context.unbindService(connection)
        context.stopService(Intent(context, CastorService::class.java))
      }
    }
  }

  @Test
  fun browserCapturesVideoAndCancellationClosesThePage() = runBlocking {
    val server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) =
          when (request.path) {
            "/watch" ->
              MockResponse()
                .setHeader("Content-Type", "text/html")
                .setHeader("Set-Cookie", "session=fixture; Path=/")
                .setBody("<video controls autoplay src='/video'></video>")
            else -> MockResponse().setHeader("Content-Type", "text/html").setBody("<p>Fixture</p>")
          }
      }
    server.start()
    val resolver = withContext(Dispatchers.Main) { BrowserResolver(context) }
    try {
      val result =
        resolver.resolve(
          ResolveRequest.newBuilder().addUrls(server.url("/watch").toString()).build()
        )
      val candidate = result.streamsList.first { it.stream.url.endsWith("/video") }
      assertEquals(server.url("/watch").toString(), candidate.sourcePage)
      assertTrue(candidate.stream.headersMap["Cookie"].orEmpty().contains("session=fixture"))
      assertFalse(resolver.active.value)
      val waiting = launch {
        resolver.resolve(
          ResolveRequest.newBuilder().addUrls(server.url("/empty").toString()).build()
        )
      }
      withTimeout(5000) { resolver.active.first { it } }
      waiting.cancelAndJoin()
      assertFalse(resolver.active.value)
    } finally {
      withContext(Dispatchers.Main) { resolver.close() }
      server.shutdown()
    }
  }

  private fun grantPermissions() {
    if (android.os.Build.VERSION.SDK_INT >= 33)
      instrumentation.uiAutomation
        .executeShellCommand(
          "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
        )
        .close()
    if (android.os.Build.VERSION.SDK_INT >= 37)
      instrumentation.uiAutomation
        .executeShellCommand(
          "pm grant ${context.packageName} android.permission.ACCESS_LOCAL_NETWORK"
        )
        .close()
  }

  private fun xml(body: String) =
    MockResponse().setHeader("Content-Type", "text/xml; charset=utf-8").setBody(body)

  private fun pixelSegment(ts: ByteArray): ByteArray {
    val compressed =
      java.io
        .ByteArrayOutputStream()
        .apply {
          java.util.zip.GZIPOutputStream(this).use { it.write(ts) }
        }
        .toByteArray()
    val payload =
      java.nio.ByteBuffer.allocate(12 + compressed.size)
        .put("TIKTIKPX".toByteArray())
        .putInt(compressed.size)
        .put(compressed)
        .array()
    val width = 128
    val height = (payload.size + width * 3 - 1) / (width * 3)
    val colors =
      IntArray(width * height) { index ->
        fun channel(at: Int) = if (at < payload.size) payload[at].toInt() and 255 else 0
        android.graphics.Color.rgb(
          channel(index * 3),
          channel(index * 3 + 1),
          channel(index * 3 + 2),
        )
      }
    val bitmap =
      android.graphics.Bitmap.createBitmap(
        colors,
        width,
        height,
        android.graphics.Bitmap.Config.ARGB_8888,
      )
    return try {
      java.io
        .ByteArrayOutputStream()
        .apply {
          check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this))
        }
        .toByteArray()
    } finally {
      bitmap.recycle()
    }
  }

  private val description =
    """<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Android fixture TV</friendlyName><UDN>uuid:castor-android-fixture</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><SCPDURL>/cm.xml</SCPDURL><controlURL>/cm</controlURL><eventSubURL>/events</eventSubURL></service><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/av.xml</SCPDURL><controlURL>/av</controlURL><eventSubURL>/events</eventSubURL></service></serviceList></device></root>"""
}
