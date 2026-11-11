package com.example.docscanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DocScannerColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00212E),
    secondary = Color(0xFF80DEEA),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE3E8EC),
    surface = Color(0xFF181D22),
    onSurface = Color(0xFFE3E8EC),
    surfaceVariant = Color(0xFF2A3037),
    onSurfaceVariant = Color(0xFFB8C2CA),
    outline = Color(0xFF5F6B75),
)

/** A single dark theme for the whole app (camera apps look best dark). */
@Composable
fun DocScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DocScannerColors, content = content)
}
