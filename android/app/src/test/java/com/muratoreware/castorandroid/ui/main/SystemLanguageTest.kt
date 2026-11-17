package com.muratoreware.castorandroid.ui.main

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemLanguageTest {
  @Test
  fun anItalianPhoneGetsItalianAndEveryOtherGetsEnglish() {
    assertEquals(AppLanguage.Italian, AppLanguage.forLocale(Locale.ITALY))
    assertEquals(AppLanguage.Italian, AppLanguage.forLocale(Locale.forLanguageTag("it-CH")))
    assertEquals(AppLanguage.English, AppLanguage.forLocale(Locale.US))
    assertEquals(AppLanguage.English, AppLanguage.forLocale(Locale.GERMANY))
    assertEquals(AppLanguage.English, AppLanguage.forLocale(Locale.forLanguageTag("es-419")))
  }
}
