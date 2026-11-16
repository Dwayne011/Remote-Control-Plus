package com.remotecontrolplus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback accents (Material Theme Builder, seed #4F6D7A) where the wallpaper palette is unavailable.
private val FallbackColors = darkColorScheme(
    primary = Color(0xFFA3CDDA),
    secondary = Color(0xFFB2CBD3),
    tertiary = Color(0xFFC3C3EB),
    inversePrimary = Color(0xFF3B6470),
)

/**
 * The frosted theme: always dark. Accents come from the wallpaper (Material
 * You); surfaces are the glass palette. Tonal buttons use the translucent
 * glass fill; anything that floats over other content (sheets, dialogs, menus)
 * gets an opaque dark surface so what is behind does not show through.
 */
@Composable
fun RemoteControlPlusTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val wallpaper = remember { runCatching { dynamicDarkColorScheme(context) }.getOrElse { FallbackColors } }
    val scheme = darkColorScheme(
        primary = wallpaper.primary,
        onPrimary = Color(0xFF0B1530),
        primaryContainer = wallpaper.primary.copy(alpha = 0.24f),
        onPrimaryContainer = GlassText,
        inversePrimary = wallpaper.inversePrimary,
        secondary = wallpaper.secondary,
        onSecondary = Color(0xFF0B1530),
        secondaryContainer = GlassFill,
        onSecondaryContainer = GlassText,
        tertiary = wallpaper.tertiary,
        onTertiary = Color(0xFF0B1530),
        tertiaryContainer = GlassFillStrong,
        onTertiaryContainer = GlassText,
        background = GlassBase,
        onBackground = GlassText,
        surface = GlassBase,
        onSurface = GlassText,
        surfaceVariant = GlassFillStrong,
        onSurfaceVariant = GlassSub,
        surfaceContainerLowest = GlassBase,
        surfaceContainerLow = GlassSheet,
        surfaceContainer = GlassSheet,
        surfaceContainerHigh = GlassDialog,
        surfaceContainerHighest = GlassFillStrong,
        inverseSurface = Color(0xFFE3E6EE),
        inverseOnSurface = Color(0xFF141925),
        outline = Color(0x40FFFFFF),
        outlineVariant = Color(0x24FFFFFF),
        error = GlassDown,
        onError = Color(0xFF3B0A06),
        errorContainer = GlassDown.copy(alpha = 0.22f),
        onErrorContainer = GlassDown,
        scrim = Color(0xFF000000),
    )
    MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
}
