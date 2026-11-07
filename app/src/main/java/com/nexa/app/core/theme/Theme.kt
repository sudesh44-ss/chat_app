package com.nexa.app.core.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Appearance mode preference for NEXA:
 * - SYSTEM: Follows Android system setting
 * - DARK: Pure Black #000000 AMOLED Mode
 * - LIGHT: Pure White #FFFFFF Light Mode
 */
enum class AppearanceMode {
    SYSTEM,
    DARK,
    LIGHT
}

val LocalAppearanceMode = compositionLocalOf { AppearanceMode.SYSTEM }

private val DarkColorScheme = darkColorScheme(
    primary = NexaCyan80,
    secondary = NexaCyanGrey80,
    tertiary = NexaAccent80,
    background = NexaPureBlack,
    surface = NexaDarkGlassSurface,
    surfaceVariant = NexaDarkGlassSurfaceHigh,
    onPrimary = NexaPureBlack,
    onSecondary = NexaPureBlack,
    onTertiary = NexaPureBlack,
    onBackground = NexaDarkTextPrimary,
    onSurface = NexaDarkTextPrimary,
    onSurfaceVariant = NexaDarkTextSecondary,
    outline = NexaDarkGlassBorder
)

private val LightColorScheme = lightColorScheme(
    primary = NexaCyan40,
    secondary = NexaCyanGrey40,
    tertiary = NexaAccent40,
    background = NexaPureWhite,
    surface = NexaLightGlassSurface,
    surfaceVariant = NexaLightGlassSurfaceHigh,
    onPrimary = NexaPureWhite,
    onSecondary = NexaPureWhite,
    onTertiary = NexaPureWhite,
    onBackground = NexaLightTextPrimary,
    onSurface = NexaLightTextPrimary,
    onSurfaceVariant = NexaLightTextSecondary,
    outline = NexaLightGlassBorder
)

@Composable
fun NexaTheme(
    appearanceMode: AppearanceMode = AppearanceMode.SYSTEM,
    darkTheme: Boolean = when (appearanceMode) {
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.DARK -> true
        AppearanceMode.LIGHT -> false
    },
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    window.statusBarColor = colorScheme.background.toArgb()
                }
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalAppearanceMode provides appearanceMode) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
