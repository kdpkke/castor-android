package io.github.stupside.castor

import android.annotation.SuppressLint
import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// The browsers this phone's owner allowed to cast through Castor. Only a SHA-256 of each granted
// token is kept, so reading the preferences file does not reveal a usable token. Writes commit at
// once: a browser receives its token only once it is stored, and a removal holds once confirmed.
@SuppressLint("ApplySharedPref")
class ExtensionAccess(context: Context) {
  private val preferences = context.getSharedPreferences("browser-extensions", Context.MODE_PRIVATE)

  private fun hashes(): Set<String> = preferences.getStringSet("tokens", emptySet()).orEmpty()

  fun allowed(token: String): Boolean = token.isNotEmpty() && hash(token) in hashes()

  fun grant(): String {
    val token =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    preferences.edit().putStringSet("tokens", hashes() + hash(token)).commit()
    return token
  }

  fun count(): Int = hashes().size

  fun revokeAll() {
    preferences.edit().remove("tokens").commit()
  }

  private fun hash(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") {
      "%02x".format(it)
    }
}
