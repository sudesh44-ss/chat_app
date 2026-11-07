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
import com.nexa.app.data.local.preferences.OnlinePrivacyMode
import com.nexa.app.data.local.preferences.TypingPrivacyMode
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.domain.repository.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val conversationPreferences: ConversationPreferences
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
                val phone = if (isSelf) myProfile?.phoneNumber else contact?.phoneNumber
                val about = if (isSelf) (myProfile?.bio ?: "Hey there! I am using NEXA encrypted chat.") else "Hey there! I am using NEXA encrypted chat."
                val avatar = contact?.effectiveAvatarUrl
                val photoUri = if (isSelf) null else contact?.deviceContactPhotoUri

                val isMuted = conversationPreferences.isMuted(initialConversationId)
                val muteUntil = conversationPreferences.getMuteUntil(initialConversationId)
                val wallpaper = conversationPreferences.getWallpaper(initialConversationId)
                val customTone = conversationPreferences.getCustomTone(initialConversationId)
                val isVibration = conversationPreferences.isVibrationEnabled(initialConversationId)
                val isBlocked = contact?.status == ContactStatus.BLOCKED

                _uiState.update { current ->
                    current.copy(
                        displayName = effectiveName,
                        customNickname = nickname,
                        originalRegisteredName = originalName,
                        phoneNumber = phone,
                        aboutText = about,
                        avatarUrl = avatar,
                        deviceContactPhotoUri = photoUri,
                        isOnline = true, // Connected via E2EE / Mesh
                        lastSeenText = "Active Now",
                        isMuted = isMuted,
                        muteUntil = muteUntil,
                        wallpaper = wallpaper,
                        customTone = customTone,
                        isVibrationEnabled = isVibration,
                        isBlocked = isBlocked,
                        onlinePrivacyMode = chatPrivacyPrefs.onlinePresenceMode,
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

    fun setPresenceMode(mode: OnlinePrivacyMode) {
        chatPrivacyPrefs.onlinePresenceMode = mode
        _uiState.update { it.copy(onlinePrivacyMode = mode) }
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

    fun toggleBlockContact() {
        viewModelScope.launch {
            val currentlyBlocked = _uiState.value.isBlocked
            val targetStatus = if (currentlyBlocked) ContactStatus.MUTUAL_FRIEND else ContactStatus.BLOCKED
            withContext(Dispatchers.IO) {
                contactDao.updateStatus(participantNxId, targetStatus)
                contactRepository.rejectOrBlockContact(participantNxId, block = !currentlyBlocked)
            }
            _uiState.update {
                it.copy(
                    isBlocked = !currentlyBlocked,
                    snackbarMessage = if (!currentlyBlocked) "Contact blocked" else "Contact unblocked"
                )
            }
        }
    }

    fun setEditNicknameDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showEditNicknameDialog = visible) }
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

    fun setPhotoViewerVisible(visible: Boolean) {
        _uiState.update { it.copy(showPhotoViewer = visible) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
