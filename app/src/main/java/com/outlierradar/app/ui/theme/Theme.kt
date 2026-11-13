package com.outlierradar.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
  primary = PrimaryViolet,
  onPrimary = Color.White,
  primaryContainer = Color(0xFF6B21A8),
  onPrimaryContainer = Color(0xFFF3E8FF),
  secondary = SecondaryCyan,
  onSecondary = Color(0xFF042F2E),
  secondaryContainer = Color(0xFF164E63),
  onSecondaryContainer = Color(0xFFCFFAFE),
  tertiary = AccentRed,
  onTertiary = Color.White,
  background = DarkBackground,
  onBackground = DarkOnBackground,
  surface = DarkSurface,
  onSurface = DarkOnSurface,
  surfaceVariant = DarkSurfaceVariant,
  onSurfaceVariant = DarkOnSurfaceVariant,
  error = ErrorRed,
  onError = Color.White,
  outline = BorderDark
)

private val LightColorScheme = lightColorScheme(
  primary = PrimaryViolet,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFF3E8FF),
  onPrimaryContainer = Color(0xFF581C87),
  secondary = Color(0xFF0891B2),
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFCFFAFE),
  onSecondaryContainer = Color(0xFF164E63),
  tertiary = AccentRed,
  onTertiary = Color.White,
  background = LightBackground,
  onBackground = LightOnBackground,
  surface = LightSurface,
  onSurface = LightOnSurface,
  surfaceVariant = LightSurfaceVariant,
  onSurfaceVariant = LightOnSurfaceVariant,
  error = ErrorRed,
  onError = Color.White,
  outline = Color(0xFFD1D5DB)
)

@Composable
fun OutlierRadarTheme(
  darkTheme: Boolean = true, // Dark by default as specified
  content: @Composable () -> Unit
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content
  )
}
