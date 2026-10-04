package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StudioColorScheme = darkColorScheme(
    primary = StudioCyan,
    onPrimary = Color(0xFF03101E),
    primaryContainer = Color(0xFF0F364A),
    onPrimaryContainer = StudioCyan,
    secondary = StudioViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2C1E4A),
    onSecondaryContainer = Color(0xFFD8B4FE),
    tertiary = StudioEmerald,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF064E3B),
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = StudioBg,
    onBackground = TextPrimary,
    surface = StudioSurface,
    onSurface = TextPrimary,
    surfaceVariant = StudioSurfaceCard,
    onSurfaceVariant = TextSecondary,
    outline = StudioBorder,
    outlineVariant = StudioBorder.copy(alpha = 0.5f),
    error = StudioRose,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Studio aesthetics shine in pro dark mode
    MaterialTheme(
        colorScheme = StudioColorScheme,
        typography = Typography,
        content = content
    )
}
