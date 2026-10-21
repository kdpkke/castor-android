package io.github.stupside.castor

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.Reader
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Immutable host rules shared by request interception and HTML video capture. */
internal class BrowserAdBlock private constructor(private val hosts: Set<String>) {
  fun blocks(url: String): Boolean {
    var host = url.toHttpUrlOrNull()?.host?.trimEnd('.') ?: return false
    while (true) {
      if (host in hosts) return true
      val dot = host.indexOf('.')
      if (dot < 0) return false
      host = host.substring(dot + 1)
    }
  }

  fun intercept(url: String): WebResourceResponse? =
    if (blocks(url))
      WebResourceResponse(
        "text/plain",
        "UTF-8",
        204,
        "No Content",
        mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(ByteArray(0)),
      )
    else null

  companion object {
    @Volatile private var bundled: BrowserAdBlock? = null

    fun bundled(context: Context): BrowserAdBlock =
      bundled
        ?: synchronized(this) {
          bundled
            ?: context.assets.open("adblock/hosts.txt").bufferedReader().use(::read).also {
              bundled = it
            }
        }

    fun read(reader: Reader): BrowserAdBlock {
      val hosts = mutableSetOf<String>()
      reader.buffered().forEachLine { line ->
        val fields = line.substringBefore('#').trim().split(Regex("\\s+"))
        if (fields.firstOrNull() in setOf("0.0.0.0", "127.0.0.1", "::1")) {
          for (field in fields.drop(1)) {
            val host = field.lowercase(Locale.ROOT).trimEnd('.')
            // A hosts file also contains localhost mappings; never block the app's loopback APIs.
            if (
              host != "localhost" &&
                host.isNotEmpty() &&
                "http://$host".toHttpUrlOrNull()?.host == host
            )
              hosts += host
          }
        }
      }
      check(hosts.isNotEmpty()) { "The bundled browser blocklist is empty" }
      return BrowserAdBlock(hosts)
    }
  }
}
