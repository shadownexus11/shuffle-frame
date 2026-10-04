package com.shuffleframe.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFE8B04B)
val Ink = Color(0xFF000000)
val Panel = Color(0xFF111111)
val Muted = Color(0xFF8A8A8A)

private val scheme = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    background = Ink,
    onBackground = Color.White,
    surface = Panel,
    onSurface = Color.White,
    surfaceContainerLow = Panel,
)

@Composable
fun ShuffleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
