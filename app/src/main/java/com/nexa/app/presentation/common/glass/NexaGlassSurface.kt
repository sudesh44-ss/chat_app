package com.nexa.app.presentation.common.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexa.app.core.theme.NexaGlassTokens

/**
 * Reusable Layered Liquid Glass Surface Component.
 *
 * Implements:
 * 1. Soft shadow elevation for floating appearance.
 * 2. Translucent surface with theme-aware opacity.
 * 3. Subtle directional gradient border (top-left highlight to bottom-right shadow).
 * 4. High-performance fallback without expensive per-frame runtime backdrop filters in scrolling lists.
 */
@Composable
fun NexaGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(NexaGlassTokens.LargeCardRadius),
    surfaceColor: Color? = null,
    borderColor: Color? = null,
    borderWidth: Dp = NexaGlassTokens.NormalBorderWidth,
    elevation: Dp = 4.dp,
    isSelected: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val baseSurface = surfaceColor ?: MaterialTheme.colorScheme.surface
    val baseBorder = borderColor ?: MaterialTheme.colorScheme.outline

    val gradientBorderBrush = if (isSelected) {
        Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                if (isDark) Color(0x40FFFFFF) else Color(0x33000000),
                baseBorder
            )
        )
    }

    val effectiveBorderWidth = if (isSelected) NexaGlassTokens.SelectedBorderWidth else borderWidth

    Box(
        modifier = modifier
            .then(
                if (elevation > 0.dp) {
                    Modifier.shadow(
                        elevation = elevation,
                        shape = shape,
                        ambientColor = if (isDark) Color.Black else Color(0x26000000),
                        spotColor = if (isDark) Color(0x4D000000) else Color(0x1A000000)
                    )
                } else Modifier
            )
            .clip(shape)
            .background(
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.18f else 0.12f)
                } else {
                    baseSurface
                }
            )
            .border(
                width = effectiveBorderWidth,
                brush = gradientBorderBrush,
                shape = shape
            ),
        content = content
    )
}
