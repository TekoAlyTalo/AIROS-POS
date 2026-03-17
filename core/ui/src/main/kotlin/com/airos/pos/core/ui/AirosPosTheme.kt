package com.airos.pos.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5EC8A2),
    onPrimary = Color(0xFF062018),
    secondary = Color(0xFFFFC857),
    onSecondary = Color(0xFF261600),
    background = Color(0xFF0F151B),
    onBackground = Color(0xFFF2F5F7),
    surface = Color(0xFF172028),
    onSurface = Color(0xFFF2F5F7),
    surfaceVariant = Color(0xFF21303B),
    onSurfaceVariant = Color(0xFFD7E3EA),
    error = Color(0xFFFF7B72),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF006A57),
    onPrimary = Color.White,
    secondary = Color(0xFF855400),
    onSecondary = Color.White,
    background = Color(0xFFF4F7F9),
    onBackground = Color(0xFF10161B),
    surface = Color.White,
    onSurface = Color(0xFF10161B),
)

@Composable
fun AirosPosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
