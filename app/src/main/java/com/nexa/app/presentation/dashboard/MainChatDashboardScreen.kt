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
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
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
import com.nexa.app.domain.repository.ContactRepository
import com.nexa.app.domain.repository.StatusRepository
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
import com.nexa.app.presentation.status.StatusViewModel
import com.nexa.app.presentation.status.components.CreateStatusDialog
import com.nexa.app.presentation.status.components.NexaSequentialStoryViewer
import com.nexa.app.presentation.status.components.StatusViewersSheet
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import com.nexa.app.NexaApplication
import com.nexa.app.presentation.dashboard.components.NexaTopAppBar
import kotlinx.coroutines.launch

/**
 * Extension to classify group vs individual conversations.
 */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
val ChatConversation.isGroup: Boolean
    get() = this.isGroup

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
 * 7. Long-Press Context Menus (Nine ordered actions for individual chats).
 * 8. Multi-Selection Mode with bulk action toolbar.
 * 9. Floating Bottom Navigation (48% width 2-item capsule + independent floating Plus button).
 * 10. Animated 5-item Quick Action Menu (Mesh, Connection Requests, New Chat, Contacts, Create Group).
 */
@Composable
fun MainChatDashboardScreen(
    authRepository: AuthRepository,
    chatRepository: ChatRepository,
    statusRepository: StatusRepository? = null,
    contactRepository: ContactRepository? = null,
    onOpenChat: (conversationId: String, participantNxId: String, participantName: String) -> Unit,
    onOpenContactProfile: (participantNxId: String, conversationId: String) -> Unit = { _, _ -> },
    onOpenSearch: () -> Unit = {},
    onOpenQr: () -> Unit = {},
    onOpenContacts: () -> Unit = {},
    onOpenPendingRequests: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenStatus: () -> Unit = {},
    onLogout: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val effectiveStatusRepository = statusRepository ?: remember(context) {
        (context.applicationContext as? NexaApplication)?.statusRepository
    }
    val statusViewModel: StatusViewModel? = effectiveStatusRepository?.let {
        viewModel(factory = StatusViewModel.Factory(it))
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, statusViewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                statusViewModel?.refreshStatusAvailability()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val myStatusGroup by (statusViewModel?.myStatusGroup ?: remember { MutableStateFlow(null) }).collectAsState(initial = null)
    val otherStatusGroups by (statusViewModel?.otherStatusGroups ?: remember { MutableStateFlow(emptyList()) }).collectAsState(initial = emptyList())
    val mutedStatusGroups by (statusViewModel?.mutedStatusGroups ?: remember { MutableStateFlow(emptyList()) }).collectAsState(initial = emptyList())
    val selectedStatusGroup by (statusViewModel?.selectedStatusGroup ?: remember { MutableStateFlow(null) }).collectAsState(initial = null)
    val currentStatusIndex by (statusViewModel?.currentStatusIndex ?: remember { MutableStateFlow(0) }).collectAsState(initial = 0)
    val showCreateStatusDialog by (statusViewModel?.showCreateStatusDialog ?: remember { MutableStateFlow(false) }).collectAsState(initial = false)
    val activeViewersStatus by (statusViewModel?.activeViewersStatus ?: remember { MutableStateFlow(null) }).collectAsState(initial = null)
    val statusViewersList by (statusViewModel?.statusViewersList ?: remember { MutableStateFlow(emptyList()) }).collectAsState(initial = emptyList())

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
    var isSearchActive by remember { mutableStateOf(false) }
    var isPlusMenuOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Multi-Selection State
    var selectedChatIds by remember { mutableStateOf(setOf<String>()) }

    // Context Menu State
    var chatForContextMenu by remember { mutableStateOf<ChatConversation?>(null) }
    var isContextContactBlocked by remember { mutableStateOf(false) }

    LaunchedEffect(chatForContextMenu?.participantNxId) {
        val nxId = chatForContextMenu?.participantNxId ?: return@LaunchedEffect
        isContextContactBlocked = contactRepository?.getContact(nxId)?.status == com.nexa.app.data.local.entity.ContactStatus.BLOCKED
    }

    // Action Confirmation Dialog States
    var chatToDelete by remember { mutableStateOf<ChatConversation?>(null) }
    var chatToClear by remember { mutableStateOf<ChatConversation?>(null) }
    var contactToBlock by remember { mutableStateOf<ChatConversation?>(null) }
    var unavailableStatusContact by remember { mutableStateOf<String?>(null) }
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
    val tabConversations = remember(conversations, currentTab) {
        conversations.filter { chat ->
            !chat.isArchived &&
                if (currentTab == HomeDashboardTab.CHATS) !chat.isGroup else chat.isGroup
        }
    }
    val tabConversationIds = remember(tabConversations) {
        tabConversations.map { it.conversationId }.toSet()
    }
    val currentSelectedChatIds = selectedChatIds.intersect(tabConversationIds)
    val isSelectionMode = currentSelectedChatIds.isNotEmpty()

    LaunchedEffect(tabConversationIds) {
        selectedChatIds = selectedChatIds.intersect(tabConversationIds)
    }

    LaunchedEffect(currentSelectedChatIds) {
        if (currentSelectedChatIds.isEmpty()) {
            showBulkDeleteDialog = false
        }
    }

    BackHandler(enabled = isSelectionMode || isSearchActive || searchQuery.isNotBlank()) {
        if (isSelectionMode) {
            selectedChatIds = emptySet()
        } else {
            searchQuery = ""
            isSearchActive = false
            focusManager.clearFocus()
        }
    }

    // Filter and Sort by Search Query and Deterministic Database Ordering (Pinned first, newest timestamp, conversationId)
    val displayedConversations = remember(tabConversations, searchQuery) {
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

        // Pinned conversations at the top (ordered by pinPosition ASC), then newest timestamp DESC, then conversationId ASC
        list.sortedWith(
            compareByDescending<ChatConversation> { it.isPinned }
                .thenBy { it.pinPosition }
                .thenByDescending { it.lastMessageTimestamp }
                .thenBy { it.conversationId }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                NexaSelectionToolbar(
                    selectedCount = currentSelectedChatIds.size,
                    allSelected = displayedConversations.isNotEmpty() &&
                        displayedConversations.all { it.conversationId in currentSelectedChatIds },
                    onCloseSelection = {
                        selectedChatIds = emptySet()
                    },
                    onSelectAll = {
                        val displayedIds = displayedConversations.map { it.conversationId }.toSet()
                        selectedChatIds = if (displayedIds.isNotEmpty() && displayedIds.all { it in currentSelectedChatIds }) {
                            emptySet()
                        } else {
                            currentSelectedChatIds + displayedIds
                        }
                    },
                    onBulkArchive = {
                        val idsToArchive = currentSelectedChatIds.toList()
                        selectedChatIds = emptySet()
                        coroutineScope.launch {
                            idsToArchive.forEach { id ->
                                chatRepository.archiveConversation(id, true)
                            }
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
                    onBrandClick = onOpenSettings,
                    onProfileClick = onOpenPendingRequests,
                    onSettingsClick = onOpenSettings,
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
                        onQueryChange = {
                            searchQuery = it
                            isSearchActive = it.isNotBlank()
                        },
                        onSearchBarClick = {
                            onOpenSearch()
                        },
                        onFocusChanged = { isFocused ->
                            isSearchActive = isFocused || searchQuery.isNotBlank()
                        }
                    )
                }

                // 2. Status / Stories Row
                if (!isSearchActive && searchQuery.isBlank()) {
                    item(key = "stories_row") {
                        NexaStoriesRow(
                            myStatusGroup = myStatusGroup,
                            otherStatusGroups = otherStatusGroups,
                            mutedStatusGroups = mutedStatusGroups,
                            onMyStatusClick = {
                                myStatusGroup?.let { statusViewModel?.openStatusGroup(it) }
                            },
                            onAddMyStatusClick = {
                                statusViewModel?.showCreateDialog()
                            },
                            onStoryClick = { group ->
                                statusViewModel?.openStatusGroup(group)
                            },
                            onToggleStatusMute = { group, isMuted ->
                                coroutineScope.launch {
                                    try {
                                        statusRepository?.setStatusMuted(group.userNxId, isMuted)
                                            ?: throw IllegalStateException("Status actions are unavailable")
                                        snackbarHostState.showSnackbar(if (isMuted) "Status updates muted" else "Status updates unmuted")
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar(e.message ?: "Unable to update status preference")
                                    }
                                }
                            },
                            onViewProfile = { group ->
                                onOpenContactProfile(group.userNxId, "")
                            },
                            onGoToChat = { group ->
                                coroutineScope.launch {
                                    try {
                                        val contact = contactRepository?.getContact(group.userNxId)
                                        if (contact?.status == com.nexa.app.data.local.entity.ContactStatus.BLOCKED) {
                                            snackbarHostState.showSnackbar("Unblock this contact before opening the chat")
                                        } else {
                                            val chat = chatRepository.createOrGetConversation(group.userNxId, group.displayName)
                                            onOpenChat(chat.conversationId, chat.participantNxId, chat.participantName)
                                        }
                                    } catch (e: Exception) {
                                        Log.e("MainChatDashboard", "Unable to open status contact chat", e)
                                        snackbarHostState.showSnackbar(e.message ?: "Unable to open chat")
                                    }
                                }
                            },
                            onOpenStatusPage = onOpenStatus
                        )
                    }
                }

                // 3. Chats / Groups Connected Pill Switcher
                item(key = "tab_switcher") {
                    NexaChatGroupSwitcher(
                        selectedTab = currentTab,
                        onTabSelected = { newTab ->
                            currentTab = newTab
                            selectedChatIds = emptySet()
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
                        val isSelected = chat.conversationId in currentSelectedChatIds
                        val isPinned = chat.isPinned
                        val isMuted = chat.isMuted

                        NexaConversationItem(
                            chat = chat,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            isPinned = isPinned,
                            isMuted = isMuted,
                            onClick = {
                                if (isSelectionMode) {
                                    selectedChatIds = if (isSelected) {
                                        currentSelectedChatIds - chat.conversationId
                                    } else {
                                        currentSelectedChatIds + chat.conversationId
                                    }
                                } else {
                                    onOpenChat(chat.conversationId, chat.participantNxId, chat.participantName)
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    selectedChatIds = currentSelectedChatIds + chat.conversationId
                                } else if (isSelected) {
                                    chatForContextMenu = chat
                                } else {
                                    selectedChatIds = currentSelectedChatIds + chat.conversationId
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
                    isSearchActive = false
                    selectedChatIds = emptySet()
                },
                onNavigateProfile = onOpenAccount,
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
        val isPinned = chat.isPinned
        val isMuted = chat.isMuted

        if (isGroup) {
            NexaGroupContextMenu(
                chat = chat,
                isPinned = isPinned,
                isMuted = isMuted,
                onDismiss = { chatForContextMenu = null },
                onTogglePin = {
                    coroutineScope.launch {
                        chatRepository.pinConversation(chat.conversationId, !isPinned)
                    }
                },
                onArchive = {
                    coroutineScope.launch {
                        chatRepository.archiveConversation(chat.conversationId, true)
                        snackbarHostState.showSnackbar("Group archived")
                    }
                },
                onViewGroup = {
                    onOpenChat(chat.conversationId, chat.participantNxId, chat.participantName)
                },
                onToggleMute = {
                    coroutineScope.launch {
                        chatRepository.muteConversation(chat.conversationId, !isMuted)
                    }
                },
                onSelect = {
                    selectedChatIds = currentSelectedChatIds + chat.conversationId
                },
                onDeleteChats = {
                    chatToDelete = chat
                },
                onToggleRead = {
                    val currentUnread = chat.unreadCount
                    if (currentUnread > 0) {
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
                isBlocked = isContextContactBlocked,
                onDismiss = { chatForContextMenu = null },
                onViewContact = {
                    onOpenContactProfile(chat.participantNxId, chat.conversationId)
                },
                onTogglePin = {
                    coroutineScope.launch {
                        try {
                            chatRepository.pinConversation(chat.conversationId, !isPinned)
                            snackbarHostState.showSnackbar(if (isPinned) "Chat unpinned" else "Chat pinned")
                        } catch (e: Exception) {
                            Log.e("MainChatDashboard", "Unable to update pin state", e)
                            snackbarHostState.showSnackbar(e.message ?: "Unable to update pin state")
                        }
                    }
                },
                onSelectAll = {
                    selectedChatIds = displayedConversations.map { it.conversationId }.toSet()
                },
                onToggleBlock = {
                    if (isContextContactBlocked) {
                        coroutineScope.launch {
                            val result = contactRepository?.unblockUser(chat.participantNxId)
                                ?: Result.failure(IllegalStateException("Blocking is unavailable"))
                            if (result.isSuccess) {
                                isContextContactBlocked = false
                                snackbarHostState.showSnackbar("Contact unblocked")
                            } else {
                                snackbarHostState.showSnackbar(result.exceptionOrNull()?.message ?: "Unable to unblock contact")
                            }
                        }
                    } else {
                        contactToBlock = chat
                    }
                },
                onToggleMute = {
                    coroutineScope.launch {
                        try {
                            chatRepository.muteConversation(chat.conversationId, !isMuted)
                            snackbarHostState.showSnackbar(if (isMuted) "Notifications unmuted" else "Notifications muted")
                        } catch (e: Exception) {
                            Log.e("MainChatDashboard", "Unable to update chat notifications", e)
                            snackbarHostState.showSnackbar(e.message ?: "Unable to update notifications")
                        }
                    }
                },
                onViewStatus = {
                    coroutineScope.launch {
                        try {
                            val repository = statusRepository
                                ?: throw IllegalStateException("Status actions are unavailable")
                            val group = repository.getStatusGroup(chat.participantNxId)
                            if (group == null) {
                                unavailableStatusContact = chat.participantName
                            } else if (statusViewModel != null) {
                                statusViewModel.openStatusGroup(group)
                            } else {
                                snackbarHostState.showSnackbar("Status viewer is unavailable")
                            }
                        } catch (e: Exception) {
                            Log.e("MainChatDashboard", "Unable to load contact status", e)
                            snackbarHostState.showSnackbar(e.message ?: "Unable to open status")
                        }
                    }
                },
                onMarkUnread = {
                    coroutineScope.launch {
                        try {
                            chatRepository.markConversationUnread(chat.conversationId)
                            snackbarHostState.showSnackbar("Chat marked as unread")
                        } catch (e: Exception) {
                            Log.e("MainChatDashboard", "Unable to mark chat unread", e)
                            snackbarHostState.showSnackbar(e.message ?: "Unable to mark chat unread")
                        }
                    }
                },
                onClearChat = {
                    chatToClear = chat
                },
                onArchive = {
                    coroutineScope.launch {
                        try {
                            chatRepository.archiveConversation(chat.conversationId, true)
                            snackbarHostState.showSnackbar("Chat archived")
                        } catch (e: Exception) {
                            Log.e("MainChatDashboard", "Unable to archive chat", e)
                            snackbarHostState.showSnackbar(e.message ?: "Unable to archive chat")
                        }
                    }
                },
            )
        }
    }

    chatToClear?.let { chat ->
        AlertDialog(
            onDismissRequest = { chatToClear = null },
            title = { Text("Clear Chat") },
            text = { Text("Clear messages in the conversation with '${chat.participantName}'? The conversation and its settings will remain.") },
            confirmButton = {
                Button(
                    onClick = {
                        val conversationId = chat.conversationId
                        chatToClear = null
                        coroutineScope.launch {
                            val result = chatRepository.clearChatMessages(conversationId)
                            if (result.isSuccess) {
                                snackbarHostState.showSnackbar("Chat cleared")
                            } else {
                                snackbarHostState.showSnackbar(result.exceptionOrNull()?.message ?: "Unable to clear chat")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { chatToClear = null }) {
                    Text("Cancel")
                }
            }
        )
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
                        coroutineScope.launch {
                            val result = chatRepository.deleteChat(id)
                            if (result.isSuccess) {
                                snackbarHostState.showSnackbar("Conversation deleted")
                            } else {
                                snackbarHostState.showSnackbar(result.exceptionOrNull()?.message ?: "Unable to delete conversation")
                            }
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
                        coroutineScope.launch {
                            val result = contactRepository?.blockUser(chat.participantNxId)
                                ?: Result.failure(IllegalStateException("Blocking is unavailable"))
                            if (result.isSuccess) {
                                isContextContactBlocked = true
                                snackbarHostState.showSnackbar("Contact blocked")
                            } else {
                                snackbarHostState.showSnackbar(result.exceptionOrNull()?.message ?: "Unable to block contact")
                            }
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

    if (unavailableStatusContact != null) {
        AlertDialog(
            onDismissRequest = { unavailableStatusContact = null },
            title = { Text("Status not available") },
            text = { Text("Status not posted by this contact.") },
            confirmButton = {
                TextButton(onClick = { unavailableStatusContact = null }) {
                    Text("OK")
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
                        val id = group.conversationId
                        groupToLeave = null
                        coroutineScope.launch {
                            chatRepository.archiveConversation(id, true)
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
                Text("Delete ${currentSelectedChatIds.size} conversations? This will remove all local messages.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val idsToDelete = currentSelectedChatIds.toList()
                        showBulkDeleteDialog = false
                        selectedChatIds = emptySet()
                        coroutineScope.launch {
                            val failures = mutableListOf<String>()
                            idsToDelete.forEach { id ->
                                val result = chatRepository.deleteChat(id)
                                if (result.isFailure) {
                                    failures += id
                                }
                            }
                            if (failures.isEmpty()) {
                                snackbarHostState.showSnackbar("Deleted selected conversations")
                            } else {
                                snackbarHostState.showSnackbar("Deleted selected conversations with ${failures.size} failed removal")
                            }
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

    // Sequential Story Viewer Dialog
    selectedStatusGroup?.let { group ->
        NexaSequentialStoryViewer(
            group = group,
            initialIndex = currentStatusIndex,
            onDismiss = { statusViewModel?.closeStatusGroup() },
            onMarkViewed = { statusId -> statusViewModel?.markStatusViewed(statusId) },
            onDeleteStatus = { statusId -> statusViewModel?.deleteStatus(statusId) },
            onShowViewers = { status -> statusViewModel?.showStatusViewers(status) }
        )
    }

    // Create Status Dialog
    if (showCreateStatusDialog) {
        CreateStatusDialog(
            onDismiss = { statusViewModel?.dismissCreateDialog() },
            onCreateTextStatus = { text -> statusViewModel?.createTextStatus(text) },
            onCreateMediaStatus = { uri, caption, type ->
                statusViewModel?.createMediaStatus(uri, caption, type)
            }
        )
    }

    // Status Viewers Sheet
    activeViewersStatus?.let { status ->
        StatusViewersSheet(
            status = status,
            viewers = statusViewersList,
            onDismiss = { statusViewModel?.dismissStatusViewers() }
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
