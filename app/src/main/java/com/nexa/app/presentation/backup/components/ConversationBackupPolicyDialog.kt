package com.nexa.app.presentation.backup.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.sp
import com.nexa.app.domain.model.ConversationBackupPolicy
import com.nexa.app.domain.model.ConversationCustomCategories

/**
 * Functional dialog allowing granular configuration of an individual conversation's backup policy.
 * Supports: DEFAULT, PRIORITY, CUSTOM (granular categories), EXCLUDED, and MANUAL_ONLY.
 */
@Composable
fun ConversationBackupPolicyDialog(
    conversationTitle: String,
    currentPolicy: ConversationBackupPolicy,
    currentCustomCategories: ConversationCustomCategories?,
    onDismiss: () -> Unit,
    onSavePolicy: (policy: ConversationBackupPolicy, customCategories: ConversationCustomCategories?) -> Unit
) {
    var selectedPolicy by remember { mutableStateOf(currentPolicy) }
    var customCategories by remember {
        mutableStateOf(currentCustomCategories ?: ConversationCustomCategories())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Backup Policy",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = conversationTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Choose how this conversation is handled during cloud backups:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                PolicyOptionRow(
                    title = "Default",
                    subtitle = "Follows global backup category configuration",
                    selected = selectedPolicy == ConversationBackupPolicy.DEFAULT,
                    onClick = { selectedPolicy = ConversationBackupPolicy.DEFAULT }
                )

                PolicyOptionRow(
                    title = "Priority",
                    subtitle = "Packaged and processed before standard conversations",
                    selected = selectedPolicy == ConversationBackupPolicy.PRIORITY,
                    onClick = { selectedPolicy = ConversationBackupPolicy.PRIORITY }
                )

                PolicyOptionRow(
                    title = "Custom Categories",
                    subtitle = "Select specific data types to include for this chat",
                    selected = selectedPolicy == ConversationBackupPolicy.CUSTOM,
                    onClick = { selectedPolicy = ConversationBackupPolicy.CUSTOM }
                )

                AnimatedVisibility(
                    visible = selectedPolicy == ConversationBackupPolicy.CUSTOM,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Included Content Types",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )

                        CategoryCheckboxRow("Messages (Text)", customCategories.includeMessages) {
                            customCategories = customCategories.copy(includeMessages = it)
                        }
                        CategoryCheckboxRow("Images & Photos", customCategories.includeImages) {
                            customCategories = customCategories.copy(includeImages = it)
                        }
                        CategoryCheckboxRow("Videos", customCategories.includeVideos) {
                            customCategories = customCategories.copy(includeVideos = it)
                        }
                        CategoryCheckboxRow("Documents", customCategories.includeDocuments) {
                            customCategories = customCategories.copy(includeDocuments = it)
                        }
                        CategoryCheckboxRow("Audio & Voice Notes", customCategories.includeAudio) {
                            customCategories = customCategories.copy(includeAudio = it)
                        }
                        CategoryCheckboxRow("Other Files", customCategories.includeOtherFiles) {
                            customCategories = customCategories.copy(includeOtherFiles = it)
                        }
                    }
                }

                PolicyOptionRow(
                    title = "Excluded",
                    subtitle = "Never back up this conversation or its media",
                    selected = selectedPolicy == ConversationBackupPolicy.EXCLUDED,
                    onClick = { selectedPolicy = ConversationBackupPolicy.EXCLUDED }
                )

                PolicyOptionRow(
                    title = "Manual Only",
                    subtitle = "Include only during manual user-initiated backups",
                    selected = selectedPolicy == ConversationBackupPolicy.MANUAL_ONLY,
                    onClick = { selectedPolicy = ConversationBackupPolicy.MANUAL_ONLY }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalCustom = if (selectedPolicy == ConversationBackupPolicy.CUSTOM) customCategories else null
                    onSavePolicy(selectedPolicy, finalCustom)
                    onDismiss()
                }
            ) {
                Text("Save Policy")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        onSavePolicy(ConversationBackupPolicy.DEFAULT, null)
                        onDismiss()
                    }
                ) {
                    Text("Reset to Default")
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
private fun PolicyOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CategoryCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
