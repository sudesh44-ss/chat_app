package com.nexa.app.presentation.profile

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nexa.app.presentation.common.viewer.ProfileImageViewerScreen
import com.nexa.app.presentation.profile.components.BlockContactDialog
import com.nexa.app.presentation.profile.components.ClearChatDialog
import com.nexa.app.presentation.profile.components.ContactActionButtons
import com.nexa.app.presentation.profile.components.ContactActiveStatus
import com.nexa.app.presentation.profile.components.ContactInfoCard
import com.nexa.app.presentation.profile.components.ContactMediaPreview
import com.nexa.app.presentation.profile.components.ContactOptions
import com.nexa.app.presentation.profile.components.ContactProfileHeader
import com.nexa.app.presentation.profile.components.CustomNotificationsDialog
import com.nexa.app.presentation.profile.components.EditUserDetailsDialog
import com.nexa.app.presentation.profile.components.MuteNotificationsDialog
import com.nexa.app.domain.model.UserStatusGroup
import com.nexa.app.R
import com.nexa.app.presentation.status.components.NexaSequentialStoryViewer
import kotlinx.coroutines.launch

/**
 * Contact Profile Screen
 *
 * Implements:
 * 1. Profile Header with large 128dp circular avatar, online indicator, edit user detail button, and copyable NEXA ID.
 * 2. Contact Information Card: Phone number and About.
 * 3. Four Communication Actions: Chat, Video Call, Voice Call, Share.
 * 4. Media, Links & Docs Preview: Horizontal scroll with tap to Media Explorer.
 * 5. Active Status & Privacy Settings.
 * 6. Contact Settings: Contact Details, Pinned Messages, Connectivity, Mute, Custom Notifications, Wallpaper.
 * 7. Destructive actions: Clear Chat and Block Contact.
 *
 * Styled with NEXA Liquid Glass Design System (AMOLED #000000 / Light #FFFFFF).
 */
@Composable
fun ContactProfileScreen(
    viewModel: ContactProfileViewModel,
    onBack: () -> Unit,
    onOpenChat: (conversationId: String, participantNxId: String, participantName: String) -> Unit,
    onOpenContactDetails: (nxId: String, conversationId: String) -> Unit,
    onOpenMediaExplorer: (conversationId: String, participantName: String) -> Unit,
    onOpenWallpaper: (conversationId: String) -> Unit,
    onOpenPinnedMessages: (conversationId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    var isStatusViewerVisible by remember { mutableStateOf(false) }
    var showStatusUnavailableDialog by remember { mutableStateOf(false) }
    val activeStatusGroup = remember(uiState.nxId, uiState.displayName, uiState.avatarUrl, uiState.activeStatuses) {
        uiState.activeStatuses.takeIf { it.isNotEmpty() }?.let { statuses ->
            UserStatusGroup(
                userNxId = uiState.nxId,
                displayName = uiState.displayName,
                avatarUrl = uiState.avatarUrl,
                statuses = statuses
            )
        }
    }

    LaunchedEffect(activeStatusGroup) {
        if (activeStatusGroup == null) isStatusViewerVisible = false
    }

    val screenBg = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshStatusAvailability()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(screenBg),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = screenBg
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .systemBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Profile Header (Back button, 128dp avatar, online dot, edit btn, name, NEXA ID)
                ContactProfileHeader(
                    displayName = uiState.displayName,
                    customNickname = uiState.customNickname,
                    originalRegisteredName = uiState.originalRegisteredName,
                    nxId = uiState.nxId,
                    avatarUrl = uiState.avatarUrl,
                    deviceContactPhotoUri = uiState.deviceContactPhotoUri,
                    isOnline = uiState.isOnline,
                    onBack = onBack,
                    onEditDetails = { viewModel.setEditNicknameDialogVisible(true) },
                    onAvatarClick = {
                        if (uiState.avatarUrl != null || uiState.deviceContactPhotoUri != null) {
                            viewModel.setPhotoViewerVisible(true)
                        } else {
                            viewModel.setEditNicknameDialogVisible(true)
                        }
                    }
                )

                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    TextButton(
                        enabled = activeStatusGroup != null,
                        onClick = {
                            if (uiState.activeStatuses.isNotEmpty()) {
                                isStatusViewerVisible = true
                            } else {
                                showStatusUnavailableDialog = true
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_nexa_clock_fading),
                            contentDescription = null
                        )
                        Text("See Status", modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(
                        text = if (activeStatusGroup != null) "Available" else "Not available",
                        color = if (activeStatusGroup != null) {
                            androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }

                // 2. Four Actions in 2-Column Capsule Layout (Chat, Video Call, Voice Call, Share)
                ContactActionButtons(
                    onChatClick = {
                        onOpenChat(uiState.conversationId, uiState.nxId, uiState.displayName)
                    },
                    onVideoCallClick = {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Initiating Secure Video Call with ${uiState.displayName}...")
                        }
                    },
                    onVoiceCallClick = {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Initiating Secure Voice Call with ${uiState.displayName}...")
                        }
                    },
                    onShareClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "NEXA Contact\nName: ${uiState.displayName}\nNEXA ID: ${uiState.nxId}\nPhone: ${uiState.phoneNumber ?: "Encrypted / Private"}"
                            )
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Contact"))
                    }
                )

                // 3. Contact Information Card (Phone Number & About)
                ContactInfoCard(
                    phoneNumber = uiState.phoneNumber,
                    aboutText = uiState.aboutText
                )

                // 4. Media, Links & Docs Preview (Horizontal Scroll Row)
                ContactMediaPreview(
                    mediaItems = uiState.mediaItems,
                    onOpenMediaExplorer = {
                        onOpenMediaExplorer(uiState.conversationId, uiState.displayName)
                    }
                )

                // 5. Active Status Section
                ContactActiveStatus(
                    isOnline = uiState.isOnline,
                    lastSeenText = uiState.lastSeenText,
                    showOnlineStatus = uiState.showOnlineStatus,
                    onlinePrivacyMode = uiState.onlinePrivacyMode,
                    offlinePresenceMode = uiState.offlinePresenceMode,
                    selectedOnlineStatusContacts = uiState.selectedOnlineStatusContacts,
                    typingPrivacyMode = uiState.typingPrivacyMode,
                    onToggleShowOnlineStatus = { viewModel.setShowOnlineStatus(it) },
                    onTogglePresenceMode = { viewModel.setPresenceMode(it) },
                    onOfflinePresenceModeChanged = { viewModel.setOfflinePresenceMode(it) },
                    onToggleTypingMode = { viewModel.setTypingMode(it) }
                )

                // 6. Settings & Operations Rows (Details, Pinned, Connectivity, Mute, Custom, Wallpaper, Clear, Block)
                ContactOptions(
                    pinnedCount = uiState.pinnedCount,
                    latestPinnedPreview = uiState.latestPinnedText,
                    isMuted = uiState.isMuted,
                    muteSubtitle = if (uiState.muteUntil == -1L) "Muted always" else "Muted",
                    connectivitySummary = uiState.connectivitySummary,
                    isBlocked = uiState.isBlocked,
                    onContactDetailsClick = {
                        onOpenContactDetails(uiState.nxId, uiState.conversationId)
                    },
                    onPinnedMessagesClick = {
                        onOpenPinnedMessages(uiState.conversationId)
                    },
                    onConnectivityClick = {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Active Route: ${uiState.connectivitySummary}")
                        }
                    },
                    onMuteClick = { viewModel.setMuteDialogVisible(true) },
                    onCustomNotificationsClick = { viewModel.setCustomNotificationsDialogVisible(true) },
                    onWallpaperClick = { onOpenWallpaper(uiState.conversationId) },
                    onClearChatClick = { viewModel.setClearChatDialogVisible(true) },
                    onBlockContactClick = { viewModel.setBlockDialogVisible(true) }
                )

                Spacer(modifier = Modifier.height(36.dp))
            }

            // --- Dialog Overlays ---

            // Edit Nickname / Alias Dialog
            if (uiState.showEditNicknameDialog) {
                EditUserDetailsDialog(
                    initialNickname = uiState.customNickname,
                    contactOriginalName = uiState.originalRegisteredName ?: uiState.displayName,
                    onSaveNickname = { viewModel.saveCustomNickname(it) },
                    onDismiss = { viewModel.setEditNicknameDialogVisible(false) }
                )
            }

            // Mute Notifications Dialog
            if (uiState.showMuteDialog) {
                MuteNotificationsDialog(
                    contactName = uiState.displayName,
                    isCurrentlyMuted = uiState.isMuted,
                    onSetMuteDuration = { viewModel.setMuteDuration(it) },
                    onDismiss = { viewModel.setMuteDialogVisible(false) }
                )
            }

            // Custom Notifications Dialog
            if (uiState.showCustomNotificationsDialog) {
                CustomNotificationsDialog(
                    contactName = uiState.displayName,
                    currentTone = uiState.customTone,
                    isVibrationEnabled = uiState.isVibrationEnabled,
                    onSavePreferences = { tone, vib ->
                        viewModel.saveCustomNotifications(tone, vib)
                    },
                    onDismiss = { viewModel.setCustomNotificationsDialogVisible(false) }
                )
            }

            // Clear Chat Confirmation Dialog
            if (uiState.showClearChatDialog) {
                ClearChatDialog(
                    contactName = uiState.displayName,
                    onConfirmClear = { viewModel.clearChatMessages() },
                    onDismiss = { viewModel.setClearChatDialogVisible(false) }
                )
            }

            // Block / Unblock Contact Dialog
            if (uiState.showBlockDialog) {
                BlockContactDialog(
                    contactName = uiState.displayName,
                    isCurrentlyBlocked = uiState.isBlocked,
                    onConfirmToggleBlock = { viewModel.toggleBlockContact() },
                    onDismiss = { viewModel.setBlockDialogVisible(false) }
                )
            }

            // Full-Screen Profile Image Viewer
            val photoModel = uiState.avatarUrl ?: uiState.deviceContactPhotoUri
            if (uiState.showPhotoViewer && photoModel != null) {
                ProfileImageViewerScreen(
                    imageModel = photoModel,
                    onCloseClick = { viewModel.setPhotoViewerVisible(false) }
                )
            }

            if (showStatusUnavailableDialog) {
                AlertDialog(
                    onDismissRequest = { showStatusUnavailableDialog = false },
                    title = { Text("Status unavailable") },
                    text = { Text("Status not posted by this contact.") },
                    confirmButton = {
                        TextButton(onClick = { showStatusUnavailableDialog = false }) {
                            Text("OK")
                        }
                    }
                )
            }

            if (isStatusViewerVisible && activeStatusGroup != null) {
                NexaSequentialStoryViewer(
                    group = activeStatusGroup,
                    onDismiss = { isStatusViewerVisible = false },
                    onMarkViewed = viewModel::markStatusViewed
                )
            }
        }
    }
}
