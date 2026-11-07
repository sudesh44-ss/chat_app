package com.nexa.app.presentation.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.presentation.common.glass.NexaGlassSurface

/**
 * Standard NEXA Quick Reaction Emojis.
 * Exact 6 reactions: ❤️, 👍, 😂, 😮, 😢, 🙏
 */
object NexaReactions {
    val QUICK_REACTIONS = listOf("❤️", "👍", "😂", "😮", "😢", "🙏")
}

/**
 * Floating Liquid Glass Quick Reaction Bar.
 */
@Composable
fun NexaReactionPicker(
    selectedReaction: String? = null,
    onReactionSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    val surfaceColor = if (isDark) {
        Color(0xEE1E1E1E)
    } else {
        Color(0xEEFFFFFF)
    }

    NexaGlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        surfaceColor = surfaceColor,
        elevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NexaReactions.QUICK_REACTIONS.forEach { emoji ->
                val isSelected = emoji == selectedReaction
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onReactionSelect(emoji)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emoji,
                        fontSize = if (isSelected) 24.sp else 20.sp
                    )
                }
            }
        }
    }
}

/**
 * Message Reaction Pill Badge to display on the message bubble.
 */
@Composable
fun NexaReactionBadge(
    reaction: String,
    count: Int = 1,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) Color(0xCC2A2A2A) else Color(0xEEF0F0F0)
    val textColor = if (isDark) Color.White else Color.Black

    NexaGlassSurface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        surfaceColor = bgColor,
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = reaction, fontSize = 13.sp)
            if (count > 1) {
                Text(
                    text = count.toString(),
                    fontSize = 11.sp,
                    color = textColor
                )
            }
        }
    }
}
