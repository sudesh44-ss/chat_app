package com.nexa.app.presentation.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaGlassTokens
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.domain.repository.ContactRepository
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import com.nexa.app.presentation.dashboard.components.NexaConversationItem
import com.nexa.app.presentation.dashboard.isGroup
import com.nexa.app.presentation.search.components.NexaSearchFilterChips
import com.nexa.app.presentation.search.model.SearchFilter
import com.nexa.app.presentation.search.model.matches
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * NEXA Advanced Search Screen.
 *
 * Strict Layout Order:
 * 1. Back button (Circular glass button)
 * 2. Search input (Rounded frosted glass field)
 * 3. Horizontal filter chips (Exact 12-filter scrollable row)
 * 4. Recent Chats heading
 * 5. Clear All action (with confirmation dialog)
 * 6. Search results / recent conversation list (Reusable glass conversation cards)
 */
@Composable
fun NexaSearchScreen(
    chatRepository: ChatRepository,
    contactRepository: ContactRepository? = null,
    onNavigateBack: () -> Unit,
    onOpenChat: (conversationId: String, participantNxId: String, participantName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val conversations by chatRepository.getConversations().collectAsState(initial = emptyList())
    val archivedConversations by chatRepository.getArchivedConversations().collectAsState(initial = emptyList())
    val contacts by (contactRepository?.getAllContacts()?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList()) })
    val searchableConversations = remember(conversations, archivedConversations) {
        (conversations + archivedConversations).distinctBy { it.conversationId }
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilters by remember { mutableStateOf<Set<SearchFilter>>(emptySet()) }
    var showClearAllConfirmation by remember { mutableStateOf(false) }
    var searchHasFocus by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Session cache of recent conversation IDs (defaults to recent 5)
    var recentChatIds by remember(conversations) {
        mutableStateOf(conversations.take(5).map { it.conversationId }.toSet())
    }

    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    // Auto-focus the search field on launch
    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    // Set of known contact NX-IDs for Contacts vs Non-contacts filtering
    val knownContactNxIds = remember(contacts) {
        contacts.map { it.nxId }.toSet()
    }

    val messagesFlow = remember(searchableConversations, chatRepository) {
        if (searchableConversations.isEmpty()) {
            flowOf<Map<String, List<Message>>>(emptyMap())
        } else {
            combine(searchableConversations.map { chatRepository.getMessages(it.conversationId) }) { messageLists ->
                searchableConversations.mapIndexed { index, conversation ->
                    conversation.conversationId to messageLists[index]
                }.toMap()
            }
        }
    }
    val messagesByConversation by messagesFlow.collectAsState(initial = emptyMap())

    val matchingContactSuggestions = remember(searchQuery, contacts) {
        val query = searchQuery.trim()
        if (query.isBlank()) emptyList() else contacts.filter {
            it.effectiveDisplayName.contains(query, ignoreCase = true) ||
                it.nxId.contains(query, ignoreCase = true)
        }.take(4)
    }
    val matchingConversationSuggestions = remember(searchQuery, searchableConversations) {
        val query = searchQuery.trim()
        if (query.isBlank()) emptyList() else searchableConversations.filter {
            it.participantName.contains(query, ignoreCase = true) ||
                it.participantNxId.contains(query, ignoreCase = true)
        }.take(4)
    }
    val matchingCategorySuggestions = remember(searchQuery, searchableConversations, messagesByConversation) {
        val query = searchQuery.trim()
        val candidateFilters = suggestedFiltersForQuery(query)
        candidateFilters.filter { filter ->
            searchableConversations.any { conversation ->
                filter.matches(
                    conversation = conversation,
                    messages = messagesByConversation[conversation.conversationId].orEmpty(),
                    knownContactIds = knownContactNxIds
                )
            }
        }
    }

    val displayedConversations = remember(
        searchableConversations,
        messagesByConversation,
        searchQuery,
        selectedFilters,
        recentChatIds,
        knownContactNxIds
    ) {
        if (searchQuery.isBlank() && selectedFilters.isEmpty()) {
            // Display Recent Chats
            conversations.filter { recentChatIds.contains(it.conversationId) }
        } else {
            searchableConversations.filter { chat ->
                val messages = messagesByConversation[chat.conversationId].orEmpty()
                val query = searchQuery.trim()
                val matchesQuery = query.isBlank() ||
                    chat.participantName.contains(query, ignoreCase = true) ||
                    chat.participantNxId.contains(query, ignoreCase = true) ||
                    messages.any { it.plainTextContent.contains(query, ignoreCase = true) }
                matchesQuery && selectedFilters.all { filter ->
                    filter.matches(chat, messages, knownContactNxIds)
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ==========================================
            // 1 & 2. Top Header Row: Back Button + Search Input
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Back Button (Circular glass button)
                NexaGlassSurface(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(onClick = onNavigateBack),
                    shape = CircleShape,
                    elevation = 3.dp
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_nexa_arrow_back),
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // 2. Search Input (Rounded frosted glass field)
                NexaGlassSurface(
                    modifier = Modifier
                        .weight(1f)
                        .height(NexaGlassTokens.SearchBarHeight),
                    shape = RoundedCornerShape(NexaGlassTokens.SearchRadius),
                    elevation = 2.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_nexa_search),
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.CenterStart)
                        )

                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp, end = if (searchQuery.isNotEmpty()) 32.dp else 0.dp)
                                .focusRequester(focusRequester)
                                .onFocusChanged { searchHasFocus = it.isFocused },
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search messages, chats, contacts...",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            fontSize = 15.sp
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        )

                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier
                                    .size(32.dp)
                                    .align(Alignment.CenterEnd)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_nexa_close),
                                    contentDescription = "Clear search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // 3. Horizontal Filter Chips (One scrollable row)
            // ==========================================
            NexaSearchFilterChips(
                selectedFilters = selectedFilters,
                onToggleFilter = { filter ->
                    selectedFilters = if (selectedFilters.contains(filter)) {
                        selectedFilters - filter
                    } else {
                        selectedFilters + filter
                    }
                }
            )

            // ==========================================
            // 4 & 5. Header Row: Recent Chats / Results + Clear All
            // ==========================================
            val isRecentMode = searchQuery.isBlank() && selectedFilters.isEmpty()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isRecentMode) {
                        "Recent Chats"
                    } else {
                        "Search Results (${displayedConversations.size})"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (isRecentMode) {
                    if (displayedConversations.isNotEmpty()) {
                        Text(
                            text = "Clear All",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showClearAllConfirmation = true }
                        )
                    }
                } else if (selectedFilters.isNotEmpty()) {
                    Text(
                        text = "Reset Filters",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { selectedFilters = emptySet() }
                    )
                }
            }

            // ==========================================
            // 6. Search Results / Recent Conversation List
            // ==========================================
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (searchHasFocus && searchQuery.isNotBlank() &&
                    (matchingContactSuggestions.isNotEmpty() ||
                        matchingConversationSuggestions.isNotEmpty() ||
                        matchingCategorySuggestions.isNotEmpty())
                ) {
                    item(key = "suggestions_heading") {
                        Text(
                            text = "Suggestions",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    items(
                        items = matchingContactSuggestions,
                        key = { "contact:${it.nxId}" }
                    ) { contact ->
                        SearchSuggestionRow(
                            title = contact.effectiveDisplayName,
                            subtitle = "Contact",
                            onClick = {
                                coroutineScope.launch {
                                    val chat = chatRepository.createOrGetConversation(
                                        contact.nxId,
                                        contact.effectiveDisplayName
                                    )
                                    onOpenChat(chat.conversationId, chat.participantNxId, chat.participantName)
                                }
                            }
                        )
                    }
                    items(
                        items = matchingConversationSuggestions,
                        key = { "conversation:${it.conversationId}" }
                    ) { conversation ->
                        SearchSuggestionRow(
                            title = conversation.participantName,
                            subtitle = if (conversation.isGroup) "Group" else "Conversation",
                            onClick = {
                                onOpenChat(
                                    conversation.conversationId,
                                    conversation.participantNxId,
                                    conversation.participantName
                                )
                            }
                        )
                    }
                    items(
                        items = matchingCategorySuggestions,
                        key = { "filter:${it.name}" }
                    ) { filter ->
                        SearchSuggestionRow(
                            title = filter.displayName,
                            subtitle = "Filter by ${filter.displayName.lowercase()}",
                            onClick = { selectedFilters = selectedFilters + filter }
                        )
                    }
                }

                if (displayedConversations.isEmpty()) {
                    item {
                        EmptySearchCard(
                            isRecentMode = isRecentMode,
                            searchQuery = searchQuery,
                            selectedFilters = selectedFilters
                        )
                    }
                } else {
                    items(
                        items = displayedConversations,
                        key = { it.conversationId }
                    ) { chat ->
                        NexaConversationItem(
                            chat = chat,
                            isPinned = false,
                            isMuted = false,
                            isSelectionMode = false,
                            isSelected = false,
                            onClick = {
                                onOpenChat(chat.conversationId, chat.participantNxId, chat.participantName)
                            },
                            onLongClick = {},
                            onPhotoClick = {}
                        )
                    }
                }
            }
        }
    }

    // ==========================================
    // Clear All Confirmation Dialog
    // ==========================================
    if (showClearAllConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirmation = false },
            title = { Text("Clear Recent Chats?") },
            text = {
                Text(
                    "This will clear your recent conversation shortcuts from search history. Your actual conversations and encrypted messages will remain completely safe."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        recentChatIds = emptySet()
                        showClearAllConfirmation = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EmptySearchCard(
    isRecentMode: Boolean,
    searchQuery: String,
    selectedFilters: Set<SearchFilter>
) {
    NexaGlassSurface(
        shape = RoundedCornerShape(NexaGlassTokens.LargeCardRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_nexa_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                modifier = Modifier.size(46.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (isRecentMode) {
                    "No Recent Chats"
                } else {
                    "No Results Found"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (isRecentMode) {
                    "Your recent searches and opened conversations will appear here."
                } else if (selectedFilters.isNotEmpty() && searchQuery.isNotBlank()) {
                    "No messages matching '$searchQuery' with the selected filters."
                } else if (selectedFilters.isNotEmpty()) {
                    "No conversations match the selected filter(s)."
                } else {
                    "No chats or messages match '$searchQuery'."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun SearchSuggestionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    NexaGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(NexaGlassTokens.SmallCardRadius),
        elevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 11.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private fun suggestedFiltersForQuery(query: String): List<SearchFilter> {
    if (query.isBlank()) return emptyList()

    val terms = mapOf(
        SearchFilter.AUDIO to setOf("audio", "voice", "mp3", "wav", "m4a"),
        SearchFilter.PHOTOS to setOf("photo", "image", "jpg", "jpeg", "png"),
        SearchFilter.DOCUMENTS to setOf("document", "file", "pdf", "doc", "xls"),
        SearchFilter.VIDEOS to setOf("video", "mp4", "mov"),
        SearchFilter.GIFS to setOf("gif"),
        SearchFilter.LINKS to setOf("link", "url", "http", "www")
    )
    return terms.filterValues { words -> words.any { query.contains(it, ignoreCase = true) } }.keys.toList()
}
