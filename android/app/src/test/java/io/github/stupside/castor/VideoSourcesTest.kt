package io.github.stupside.castor

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class VideoSourcesTest {
  @Test
  fun extensionlessAttachmentIsRecognizedWithoutDownloadingTheVideo() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(
        MockResponse()
          .setHeader("Content-Type", "application/octet-stream")
          .setHeader("Content-Disposition", "attachment; filename=\"movie.mkv\"")
          .setHeader("Content-Length", "2443321123")
      )
      val source = VideoSources.automatic(server.url("/download?token=fixture").toString())
      assertTrue(source.hasStream())
      assertEquals("video/x-matroska", source.stream.contentType)
      assertEquals("HEAD", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
      assertEquals(1, server.requestCount)
    }
  }

  @Test
  fun headFailureFallsBackToOneByteRangeAndDoesNotReadTheBody() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(405))
      server.enqueue(
        MockResponse()
          .setResponseCode(206)
          .setHeader("Content-Type", "video/mp4")
          .setBody("a slow video body")
          .setBodyDelay(2, TimeUnit.SECONDS)
      )
      val source = withTimeout(5000) { VideoSources.automatic(server.url("/download").toString()) }
      assertTrue(source.hasStream())
      server.takeRequest()
      val request = server.takeRequest()
      assertEquals("GET", request.method)
      assertEquals("bytes=0-0", request.getHeader("Range"))
    }
  }

  @Test
  fun htmlIsStillOpenedAsAWebpage() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8"))
      assertTrue(VideoSources.automatic(server.url("/watch").toString()).hasPages())
      assertEquals(1, server.requestCount)
    }
  }

  @Test
  fun cancellingDetectionCancelsTheNetworkRequest() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
      val detection = async { VideoSources.automatic(server.url("/download").toString()) }
      withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
      withTimeout(3000) { detection.cancelAndJoin() }
      assertTrue(detection.isCancelled)
    }
  }

  @Test
  fun downloadMetadataSupportsEncodedNamesAndRejectsHtml() {
    assertEquals(
      "video/x-matroska",
      VideoSources.detectedType(
        "application/octet-stream",
        "attachment; filename*=UTF-8''Video%20di%20prova.MKV",
      ),
    )
    assertEquals(
      "application/vnd.apple.mpegurl",
      VideoSources.detectedType("application/x-mpegURL", null),
    )
    assertEquals("", VideoSources.detectedType("text/html", "attachment; filename=movie.mp4"))
    assertEquals(
      "",
      VideoSources.detectedType("application/octet-stream", "attachment; filename=archive.zip"),
    )
  }
}
