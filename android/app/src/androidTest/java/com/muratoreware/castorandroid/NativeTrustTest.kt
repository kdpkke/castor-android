package com.muratoreware.castorandroid

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class NativeTrustTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun nativeHttpsUsesExportedAnchorsAndRejectsUntrustedOrWrongHost() {
    val directory = Files.createTempDirectory(context.cacheDir.toPath(), "native-trust-").toFile()
    val bundle = nativeCaBundle(directory)
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    val expected =
      factory.trustManagers
        .filterIsInstance<X509TrustManager>()
        .flatMap {
          it.acceptedIssuers.toList()
        }
        .toSet()
    val exported =
      bundle.inputStream().use {
        CertificateFactory.getInstance("X.509").generateCertificates(it).toSet()
      }
    assertTrue("Android must supply trust anchors", expected.isNotEmpty())
    assertEquals("Native trust must match Android's default anchors", expected, exported)

    val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Fixture CA").build()
    val leaf =
      HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("localhost")
        .signedBy(root)
        .build()
    val certificates =
      HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build()
    val server = MockWebServer()
    server.useHttps(certificates.sslSocketFactory(), false)
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) =
          MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wave()))
      }
    server.start()
    try {
      val source = server.url("/fixture.wav").toString()
      val untrusted = probe(source, bundle)
      assertNotEquals("An unknown CA was accepted: ${untrusted.second}", 0, untrusted.first)
      assertTrue(untrusted.second.contains("certificate verify failed"))

      // Only this fixture gets the test CA. Production exports Android's anchors unchanged.
      val trusted = File(directory, "fixture-ca.pem")
      trusted.writeText(bundle.readText() + root.certificatePem())
      val valid = probe(source, trusted)
      assertEquals(valid.second, 0, valid.first)
      assertTrue(valid.second.contains("wav"))

      val wrongHost =
        probe(server.url("/fixture.wav").newBuilder().host("127.0.0.1").build().toString(), trusted)
      assertNotEquals(
        "A certificate for the wrong host was accepted: ${wrongHost.second}",
        0,
        wrongHost.first,
      )
    } finally {
      server.shutdown()
      bundle.delete()
      File(directory, "fixture-ca.pem").delete()
      directory.delete()
    }
  }

  private fun probe(url: String, bundle: File): Pair<Int, String> {
    val builder =
      ProcessBuilder(
          "${context.applicationInfo.nativeLibraryDir}/libffprobe.so",
          "-v",
          "error",
          "-tls_verify",
          "1",
          "-rw_timeout",
          "5000000",
          "-show_entries",
          "format=format_name",
          "-of",
          "default=nw=1:nk=1",
          url,
        )
        .redirectErrorStream(true)
    builder.environment()["SSL_CERT_FILE"] = bundle.path
    builder.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
    val process = builder.start()
    val output = process.inputStream.bufferedReader().readText()
    return process.waitFor() to output
  }

  private fun wave(): ByteArray =
    ByteBuffer.allocate(44 + 1600)
      .order(ByteOrder.LITTLE_ENDIAN)
      .apply {
        put("RIFF".toByteArray())
        putInt(36 + 1600)
        put("WAVEfmt ".toByteArray())
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(8000)
        putInt(16000)
        putShort(2)
        putShort(16)
        put("data".toByteArray())
        putInt(1600)
      }
      .array()
}
