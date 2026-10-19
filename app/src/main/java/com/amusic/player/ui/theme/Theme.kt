package com.amusic.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 深色优先的极简主题（文档 §48）。
 * 主色取自应用图标的主色 #E08030。
 */
private val AMusicColors = darkColorScheme(
    primary = Color(0xFFE08030),
    onPrimary = Color(0xFF1A1206),
    primaryContainer = Color(0xFF3A2410),
    onPrimaryContainer = Color(0xFFFFD9B0),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFE8E8EA),
    surface = Color(0xFF17171A),
    onSurface = Color(0xFFE8E8EA),
    surfaceVariant = Color(0xFF232327),
    onSurfaceVariant = Color(0xFF9A9AA0),
    outline = Color(0xFF34343A),
    outlineVariant = Color(0xFF26262B),
)

@Composable
fun AMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AMusicColors, content = content)
}
