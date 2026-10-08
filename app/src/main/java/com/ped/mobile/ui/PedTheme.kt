package com.ped.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightPedColors = lightColorScheme(
    primary = Color(0xFF172B4D), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8C888), onPrimaryContainer = Color(0xFF172B4D),
    secondary = Color(0xFF284D7C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EDF4), onSecondaryContainer = Color(0xFF172B4D),
    background = Color(0xFFFCFBF8), onBackground = Color(0xFF1B2B44),
    surface = Color.White, onSurface = Color(0xFF1B2B44),
    surfaceVariant = Color(0xFFEEEFF2), onSurfaceVariant = Color(0xFF626B79),
    outline = Color(0xFF8993A2), error = Color(0xFFA82E35)
)
private val DarkPedColors = darkColorScheme(
    primary = Color(0xFFE8C888), onPrimary = Color(0xFF172B4D),
    primaryContainer = Color(0xFF3D3525), onPrimaryContainer = Color(0xFFF2DBA9),
    secondary = Color(0xFFA9C8EF), onSecondary = Color(0xFF172B4D),
    secondaryContainer = Color(0xFF243956), onSecondaryContainer = Color(0xFFD8E6F8),
    background = Color(0xFF111C2C), onBackground = Color(0xFFEAF0F8),
    surface = Color(0xFF19283D), onSurface = Color(0xFFEAF0F8),
    surfaceVariant = Color(0xFF24344B), onSurfaceVariant = Color(0xFFBDC8D8),
    outline = Color(0xFF8D9CB1), error = Color(0xFFFFB4AB)
)
@Composable fun PedTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(typography = PedTypography, colorScheme = if (darkTheme) DarkPedColors else LightPedColors) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
    }
}

internal object PedBrand {
    val Navy = Color(0xFF172B4D)
    val Gold = Color(0xFFE8C888)
    val OnNavy = Color(0xFFEAF0F8)
}
