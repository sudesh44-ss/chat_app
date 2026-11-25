package com.nexa.app.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Supported Font Styles for NEXA:
 * - DEFAULT: System Default (Clean modern sans-serif)
 * - ROBOTO: Roboto (Google standard geometric sans-serif)
 * - SERIF: Serif (Classic elegant editorial serif)
 * - MONOSPACE: Monospace (Technical fixed-pitch)
 * - CURSIVE: Cursive (Handwritten script)
 *
 * All fonts map directly to Android platform font families, ensuring zero download
 * overhead, complete offline availability, and 100% crash immunity.
 */
enum class NexaFontStyle(val displayName: String, val fontFamily: FontFamily) {
    DEFAULT("Default NEXA", FontFamily.Default),
    ROBOTO("Roboto", FontFamily.SansSerif),
    SERIF("Serif", FontFamily.Serif),
    MONOSPACE("Monospace", FontFamily.Monospace),
    CURSIVE("Cursive", FontFamily.Cursive);

    companion object {
        fun fromName(name: String?): NexaFontStyle {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
        }
    }
}

/**
 * Text size scaling options for App UI and Chat Bubbles:
 * - EXTRA_SMALL: 85% scale
 * - SMALL: 92% scale
 * - MEDIUM: 100% scale (Default)
 * - LARGE: 110% (App) / 115% (Chat)
 * - EXTRA_LARGE: 120% (App) / 130% (Chat)
 */
enum class NexaTextSize(
    val displayName: String,
    val shortLabel: String,
    val appScale: Float,
    val chatScale: Float
) {
    EXTRA_SMALL("Extra Small", "XS", 0.85f, 0.85f),
    SMALL("Small", "S", 0.92f, 0.92f),
    MEDIUM("Medium", "M", 1.00f, 1.00f),
    LARGE("Large", "L", 1.10f, 1.15f),
    EXTRA_LARGE("Extra Large", "XL", 1.20f, 1.30f);

    companion object {
        fun fromName(name: String?): NexaTextSize {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MEDIUM
        }
    }
}

/**
 * Creates dynamic Material 3 Typography scaled by the requested factor and applied
 * with the specified FontFamily.
 */
fun createNexaTypography(
    fontFamily: FontFamily = FontFamily.Default,
    scale: Float = 1.0f
): Typography {
    val s = scale.coerceIn(0.7f, 1.6f)
    return Typography(
        headlineLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (32f * s).sp,
            lineHeight = (40f * s).sp,
            letterSpacing = 0.sp
        ),
        headlineMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (26f * s).sp,
            lineHeight = (34f * s).sp,
            letterSpacing = 0.sp
        ),
        headlineSmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (22f * s).sp,
            lineHeight = (28f * s).sp,
            letterSpacing = 0.sp
        ),
        titleLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (20f * s).sp,
            lineHeight = (26f * s).sp,
            letterSpacing = 0.sp
        ),
        titleMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (16f * s).sp,
            lineHeight = (24f * s).sp,
            letterSpacing = 0.15.sp
        ),
        titleSmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (14f * s).sp,
            lineHeight = (20f * s).sp,
            letterSpacing = 0.1.sp
        ),
        bodyLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (16f * s).sp,
            lineHeight = (24f * s).sp,
            letterSpacing = 0.5.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (14f * s).sp,
            lineHeight = (20f * s).sp,
            letterSpacing = 0.25.sp
        ),
        bodySmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = (12f * s).sp,
            lineHeight = (16f * s).sp,
            letterSpacing = 0.4.sp
        ),
        labelLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (14f * s).sp,
            lineHeight = (20f * s).sp,
            letterSpacing = 0.1.sp
        ),
        labelMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (12f * s).sp,
            lineHeight = (16f * s).sp,
            letterSpacing = 0.5.sp
        ),
        labelSmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (11f * s).sp,
            lineHeight = (16f * s).sp,
            letterSpacing = 0.5.sp
        )
    )
}

/**
 * Default Typography instance preserving standard system sans-serif at 100% scale.
 */
val Typography: Typography = createNexaTypography()
