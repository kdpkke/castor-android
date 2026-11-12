package io.github.stupside.castor

import io.github.stupside.castor.ui.main.NoteBlock
import io.github.stupside.castor.ui.main.noteBlocks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleasesTest {
  private val files = "https://github.com/kdpkke/castor-android/releases/download/android-v1.4.0/"

  private fun release(
    tag: String = "android-v1.4.0",
    apk: String = files + "castor-android.apk",
    sums: String = files + "SHA256SUMS.txt",
    prerelease: Boolean = false,
    size: Long = 200_904_430,
  ): String =
    JSONObject()
      .put("tag_name", tag)
      .put("name", "Castor Android 1.4.0")
      .put("body", "Notes")
      .put("draft", false)
      .put("prerelease", prerelease)
      .put(
        "assets",
        JSONArray()
          .put(JSONObject().put("name", "castor-android-source.zip").put("size", 1).put("browser_download_url", files + "castor-android-source.zip"))
          .put(JSONObject().put("name", "castor-android.apk").put("size", size).put("browser_download_url", apk))
          .put(JSONObject().put("name", "SHA256SUMS.txt").put("size", 361).put("browser_download_url", sums)),
      )
      .toString()

  @Test
  fun aReleaseOfThisRepositoryIsReadWithItsOwnFiles() {
    val release = Releases.parse(release())!!
    assertEquals("1.4.0", release.version)
    assertEquals("Castor Android 1.4.0", release.title)
    assertEquals(files + "castor-android.apk", release.apk)
    assertEquals(files + "SHA256SUMS.txt", release.checksums)
    assertEquals("https://github.com/kdpkke/castor-android/releases/tag/android-v1.4.0", release.page)
    assertEquals(200_904_430, release.size)
  }

  @Test
  fun filesFromAnywhereElsePreReleasesAndOtherTagsAreNeverOffered() {
    assertNull(Releases.parse(release(apk = "https://example.org/castor-android.apk")))
    assertNull(Releases.parse(release(apk = files.replace("android-v1.4.0", "android-v1.3.0") + "castor-android.apk")))
    assertNull(Releases.parse(release(sums = "https://github.com/other/repo/releases/download/android-v1.4.0/SHA256SUMS.txt")))
    assertNull(Releases.parse(release(prerelease = true)))
    assertNull(Releases.parse(release(tag = "android-dev.14")))
    assertNull(Releases.parse(release(size = 0)))
    assertNull(Releases.parse("not json"))
  }

  @Test
  fun onlyALaterVersionIsNewerAndATestBuildComesBeforeItsVersion() {
    assertTrue(Releases.newer("1.4.0", "1.3.0"))
    assertTrue(Releases.newer("1.10.0", "1.9.9"))
    assertTrue(Releases.newer("2.0.0", "1.99.99"))
    assertTrue(Releases.newer("1.4.0", "1.4.0-test.33"))
    assertFalse(Releases.newer("1.4.0", "1.4.0"))
    assertFalse(Releases.newer("1.3.0", "1.4.0-test.33"))
    assertFalse(Releases.newer("1.3.9", "1.4.0"))
    assertFalse(Releases.newer("1.4", "1.3.0"))
    assertFalse(Releases.newer("1.4.0", ""))
  }

  @Test
  fun notesShowTheHalfInTheAppsLanguage() {
    val notes =
      "Castor puts videos on your TV.\n\n*Versione italiana più sotto.*\n\n## What's new\n\n- Faster\n\n---\n\n## Italiano\n\nCastor manda i video sulla TV.\n\n### Novità\n\n- Più veloce\n"
    assertEquals("Castor puts videos on your TV.\n\n\n## What's new\n\n- Faster", Releases.notes(notes, "en"))
    assertEquals("Castor manda i video sulla TV.\n\n### Novità\n\n- Più veloce", Releases.notes(notes, "it"))
    assertEquals("Only English", Releases.notes("Only English\n", "it"))
  }

  @Test
  fun theAppsChecksumIsReadAsSha256sumWritesIt() {
    val apk = "fe6b76e79cb72d6131c9b2f6cf4a11ff12c79757040b023cf85035997b1b7e26"
    val list =
      "1111111111111111111111111111111111111111111111111111111111111111  castor-android-source.zip\n" +
        "${apk.uppercase()} *castor-android.apk\n"
    assertEquals(apk, Releases.checksum(list))
    assertNull(Releases.checksum("$apk  castor-android.apk.sig\n"))
    assertNull(Releases.checksum("abc  castor-android.apk\n"))
  }

  @Test
  fun releaseNotesBecomeHeadingsParagraphsBulletsCodeAndRules() {
    val blocks =
      noteBlocks("## What's new\n\n**Casting**\n- One\n- Two\nwrapped\nline\n\n```text\nfe6b\n```\n---\n### Tested\n")
    assertEquals(
      listOf(
        NoteBlock.Heading(2, "What's new"),
        NoteBlock.Paragraph("**Casting**"),
        NoteBlock.Bullet("One"),
        NoteBlock.Bullet("Two"),
        NoteBlock.Paragraph("wrapped line"),
        NoteBlock.Code("fe6b"),
        NoteBlock.Rule,
        NoteBlock.Heading(3, "Tested"),
      ),
      blocks,
    )
  }
}
