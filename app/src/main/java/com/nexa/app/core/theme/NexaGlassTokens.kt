package com.nexa.app.core.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Global Liquid Glass Design System Tokens.
 *
 * Provides centralized proportions, radii, borders, blur values, and elevations
 * to maintain visual harmony across the application.
 */
object NexaGlassTokens {
    // Radii
    val LargeCardRadius: Dp = 24.dp
    val SmallCardRadius: Dp = 18.dp
    val SearchRadius: Dp = 32.dp
    val PillRadius: Dp = 36.dp
    val ContextMenuRadius: Dp = 20.dp
    val AvatarRadius: Dp = 28.dp

    // Borders
    val NormalBorderWidth: Dp = 1.dp
    val SelectedBorderWidth: Dp = 1.5.dp
    val HighlightBorderWidth: Dp = 2.dp

    // Opacities & Blur
    const val NormalGlassOpacity: Float = 0.76f
    const val HighGlassOpacity: Float = 0.88f
    const val SubtleGlassOpacity: Float = 0.45f
    val GlassBlurStandard: Dp = 20.dp
    val GlassBlurSubtle: Dp = 12.dp

    // Element Dimensions
    val SearchBarHeight: Dp = 56.dp
    val AvatarSize: Dp = 54.dp
    val AvatarLargeSize: Dp = 64.dp
    val CardSpacing: Dp = 12.dp
    val CardHorizontalPadding: Dp = 16.dp

    // Floating Bottom Navigation & Plus Button
    val FloatingCapsuleHeight: Dp = 66.dp
    val FloatingCapsuleRadius: Dp = 36.dp
    val FloatingCapsuleIconSize: Dp = 24.dp
    val FloatingPlusSize: Dp = 58.dp
    val FloatingControlsGap: Dp = 12.dp
    val FloatingControlsBottomMargin: Dp = 16.dp
    val ConversationListBottomPadding: Dp = 110.dp
}
