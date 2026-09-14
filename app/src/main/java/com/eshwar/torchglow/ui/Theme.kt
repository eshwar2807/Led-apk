package com.eshwar.torchglow.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TorchColors = darkColorScheme(
    primary = Color(0xFFFFC85C),
    onPrimary = Color(0xFF241A00),
    primaryContainer = Color(0xFF3A2E00),
    onPrimaryContainer = Color(0xFFFFE6AE),
    secondary = Color(0xFF8FD3FF),
    onSecondary = Color(0xFF00344D),
    background = Color(0xFF0D0B14),
    onBackground = Color(0xFFE8E4EF),
    surface = Color(0xFF15131E),
    onSurface = Color(0xFFE8E4EF),
    surfaceVariant = Color(0xFF221F2E),
    onSurfaceVariant = Color(0xFFBDB8CB),
    outline = Color(0xFF57536A),
)

@Composable
fun TorchGlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TorchColors,
        typography = Typography(),
        content = content,
    )
}
