package com.waizusora.wdb.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WdbDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB7C8FF),
    onPrimary = Color(0xFF172B55),
    secondary = Color(0xFF8DD8C2),
    onSecondary = Color(0xFF00382D),
    tertiary = Color(0xFFE5B9D5),
    background = Color(0xFF101318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF171A20),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF292D36),
    onSurfaceVariant = Color(0xFFC3C6D1),
    outline = Color(0xFF8D919D)
)

/** Shared Material 3 design system used by the activity and floating Compose UI. */
@Composable
fun WdbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WdbDarkColorScheme,
        content = content
    )
}
