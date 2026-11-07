package com.nexa.app.presentation.profile.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Edit User Detail Dialog
 *
 * For contacts: Edits local Zero-Knowledge custom nickname alias (Tier 1 Anti-Impersonation).
 * Strictly enforced: Contacts cannot modify server-owned identity or remote accounts.
 */
@Composable
fun EditUserDetailsDialog(
    initialNickname: String?,
    contactOriginalName: String,
    onSaveNickname: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    var textValue by remember { mutableStateOf(initialNickname ?: "") }

    val surfaceColor = if (isDark) Color(0xFF1E1E1E) else Color.White
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = surfaceColor,
        title = {
            Text(
                text = "Edit Contact Alias",
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
                    text = "Set a private custom nickname stored strictly on this device in your encrypted vault. The peer remains registered as '$contactOriginalName'.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = secondaryTextColor
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text("Custom Nickname") },
                    placeholder = { Text(contactOriginalName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = primaryTextColor,
                        unfocusedTextColor = primaryTextColor,
                        focusedBorderColor = if (isDark) Color(0xFF2979FF) else Color(0xFF1976D2),
                        unfocusedBorderColor = if (isDark) Color.White.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.2f)
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = textValue.trim().ifBlank { null }
                    onSaveNickname(clean)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDark) Color(0xFF2979FF) else Color(0xFF1976D2)
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Save Alias", color = Color.White)
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
