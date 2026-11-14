package com.nexa.app.presentation.dashboard.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nexa.app.R
import com.nexa.app.core.theme.NexaGlassTokens
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.presentation.common.glass.NexaGlassSurface

@Composable
fun NexaChatContextMenu(
    chat: ChatConversation,
    isPinned: Boolean = false,
    isMuted: Boolean = false,
    isBlocked: Boolean = false,
    onDismiss: () -> Unit,
    onViewContact: () -> Unit,
    onTogglePin: () -> Unit,
    onSelectAll: () -> Unit,
    onToggleBlock: () -> Unit,
    onToggleMute: () -> Unit,
    onViewStatus: () -> Unit,
    onMarkUnread: () -> Unit,
    onClearChat: () -> Unit,
    onArchive: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 300.dp)
        ) {
            NexaGlassSurface(
                shape = RoundedCornerShape(NexaGlassTokens.ContextMenuRadius),
                elevation = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        text = chat.participantName,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                    ContextMenuItemRow("View Contact", R.drawable.ic_nexa_view_contact) {
                        onDismiss()
                        onViewContact()
                    }
                    ContextMenuItemRow(if (isPinned) "Unpin" else "Pin", R.drawable.ic_nexa_pin) {
                        onDismiss()
                        onTogglePin()
                    }
                    ContextMenuItemRow("Select All", R.drawable.ic_nexa_select) {
                        onDismiss()
                        onSelectAll()
                    }
                    ContextMenuItemRow(
                        if (isBlocked) "Unblock" else "Block",
                        R.drawable.ic_nexa_block,
                        if (isBlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                    ) {
                        onDismiss()
                        onToggleBlock()
                    }
                    ContextMenuItemRow(
                        if (isMuted) "Unmute Notifications" else "Silent Notifications",
                        if (isMuted) R.drawable.ic_nexa_bell else R.drawable.ic_nexa_mute
                    ) {
                        onDismiss()
                        onToggleMute()
                    }
                    ContextMenuItemRow("View Status", R.drawable.ic_nexa_clock_fading) {
                        onDismiss()
                        onViewStatus()
                    }
                    ContextMenuItemRow("Mark as Unread", R.drawable.ic_nexa_mark_read) {
                        onDismiss()
                        onMarkUnread()
                    }
                    ContextMenuItemRow("Clear Chat", R.drawable.ic_nexa_clear_chat) {
                        onDismiss()
                        onClearChat()
                    }
                    ContextMenuItemRow("Archive", R.drawable.ic_nexa_archive) {
                        onDismiss()
                        onArchive()
                    }
                }
            }
        }
    }
}

@Composable
fun ContextMenuItemRow(
    label: String,
    @DrawableRes iconRes: Int,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(19.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = tint
        )
    }
}
