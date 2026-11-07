package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.presentation.common.glass.NexaGlassSurface

enum class ChatOverflowAction {
    VIEW_CONTACT,
    SEARCH_CHAT,
    MEDIA_LINKS_FILES,
    GHOST_MODE,
    PINNED_MESSAGES,
    CHAT_WALLPAPER,
    CLEAR_CHAT,
    BLOCK_CONTACT
}

/**
 * Chat 3-Dot Overflow Menu with exact 8 items in specified order:
 * 1. View Contact
 * 2. Search Chat
 * 3. Media, Links & Files
 * 4. Ghost Mode
 * 5. Pinned Messages
 * 6. Chat Wallpaper
 * 7. Clear Chat
 * 8. Block Contact
 */
@Composable
fun NexaChatOverflowMenu(
    isGhostModeActive: Boolean = false,
    onActionSelected: (ChatOverflowAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    val surfaceColor = if (isDark) {
        Color(0xEE1E1E1E)
    } else {
        Color(0xEEFFFFFF)
    }

    val contentColor = if (isDark) Color.White else Color(0xFF111111)
    val dangerColor = MaterialTheme.colorScheme.error

    NexaGlassSurface(
        modifier = modifier.widthIn(min = 220.dp, max = 260.dp),
        shape = RoundedCornerShape(20.dp),
        surfaceColor = surfaceColor,
        elevation = 12.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            // 1. View Contact
            OverflowMenuItem(
                title = "View Contact",
                iconRes = R.drawable.ic_nexa_view_contact,
                tint = contentColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.VIEW_CONTACT)
                    onDismiss()
                }
            )

            // 2. Search Chat
            OverflowMenuItem(
                title = "Search Chat",
                iconRes = R.drawable.ic_nexa_search,
                tint = contentColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.SEARCH_CHAT)
                    onDismiss()
                }
            )

            // 3. Media, Links & Files
            OverflowMenuItem(
                title = "Media, Links & Files",
                iconRes = R.drawable.ic_nexa_file,
                tint = contentColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.MEDIA_LINKS_FILES)
                    onDismiss()
                }
            )

            // 4. Ghost Mode
            OverflowMenuItem(
                title = if (isGhostModeActive) "Ghost Mode (On)" else "Ghost Mode",
                iconRes = R.drawable.ic_nexa_ghost,
                tint = if (isGhostModeActive) MaterialTheme.colorScheme.primary else contentColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.GHOST_MODE)
                    onDismiss()
                }
            )

            // 5. Pinned Messages
            OverflowMenuItem(
                title = "Pinned Messages",
                iconRes = R.drawable.ic_nexa_pin,
                tint = contentColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.PINNED_MESSAGES)
                    onDismiss()
                }
            )

            // 6. Chat Wallpaper
            OverflowMenuItem(
                title = "Chat Wallpaper",
                iconRes = R.drawable.ic_nexa_wallpaper,
                tint = contentColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.CHAT_WALLPAPER)
                    onDismiss()
                }
            )

            HorizontalDivider(
                color = if (isDark) Color(0x22FFFFFF) else Color(0x15000000),
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // 7. Clear Chat
            OverflowMenuItem(
                title = "Clear Chat",
                iconRes = R.drawable.ic_nexa_clear_chat,
                tint = dangerColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.CLEAR_CHAT)
                    onDismiss()
                }
            )

            // 8. Block Contact
            OverflowMenuItem(
                title = "Block Contact",
                iconRes = R.drawable.ic_nexa_block_ban,
                tint = dangerColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.BLOCK_CONTACT)
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun OverflowMenuItem(
    title: String,
    iconRes: Int,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = title,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            color = tint,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
