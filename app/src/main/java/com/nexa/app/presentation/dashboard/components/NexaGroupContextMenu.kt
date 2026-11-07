package com.nexa.app.presentation.dashboard.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nexa.app.R
import com.nexa.app.core.theme.NexaGlassTokens
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.presentation.common.glass.NexaGlassSurface

/**
 * Group Context Menu (Long Press).
 *
 * Exact mandatory order:
 * 1. Pin / Unpin
 * 2. Archive
 * 3. View Group
 * 4. Mute / Unmute
 * 5. Select
 * 6. Delete Chats
 * 7. Mark as Read / Unread
 * 8. Leave Group
 */
@Composable
fun NexaGroupContextMenu(
    chat: ChatConversation,
    isPinned: Boolean = false,
    isMuted: Boolean = false,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    onViewGroup: () -> Unit,
    onToggleMute: () -> Unit,
    onSelect: () -> Unit,
    onDeleteChats: () -> Unit,
    onToggleRead: () -> Unit,
    onLeaveGroup: () -> Unit
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
                    // Header: Group Title
                    Text(
                        text = chat.participantName,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                    // 1. Pin / Unpin
                    ContextMenuItemRow(
                        label = if (isPinned) "Unpin" else "Pin",
                        iconRes = R.drawable.ic_nexa_pin,
                        onClick = { onDismiss(); onTogglePin() }
                    )

                    // 2. Archive
                    ContextMenuItemRow(
                        label = "Archive",
                        iconRes = R.drawable.ic_nexa_archive,
                        onClick = { onDismiss(); onArchive() }
                    )

                    // 3. View Group
                    ContextMenuItemRow(
                        label = "View Group",
                        iconRes = R.drawable.ic_nexa_groups,
                        onClick = { onDismiss(); onViewGroup() }
                    )

                    // 4. Mute / Unmute
                    ContextMenuItemRow(
                        label = if (isMuted) "Unmute" else "Mute",
                        iconRes = if (isMuted) R.drawable.ic_nexa_bell else R.drawable.ic_nexa_mute,
                        onClick = { onDismiss(); onToggleMute() }
                    )

                    // 5. Select
                    ContextMenuItemRow(
                        label = "Select",
                        iconRes = R.drawable.ic_nexa_select,
                        onClick = { onDismiss(); onSelect() }
                    )

                    // 6. Delete Chats
                    ContextMenuItemRow(
                        label = "Delete Chats",
                        iconRes = R.drawable.ic_nexa_delete,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { onDismiss(); onDeleteChats() }
                    )

                    // 7. Mark as Read / Unread
                    ContextMenuItemRow(
                        label = if (chat.unreadCount > 0) "Mark as Read" else "Mark as Unread",
                        iconRes = R.drawable.ic_nexa_mark_read,
                        onClick = { onDismiss(); onToggleRead() }
                    )

                    // 8. Leave Group
                    ContextMenuItemRow(
                        label = "Leave Group",
                        iconRes = R.drawable.ic_nexa_leave_group,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { onDismiss(); onLeaveGroup() }
                    )
                }
            }
        }
    }
}
