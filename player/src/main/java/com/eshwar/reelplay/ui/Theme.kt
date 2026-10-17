package com.eshwar.reelplay.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ReelColors = darkColorScheme(
    primary = Color(0xFF3D8BFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF173B73),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFFF7A45),
    onSecondary = Color(0xFF2B0E00),
    secondaryContainer = Color(0xFF5A2A12),
    onSecondaryContainer = Color(0xFFFFDBCB),
    background = Color(0xFF0B0E16),
    onBackground = Color(0xFFE4E7F0),
    surface = Color(0xFF121722),
    onSurface = Color(0xFFE4E7F0),
    surfaceVariant = Color(0xFF1E2533),
    onSurfaceVariant = Color(0xFFB5BCCB),
    surfaceContainer = Color(0xFF171D2A),
    surfaceContainerHigh = Color(0xFF1E2533),
    surfaceContainerHighest = Color(0xFF262E3E),
    outline = Color(0xFF4A5468),
)

@Composable
fun ReelPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ReelColors, typography = Typography(), content = content)
}
