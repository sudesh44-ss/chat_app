package com.nexa.app.presentation.contacts.components

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.domain.model.DeviceContact

@Composable
fun AddContactByPhoneDialog(
    onDismiss: () -> Unit,
    onLookupPhone: (phone: String, onResult: (DeviceContact?) -> Unit) -> Unit,
    onStartChat: (nxId: String, displayName: String) -> Unit
) {
    val context = LocalContext.current
    var inputPhone by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResult by remember { mutableStateOf<DeviceContact?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Add Contact by Phone",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Enter a phone number to discover if they have an active, verified NEXA account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = inputPhone,
                    onValueChange = {
                        inputPhone = it
                        searchResult = null
                        searchError = null
                    },
                    label = { Text("Phone Number (+Country Code)") },
                    placeholder = { Text("+91 98765 43210") },
                    leadingIcon = {
                        Icon(Icons.Default.Phone, contentDescription = null)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )

                if (isSearching) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Checking verified registry...", style = MaterialTheme.typography.bodySmall)
                    }
                }

                searchError?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Search Result Card
                searchResult?.let { contact ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                if (contact.isNexaUser) NexaVerifiedGreen.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(12.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            if (contact.isNexaUser) NexaVerifiedGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.secondaryContainer,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = contact.displayName.firstOrNull()?.uppercase() ?: "U",
                                        fontWeight = FontWeight.Bold,
                                        color = if (contact.isNexaUser) NexaVerifiedGreen else MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = contact.displayName,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = contact.normalizedNumber,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (contact.isNexaUser && contact.nexaNxId != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("NX-ID:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = contact.nexaNxId,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = NexaBadgeMonospace
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Save to Android Contacts button
                                    OutlinedButton(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_INSERT).apply {
                                                type = ContactsContract.RawContacts.CONTENT_TYPE
                                                putExtra(ContactsContract.Intents.Insert.PHONE, contact.normalizedNumber)
                                                putExtra(ContactsContract.Intents.Insert.NAME, contact.displayName)
                                            }
                                            context.startActivity(intent)
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Save Contact", fontSize = 12.sp)
                                    }

                                    // Start Chat button
                                    Button(
                                        onClick = {
                                            onDismiss()
                                            onStartChat(contact.nexaNxId, contact.displayName)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = NexaVerifiedGreen),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Chat", fontSize = 12.sp)
                                    }
                                }
                            } else {
                                Text(
                                    text = "This phone number is not registered on NEXA yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Button(
                                    onClick = {
                                        val inviteIntent = Intent(Intent.ACTION_SENDTO).apply {
                                            data = Uri.parse("smsto:${contact.normalizedNumber}")
                                            putExtra("sms_body", "Hey! Join me on NEXA, the private end-to-end encrypted messaging app.")
                                        }
                                        context.startActivity(inviteIntent)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Invite via SMS")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (searchResult == null) {
                Button(
                    onClick = {
                        if (inputPhone.isNotBlank()) {
                            isSearching = true
                            searchError = null
                            onLookupPhone(inputPhone.trim()) { res ->
                                isSearching = false
                                if (res != null) {
                                    searchResult = res
                                } else {
                                    searchError = "Could not verify phone number format"
                                }
                            }
                        }
                    },
                    enabled = inputPhone.isNotBlank() && !isSearching
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Search")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
