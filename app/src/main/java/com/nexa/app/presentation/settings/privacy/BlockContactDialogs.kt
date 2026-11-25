package com.nexa.app.presentation.settings.privacy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nexa.app.presentation.common.glass.NexaGlassSurface

/**
 * Authoritative NEXA Block Confirmation Dialog.
 *
 * Provides two independent, optional checkboxes:
 * 1. "Also report this contact" (default unchecked)
 * 2. "Delete chat and conversation history" (default unchecked)
 */
@Composable
fun BlockContactConfirmationDialog(
    contactName: String,
    onConfirmBlock: (alsoReport: Boolean, deleteChat: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var alsoReport by remember { mutableStateOf(false) }
    var deleteChat by remember { mutableStateOf(false) }

    val isDark = isSystemInDarkTheme()
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    Dialog(onDismissRequest = onDismiss) {
        NexaGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            surfaceColor = if (isDark) Color(0xF0181818) else Color(0xF8FFFFFF),
            elevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Dialog Title
                Text(
                    text = "Block Contact?",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryText
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Core Explanation Message
                Text(
                    text = "Are you sure you want to block this contact?\n\nBlocking this person will prevent them from contacting you through NEXA according to the application's blocking rules. They will not receive a direct notification that you have blocked them.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = secondaryText
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(
                    color = if (isDark) Color(0x1FFFFFFF) else Color(0x14000000),
                    thickness = 0.5.dp
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Checkbox 1: Also report this contact
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { alsoReport = !alsoReport }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Checkbox(
                        checked = alsoReport,
                        onCheckedChange = { alsoReport = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            checkmarkColor = Color.Black
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Also report this contact",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryText
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Report this account for spam, abuse, or inappropriate behavior. Reporting may submit relevant information for review according to NEXA's reporting policy.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = secondaryText
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Checkbox 2: Delete chat and conversation history
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { deleteChat = !deleteChat }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Checkbox(
                        checked = deleteChat,
                        onCheckedChange = { deleteChat = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.error,
                            checkmarkColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Delete chat and conversation history",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryText
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Delete the existing conversation and locally stored chat history from this device. This action may not be reversible.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = secondaryText
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = secondaryText, fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirmBlock(alsoReport, deleteChat)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Block Contact", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * Authoritative NEXA Unblock Confirmation Dialog.
 */
@Composable
fun UnblockContactConfirmationDialog(
    contactName: String,
    onConfirmUnblock: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    Dialog(onDismissRequest = onDismiss) {
        NexaGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            surfaceColor = if (isDark) Color(0xF0181818) else Color(0xF8FFFFFF),
            elevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                Text(
                    text = "Unblock Contact?",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryText
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "This person will no longer be blocked. They may be able to contact you again depending on your privacy and connection settings.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = secondaryText
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = secondaryText, fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirmUnblock()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Unblock", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
