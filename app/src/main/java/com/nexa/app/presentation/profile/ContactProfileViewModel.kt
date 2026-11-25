package com.nexa.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.data.local.dao.ChatDao
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.dao.FileTransferDao
import com.nexa.app.data.local.dao.MessageDao
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.data.local.preferences.ChatPrivacyPrefs
import com.nexa.app.data.local.preferences.ConversationPreferences
import com.nexa.app.data.local.preferences.OfflinePresenceMode
import com.nexa.app.data.local.preferences.OnlinePrivacyMode
import com.nexa.app.data.local.preferences.TypingPrivacyMode
import com.nexa.app.domain.model.ConversationBackupPolicy
import com.nexa.app.domain.model.ConversationCustomCategories
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.domain.repository.ContactRepository
import com.nexa.app.domain.repository.StatusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactProfileViewModel(
    private val participantNxId: String,
    private val initialConversationId: String,
    private val contactRepository: ContactRepository,
    private val chatRepository: ChatRepository,
    private val contactDao: ContactDao,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val fileTransferDao: FileTransferDao,
    private val userDao: UserDao,
    private val chatPrivacyPrefs: ChatPrivacyPrefs,
    private val conversationPreferences: ConversationPreferences,
    private val statusRepository: StatusRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ContactProfileUiState(
            nxId = participantNxId,
            conversationId = initialConversationId
        )
    )
    val uiState: StateFlow<ContactProfileUiState> = _uiState.asStateFlow()

    init {
        loadContactProfile()
        observePinnedMessages()
        observeMedia()
        observeStatuses()
    }

    private fun loadContactProfile() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val contact = contactDao.getContact(participantNxId)
                val myProfile = userDao.getLocalProfile()
                val isSelf = participantNxId == myProfile?.nxId
                val chat = if (initialConversationId.isNotBlank()) {
                    chatDao.getChatById(initialConversationId)
                } else {
                    chatDao.getChatByParticipant(participantNxId)
                }

                val effectiveName = when {
                    isSelf -> myProfile?.fullName ?: participantNxId
                    contact != null -> contact.effectiveDisplayName
                    chat != null -> chat.participantName
                    else -> participantNxId
                }

                val originalName = if (isSelf) (myProfile?.fullName ?: effectiveName) else (contact?.displayName ?: effectiveName)
                val nickname = if (isSelf) null else contact?.customNickname
                val phone = if (isSelf) {
                    myProfile?.phoneNumber
                } else if (contact?.shouldShowPhoneNumber == true) {
                    contact.phoneNumber
                } else {
                    null
                }
                val about = if (isSelf) (myProfile?.bio ?: "Hey there! I am using NEXA encrypted chat.") else "Hey there! I am using NEXA encrypted chat."
                val avatar = contact?.effectiveAvatarUrl
                val photoUri = if (isSelf) null else contact?.deviceContactPhotoUri

                val isMuted = conversationPreferences.isMuted(initialConversationId)
                val muteUntil = conversationPreferences.getMuteUntil(initialConversationId)
                val wallpaper = conversationPreferences.getWallpaper(initialConversationId)
                val customTone = conversationPreferences.getCustomTone(initialConversationId)
                val isVibration = conversationPreferences.isVibrationEnabled(initialConversationId)
                val isBlocked = contact?.status == ContactStatus.BLOCKED

                val parsedPolicy = chat?.backupPolicy?.let { raw ->
                    runCatching { ConversationBackupPolicy.valueOf(raw.trim().uppercase()) }.getOrNull()
                } ?: (if (chat?.isPriorityBackup == true) ConversationBackupPolicy.PRIORITY else ConversationBackupPolicy.DEFAULT)
                val parsedCategories = chat?.customBackupCategories?.let { ConversationCustomCategories.fromJson(it) }

                _uiState.update { current ->
                    current.copy(
                        displayName = effectiveName,
                        customNickname = nickname,
                        originalRegisteredName = originalName,
                        phoneNumber = phone,
                        aboutText = about,
                        avatarUrl = avatar,
                        deviceContactPhotoUri = photoUri,
                        isOnline = chatPrivacyPrefs.showOnlineStatus && chatPrivacyPrefs.onlinePresenceMode != OnlinePrivacyMode.NOBODY,
                        lastSeenText = when (chatPrivacyPrefs.offlinePresenceMode) {
                            OfflinePresenceMode.SHOW_OFFLINE -> "Offline"
                            OfflinePresenceMode.LAST_SEEN_RECENTLY -> "Last seen recently"
                            OfflinePresenceMode.TAKING_A_BREAK -> "${effectiveName} is taking a break — back soon"
                        },
                        isMuted = isMuted,
                        muteUntil = muteUntil,
                        wallpaper = wallpaper,
                        customTone = customTone,
                        isVibrationEnabled = isVibration,
                        isBlocked = isBlocked,
                        backupPolicy = parsedPolicy,
                        customBackupCategories = parsedCategories,
                        showOnlineStatus = chatPrivacyPrefs.showOnlineStatus,
                        onlinePrivacyMode = chatPrivacyPrefs.onlinePresenceMode,
                        offlinePresenceMode = chatPrivacyPrefs.offlinePresenceMode,
                        selectedOnlineStatusContacts = chatPrivacyPrefs.selectedOnlineStatusContacts.toList(),
                        typingPrivacyMode = chatPrivacyPrefs.typingIndicatorMode,
                        isPhoneInAddressBook = contact?.isPhoneInLocalAddressBook ?: false
                    )
                }
            }
        }
    }

    private fun observePinnedMessages() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            messageDao.getPinnedMessagesFlow(initialConversationId, now).collect { pinnedList ->
                _uiState.update { current ->
                    current.copy(
                        pinnedCount = pinnedList.size,
                        latestPinnedText = pinnedList.firstOrNull()?.plainTextContent
                    )
                }
            }
        }
    }

    private fun observeMedia() {
        viewModelScope.launch {
            fileTransferDao.observeTransfersForConversation(initialConversationId).collect { transfers ->
                _uiState.update { current ->
                    current.copy(mediaItems = transfers)
                }
            }
        }
    }

    private fun observeStatuses() {
        val repository = statusRepository ?: return
        viewModelScope.launch {
            repository.getStatusesForUserFlow(participantNxId).collect { statuses ->
                _uiState.update { it.copy(activeStatuses = statuses) }
            }
        }
    }

    fun markStatusViewed(statusId: String) {
        viewModelScope.launch {
            statusRepository?.markStatusViewed(statusId)
        }
    }

    fun refreshStatusAvailability() {
        viewModelScope.launch {
            statusRepository?.refreshAvailability()
        }
    }

    fun saveCustomNickname(nickname: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                contactDao.updateNickname(participantNxId, nickname)
                contactRepository.setContactNickname(participantNxId, nickname)
            }
            loadContactProfile()
            _uiState.update { it.copy(snackbarMessage = "Updated alias") }
        }
    }

    fun setMuteDuration(durationMillis: Long) {
        val until = when (durationMillis) {
            0L -> 0L
            -1L -> -1L
            else -> System.currentTimeMillis() + durationMillis
        }
        val isMuted = durationMillis != 0L
        conversationPreferences.setMuted(initialConversationId, isMuted, until)
        viewModelScope.launch {
            chatRepository.muteConversation(
                initialConversationId,
                isMuted,
                if (until == -1L) 0L else until
            )
        }
        _uiState.update {
            it.copy(
                isMuted = isMuted,
                muteUntil = until,
                snackbarMessage = if (isMuted) "Notifications muted" else "Notifications unmuted"
            )
        }
    }

    fun saveCustomNotifications(tone: String, vibration: Boolean) {
        conversationPreferences.setCustomTone(initialConversationId, tone)
        conversationPreferences.setVibrationEnabled(initialConversationId, vibration)
        _uiState.update {
            it.copy(
                customTone = tone,
                isVibrationEnabled = vibration,
                snackbarMessage = "Saved custom notification preferences"
            )
        }
    }

    fun setShowOnlineStatus(show: Boolean) {
        chatPrivacyPrefs.showOnlineStatus = show
        _uiState.update { it.copy(showOnlineStatus = show, isOnline = show && it.onlinePrivacyMode != OnlinePrivacyMode.NOBODY) }
    }

    fun setPresenceMode(mode: OnlinePrivacyMode) {
        chatPrivacyPrefs.onlinePresenceMode = mode
        _uiState.update { it.copy(onlinePrivacyMode = mode, isOnline = it.showOnlineStatus && mode != OnlinePrivacyMode.NOBODY) }
    }

    fun setOfflinePresenceMode(mode: OfflinePresenceMode) {
        chatPrivacyPrefs.offlinePresenceMode = mode
        _uiState.update { it.copy(offlinePresenceMode = mode) }
    }

    fun setTypingMode(mode: TypingPrivacyMode) {
        chatPrivacyPrefs.typingIndicatorMode = mode
        _uiState.update { it.copy(typingPrivacyMode = mode) }
    }

    fun clearChatMessages() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                chatRepository.clearChatMessages(initialConversationId)
            }
            _uiState.update { it.copy(snackbarMessage = "Chat cleared") }
        }
    }

    fun blockContact(alsoReport: Boolean, deleteChat: Boolean) {
        viewModelScope.launch {
            val convId = _uiState.value.conversationId
            withContext(Dispatchers.IO) {
                contactRepository.blockUser(participantNxId)
                if (deleteChat && convId.isNotBlank()) {
                    chatRepository.deleteChat(convId)
                }
            }
            _uiState.update {
                it.copy(
                    isBlocked = true,
                    showBlockDialog = false,
                    snackbarMessage = if (alsoReport) {
                        "Contact blocked. Report feature is not supported in this build."
                    } else {
                        "Contact blocked"
                    }
                )
            }
        }
    }

    fun unblockContact() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                contactRepository.unblockUser(participantNxId)
            }
            _uiState.update {
                it.copy(
                    isBlocked = false,
                    showUnblockDialog = false,
                    snackbarMessage = "Contact unblocked"
                )
            }
        }
    }

    fun toggleBlockContact() {
        if (_uiState.value.isBlocked) {
            setUnblockDialogVisible(true)
        } else {
            setBlockDialogVisible(true)
        }
    }

    fun setEditNicknameDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showEditNicknameDialog = visible) }
    }

    fun setBackupPolicyDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showBackupPolicyDialog = visible) }
    }

    fun setBackupPolicy(policy: ConversationBackupPolicy, customCategories: ConversationCustomCategories?) {
        val convId = _uiState.value.conversationId
        if (convId.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val json = customCategories?.toJson()
                val isPriority = policy == ConversationBackupPolicy.PRIORITY
                chatDao.setConversationBackupPolicy(convId, policy.name, json, isPriority)
            }
            _uiState.update {
                it.copy(
                    backupPolicy = policy,
                    customBackupCategories = customCategories,
                    showBackupPolicyDialog = false,
                    snackbarMessage = "Backup policy updated"
                )
            }
        }
    }

    fun setMuteDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showMuteDialog = visible) }
    }

    fun setCustomNotificationsDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showCustomNotificationsDialog = visible) }
    }

    fun setClearChatDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showClearChatDialog = visible) }
    }

    fun setBlockDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showBlockDialog = visible) }
    }

    fun setUnblockDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showUnblockDialog = visible) }
    }

    fun setPhotoViewerVisible(visible: Boolean) {
        _uiState.update { it.copy(showPhotoViewer = visible) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
