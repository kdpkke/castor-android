package io.github.stupside.castor

import castor.scraping.v1.Scraping.ResolveRequest
import castor.scraping.v1.Scraping.ResolveResponse
import castor.v1.StreamOuterClass.Stream
import castor.v1.StreamOuterClass.StreamCandidate
import io.github.stupside.castor.data.Rpc
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Captured HLS stays on a capability URL; only the original page can renew its access.
internal class BrowserPlaylists(private val resolver: BrowserResolver) {
  private class Session(var candidate: StreamCandidate) {
    val mutex = Mutex()
    var refused: Document? = null
  }

  internal data class Document(val status: Int, val body: String, val from: String = "")

  private val sessions = mutableMapOf<String, Session>()
  private val routes = mutableMapOf<Pair<String, String>, String>()
  private val uriAttribute = Regex("([:,]\\s*URI=\")([^\"]*)(\")")

  @Synchronized
  private fun register(candidate: StreamCandidate, base: String): String {
    val key = candidate.sourcePage to candidate.stream.url
    routes[key]?.let {
      return base + it
    }
    check(sessions.size < 1600) { "Too many captured playlists" }
    val path = "/playlists/${UUID.randomUUID()}.m3u8"
    sessions[path] = Session(candidate)
    routes[key] = path
    return base + path
  }

  fun retain(response: ResolveResponse, base: String): ResolveResponse =
    response
      .toBuilder()
      .clearStreams()
      .addAllStreams(
        response.streamsList.map { candidate ->
          if (!hls(candidate.stream)) candidate
          else
            candidate
              .toBuilder()
              .setStream(candidate.stream.toBuilder().setUrl(register(candidate, base)))
              .build()
        }
      )
      .build()

  suspend fun read(path: String, base: String): Document? {
    val session = synchronized(this) { sessions[path] } ?: return null
    return session.mutex.withLock {
      session.refused?.let {
        return@withLock it
      }
      var document = fetch(session.candidate)
      if (document.status == 401 || document.status == 403) {
        val old = URI(session.candidate.stream.url)
        val fresh =
          try {
            resolver.resolve(
              ResolveRequest.newBuilder().addUrls(session.candidate.sourcePage).build(),
              interactive = false,
            )
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            session.refused = document
            return@withLock document
          }
        val matches =
          fresh.streamsList
            .filter {
              val next = URI(it.stream.url)
              old.scheme == next.scheme &&
                old.rawAuthority == next.rawAuthority &&
                old.rawPath == next.rawPath
            }
            .distinctBy { it.stream.url }
        if (matches.size != 1) {
          session.refused = Document(403, "The page could not renew the selected playlist")
          return@withLock session.refused!!
        }
        session.candidate = matches.single()
        currentCoroutineContext().ensureActive()
        document = fetch(session.candidate)
        if (document.status == 401 || document.status == 403) session.refused = document
      }
      if (document.status !in 200..299) return@withLock document
      check(document.body.trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")) {
        "The captured source returned an invalid HLS playlist"
      }
      val origin = URI(document.from)
      fun reference(value: String): String {
        val absolute = origin.resolve(value).toASCIIString()
        check(absolute.length <= 8192 && BrowserResolver.isHttp(absolute)) {
          "Unsupported playlist resource URL"
        }
        if (!hls(absolute)) return absolute
        val candidate =
          session.candidate
            .toBuilder()
            .setStream(session.candidate.stream.toBuilder().setUrl(absolute))
            .build()
        return register(candidate, base)
      }
      var renderedBytes = 0
      val body = buildString {
        for (line in document.body.removePrefix("\uFEFF").lineSequence()) {
          val text = line.trim()
          var expanded = text.length
          val rewritten =
            when {
              text.isEmpty() -> text
              !text.startsWith('#') -> reference(text)
              else ->
                uriAttribute.replace(text) {
                  val absolute = reference(it.groupValues[2])
                  expanded += absolute.length - it.groupValues[2].length
                  check(length + expanded + 1 <= DOCUMENT_LIMIT) {
                    "Expanded playlist exceeds the document limit"
                  }
                  it.groupValues[1] + absolute + it.groupValues[3]
                }
            }
          check(length + rewritten.length + 1 <= DOCUMENT_LIMIT) {
            "Expanded playlist exceeds the document limit"
          }
          renderedBytes += rewritten.toByteArray(Charsets.UTF_8).size + 1
          check(renderedBytes <= DOCUMENT_LIMIT) { "Expanded playlist exceeds the document limit" }
          append(rewritten).append('\n')
        }
      }
      Document(200, body)
    }
  }

  private suspend fun fetch(candidate: StreamCandidate): Document =
    withContext(Dispatchers.IO) {
      var address = candidate.stream.url
      var headers = candidate.stream.headersMap.filterKeys { it.lowercase() != "accept-encoding" }
      repeat(6) {
        check(BrowserResolver.isHttp(address)) { "Unsupported playlist URL" }
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        val document = coroutineScope {
          val cancelled =
            launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
              try {
                awaitCancellation()
              } finally {
                connection.disconnect()
              }
            }
          try {
            val status = connection.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
              Document(
                status,
                connection.getHeaderField("Location") ?: error("Missing playlist redirect"),
                address,
              )
            } else if (status !in 200..299) {
              Document(status, "Playlist source returned HTTP $status", address)
            } else {
              val body =
                connection.inputStream
                  .use { Rpc.readLimited(it, DOCUMENT_LIMIT) }
                  .toString(Charsets.UTF_8)
              Document(status, body, address)
            }
          } finally {
            connection.disconnect()
            cancelled.cancelAndJoin()
          }
        }
        if (document.status !in setOf(301, 302, 303, 307, 308)) return@withContext document
        val next = URI(address).resolve(document.body)
        val previous = URI(address)
        if (previous.scheme != next.scheme || previous.rawAuthority != next.rawAuthority) {
          headers =
            headers.filterKeys {
              it.lowercase() !in setOf("authorization", "cookie", "proxy-authorization")
            }
        }
        address = next.toString()
      }
      error("Too many playlist redirects")
    }

  @Synchronized
  fun clear() {
    sessions.clear()
    routes.clear()
  }

  // The browser names HLS by MIME type even when the URL has no .m3u8 suffix; both need renewal.
  private fun hls(stream: Stream) =
    stream.contentType == BrowserResolver.HLS && BrowserResolver.isHttp(stream.url) ||
      hls(stream.url)

  private fun hls(value: String) =
    BrowserResolver.isHttp(value) && URI(value).path.orEmpty().endsWith(".m3u8", ignoreCase = true)

  companion object {
    private const val DOCUMENT_LIMIT = 4 * 1024 * 1024
  }
}
