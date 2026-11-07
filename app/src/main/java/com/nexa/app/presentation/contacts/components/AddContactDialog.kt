package com.nexa.app.presentation.contacts.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexa.app.data.remote.dto.DiscoveredUserDto
import com.nexa.app.presentation.common.avatar.NexaAvatar

/**
 * Modal dialog for discovering a NEXA user by exact NX-ID and initiating a connection request.
 */
@Composable
fun AddContactDialog(
    isPoWComputing: Boolean,
    isSearching: Boolean = false,
    discoveredUser: DiscoveredUserDto? = null,
    onDismiss: () -> Unit,
    onSearch: ((String) -> Unit)? = null,
    onSendRequest: (targetNxId: String, introMessage: String?) -> Unit
) {
    var targetNxId by remember { mutableStateOf("") }
    var introMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isPoWComputing && !isSearching) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Contact by NX-ID", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Enter a verified NX-ID (e.g. NX-29AB731Q) to find and connect with friends.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = targetNxId,
                        onValueChange = { targetNxId = it.trim().uppercase() },
                        label = { Text("NX-ID") },
                        placeholder = { Text("NX-XXXXXXXX") },
                        singleLine = true,
                        enabled = !isPoWComputing && !isSearching,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )

                    if (onSearch != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { if (targetNxId.isNotBlank()) onSearch(targetNxId) },
                            enabled = targetNxId.isNotBlank() && !isSearching
                        ) {
                            if (isSearching) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                // Discovered Profile Card Preview
                if (discoveredUser != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NexaAvatar(
                                nexaAvatarUrl = discoveredUser.avatarUrl,
                                deviceContactPhotoUri = null,
                                displayName = discoveredUser.displayName,
                                size = 44.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = discoveredUser.displayName,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = discoveredUser.nxId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (!discoveredUser.bio.isNullOrBlank()) {
                                    Text(
                                        text = discoveredUser.bio,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

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
            if (!isPoWComputing && !isSearching) {
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
