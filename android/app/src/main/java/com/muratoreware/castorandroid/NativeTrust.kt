package com.muratoreware.castorandroid

import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// Android's hashed CA directory uses a different filename scheme from OpenSSL.
// Export the app's default trust anchors as PEM instead of relying on that index.
internal fun nativeCaBundle(directory: File): File {
  val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
  factory.init(null as KeyStore?)
  val certificates =
    factory.trustManagers.filterIsInstance<X509TrustManager>().flatMap {
      it.acceptedIssuers.toList()
    }
  check(certificates.isNotEmpty()) { "Android did not supply any trusted CA certificates." }
  return File(directory, "native-ca.pem").apply {
    bufferedWriter(Charsets.US_ASCII).use { output ->
      for (certificate in certificates) {
        output.write("-----BEGIN CERTIFICATE-----\n")
        output.write(
          Base64.encodeToString(certificate.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n"),
        )
        output.write("\n-----END CERTIFICATE-----\n")
      }
    }
  }
}
