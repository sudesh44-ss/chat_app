package com.nexa.app.core.theme

import androidx.compose.ui.graphics.Color

// ==========================================
// 1. Core NEXA AMOLED & Light Palettes
// ==========================================

// Pure Dark / AMOLED Mode
val NexaPureBlack = Color(0xFF000000)
val NexaDarkTextPrimary = Color(0xFFFFFFFF)
val NexaDarkTextSecondary = Color(0xFFA0A7B5)
val NexaDarkTextMuted = Color(0xFF737C8D)

val NexaDarkGlassSurface = Color(0xCC121824)        // ~80% opacity dark glass
val NexaDarkGlassSurfaceHigh = Color(0xE61A2333)    // ~90% opacity elevated glass
val NexaDarkGlassBorder = Color(0x26FFFFFF)         // 15% subtle white border
val NexaDarkGlassBorderHighlight = Color(0x40FFFFFF)// 25% inner highlight

// Pure Light Mode
val NexaPureWhite = Color(0xFFFFFFFF)
val NexaLightTextPrimary = Color(0xFF000000)
val NexaLightTextSecondary = Color(0xFF555B66)
val NexaLightTextMuted = Color(0xFF777777)

val NexaLightGlassSurface = Color(0xD9FFFFFF)       // ~85% opacity light glass
val NexaLightGlassSurfaceHigh = Color(0xF2F1F5F9)   // ~95% opacity elevated glass
val NexaLightGlassBorder = Color(0x1F000000)        // 12% subtle dark border
val NexaLightGlassBorderHighlight = Color(0x33000000)

// Accent & Status Colors
val NexaCyan80 = Color(0xFF64FFDA)
val NexaCyanGrey80 = Color(0xFFB2DFDB)
val NexaAccent80 = Color(0xFF80DEEA)

val NexaCyan40 = Color(0xFF00897B)
val NexaCyanGrey40 = Color(0xFF004D40)
val NexaAccent40 = Color(0xFF006064)

val NexaBlueHighlight = Color(0xFF00E5FF)
val NexaVerifiedGreen = Color(0xFF10B981)
val NexaVerifiedGreenLight = Color(0xFFD1FAE5)
val NexaWarningOrange = Color(0xFFF59E0B)
val NexaBadgeMonospace = Color(0xFF00E5FF)
val NexaErrorRed = Color(0xFFEF4444)

// Legacy compatibility
val NexaDarkBackground = NexaPureBlack
val NexaDarkSurface = NexaDarkGlassSurface
val NexaDarkSurfaceVariant = Color(0xFF161E2E)
val NexaDarkCardBorder = NexaDarkGlassBorder

val NexaLightBackground = NexaPureWhite
val NexaLightSurface = NexaLightGlassSurface
val NexaLightSurfaceVariant = Color(0xFFF8FAFC)
val NexaLightCardBorder = NexaLightGlassBorder
