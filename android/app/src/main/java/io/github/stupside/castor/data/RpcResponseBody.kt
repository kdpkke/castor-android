package io.github.stupside.castor.data

import java.io.IOException
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer

// Bound bytes before Connect buffers a unary response or allocates a stream message.
internal class RpcResponseBody(private val body: ResponseBody, private val streaming: Boolean) :
  ResponseBody() {
  private val bounded =
    object : Source {
        private val input = body.source()
        private var remaining = 0L
        private var received = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
          require(byteCount >= 0)
          if (byteCount == 0L) return 0L
          val count =
            if (streaming) {
              if (remaining == 0L && input.request(5)) {
                val flags = input.buffer[0].toInt() and 255
                if (flags != 0 && flags != 2) throw IOException("Unsupported status message flags")
                var length = 0L
                for (index in 1L..4L) length =
                  (length shl 8) or (input.buffer[index].toLong() and 255)
                if (length > MAX_MESSAGE) throw IOException("Invalid status message length")
                remaining = length + 5
              }
              input.read(sink, minOf(byteCount, if (remaining > 0) remaining else 5L)).also {
                if (it > 0) remaining -= it
              }
            } else {
              input.read(sink, minOf(byteCount, MAX_MESSAGE - received + 1)).also {
                if (it > 0) {
                  received += it
                  if (received > MAX_MESSAGE)
                    throw IOException("Castor response exceeds the message limit")
                }
              }
            }
          return count
        }

        override fun timeout() = input.timeout()

        override fun close() = input.close()
      }
      .buffer()

  override fun contentType() = body.contentType()

  override fun contentLength() = body.contentLength()

  override fun source(): BufferedSource = bounded

  companion object {
    private const val MAX_MESSAGE = 8L * 1024 * 1024
  }
}
