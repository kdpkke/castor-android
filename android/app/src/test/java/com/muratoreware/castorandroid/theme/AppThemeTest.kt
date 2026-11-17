package com.muratoreware.castorandroid.theme

import org.junit.Assert.*
import org.junit.Test

class AppThemeTest {
  @Test
  fun explicitModesOverrideTheSystemAndSystemModeFollowsIt() {
    for (systemDark in listOf(false, true)) {
      assertFalse(AppTheme.Light.isDark(systemDark))
      assertTrue(AppTheme.Dark.isDark(systemDark))
      assertEquals(systemDark, AppTheme.System.isDark(systemDark))
    }
  }
}
