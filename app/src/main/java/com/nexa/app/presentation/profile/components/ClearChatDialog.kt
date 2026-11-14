package com.nexa.app.presentation.profile.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Clear Chat Confirmation Dialog
 * Clearly explains that conversation messages will be deleted locally,
 * while contact relationship, phone numbers, and E2EE keys are preserved.
 */
@Composable
fun ClearChatDialog(
    contactName: String,
    onConfirmClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) Color(0xFF1E1E1E) else Color.White
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)
    val dangerColor = MaterialTheme.colorScheme.error

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = surfaceColor,
        title = {
            Text(
                text = "Clear Chat with $contactName?",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = primaryTextColor
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "All messages and media transferred in this chat will be permanently deleted from your local device vault.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = primaryTextColor.copy(alpha = 0.9f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Your contact connection and encryption session keys remain intact.\n• Other participants retain their independent message copies.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = secondaryTextColor
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirmClear()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = dangerColor),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Clear Chat", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Cancel", color = secondaryTextColor)
            }
        }
    )
}

/**
 * Block / Unblock Contact Dialog
 */
@Composable
fun BlockContactDialog(
    contactName: String,
    isCurrentlyBlocked: Boolean,
    onConfirmToggleBlock: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) Color(0xFF1E1E1E) else Color.White
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)
    val dangerColor = MaterialTheme.colorScheme.error

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = surfaceColor,
        title = {
            Text(
                text = if (isCurrentlyBlocked) "Unblock this person?" else "Block this person?",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = primaryTextColor
            )
        },
        text = {
            Text(
                text = if (isCurrentlyBlocked) {
                    "This person will be able to contact you again through NEXA, subject to your other privacy and communication settings."
                } else {
                    "They won't be able to send you messages, call you, or interact with you through NEXA. Your existing chat will not be deleted."
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = if (isCurrentlyBlocked) primaryTextColor else secondaryTextColor
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirmToggleBlock()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCurrentlyBlocked) {
                        if (isDark) Color(0xFF2979FF) else Color(0xFF1976D2)
                    } else dangerColor
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(if (isCurrentlyBlocked) "Unblock" else "Block", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Cancel", color = secondaryTextColor)
            }
        }
    )
}
