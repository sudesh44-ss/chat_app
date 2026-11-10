package com.nexa.app.presentation.dashboard

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.painterResource
import com.nexa.app.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.theme.NexaGlassTokens
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import com.nexa.app.presentation.common.viewer.ProfileImageViewerDialog
import com.nexa.app.presentation.dashboard.components.BottomNavDestination
import com.nexa.app.presentation.dashboard.components.HomeDashboardTab
import com.nexa.app.presentation.dashboard.components.NexaChatContextMenu
import com.nexa.app.presentation.dashboard.components.NexaChatGroupSwitcher
import com.nexa.app.presentation.dashboard.components.NexaConversationItem
import com.nexa.app.presentation.dashboard.components.NexaFloatingBottomNavigation
import com.nexa.app.presentation.dashboard.components.NexaGroupContextMenu
import com.nexa.app.presentation.dashboard.components.NexaQuickActionMenu
import com.nexa.app.presentation.dashboard.components.NexaSearchBar
import com.nexa.app.presentation.dashboard.components.NexaSelectionToolbar
import com.nexa.app.presentation.dashboard.components.NexaStoriesRow
import com.nexa.app.presentation.dashboard.components.NexaTopAppBar
import kotlinx.coroutines.launch

/**
 * Extension to classify group vs individual conversations.
 */
val ChatConversation.isGroup: Boolean
    get() = conversationId.contains("group", ignoreCase = true) ||
        participantNxId.contains("GROUP", ignoreCase = true) ||
        participantName.contains("group", ignoreCase = true)

/**
 * Redesigned NEXA Home Screen.
 *
 * Implements:
 * 1. Global Liquid Glass Design System (Pure Black #000000 AMOLED & Pure White #FFFFFF).
 * 2. Top App Bar (Headphones icon, NEXA branding, profile icon, overflow menu).
 * 3. Rounded Pill Search Bar with clear action.
 * 4. Horizontally scrollable Status/Stories section with glass rings.
 * 5. Connected Pill Switcher for Chats vs Groups.
 * 6. Responsive Conversation List with rounded glass cards and real database data.
 * 7. Long-Press Context Menus (Separate 8-item menus for individual chats and groups).
 * 8. Multi-Selection Mode with bulk action toolbar.
 * 9. Floating Bottom Navigation (48% width 2-item capsule + independent floating Plus button).
 * 10. Animated 5-item Quick Action Menu (Mesh, Connection Requests, New Chat, Contacts, Create Group).
 */
@Composable
fun MainChatDashboardScreen(
    authRepository: AuthRepository,
    chatRepository: ChatRepository,
    onOpenChat: (conversationId: String, participantNxId: String, participantName: String) -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenQr: () -> Unit = {},
    onOpenContacts: () -> Unit = {},
    onOpenPendingRequests: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenProfile: () -> Unit = onOpenQr,
    onLogout: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val activeProfile by authRepository.getActiveProfile().collectAsState(initial = null)
    val rawConversations by chatRepository.getConversations().collectAsState(initial = emptyList())
    val connectionState by chatRepository.getConnectionState().collectAsState()

    // Provide immediate demo conversations if DB is empty during development/testing
    val conversations = remember(rawConversations) {
        if (rawConversations.isEmpty() && (com.nexa.app.BuildConfig.DEBUG || com.nexa.app.core.config.AppLaunchConfig.isBypassAuthToHomeScreenEnabled)) {
            com.nexa.app.core.demo.DemoDataSeeder.getInMemoryDemoConversations()
        } else {
            rawConversations
        }
    }

    // Screen State
    var currentTab by remember { mutableStateOf(HomeDashboardTab.CHATS) }
    var searchQuery by remember { mutableStateOf("") }
    var isPlusMenuOpen by remember { mutableStateOf(false) }

    // Multi-Selection State
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedChatIds by remember { mutableStateOf(setOf<String>()) }

    // Chat Metadata States (Pins, Mutes, Archives, Overrides)
    var pinnedChatIds by remember { mutableStateOf(setOf<String>()) }
    var mutedChatIds by remember { mutableStateOf(setOf<String>()) }
    var archivedChatIds by remember { mutableStateOf(setOf<String>()) }
    var unreadOverrides by remember { mutableStateOf(mapOf<String, Int>()) }

    // Context Menu State
    var chatForContextMenu by remember { mutableStateOf<ChatConversation?>(null) }

    // Action Confirmation Dialog States
    var chatToDelete by remember { mutableStateOf<ChatConversation?>(null) }
    var contactToBlock by remember { mutableStateOf<ChatConversation?>(null) }
    var groupToLeave by remember { mutableStateOf<ChatConversation?>(null) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }

    // New Conversation Dialog State
    var showNewChatDialog by remember { mutableStateOf(false) }
    var targetNxIdInput by remember { mutableStateOf("") }
    var targetNameInput by remember { mutableStateOf("") }

    // Profile Photo Viewer State
    var activeViewerPhoto by remember { mutableStateOf<Any?>(null) }

    val photoToView = activeViewerPhoto
    if (photoToView != null) {
        ProfileImageViewerDialog(
            imageModel = photoToView,
            onDismiss = { activeViewerPhoto = null }
        )
    }

    // Connect WebSocket Relay on Launch and ensure demo data seeded in debug
    LaunchedEffect(Unit) {
        try {
            chatRepository.startConnection()
        } catch (e: Exception) {
            Log.e("MainChatDashboard", "Failed to start chat connection", e)
        }
        try {
            if (com.nexa.app.BuildConfig.DEBUG || com.nexa.app.core.config.AppLaunchConfig.isBypassAuthToHomeScreenEnabled) {
                try {
                    val app = com.nexa.app.NexaApplication.instance
                    com.nexa.app.core.demo.DemoDataSeeder.seedDemoConversations(app.database)
                } catch (e: Exception) {
                    Log.d("MainChatDashboard", "App instance not ready for seeding: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("MainChatDashboard", "Demo data seed note: ${e.message}")
        }
    }

    // Filter conversations by active Tab (Chats vs Groups) and exclude archived
    val tabConversations = remember(conversations, currentTab, archivedChatIds) {
        conversations.filter { chat ->
            !archivedChatIds.contains(chat.conversationId) &&
                if (currentTab == HomeDashboardTab.CHATS) !chat.isGroup else chat.isGroup
        }
    }

    // Filter and Sort by Search Query and Pinned Status
    val displayedConversations = remember(tabConversations, searchQuery, pinnedChatIds, unreadOverrides) {
        val query = searchQuery.trim()
        val list = if (query.isBlank()) {
            tabConversations
        } else {
            tabConversations.filter {
                it.participantName.contains(query, ignoreCase = true) ||
                    it.participantNxId.contains(query, ignoreCase = true) ||
                    it.lastMessageText.contains(query, ignoreCase = true)
            }
        }

        // Apply unread count overrides if toggled manually
        val mappedList = list.map { chat ->
            val override = unreadOverrides[chat.conversationId]
            if (override != null) chat.copy(unreadCount = override) else chat
        }

        // Pinned conversations at the top, then newest timestamp
        mappedList.sortedWith(
            compareByDescending<ChatConversation> { pinnedChatIds.contains(it.conversationId) }
                .thenByDescending { it.lastMessageTimestamp }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                NexaSelectionToolbar(
                    selectedCount = selectedChatIds.size,
                    onCloseSelection = {
                        isSelectionMode = false
                        selectedChatIds = emptySet()
                    },
                    onSelectAll = {
                        selectedChatIds = displayedConversations.map { it.conversationId }.toSet()
                    },
                    onBulkPin = {
                        val allPinned = selectedChatIds.all { pinnedChatIds.contains(it) }
                        pinnedChatIds = if (allPinned) {
                            pinnedChatIds - selectedChatIds
                        } else {
                            pinnedChatIds + selectedChatIds
                        }
                        isSelectionMode = false
                        selectedChatIds = emptySet()
                    },
                    onBulkMute = {
                        val allMuted = selectedChatIds.all { mutedChatIds.contains(it) }
                        mutedChatIds = if (allMuted) {
                            mutedChatIds - selectedChatIds
                        } else {
                            mutedChatIds + selectedChatIds
                        }
                        isSelectionMode = false
                        selectedChatIds = emptySet()
                    },
                    onBulkArchive = {
                        archivedChatIds = archivedChatIds + selectedChatIds
                        isSelectionMode = false
                        selectedChatIds = emptySet()
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Conversations archived")
                        }
                    },
                    onBulkDelete = {
                        showBulkDeleteDialog = true
                    }
                )
            } else {
                NexaTopAppBar(
                    connectionState = connectionState,
                    profilePhotoUri = activeProfile?.avatarUrl,
                    avatarUrl = activeProfile?.avatarUrl,
                    displayName = activeProfile?.fullName ?: "NEXA",
                    onAudioHeadphonesClick = {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Audio / Radio Mesh is ready")
                        }
                    },
                    onProfileClick = onOpenProfile,
                    onNotificationSettingsClick = onOpenNotificationSettings,
                    onQrClick = onOpenQr,
                    onLogoutClick = onLogout
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = NexaGlassTokens.ConversationListBottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(NexaGlassTokens.CardSpacing)
            ) {
                // 1. Search Bar
                item(key = "search_bar") {
                    NexaSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearchBarClick = onOpenSearch
                    )
                }

                // 2. Status / Stories Row
                item(key = "stories_row") {
                    NexaStoriesRow(
                        onMyStatusClick = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Create Status Story")
                            }
                        },
                        onStoryClick = { story ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Viewing ${story.name}'s story")
                            }
                        }
                    )
                }

                // 3. Chats / Groups Connected Pill Switcher
                item(key = "tab_switcher") {
                    NexaChatGroupSwitcher(
                        selectedTab = currentTab,
                        onTabSelected = { newTab ->
                            currentTab = newTab
                            if (isSelectionMode) {
                                isSelectionMode = false
                                selectedChatIds = emptySet()
                            }
                        }
                    )
                }

                // 4. Conversation List Items
                if (displayedConversations.isEmpty()) {
                    item(key = "empty_state") {
                        EmptyConversationCard(
                            isGroupTab = currentTab == HomeDashboardTab.GROUPS,
                            hasSearch = searchQuery.isNotBlank(),
                            onStartNew = {
                                targetNxIdInput = ""
                                targetNameInput = ""
                                showNewChatDialog = true
                            }
                        )
                    }
                } else {
                    items(
                        items = displayedConversations,
                        key = { it.conversationId }
                    ) { chat ->
                        val isSelected = selectedChatIds.contains(chat.conversationId)
                        val isPinned = pinnedChatIds.contains(chat.conversationId)
                        val isMuted = mutedChatIds.contains(chat.conversationId)

                        NexaConversationItem(
                            chat = chat,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            isPinned = isPinned,
                            isMuted = isMuted,
                            onClick = {
                                if (isSelectionMode) {
                                    selectedChatIds = if (isSelected) {
                                        selectedChatIds - chat.conversationId
                                    } else {
                                        selectedChatIds + chat.conversationId
                                    }
                                } else {
                                    onOpenChat(chat.conversationId, chat.participantNxId, chat.participantName)
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    chatForContextMenu = chat
                                } else {
                                    selectedChatIds = if (isSelected) {
                                        selectedChatIds - chat.conversationId
                                    } else {
                                        selectedChatIds + chat.conversationId
                                    }
                                }
                            },
                            onPhotoClick = { photo ->
                                activeViewerPhoto = photo
                            }
                        )
                    }
                }
            }

            // Floating Bottom Navigation (Capsule + Independent Plus Button)
            NexaFloatingBottomNavigation(
                currentDestination = BottomNavDestination.HOME,
                isPlusMenuOpen = isPlusMenuOpen,
                onNavigateHome = {
                    currentTab = HomeDashboardTab.CHATS
                    searchQuery = ""
                },
                onNavigateProfile = onOpenProfile,
                onTogglePlusMenu = {
                    isPlusMenuOpen = !isPlusMenuOpen
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // Animated Quick Action Menu Overlay
            NexaQuickActionMenu(
                isOpen = isPlusMenuOpen,
                onDismiss = { isPlusMenuOpen = false },
                onNavigateNearbyMesh = {
                    isPlusMenuOpen = false
                    onOpenQr()
                },
                onNavigateConnectionRequests = {
                    isPlusMenuOpen = false
                    onOpenPendingRequests()
                },
                onNavigateNewConversation = {
                    isPlusMenuOpen = false
                    targetNxIdInput = ""
                    targetNameInput = ""
                    showNewChatDialog = true
                },
                onNavigateContacts = {
                    isPlusMenuOpen = false
                    onOpenContacts()
                },
                onNavigateCreateGroup = {
                    isPlusMenuOpen = false
                    targetNxIdInput = ""
                    targetNameInput = ""
                    showNewChatDialog = true
                }
            )
        }
    }

    // Context Menu Handling
    chatForContextMenu?.let { chat ->
        val isGroup = chat.isGroup
        val isPinned = pinnedChatIds.contains(chat.conversationId)
        val isMuted = mutedChatIds.contains(chat.conversationId)

        if (isGroup) {
            NexaGroupContextMenu(
                chat = chat,
                isPinned = isPinned,
                isMuted = isMuted,
                onDismiss = { chatForContextMenu = null },
                onTogglePin = {
                    pinnedChatIds = if (isPinned) pinnedChatIds - chat.conversationId else pinnedChatIds + chat.conversationId
                },
                onArchive = {
                    archivedChatIds = archivedChatIds + chat.conversationId
                    coroutineScope.launch { snackbarHostState.showSnackbar("Group archived") }
                },
                onViewGroup = {
                    onOpenChat(chat.conversationId, chat.participantNxId, chat.participantName)
                },
                onToggleMute = {
                    mutedChatIds = if (isMuted) mutedChatIds - chat.conversationId else mutedChatIds + chat.conversationId
                },
                onSelect = {
                    isSelectionMode = true
                    selectedChatIds = setOf(chat.conversationId)
                },
                onDeleteChats = {
                    chatToDelete = chat
                },
                onToggleRead = {
                    val currentUnread = chat.unreadCount
                    val newCount = if (currentUnread > 0) 0 else 1
                    unreadOverrides = unreadOverrides + (chat.conversationId to newCount)
                    if (newCount == 0) {
                        coroutineScope.launch { chatRepository.markConversationRead(chat.conversationId) }
                    }
                },
                onLeaveGroup = {
                    groupToLeave = chat
                }
            )
        } else {
            NexaChatContextMenu(
                chat = chat,
                isPinned = isPinned,
                isMuted = isMuted,
                onDismiss = { chatForContextMenu = null },
                onTogglePin = {
                    pinnedChatIds = if (isPinned) pinnedChatIds - chat.conversationId else pinnedChatIds + chat.conversationId
                },
                onArchive = {
                    archivedChatIds = archivedChatIds + chat.conversationId
                    coroutineScope.launch { snackbarHostState.showSnackbar("Chat archived") }
                },
                onViewContact = {
                    onOpenChat(chat.conversationId, chat.participantNxId, chat.participantName)
                },
                onToggleMute = {
                    mutedChatIds = if (isMuted) mutedChatIds - chat.conversationId else mutedChatIds + chat.conversationId
                },
                onSelect = {
                    isSelectionMode = true
                    selectedChatIds = setOf(chat.conversationId)
                },
                onDelete = {
                    chatToDelete = chat
                },
                onToggleRead = {
                    val currentUnread = chat.unreadCount
                    val newCount = if (currentUnread > 0) 0 else 1
                    unreadOverrides = unreadOverrides + (chat.conversationId to newCount)
                    if (newCount == 0) {
                        coroutineScope.launch { chatRepository.markConversationRead(chat.conversationId) }
                    }
                },
                onBlock = {
                    contactToBlock = chat
                }
            )
        }
    }

    // Confirmation Dialog: Delete Conversation / Delete Chats
    chatToDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { chatToDelete = null },
            title = { Text("Delete Conversation") },
            text = {
                Text(
                    text = "Are you sure you want to delete conversation with '${chat.participantName}'? All messages will be permanently removed from your encrypted local vault."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = chat.conversationId
                        chatToDelete = null
                        archivedChatIds = archivedChatIds + id
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Conversation deleted")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { chatToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirmation Dialog: Block Contact
    contactToBlock?.let { chat ->
        AlertDialog(
            onDismissRequest = { contactToBlock = null },
            title = { Text("Block ${chat.participantName}?") },
            text = {
                Text(
                    text = "Blocked contacts will not be able to send you messages or discover your presence on the mesh. You can unblock them anytime from Settings."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        contactToBlock = null
                        archivedChatIds = archivedChatIds + chat.conversationId
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Contact blocked")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Block")
                }
            },
            dismissButton = {
                TextButton(onClick = { contactToBlock = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirmation Dialog: Leave Group
    groupToLeave?.let { group ->
        AlertDialog(
            onDismissRequest = { groupToLeave = null },
            title = { Text("Leave ${group.participantName}?") },
            text = {
                Text(
                    text = "Are you sure you want to leave this group? You will no longer be able to send or receive messages in this group."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        groupToLeave = null
                        archivedChatIds = archivedChatIds + group.conversationId
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Left group")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Leave")
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToLeave = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirmation Dialog: Bulk Delete
    if (showBulkDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = { Text("Delete Selected Conversations") },
            text = {
                Text("Delete ${selectedChatIds.size} conversations? This will remove all local messages.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        archivedChatIds = archivedChatIds + selectedChatIds
                        showBulkDeleteDialog = false
                        isSelectionMode = false
                        selectedChatIds = emptySet()
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Deleted selected conversations")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // New Encrypted Chat Modal Dialog
    if (showNewChatDialog) {
        AlertDialog(
            onDismissRequest = { showNewChatDialog = false },
            title = { Text("New Encrypted Conversation", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter recipient NX-ID to initiate an end-to-end encrypted session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = targetNxIdInput,
                        onValueChange = { targetNxIdInput = it.uppercase() },
                        label = { Text("Recipient NX-ID (e.g. NX-29AB731Q)") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = targetNameInput,
                        onValueChange = { targetNameInput = it },
                        label = { Text("Display Name (Optional)") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val nxId = targetNxIdInput.trim()
                        if (nxId.isNotBlank()) {
                            showNewChatDialog = false
                            coroutineScope.launch {
                                try {
                                    val chat = chatRepository.createOrGetConversation(
                                        participantNxId = nxId,
                                        participantName = targetNameInput.trim().ifBlank { nxId }
                                    )
                                    onOpenChat(chat.conversationId, chat.participantNxId, chat.participantName)
                                } catch (e: Exception) {
                                    Log.e("MainChatDashboard", "Failed to start chat with $nxId", e)
                                }
                            }
                        }
                    },
                    enabled = targetNxIdInput.isNotBlank()
                ) {
                    Text("Start Chat")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewChatDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EmptyConversationCard(
    isGroupTab: Boolean,
    hasSearch: Boolean,
    onStartNew: () -> Unit
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
                painter = painterResource(id = if (isGroupTab) R.drawable.ic_nexa_groups else R.drawable.ic_nexa_chats),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.size(46.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (hasSearch) "No Matching Conversations" else if (isGroupTab) "No Groups Yet" else "No Direct Chats Yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (hasSearch) {
                    "Try a different search keyword."
                } else if (isGroupTab) {
                    "Tap the + button to create an encrypted group."
                } else {
                    "Tap the + button to initiate a new end-to-end encrypted chat."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            if (!hasSearch) {
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onStartNew,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (isGroupTab) "Create Group" else "Start Conversation")
                }
            }
        }
    }
}
