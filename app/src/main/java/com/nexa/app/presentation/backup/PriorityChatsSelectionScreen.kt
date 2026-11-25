package com.nexa.app.presentation.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.model.ConversationBackupPolicy
import com.nexa.app.presentation.backup.components.ConversationBackupPolicyDialog
import com.nexa.app.presentation.common.glass.NexaGlassSurface

/**
 * Priority Chat Selection Screen.
 * Allows independent priority selection for Personal Chats and Group Chats.
 * Directly reflects and updates the Room SQLite authoritative state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriorityChatsSelectionScreen(
    viewModel: BackupViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val personalChats by viewModel.allPersonalConversations.collectAsState(initial = emptyList())
    val groupChats by viewModel.allGroupConversations.collectAsState(initial = emptyList())
    val priorityCount by viewModel.priorityChatCount.collectAsState(initial = 0)

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var editingConversation by remember { mutableStateOf<ChatConversation?>(null) }

    val activeList = if (selectedTabIndex == 0) personalChats else groupChats
    val filteredList = remember(activeList, searchQuery) {
        if (searchQuery.isBlank()) {
            activeList
        } else {
            val q = searchQuery.trim().lowercase()
            activeList.filter {
                it.participantName.lowercase().contains(q) ||
                (it.title?.lowercase()?.contains(q) == true) ||
                it.participantNxId.lowercase().contains(q)
            }
        }
    }

    val personalPriorityCount = remember(personalChats) { personalChats.count { it.isPriorityBackup } }
    val groupPriorityCount = remember(groupChats) { groupChats.count { it.isPriorityBackup } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Chat Backup Policies & Priority",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "$priorityCount prioritized • Tap row to edit policy",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Informational Banner
            NexaGlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Priority chats and messages are packaged and processed first during cloud backups without waiting for large media files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search conversations...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Tabs for Personal Chats and Groups
            SecondaryTabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = {
                        Text("Personal (${personalPriorityCount}/${personalChats.size})")
                    },
                    icon = {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = {
                        Text("Groups (${groupPriorityCount}/${groupChats.size})")
                    },
                    icon = {
                        Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
            }

            // Bulk Controls (Select All / Deselect All for active tab)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val currentCategoryName = if (selectedTabIndex == 0) "Personal Conversations" else "Group Conversations"
                Text(
                    text = currentCategoryName,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            viewModel.setBulkPriority(activeList.map { it.conversationId }, true)
                        }
                    ) {
                        Text("Select All", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = {
                            viewModel.setBulkPriority(activeList.map { it.conversationId }, false)
                        }
                    ) {
                        Text("Deselect All", fontSize = 12.sp)
                    }
                }
            }

            // Conversation List
            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "No conversations match '$searchQuery'"
                                   else if (selectedTabIndex == 0) "No personal conversations found"
                                   else "No group conversations found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val (prioritizedChats, standardChats) = remember(filteredList) {
                    filteredList.partition { it.isPriorityBackup }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (prioritizedChats.isNotEmpty()) {
                        item {
                            Text(
                                text = "PRIORITIZED CONVERSATIONS (${prioritizedChats.size})",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(prioritizedChats, key = { it.conversationId }) { chat ->
                            PriorityConversationItem(
                                conversation = chat,
                                isPriority = true,
                                onTogglePriority = { isPrioritized ->
                                    viewModel.setConversationPriority(chat.conversationId, isPrioritized)
                                },
                                onConfigurePolicy = {
                                    editingConversation = chat
                                }
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }

                    if (prioritizedChats.isNotEmpty() && standardChats.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                thickness = 2.dp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }

                    if (standardChats.isNotEmpty()) {
                        item {
                            Text(
                                text = "STANDARD CONVERSATIONS (${standardChats.size})",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(standardChats, key = { it.conversationId }) { chat ->
                            PriorityConversationItem(
                                conversation = chat,
                                isPriority = false,
                                onTogglePriority = { isPrioritized ->
                                    viewModel.setConversationPriority(chat.conversationId, isPrioritized)
                                },
                                onConfigurePolicy = {
                                    editingConversation = chat
                                }
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }

            editingConversation?.let { targetChat ->
                val chatTitle = targetChat.title?.takeIf { it.isNotBlank() }
                    ?: targetChat.participantName.takeIf { it.isNotBlank() }
                    ?: targetChat.participantNxId.take(12)
                ConversationBackupPolicyDialog(
                    conversationTitle = chatTitle,
                    currentPolicy = targetChat.backupPolicy,
                    currentCustomCategories = targetChat.customBackupCategories,
                    onDismiss = { editingConversation = null },
                    onSavePolicy = { policy, customCategories ->
                        viewModel.setConversationBackupPolicy(targetChat.conversationId, policy, customCategories)
                        editingConversation = null
                    }
                )
            }
        }
    }
}

@Composable
private fun PriorityConversationItem(
    conversation: ChatConversation,
    isPriority: Boolean,
    onTogglePriority: (Boolean) -> Unit,
    onConfigurePolicy: () -> Unit
) {
    val displayName = conversation.title?.takeIf { it.isNotBlank() }
        ?: conversation.participantName.takeIf { it.isNotBlank() }
        ?: conversation.participantNxId.take(12)

    val policy = conversation.backupPolicy
    val policyLabel = when (policy) {
        ConversationBackupPolicy.DEFAULT -> if (isPriority) "PRIORITY" else "DEFAULT"
        ConversationBackupPolicy.PRIORITY -> "PRIORITY"
        ConversationBackupPolicy.CUSTOM -> "CUSTOM"
        ConversationBackupPolicy.MANUAL_ONLY -> "MANUAL ONLY"
        ConversationBackupPolicy.EXCLUDED -> "EXCLUDED"
    }
    val badgeColor = when (policy) {
        ConversationBackupPolicy.EXCLUDED -> MaterialTheme.colorScheme.error
        ConversationBackupPolicy.MANUAL_ONLY -> MaterialTheme.colorScheme.tertiary
        ConversationBackupPolicy.CUSTOM -> MaterialTheme.colorScheme.secondary
        ConversationBackupPolicy.PRIORITY -> MaterialTheme.colorScheme.primary
        ConversationBackupPolicy.DEFAULT -> if (isPriority) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTogglePriority(!isPriority) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar circle
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (isPriority) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isPriority) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = policyLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .clickable { onConfigurePolicy() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (conversation.lastMessageText.isNotBlank()) conversation.lastMessageText
                       else conversation.participantNxId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = onConfigurePolicy,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Configure Policy",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Switch(
            checked = isPriority,
            onCheckedChange = onTogglePriority
        )
    }
}
