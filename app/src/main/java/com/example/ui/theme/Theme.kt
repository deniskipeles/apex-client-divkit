package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SduiNavyBg = Color(0xFF0B1120)
val SduiSlateSurface = Color(0xFF152033)
val SduiCyanPrimary = Color(0xFF38BDF8)
val SduiIndigoSecondary = Color(0xFF818CF8)
val SduiEmeraldAccent = Color(0xFF34D399)

private val SduiDarkColorScheme = darkColorScheme(
    primary = SduiCyanPrimary,
    onPrimary = Color(0xFF0F172A),
    secondary = SduiIndigoSecondary,
    onSecondary = Color(0xFF0F172A),
    tertiary = SduiEmeraldAccent,
    background = SduiNavyBg,
    onBackground = Color(0xFFF8FAFC),
    surface = SduiSlateSurface,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155)
)

private val SduiLightColorScheme = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF4F46E5),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF059669),
    background = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) SduiDarkColorScheme else SduiLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
