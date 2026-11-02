package com.remotecontrolplus.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback seed palette (Material Theme Builder, seed #4F6D7A) for devices
// where dynamic colour is unavailable or disabled by the user.
private val LightColors = lightColorScheme(
    primary = Color(0xFF3B6470),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBFE9F7),
    onPrimaryContainer = Color(0xFF001F27),
    secondary = Color(0xFF4B6269),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCEE7EF),
    onSecondaryContainer = Color(0xFF061F25),
    tertiary = Color(0xFF5A5C7E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0E0FF),
    onTertiaryContainer = Color(0xFF161937),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FAFD),
    onBackground = Color(0xFF171C1F),
    surface = Color(0xFFF5FAFD),
    onSurface = Color(0xFF171C1F),
    surfaceVariant = Color(0xFFDBE4E8),
    onSurfaceVariant = Color(0xFF3F484C),
    outline = Color(0xFF70797C),
    outlineVariant = Color(0xFFBFC8CC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA3CDDA),
    onPrimary = Color(0xFF033541),
    primaryContainer = Color(0xFF214C58),
    onPrimaryContainer = Color(0xFFBFE9F7),
    secondary = Color(0xFFB2CBD3),
    onSecondary = Color(0xFF1D343A),
    secondaryContainer = Color(0xFF344A51),
    onSecondaryContainer = Color(0xFFCEE7EF),
    tertiary = Color(0xFFC3C3EB),
    onTertiary = Color(0xFF2C2E4D),
    tertiaryContainer = Color(0xFF424465),
    onTertiaryContainer = Color(0xFFE0E0FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1417),
    onBackground = Color(0xFFDFE3E6),
    surface = Color(0xFF0F1417),
    onSurface = Color(0xFFDFE3E6),
    surfaceVariant = Color(0xFF3F484C),
    onSurfaceVariant = Color(0xFFBFC8CC),
    outline = Color(0xFF899296),
    outlineVariant = Color(0xFF3F484C),
)

@Composable
fun RemoteControlPlusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
