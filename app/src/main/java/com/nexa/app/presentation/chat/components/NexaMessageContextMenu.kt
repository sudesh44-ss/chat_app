package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.domain.model.Message
import com.nexa.app.presentation.common.glass.NexaGlassSurface

enum class ContextMenuAction {
    REPLY,
    COPY,
    FORWARD,
    PIN,
    MESSAGE_INFO,
    DELETE
}

/**
 * Message Context Menu with exact 6 actions:
 * 1. Reply
 * 2. Copy
 * 3. Forward
 * 4. Pin
 * 5. Message Info
 * 6. Delete
 */
@Composable
fun NexaMessageContextMenu(
    message: Message,
    isPinned: Boolean = message.isPinned,
    onActionSelected: (ContextMenuAction) -> Unit,
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
    val deleteColor = MaterialTheme.colorScheme.error

    NexaGlassSurface(
        modifier = modifier.widthIn(min = 200.dp, max = 240.dp),
        shape = RoundedCornerShape(20.dp),
        surfaceColor = surfaceColor,
        elevation = 12.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            // 1. Reply
            ContextMenuItem(
                title = "Reply",
                iconRes = R.drawable.ic_nexa_reply,
                tint = contentColor,
                onClick = {
                    onActionSelected(ContextMenuAction.REPLY)
                    onDismiss()
                }
            )

            // 2. Copy
            ContextMenuItem(
                title = "Copy",
                iconRes = R.drawable.ic_nexa_copy,
                tint = contentColor,
                onClick = {
                    onActionSelected(ContextMenuAction.COPY)
                    onDismiss()
                }
            )

            // 3. Forward
            ContextMenuItem(
                title = "Forward",
                iconRes = R.drawable.ic_nexa_forward,
                tint = contentColor,
                onClick = {
                    onActionSelected(ContextMenuAction.FORWARD)
                    onDismiss()
                }
            )

            // 4. Pin
            ContextMenuItem(
                title = if (isPinned) "Unpin" else "Pin",
                iconRes = R.drawable.ic_nexa_pin,
                tint = contentColor,
                onClick = {
                    onActionSelected(ContextMenuAction.PIN)
                    onDismiss()
                }
            )

            // 5. Message Info
            ContextMenuItem(
                title = "Message Info",
                iconRes = R.drawable.ic_nexa_info,
                tint = contentColor,
                onClick = {
                    onActionSelected(ContextMenuAction.MESSAGE_INFO)
                    onDismiss()
                }
            )

            // 6. Delete
            ContextMenuItem(
                title = "Delete",
                iconRes = R.drawable.ic_nexa_delete,
                tint = deleteColor,
                onClick = {
                    onActionSelected(ContextMenuAction.DELETE)
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun ContextMenuItem(
    title: String,
    iconRes: Int,
    tint: Color,
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

/**
 * Message Deletion Confirmation Dialog with "Delete for Me" and optional "Delete for Everyone".
 */
@Composable
fun NexaDeleteMessageDialog(
    isOutgoing: Boolean,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Delete Message?",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Text(
                text = "Are you sure you want to delete this message? This action cannot be undone.",
                fontSize = 14.sp,
                color = if (isDark) Color(0xFFB0B0B0) else Color(0xFF555555)
            )
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End
            ) {
                if (isOutgoing) {
                    TextButton(
                        onClick = {
                            onDeleteForEveryone()
                            onDismiss()
                        }
                    ) {
                        Text(
                            text = "Delete for Everyone",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                TextButton(
                    onClick = {
                        onDeleteForMe()
                        onDismiss()
                    }
                ) {
                    Text(
                        text = "Delete for Me",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
                TextButton(
                    onClick = onDismiss
                ) {
                    Text(text = "Cancel")
                }
            }
        },
        dismissButton = {}
    )
}
