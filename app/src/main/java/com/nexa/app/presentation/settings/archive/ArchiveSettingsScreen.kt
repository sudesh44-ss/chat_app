package com.nexa.app.presentation.settings.archive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.presentation.settings.components.SettingsCard
import com.nexa.app.presentation.settings.components.SettingsDetailPage
import com.nexa.app.presentation.settings.components.SettingsDivider
import com.nexa.app.presentation.settings.components.SettingsInfoBanner
import com.nexa.app.presentation.settings.components.SettingsSectionLabel

@Composable
fun ArchiveSettingsScreen(
    chatRepository: ChatRepository,
    onNavigateBack: () -> Unit
) {
    val archivedChats by chatRepository.getArchivedConversations().collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    SettingsDetailPage(title = "Archive", onBack = onNavigateBack) {
        SettingsInfoBanner(
            text = "Archived conversations remain in the local database and can be restored to the main chat list without losing history or attachments."
        )

        SettingsSectionLabel("Archived conversations")
        if (archivedChats.isEmpty()) {
            SettingsCard {
                Text(
                    text = "No archived conversations",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            SettingsCard {
                archivedChats.forEachIndexed { index, conversation ->
                    if (index > 0) {
                        SettingsDivider()
                    }
                    ArchivedConversationRow(
                        conversation = conversation,
                        onUnarchive = {
                            coroutineScope.launch {
                                chatRepository.archiveConversation(conversation.conversationId, false)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchivedConversationRow(
    conversation: ChatConversation,
    onUnarchive: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.participantName.ifBlank { "Archived conversation" },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (conversation.lastMessageText.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = conversation.lastMessageText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }

            if (conversation.lastMessageTimestamp > 0L) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.MEDIUM,
                        java.text.DateFormat.SHORT
                    ).format(java.util.Date(conversation.lastMessageTimestamp)),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))
        TextButton(onClick = onUnarchive) {
            Text("Unarchive")
        }
    }
}
