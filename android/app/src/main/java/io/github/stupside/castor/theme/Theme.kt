package io.github.stupside.castor.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class AppTheme(val preference: String) {
  System("system"),
  Light("light"),
  Dark("dark");

  fun isDark(systemDark: Boolean): Boolean =
    when (this) {
      System -> systemDark
      Light -> false
      Dark -> true
    }
}

private val light =
  lightColorScheme(
    primary = Color(0xFF075E63),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF075E63),
    onPrimaryContainer = Color(0xFFE7F6F3),
    secondary = Color(0xFF365F63),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E8E5),
    onSecondaryContainer = Color(0xFF183B3E),
    background = Color(0xFFDAECEF),
    onBackground = Color(0xFF172E31),
    surface = Color.White,
    onSurface = Color(0xFF172E31),
    surfaceVariant = Color(0xFFDEEAE8),
    onSurfaceVariant = Color(0xFF365456),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F9F8),
    surfaceContainer = Color(0xFFEEF5F3),
    surfaceContainerHigh = Color(0xFFE0EDEB),
    surfaceContainerHighest = Color(0xFFD3E5E2),
    outline = Color(0xFF6A8585),
    outlineVariant = Color(0xFFC3D6D2),
    error = Color(0xFFAA3434),
    onError = Color.White,
    errorContainer = Color(0xFFFFE7E3),
    onErrorContainer = Color(0xFF6A2020),
  )
private val dark =
  darkColorScheme(
    primary = Color(0xFF9AD9D1),
    onPrimary = Color(0xFF00373B),
    primaryContainer = Color(0xFF103E42),
    onPrimaryContainer = Color(0xFFC9EEE7),
    secondary = Color(0xFFA5CFC9),
    onSecondary = Color(0xFF143A3D),
    secondaryContainer = Color(0xFF24494B),
    onSecondaryContainer = Color(0xFFD5EFEA),
    background = Color(0xFF071B26),
    onBackground = Color(0xFFDCEDE9),
    surface = Color(0xFF13272A),
    onSurface = Color(0xFFDCEDE9),
    surfaceVariant = Color(0xFF254245),
    onSurfaceVariant = Color(0xFFAEC9C5),
    surfaceContainerLowest = Color(0xFF0A181A),
    surfaceContainerLow = Color(0xFF112326),
    surfaceContainer = Color(0xFF172F32),
    surfaceContainerHigh = Color(0xFF203A3D),
    surfaceContainerHighest = Color(0xFF29464A),
    outline = Color(0xFF7B9B97),
    outlineVariant = Color(0xFF375351),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF630F10),
    errorContainer = Color(0xFF552829),
    onErrorContainer = Color(0xFFFFDAD5),
  )

// Amber for a warning that stays on screen until it is dealt with: a role the Material scheme does not have.
data class WarningColors(val container: Color, val content: Color)

private val lightWarning = WarningColors(container = Color(0xFFFFE08A), content = Color(0xFF3B2D00))
private val darkWarning = WarningColors(container = Color(0xFF5B4500), content = Color(0xFFFFE399))

val LocalWarningColors = staticCompositionLocalOf { darkWarning }

@Composable
fun CastorTheme(theme: AppTheme = AppTheme.Dark, content: @Composable () -> Unit) {
  val isDark = theme.isDark(isSystemInDarkTheme())
  CompositionLocalProvider(LocalWarningColors provides if (isDark) darkWarning else lightWarning) {
    CastorMaterialTheme(isDark, content)
  }
}

@Composable
private fun CastorMaterialTheme(isDark: Boolean, content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (isDark) dark else light,
    typography = Typography,
    shapes =
      Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(32.dp),
      ),
    content = { GlassScene(content = content) },
  )
}
