package com.shilapi.xcertplay.installer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = DiPlayDarkPrimary,
    onPrimary = DiPlayDarkOnPrimary,
    background = DiPlayDarkBackground,
    onBackground = Color(0xFFF3F6FB),
    surface = DiPlayDarkSurface,
    onSurface = Color(0xFFF3F6FB),
    surfaceVariant = DiPlayDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB8C4D6),
    error = DiPlayDanger,
)

private val LightColors = lightColorScheme(
    primary = DiPlayLightPrimary,
    onPrimary = DiPlayLightOnPrimary,
    background = DiPlayLightBackground,
    onBackground = Color(0xFF101720),
    surface = DiPlayLightSurface,
    onSurface = Color(0xFF101720),
    surfaceVariant = DiPlayLightSurfaceVariant,
    onSurfaceVariant = Color(0xFF445166),
    error = DiPlayDanger,
)

@Composable
fun DiPlayInstallerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
