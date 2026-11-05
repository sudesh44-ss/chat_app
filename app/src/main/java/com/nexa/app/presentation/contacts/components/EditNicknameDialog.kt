package com.nexa.app.presentation.contacts.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.theme.NexaBadgeMonospace

private const val MAX_NICKNAME_LENGTH = 40

/**
 * Material 3 Dialog to set, edit, or clear a private client-side contact nickname.
 * Zero-Knowledge: Stored strictly in local SQLCipher Room DB. Never leaves the device.
 */
@Composable
fun EditNicknameDialog(
    contactNxId: String,
    originalProfileName: String,
    currentNickname: String?,
    onSaveNickname: (String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var nicknameInput by remember { mutableStateOf(currentNickname ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Edit Contact Nickname",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "Enter a custom name for this contact. Only you will see this name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = nicknameInput,
                    onValueChange = {
                        if (it.length <= MAX_NICKNAME_LENGTH) {
                            nicknameInput = it
                        }
                    },
                    label = { Text("Custom Nickname") },
                    placeholder = { Text("e.g. Rahul Bhai") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    trailingIcon = {
                        if (nicknameInput.isNotEmpty()) {
                            IconButton(onClick = { nicknameInput = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear input",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    supportingText = {
                        Text(
                            text = "${nicknameInput.length} / $MAX_NICKNAME_LENGTH",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Original: ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = originalProfileName.ifBlank { contactNxId },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = NexaBadgeMonospace
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = nicknameInput.trim()
                    onSaveNickname(trimmed.ifEmpty { null })
                    onDismiss()
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (!currentNickname.isNullOrBlank()) {
                    TextButton(
                        onClick = {
                            onSaveNickname(null)
                            onDismiss()
                        }
                    ) {
                        Text(
                            text = "Remove",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
