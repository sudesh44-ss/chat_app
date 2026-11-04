package com.nexa.app.presentation.contacts.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modal dialog for initiating a cryptographic connection request with client-side Proof-of-Work.
 */
@Composable
fun AddContactDialog(
    isPoWComputing: Boolean,
    onDismiss: () -> Unit,
    onSendRequest: (targetNxId: String, introMessage: String?) -> Unit
) {
    var targetNxId by remember { mutableStateOf("") }
    var introMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isPoWComputing) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Contact", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Enter your friend's NX-ID to initiate a zero-knowledge connection request.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = targetNxId,
                    onValueChange = { targetNxId = it.trim() },
                    label = { Text("Target NX-ID (e.g. NX-8492-ABC)") },
                    singleLine = true,
                    enabled = !isPoWComputing,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = introMessage,
                    onValueChange = { if (it.length <= 140) introMessage = it },
                    label = { Text("Intro message (Optional, max 140 chars)") },
                    maxLines = 3,
                    enabled = !isPoWComputing,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (isPoWComputing) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Securing connection request...",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (targetNxId.isNotBlank()) {
                        onSendRequest(targetNxId, introMessage.takeIf { it.isNotBlank() })
                    }
                },
                enabled = targetNxId.isNotBlank() && !isPoWComputing,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Send Request", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            if (!isPoWComputing) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cancel")
                }
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}
