package com.nexa.app.presentation.contacts.details

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
 * Remove Contact Confirmation Dialog
 */
@Composable
fun RemoveContactDialog(
    contactName: String,
    onConfirmRemove: () -> Unit,
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
                text = "Remove Contact?",
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
                    text = "$contactName will be removed from your NEXA saved contacts.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = primaryTextColor.copy(alpha = 0.9f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Your existing chat messages and E2EE session keys will remain safe.\n• The contact is not blocked and can still reach you.\n• Phone contacts stored on your device will not be deleted.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = secondaryTextColor
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirmRemove()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = dangerColor),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Remove Contact", color = Color.White)
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
