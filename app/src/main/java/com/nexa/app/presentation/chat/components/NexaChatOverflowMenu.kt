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
    VIEW_PROFILE,
    SEARCH_CHAT,
    DELETE_CHAT,
    CLEAR_CHAT,
    SHARED_FILES,
    PINNED_MESSAGES,
    MUTE_NOTIFICATIONS,
    ARCHIVE_CHAT
}

/**
 * Chat header overflow menu used by the current conversation.
 * The menu intentionally contains only the canonical management actions required by the app.
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
            OverflowMenuItem(
                title = "View Profile",
                iconRes = R.drawable.ic_nexa_view_contact,
                tint = contentColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.VIEW_PROFILE)
                    onDismiss()
                }
            )

            OverflowMenuItem(
                title = "Search",
                iconRes = R.drawable.ic_nexa_search,
                tint = contentColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.SEARCH_CHAT)
                    onDismiss()
                }
            )

            OverflowMenuItem(
                title = "Delete Chat",
                iconRes = R.drawable.ic_nexa_delete,
                tint = dangerColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.DELETE_CHAT)
                    onDismiss()
                }
            )

            OverflowMenuItem(
                title = "Clear Chat",
                iconRes = R.drawable.ic_nexa_clear_chat,
                tint = dangerColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.CLEAR_CHAT)
                    onDismiss()
                }
            )

            OverflowMenuItem(
                title = "Shared Files",
                iconRes = R.drawable.ic_nexa_file,
                tint = contentColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.SHARED_FILES)
                    onDismiss()
                }
            )

            OverflowMenuItem(
                title = "Pinned Messages",
                iconRes = R.drawable.ic_nexa_pin,
                tint = contentColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.PINNED_MESSAGES)
                    onDismiss()
                }
            )

            OverflowMenuItem(
                title = "Mute Notifications",
                iconRes = R.drawable.ic_nexa_mute,
                tint = if (isGhostModeActive) MaterialTheme.colorScheme.primary else contentColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.MUTE_NOTIFICATIONS)
                    onDismiss()
                }
            )

            OverflowMenuItem(
                title = "Archive",
                iconRes = R.drawable.ic_nexa_archive,
                tint = contentColor,
                onClick = {
                    onActionSelected(ChatOverflowAction.ARCHIVE_CHAT)
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
