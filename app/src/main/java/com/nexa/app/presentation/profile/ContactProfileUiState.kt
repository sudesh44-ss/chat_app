package com.nexa.app.presentation.profile

import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.TransferEntity
import com.nexa.app.data.local.preferences.OnlinePrivacyMode
import com.nexa.app.data.local.preferences.TypingPrivacyMode

data class ContactProfileUiState(
    val nxId: String = "",
    val conversationId: String = "",
    val displayName: String = "",
    val customNickname: String? = null,
    val originalRegisteredName: String? = null,
    val phoneNumber: String? = null,
    val aboutText: String? = null,
    val avatarUrl: String? = null,
    val deviceContactPhotoUri: String? = null,
    val isOnline: Boolean = false,
    val lastSeenText: String = "Offline",
    val mediaItems: List<TransferEntity> = emptyList(),
    val pinnedCount: Int = 0,
    val latestPinnedText: String? = null,
    val isMuted: Boolean = false,
    val muteUntil: Long = 0L,
    val wallpaper: String? = null,
    val customTone: String? = null,
    val isVibrationEnabled: Boolean = true,
    val isBlocked: Boolean = false,
    val onlinePrivacyMode: OnlinePrivacyMode = OnlinePrivacyMode.MY_CONTACTS,
    val typingPrivacyMode: TypingPrivacyMode = TypingPrivacyMode.EVERYONE,
    val connectivitySummary: String = "Internet • E2EE Direct",
    val isPhoneInAddressBook: Boolean = false,
    val connectedEmail: String? = null,
    val showEditNicknameDialog: Boolean = false,
    val showMuteDialog: Boolean = false,
    val showCustomNotificationsDialog: Boolean = false,
    val showClearChatDialog: Boolean = false,
    val showBlockDialog: Boolean = false,
    val showPhotoViewer: Boolean = false,
    val isLoading: Boolean = false,
    val snackbarMessage: String? = null
)
