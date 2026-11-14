package com.nexa.app.presentation.chat.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nexa.app.R
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Swipe-to-reply gesture container for message bubbles.
 *
 * Requirements:
 * - Incoming message: Right swipe triggers reply.
 * - Outgoing message: Left swipe triggers reply.
 * - Reveal animated circular reply icon indicator while dragging.
 * - Smooth spring physics with threshold (~56dp).
 * - Haptic feedback on threshold trigger.
 * - Resets offset cleanly on release.
 */
@Composable
fun SwipeToReplyContainer(
    isOutgoing: Boolean,
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()

    val thresholdPx = with(density) { 56.dp.toPx() }
    val maxDragPx = with(density) { 88.dp.toPx() }

    val offsetX = remember { Animatable(0f) }
    var hasTriggeredHaptic by remember { mutableStateOf(false) }

    val iconAlignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    val iconPadding = if (isOutgoing) Modifier.padding(end = 16.dp) else Modifier.padding(start = 16.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(isOutgoing) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val current = offsetX.value
                        val newOffset = if (isOutgoing) {
                            // Left swipe (negative offset) for outgoing messages
                            (current + dragAmount).coerceIn(-maxDragPx, 0f)
                        } else {
                            // Right swipe (positive offset) for incoming messages
                            (current + dragAmount).coerceIn(0f, maxDragPx)
                        }

                        coroutineScope.launch {
                            offsetX.snapTo(newOffset)
                        }

                        if (abs(newOffset) >= thresholdPx) {
                            if (!hasTriggeredHaptic) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                hasTriggeredHaptic = true
                            }
                        } else {
                            hasTriggeredHaptic = false
                        }
                    },
                    onDragEnd = {
                        val triggered = hasTriggeredHaptic || abs(offsetX.value) >= thresholdPx
                        if (triggered) {
                            onReply()
                        }
                        hasTriggeredHaptic = false
                        coroutineScope.launch {
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    },
                    onDragCancel = {
                        hasTriggeredHaptic = false
                        coroutineScope.launch {
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                        }
                    }
                )
            }
    ) {
        // Behind the bubble: Animated Reply Indicator Badge
        val currentAbsOffset = abs(offsetX.value)
        if (currentAbsOffset > 4f) {
            val progress = (currentAbsOffset / thresholdPx).coerceIn(0f, 1f)
            val scale = (0.5f + 0.5f * progress) * (if (hasTriggeredHaptic) 1.15f else 1f)
            val alpha = progress

            val badgeBgColor = if (hasTriggeredHaptic) {
                MaterialTheme.colorScheme.primary
            } else {
                if (isDark) Color(0x55444444) else Color(0x33AAAAAA)
            }
            val iconTint = if (hasTriggeredHaptic) {
                if (isDark) Color.Black else Color.White
            } else {
                if (isDark) Color.White else Color.Black
            }

            Box(
                modifier = Modifier
                    .align(iconAlignment)
                    .then(iconPadding)
                    .size(36.dp)
                    .scale(scale)
                    .background(badgeBgColor.copy(alpha = alpha), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nexa_reply),
                    contentDescription = "Reply",
                    tint = iconTint.copy(alpha = alpha),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Swipable Message Bubble Content
        Box(
            modifier = Modifier.offset {
                IntOffset(x = offsetX.value.roundToInt(), y = 0)
            }
        ) {
            content()
        }
    }
}
