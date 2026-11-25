package com.nexa.app.core.theme

import android.app.Activity
import android.content.res.Configuration
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
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat

/**
 * Appearance mode preference for NEXA:
 * - SYSTEM: Follows Android system setting
 * - DARK: Comfortable dark charcoal #13151A mode with distinct card surfaces
 * - AMOLED: Pure Black #000000 AMOLED Mode for OLED displays
 * - LIGHT: Clean White #FFFFFF Light Mode
 */
enum class AppearanceMode {
    SYSTEM,
    DARK,
    AMOLED,
    LIGHT
}

val LocalAppearanceMode = compositionLocalOf { AppearanceMode.SYSTEM }
val LocalThemeIsDark = compositionLocalOf { true }
val LocalNexaAmoled = compositionLocalOf { false }
val LocalReduceAnimations = compositionLocalOf { false }
val LocalNexaFontStyle = compositionLocalOf { NexaFontStyle.DEFAULT }
val LocalAppTextSize = compositionLocalOf { NexaTextSize.MEDIUM }
val LocalChatTextScale = compositionLocalOf { 1.0f }
val LocalNexaFontFamily = compositionLocalOf<FontFamily> { FontFamily.Default }

/**
 * Returns true if the active theme is dark (DARK or AMOLED, or SYSTEM when system is dark).
 */
@Composable
fun isNexaDarkTheme(): Boolean = LocalThemeIsDark.current

/**
 * Returns true if the active theme is AMOLED pure black.
 */
@Composable
fun isNexaAmoled(): Boolean = LocalNexaAmoled.current

/**
 * Returns true if the user has enabled Reduce Animations.
 */
@Composable
fun isNexaReduceAnimations(): Boolean = LocalReduceAnimations.current

/**
 * Returns the currently active font style.
 */
@Composable
fun isNexaFontStyle(): NexaFontStyle = LocalNexaFontStyle.current

/**
 * Returns the active app-wide text size.
 */
@Composable
fun isNexaAppTextSize(): NexaTextSize = LocalAppTextSize.current

/**
 * Returns the active chat message text scaling factor.
 */
@Composable
fun isNexaChatTextScale(): Float = LocalChatTextScale.current

private val AmoledColorScheme = darkColorScheme(
    primary = NexaCyan80,
    secondary = NexaCyanGrey80,
    tertiary = NexaAccent80,
    background = NexaPureBlack,
    surface = NexaAmoledGlassSurface,
    surfaceVariant = NexaAmoledGlassSurfaceHigh,
    onPrimary = NexaPureBlack,
    onSecondary = NexaPureBlack,
    onTertiary = NexaPureBlack,
    onBackground = NexaDarkTextPrimary,
    onSurface = NexaDarkTextPrimary,
    onSurfaceVariant = NexaDarkTextSecondary,
    outline = NexaAmoledGlassBorder
)

private val DarkColorScheme = darkColorScheme(
    primary = NexaCyan80,
    secondary = NexaCyanGrey80,
    tertiary = NexaAccent80,
    background = NexaDarkCharcoalBackground,
    surface = NexaDarkCharcoalSurface,
    surfaceVariant = NexaDarkCharcoalSurfaceHigh,
    onPrimary = NexaDarkCharcoalBackground,
    onSecondary = NexaDarkCharcoalBackground,
    onTertiary = NexaDarkCharcoalBackground,
    onBackground = NexaDarkCharcoalTextPrimary,
    onSurface = NexaDarkCharcoalTextPrimary,
    onSurfaceVariant = NexaDarkCharcoalTextSecondary,
    outline = NexaDarkCharcoalBorder
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
    reduceAnimations: Boolean = false,
    fontStyle: NexaFontStyle = NexaFontStyle.DEFAULT,
    appTextSize: NexaTextSize = NexaTextSize.MEDIUM,
    chatTextSize: NexaTextSize = NexaTextSize.MEDIUM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (appearanceMode) {
        AppearanceMode.SYSTEM -> systemInDark
        AppearanceMode.DARK, AppearanceMode.AMOLED -> true
        AppearanceMode.LIGHT -> false
    }
    val isAmoled = appearanceMode == AppearanceMode.AMOLED

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isAmoled -> AmoledColorScheme
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }

    val dynamicTypography = remember(fontStyle, appTextSize) {
        createNexaTypography(
            fontFamily = fontStyle.fontFamily,
            scale = appTextSize.appScale
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    window.statusBarColor = colorScheme.background.toArgb()
                    window.navigationBarColor = colorScheme.background.toArgb()
                }
                val insets = WindowCompat.getInsetsController(window, view)
                insets.isAppearanceLightStatusBars = !isDark
                insets.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    // Intercept LocalConfiguration so any composable calling isSystemInDarkTheme()
    // across the entire application will reliably see the active theme's night mode.
    val currentConfig = LocalConfiguration.current
    val overriddenConfig = Configuration(currentConfig).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }

    CompositionLocalProvider(
        LocalConfiguration provides overriddenConfig,
        LocalAppearanceMode provides appearanceMode,
        LocalThemeIsDark provides isDark,
        LocalNexaAmoled provides isAmoled,
        LocalReduceAnimations provides reduceAnimations,
        LocalNexaFontStyle provides fontStyle,
        LocalAppTextSize provides appTextSize,
        LocalChatTextScale provides chatTextSize.chatScale,
        LocalNexaFontFamily provides fontStyle.fontFamily
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = dynamicTypography,
            content = content
        )
    }
}
