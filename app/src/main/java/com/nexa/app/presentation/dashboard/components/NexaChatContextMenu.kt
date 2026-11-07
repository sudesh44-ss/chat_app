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

/**
 * Individual Chat Context Menu (Long Press).
 *
 * Exact mandatory order:
 * 1. Pin / Unpin
 * 2. Archive
 * 3. View Contact
 * 4. Mute / Unmute
 * 5. Select
 * 6. Delete
 * 7. Mark as Read / Unread
 * 8. Block
 */
@Composable
fun NexaChatContextMenu(
    chat: ChatConversation,
    isPinned: Boolean = false,
    isMuted: Boolean = false,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    onViewContact: () -> Unit,
    onToggleMute: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onToggleRead: () -> Unit,
    onBlock: () -> Unit
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
                    // Header: Chat Title
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

                    // 3. View Contact
                    ContextMenuItemRow(
                        label = "View Contact",
                        iconRes = R.drawable.ic_nexa_view_contact,
                        onClick = { onDismiss(); onViewContact() }
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

                    // 6. Delete
                    ContextMenuItemRow(
                        label = "Delete",
                        iconRes = R.drawable.ic_nexa_delete,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { onDismiss(); onDelete() }
                    )

                    // 7. Mark as Read / Unread
                    ContextMenuItemRow(
                        label = if (chat.unreadCount > 0) "Mark as Read" else "Mark as Unread",
                        iconRes = R.drawable.ic_nexa_mark_read,
                        onClick = { onDismiss(); onToggleRead() }
                    )

                    // 8. Block
                    ContextMenuItemRow(
                        label = "Block",
                        iconRes = R.drawable.ic_nexa_block,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { onDismiss(); onBlock() }
                    )
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
