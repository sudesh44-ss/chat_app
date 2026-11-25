package com.nexa.app.core.theme

import androidx.compose.ui.graphics.Color

// ==========================================
// 1. Core NEXA AMOLED & Dark & Light Palettes
// ==========================================

// Pure AMOLED Mode (Pitch black for OLED power efficiency)
val NexaPureBlack = Color(0xFF000000)
val NexaDarkTextPrimary = Color(0xFFFFFFFF)
val NexaDarkTextSecondary = Color(0xFFA0A7B5)
val NexaDarkTextMuted = Color(0xFF737C8D)

val NexaAmoledGlassSurface = Color(0xCC141416)        // ~80% opacity dark glass over pitch black
val NexaAmoledGlassSurfaceHigh = Color(0xE61C1C20)    // ~90% opacity elevated glass
val NexaAmoledGlassBorder = Color(0x26FFFFFF)         // 15% subtle white border
val NexaAmoledGlassBorderHighlight = Color(0x40FFFFFF)// 25% inner highlight

// Comfortable Dark Charcoal Mode (Comfortable dark surfaces with distinct contrast)
val NexaDarkCharcoalBackground = Color(0xFF13151A)    // Dark slate/charcoal background, visibly distinct from #000000
val NexaDarkCharcoalSurface = Color(0xFF1E222B)       // Elevated distinct charcoal card surface
val NexaDarkCharcoalSurfaceHigh = Color(0xFF282E3A)   // Higher elevation charcoal surface
val NexaDarkCharcoalBorder = Color(0x33FFFFFF)        // 20% visible border
val NexaDarkCharcoalTextPrimary = Color(0xFFF1F5F9)   // Soft off-white primary text
val NexaDarkCharcoalTextSecondary = Color(0xFF94A3B8) // Slate secondary text
val NexaDarkCharcoalTextMuted = Color(0xFF64748B)     // Muted caption text

// Pure Light Mode
val NexaPureWhite = Color(0xFFFFFFFF)
val NexaLightTextPrimary = Color(0xFF000000)          // Pure black primary text (21:1 contrast ratio)
val NexaLightTextSecondary = Color(0xFF555B66)        // Balanced secondary text
val NexaLightTextMuted = Color(0xFF777777)

val NexaLightGlassSurface = Color(0xEEF8F9FA)         // ~93% opacity light card surface
val NexaLightGlassSurfaceHigh = Color(0xF2F1F5F9)     // ~95% opacity elevated surface
val NexaLightGlassBorder = Color(0x1F000000)          // 12% subtle dark border
val NexaLightGlassBorderHighlight = Color(0x33000000)

// Accent & Status Colors (NEXA Mint/Teal brand)
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
val NexaDarkBackground = NexaDarkCharcoalBackground
val NexaDarkSurface = NexaDarkCharcoalSurface
val NexaDarkSurfaceVariant = NexaDarkCharcoalSurfaceHigh
val NexaDarkCardBorder = NexaDarkCharcoalBorder
val NexaDarkGlassSurface = NexaAmoledGlassSurface
val NexaDarkGlassSurfaceHigh = NexaAmoledGlassSurfaceHigh
val NexaDarkGlassBorder = NexaAmoledGlassBorder
val NexaDarkGlassBorderHighlight = NexaAmoledGlassBorderHighlight

val NexaLightBackground = NexaPureWhite
val NexaLightSurface = NexaLightGlassSurface
val NexaLightSurfaceVariant = Color(0xFFF8FAFC)
val NexaLightCardBorder = NexaLightGlassBorder
