package com.nexa.app.presentation.chat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.core.identity.PeerIdentityResolver
import com.nexa.app.core.media.CompressionProgress
import com.nexa.app.core.media.VideoMetadata
import com.nexa.app.core.media.VideoMetadataProbe
import com.nexa.app.core.network.ConnectionState
import com.nexa.app.core.telemetry.NexaTelemetryEngine
import com.nexa.app.core.util.SafeMessageParser
import com.nexa.app.data.local.preferences.ChatPrivacyPrefs
import com.nexa.app.data.local.preferences.DefaultVideoQuality
import com.nexa.app.data.local.preferences.MediaPrefs
import com.nexa.app.data.local.preferences.OnlinePrivacyMode
import com.nexa.app.data.local.preferences.TransportRouteMode
import com.nexa.app.data.local.preferences.TypingPrivacyMode
import com.nexa.app.domain.manager.ScheduledMessageManager
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageType
import com.nexa.app.domain.model.ScheduledMessage
import com.nexa.app.core.sms.SmsComposerRequest
import com.nexa.app.core.sms.SmsEligibility
import com.nexa.app.core.sms.SmsFallbackState
import com.nexa.app.core.sms.SmsImportOutcome
import com.nexa.app.core.sms.SmsOutboundPlan
import com.nexa.app.core.sms.SmsPrepareOutcome
import com.nexa.app.domain.repository.SmsFallbackRepository
import com.nexa.app.presentation.chat.components.SmsFallbackUiState
import com.nexa.app.domain.model.TransportType
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.domain.repository.ContactRepository
import com.nexa.app.domain.repository.MediaProcessingRepository
import com.nexa.app.domain.repository.VideoSendMode
import com.nexa.app.presentation.chat.components.PinDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class VideoCandidate(
    val uri: Uri,
    val metadata: VideoMetadata,
    val estimatedCompressedBytes: Long
)

data class TranscodeState(
    val isTranscoding: Boolean = false,
    val progressPercent: Int = 0,
    val currentUri: Uri? = null
)

data class VideoReadyForTransfer(
    val conversationId: String,
    val recipientNxId: String,
    val file: File,
    val fileName: String,
    val fileSizeBytes: Long
)

data class ChatUiState(
    val conversationId: String = "",
    val participantNxId: String = "",
    val participantName: String = "",
    val destinationPhone: String = "",
    val inputText: String = "",
    val messages: List<Message> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val selectedTransport: TransportType = TransportType.INTERNET,
    val selectedRouteMode: TransportRouteMode = TransportRouteMode.AUTO,
    val showTransportBadges: Boolean = true,
    val showSmsFailureSheet: Boolean = false,
    val failedMessageCandidate: Message? = null,
    val isPeerOnline: Boolean = false,
    val customNickname: String? = null,
    val deviceContactName: String? = null,
    val originalProfileName: String = "",
    val participantAvatarUrl: String? = null,
    val deviceContactPhotoUri: String? = null,
    val showNicknameDialog: Boolean = false,
    val showProfileDialog: Boolean = false,
    val isSending: Boolean = false,
    val showPhoneDialog: Boolean = false,
    val errorMessage: String? = null,
    val isPeerTyping: Boolean = false,
    // Video compression & quality selection state
    val pendingVideoCandidate: VideoCandidate? = null,
    val showVideoQualityDialog: Boolean = false,
    val transcodeState: TranscodeState = TranscodeState(),
    // Pinned messages state
    val pinnedMessages: List<Message> = emptyList(),
    val currentPinnedIndex: Int = 0,
    val highlightedMessageId: String? = null,
    val showPinDurationDialog: Boolean = false,
    val candidateMessageToPin: Message? = null,
    val pinLimitReachedSnackbar: Boolean = false,
    val selectedMessageForAction: Message? = null,
    // Reply to message state
    val replyingToMessage: Message? = null,
    // Scheduled messages state
    val scheduledMessages: List<ScheduledMessage> = emptyList(),
    val showScheduleDialog: Boolean = false,
    val showScheduledMessagesSheet: Boolean = false,
    // Find in chat & search state
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Message> = emptyList(),
    val isSearching: Boolean = false,
    val searchMatchIndex: Int = 0,
    val showDatePickerDialog: Boolean = false,
    // Encrypted SMS fallback (Module 3)
    val smsFallback: SmsFallbackUiState = SmsFallbackUiState(),
    val smsFallbackState: SmsFallbackState? = null,
    // Blocked contact state
    val isContactBlocked: Boolean = false,
    val showBlockContactDialog: Boolean = false,
    val showUnblockContactDialog: Boolean = false
)

sealed interface ChatUiEvent {
    data class Init(
        val conversationId: String,
        val participantNxId: String,
        val participantName: String,
        val destinationPhone: String? = null
    ) : ChatUiEvent
    data class OnInputTextChanged(val text: String) : ChatUiEvent
    data class SetTransport(val transport: TransportType) : ChatUiEvent
    data class SetRouteMode(val mode: TransportRouteMode) : ChatUiEvent
    data class SetShowTransportBadges(val show: Boolean) : ChatUiEvent
    data object ToggleTransport : ChatUiEvent
    data class SetDestinationPhone(val phone: String) : ChatUiEvent
    data class SetPhoneDialogVisible(val visible: Boolean) : ChatUiEvent
    data object SendMessage : ChatUiEvent
    data object MarkAsRead : ChatUiEvent
    data object ClearError : ChatUiEvent

    // Carrier SMS & transport failure remediation events
    data class OnFailedMessageClicked(val message: Message) : ChatUiEvent
    data object DismissSmsFailureSheet : ChatUiEvent
    data class RetryFailedMessage(val targetTransport: TransportType) : ChatUiEvent

    // Client-side contact nickname & identity profile events
    data class SetNicknameDialogVisible(val visible: Boolean) : ChatUiEvent
    data class SetProfileDialogVisible(val visible: Boolean) : ChatUiEvent
    data class SaveNickname(val nickname: String?) : ChatUiEvent

    // Video transcode & dispatch events
    data class OnVideoSelected(val uri: Uri) : ChatUiEvent
    data class OnSelectVideoQuality(val mode: VideoSendMode, val rememberPreference: Boolean) : ChatUiEvent
    data object DismissVideoQualityDialog : ChatUiEvent
    data object CancelVideoTranscode : ChatUiEvent

    // Pinned message events
    data class RequestPinMessage(val message: Message) : ChatUiEvent
    data class ConfirmPinMessage(val duration: PinDuration) : ChatUiEvent
    data class RequestUnpinMessage(val message: Message) : ChatUiEvent
    data object DismissPinDurationDialog : ChatUiEvent
    data object OnPinnedBannerClicked : ChatUiEvent
    data object DismissPinLimitSnackbar : ChatUiEvent
    data class SetSelectedMessageForAction(val message: Message?) : ChatUiEvent

    // Reply-to-message events
    data class SetReplyingMessage(val message: Message?) : ChatUiEvent
    data object CancelReply : ChatUiEvent
    data class HighlightMessage(val packetId: String) : ChatUiEvent

    // Scheduled message events
    data class SetScheduleDialogVisible(val visible: Boolean) : ChatUiEvent
    data class SetScheduledMessagesSheetVisible(val visible: Boolean) : ChatUiEvent
    data class ScheduleCurrentMessage(val scheduledAtUtcMillis: Long) : ChatUiEvent
    data class CancelScheduledMessage(val id: String) : ChatUiEvent

    // Find in chat & date navigation events
    data class SetSearchActive(val active: Boolean) : ChatUiEvent
    data class OnSearchQueryChanged(val query: String) : ChatUiEvent
    data object ClearSearchQuery : ChatUiEvent
    data object NextSearchMatch : ChatUiEvent
    data object PreviousSearchMatch : ChatUiEvent
    data class SelectSearchResult(val message: Message) : ChatUiEvent
    data class SetDatePickerVisible(val visible: Boolean) : ChatUiEvent
    data class SelectDate(val epochDayMillis: Long) : ChatUiEvent

    // GIF, Meme & Media messaging events
    data class SendGifMeme(val content: String, val messageType: MessageType = MessageType.GIF) : ChatUiEvent

    // Reactions & Message Management events
    data class ToggleReaction(val messageId: String, val emoji: String) : ChatUiEvent
    data class DeleteMessage(val message: Message) : ChatUiEvent
    data class ToggleStarMessage(val message: Message) : ChatUiEvent

    // Encrypted SMS fallback (Module 3)
    data object OpenSmsFallback : ChatUiEvent
    data object CloseSmsFallback : ChatUiEvent
    data object PrepareSmsFallback : ChatUiEvent
    data object ConfirmSmsFallbackHandoff : ChatUiEvent
    data object MarkSmsFallbackSent : ChatUiEvent
    data class SetSmsImportSender(val sender: String) : ChatUiEvent
    data class SetSmsImportBody(val body: String) : ChatUiEvent
    data object ImportSmsPacket : ChatUiEvent
    data object ClearSmsAck : ChatUiEvent

    // Block / unblock contact events
    data class BlockContact(val alsoReport: Boolean, val deleteChat: Boolean) : ChatUiEvent
    data object UnblockContact : ChatUiEvent
    data class SetBlockDialogVisible(val visible: Boolean) : ChatUiEvent
    data class SetUnblockDialogVisible(val visible: Boolean) : ChatUiEvent
}

class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val mediaProcessingRepository: MediaProcessingRepository? = null,
    private val mediaPrefs: MediaPrefs? = null,
    private val chatPrivacyPrefs: ChatPrivacyPrefs? = null,
    private val contactRepository: ContactRepository? = null,
    private val scheduledMessageManager: ScheduledMessageManager? = null,
    private val smsFallbackRepository: SmsFallbackRepository? = null
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _videoReadyFlow = MutableSharedFlow<VideoReadyForTransfer>(extraBufferCapacity = 8)
    val videoReadyFlow: SharedFlow<VideoReadyForTransfer> = _videoReadyFlow.asSharedFlow()

    private val _navigateToMessageFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val navigateToMessageFlow: SharedFlow<String> = _navigateToMessageFlow.asSharedFlow()

    private val _smsComposerRequests = MutableSharedFlow<SmsComposerRequest>(extraBufferCapacity = 4)
    val smsComposerRequests: SharedFlow<SmsComposerRequest> = _smsComposerRequests.asSharedFlow()

    private var messagesJob: Job? = null
    private var connectionJob: Job? = null
    private var transportHandoffJob: Job? = null
    private var transcodeJob: Job? = null
    private var typingJob: Job? = null
    private var typingExpiryJob: Job? = null
    private var typingDebounceJob: Job? = null
    private var pinnedMessagesJob: Job? = null
    private var scheduledMessagesJob: Job? = null
    private var searchJob: Job? = null
    private var draftSaveJob: Job? = null
    private var blockStatusJob: Job? = null
    private var isCurrentlyTypingSent = false
    private var lastTypingSentMs: Long = 0L

    init {
        chatPrivacyPrefs?.let { prefs ->
            val route = prefs.preferredTransportRoute
            val showBadges = prefs.showTransportBadges
            val initialTransport = when (route) {
                TransportRouteMode.AUTO -> TransportType.INTERNET
                TransportRouteMode.INTERNET -> TransportType.INTERNET
                TransportRouteMode.MESH -> TransportType.DTN_MESH
                TransportRouteMode.SMS -> TransportType.DATA_SMS
            }
            _uiState.update {
                it.copy(
                    selectedRouteMode = route,
                    showTransportBadges = showBadges,
                    selectedTransport = initialTransport
                )
            }
        }
        observeConnection()
    }

    fun onEvent(event: ChatUiEvent) {
        when (event) {
            is ChatUiEvent.Init -> initializeConversation(event)
            is ChatUiEvent.OnInputTextChanged -> handleInputTextChanged(event.text)
            is ChatUiEvent.SetTransport -> _uiState.update { it.copy(selectedTransport = event.transport) }
            is ChatUiEvent.SetRouteMode -> {
                _uiState.update { current ->
                    val resolvedTransport = when (event.mode) {
                        TransportRouteMode.AUTO -> {
                            if (current.connectionState is ConnectionState.Connected) {
                                TransportType.INTERNET
                            } else {
                                TransportType.DTN_MESH
                            }
                        }
                        TransportRouteMode.INTERNET -> TransportType.INTERNET
                        TransportRouteMode.MESH -> TransportType.DTN_MESH
                        TransportRouteMode.SMS -> TransportType.DATA_SMS
                    }
                    current.copy(
                        selectedRouteMode = event.mode,
                        selectedTransport = resolvedTransport
                    )
                }
                chatPrivacyPrefs?.preferredTransportRoute = event.mode
            }
            is ChatUiEvent.SetShowTransportBadges -> {
                _uiState.update { it.copy(showTransportBadges = event.show) }
                chatPrivacyPrefs?.showTransportBadges = event.show
            }
            is ChatUiEvent.ToggleTransport -> {
                _uiState.update {
                    val next = when (it.selectedTransport) {
                        TransportType.INTERNET -> TransportType.DATA_SMS
                        TransportType.DATA_SMS -> TransportType.DTN_MESH
                        TransportType.DTN_MESH -> TransportType.INTERNET
                    }
                    it.copy(selectedTransport = next)
                }
            }
            is ChatUiEvent.SetDestinationPhone -> _uiState.update { it.copy(destinationPhone = event.phone) }
            is ChatUiEvent.SetPhoneDialogVisible -> _uiState.update { it.copy(showPhoneDialog = event.visible) }
            is ChatUiEvent.SendMessage -> dispatchMessage()
            is ChatUiEvent.MarkAsRead -> markConversationAsRead()
            is ChatUiEvent.ClearError -> _uiState.update { it.copy(errorMessage = null) }

            // Carrier SMS & transport failure remediation events
            is ChatUiEvent.OnFailedMessageClicked -> _uiState.update {
                it.copy(showSmsFailureSheet = true, failedMessageCandidate = event.message)
            }
            is ChatUiEvent.DismissSmsFailureSheet -> _uiState.update {
                it.copy(showSmsFailureSheet = false, failedMessageCandidate = null)
            }
            is ChatUiEvent.RetryFailedMessage -> retryFailedMessage(event.targetTransport)

            // Client-side contact nickname & identity profile events
            is ChatUiEvent.SetNicknameDialogVisible -> _uiState.update { it.copy(showNicknameDialog = event.visible) }
            is ChatUiEvent.SetProfileDialogVisible -> _uiState.update { it.copy(showProfileDialog = event.visible) }
            is ChatUiEvent.SaveNickname -> saveNickname(event.nickname)

            // Video compression & quality flow
            is ChatUiEvent.OnVideoSelected -> handleVideoSelected(event.uri)
            is ChatUiEvent.OnSelectVideoQuality -> handleVideoQualitySelected(event.mode, event.rememberPreference)
            is ChatUiEvent.DismissVideoQualityDialog -> _uiState.update {
                it.copy(showVideoQualityDialog = false, pendingVideoCandidate = null)
            }
            is ChatUiEvent.CancelVideoTranscode -> cancelVideoTranscode()

            // Pinned message events
            is ChatUiEvent.RequestPinMessage -> {
                if (_uiState.value.pinnedMessages.size >= 4) {
                    _uiState.update { it.copy(pinLimitReachedSnackbar = true, selectedMessageForAction = null) }
                } else {
                    _uiState.update {
                        it.copy(
                            showPinDurationDialog = true,
                            candidateMessageToPin = event.message,
                            selectedMessageForAction = null
                        )
                    }
                }
            }
            is ChatUiEvent.ConfirmPinMessage -> {
                val candidate = _uiState.value.candidateMessageToPin
                val convId = _uiState.value.conversationId
                val peerId = _uiState.value.participantNxId
                if (candidate != null && convId.isNotBlank()) {
                    val durationMs = event.duration.durationMillis
                    viewModelScope.launch {
                        chatRepository.pinMessage(
                            conversationId = convId,
                            packetId = candidate.packetId,
                            durationMillis = durationMs,
                            recipientNxId = peerId
                        )
                    }
                }
                _uiState.update { it.copy(showPinDurationDialog = false, candidateMessageToPin = null) }
            }
            is ChatUiEvent.RequestUnpinMessage -> {
                val convId = _uiState.value.conversationId
                val peerId = _uiState.value.participantNxId
                viewModelScope.launch {
                    chatRepository.unpinMessage(
                        conversationId = convId,
                        packetId = event.message.packetId,
                        recipientNxId = peerId
                    )
                }
                _uiState.update { it.copy(selectedMessageForAction = null) }
            }
            is ChatUiEvent.DismissPinDurationDialog -> _uiState.update {
                it.copy(showPinDurationDialog = false, candidateMessageToPin = null)
            }
            is ChatUiEvent.DismissPinLimitSnackbar -> _uiState.update {
                it.copy(pinLimitReachedSnackbar = false)
            }
            is ChatUiEvent.SetSelectedMessageForAction -> _uiState.update {
                it.copy(selectedMessageForAction = event.message)
            }
            is ChatUiEvent.OnPinnedBannerClicked -> {
                val pinned = _uiState.value.pinnedMessages
                if (pinned.isNotEmpty()) {
                    val curr = _uiState.value.currentPinnedIndex.coerceIn(0, pinned.lastIndex)
                    val targetMsg = pinned[curr]
                    val nextIndex = (curr + 1) % pinned.size
                    _uiState.update {
                        it.copy(
                            highlightedMessageId = targetMsg.packetId,
                            currentPinnedIndex = nextIndex
                        )
                    }
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(1200)
                        _uiState.update { current ->
                            if (current.highlightedMessageId == targetMsg.packetId) {
                                current.copy(highlightedMessageId = null)
                            } else current
                        }
                    }
                }
            }
            is ChatUiEvent.SetReplyingMessage -> _uiState.update { it.copy(replyingToMessage = event.message) }
            is ChatUiEvent.CancelReply -> _uiState.update { it.copy(replyingToMessage = null) }
            is ChatUiEvent.HighlightMessage -> highlightMessage(event.packetId)

            // Scheduled message events
            is ChatUiEvent.SetScheduleDialogVisible -> _uiState.update { it.copy(showScheduleDialog = event.visible) }
            is ChatUiEvent.SetScheduledMessagesSheetVisible -> _uiState.update { it.copy(showScheduledMessagesSheet = event.visible) }
            is ChatUiEvent.ScheduleCurrentMessage -> scheduleCurrentMessage(event.scheduledAtUtcMillis)
            is ChatUiEvent.CancelScheduledMessage -> cancelScheduledMessage(event.id)

            // Find in chat & date navigation events
            is ChatUiEvent.SetSearchActive -> {
                searchJob?.cancel()
                _uiState.update {
                    it.copy(
                        isSearchActive = event.active,
                        searchQuery = if (event.active) it.searchQuery else "",
                        searchResults = if (event.active) it.searchResults else emptyList(),
                        isSearching = false,
                        searchMatchIndex = 0
                    )
                }
            }
            is ChatUiEvent.OnSearchQueryChanged -> handleSearchQueryChanged(event.query)
            is ChatUiEvent.ClearSearchQuery -> {
                searchJob?.cancel()
                _uiState.update {
                    it.copy(
                        searchQuery = "",
                        searchResults = emptyList(),
                        isSearching = false,
                        searchMatchIndex = 0
                    )
                }
            }
            is ChatUiEvent.NextSearchMatch -> {
                val results = _uiState.value.searchResults
                if (results.isNotEmpty()) {
                    val nextIdx = (_uiState.value.searchMatchIndex + 1) % results.size
                    _uiState.update { it.copy(searchMatchIndex = nextIdx) }
                    viewModelScope.launch {
                        _navigateToMessageFlow.emit(results[nextIdx].packetId)
                        highlightMessage(results[nextIdx].packetId)
                    }
                }
            }
            is ChatUiEvent.PreviousSearchMatch -> {
                val results = _uiState.value.searchResults
                if (results.isNotEmpty()) {
                    val prevIdx = if (_uiState.value.searchMatchIndex <= 0) results.lastIndex else _uiState.value.searchMatchIndex - 1
                    _uiState.update { it.copy(searchMatchIndex = prevIdx) }
                    viewModelScope.launch {
                        _navigateToMessageFlow.emit(results[prevIdx].packetId)
                        highlightMessage(results[prevIdx].packetId)
                    }
                }
            }
            is ChatUiEvent.SelectSearchResult -> {
                _uiState.update { it.copy(isSearchActive = false) }
                viewModelScope.launch {
                    _navigateToMessageFlow.emit(event.message.packetId)
                    highlightMessage(event.message.packetId)
                }
            }
            is ChatUiEvent.SetDatePickerVisible -> _uiState.update { it.copy(showDatePickerDialog = event.visible) }
            is ChatUiEvent.SelectDate -> handleDateSelected(event.epochDayMillis)

            // GIF, Meme & Reactions
            is ChatUiEvent.SendGifMeme -> sendGifMeme(event.content, event.messageType)
            is ChatUiEvent.ToggleReaction -> toggleReaction(event.messageId, event.emoji)
            is ChatUiEvent.DeleteMessage -> deleteMessage(event.message)
            is ChatUiEvent.ToggleStarMessage -> toggleStarMessage(event.message)

            // Encrypted SMS fallback (Module 3)
            is ChatUiEvent.OpenSmsFallback -> openSmsFallback()
            is ChatUiEvent.CloseSmsFallback -> _uiState.update {
                it.copy(smsFallback = it.smsFallback.copy(isOpen = false))
            }
            is ChatUiEvent.PrepareSmsFallback -> prepareSmsFallback()
            is ChatUiEvent.ConfirmSmsFallbackHandoff -> confirmSmsFallbackHandoff()
            is ChatUiEvent.MarkSmsFallbackSent -> markSmsFallbackSent()
            is ChatUiEvent.SetSmsImportSender -> _uiState.update {
                it.copy(smsFallback = it.smsFallback.copy(importSender = event.sender))
            }
            is ChatUiEvent.SetSmsImportBody -> _uiState.update {
                it.copy(smsFallback = it.smsFallback.copy(importBody = event.body))
            }
            is ChatUiEvent.ImportSmsPacket -> importSmsPacket()
            is ChatUiEvent.ClearSmsAck -> _uiState.update {
                it.copy(smsFallback = it.smsFallback.copy(ackToken = null))
            }

            // Block / unblock contact events
            is ChatUiEvent.BlockContact -> blockCurrentContact(event.alsoReport, event.deleteChat)
            is ChatUiEvent.UnblockContact -> unblockCurrentContact()
            is ChatUiEvent.SetBlockDialogVisible -> _uiState.update { it.copy(showBlockContactDialog = event.visible) }
            is ChatUiEvent.SetUnblockDialogVisible -> _uiState.update { it.copy(showUnblockContactDialog = event.visible) }
        }
    }

    private fun highlightMessage(packetId: String) {
        _uiState.update { it.copy(highlightedMessageId = packetId) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(1500)
            _uiState.update { current ->
                if (current.highlightedMessageId == packetId) {
                    current.copy(highlightedMessageId = null)
                } else current
            }
        }
    }

    private fun handleSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false, searchMatchIndex = 0) }
            return
        }
        val convId = _uiState.value.conversationId
        if (convId.isBlank()) return

        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            _uiState.update { it.copy(isSearching = true) }
            val results = chatRepository.searchMessagesInConversation(convId, trimmed)
            _uiState.update {
                it.copy(
                    searchResults = results,
                    isSearching = false,
                    searchMatchIndex = 0
                )
            }
            if (results.isNotEmpty()) {
                _navigateToMessageFlow.emit(results[0].packetId)
                highlightMessage(results[0].packetId)
            }
        }
    }

    private fun handleDateSelected(epochDayMillis: Long) {
        val convId = _uiState.value.conversationId
        if (convId.isBlank()) return
        val zoneId = java.time.ZoneId.systemDefault()
        val instant = java.time.Instant.ofEpochMilli(epochDayMillis)
        val localDate = instant.atZone(zoneId).toLocalDate()
        val startOfDay = localDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endOfDay = localDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        _uiState.update { it.copy(showDatePickerDialog = false) }
        viewModelScope.launch {
            val firstMessage = chatRepository.getFirstMessageOnDate(convId, startOfDay, endOfDay)
            if (firstMessage != null) {
                _uiState.update { it.copy(isSearchActive = false) }
                _navigateToMessageFlow.emit(firstMessage.packetId)
                highlightMessage(firstMessage.packetId)
            } else {
                _uiState.update { it.copy(errorMessage = "No messages found on this date.") }
            }
        }
    }

    private fun initializeConversation(event: ChatUiEvent.Init) {
        if (event.conversationId.isBlank() || event.participantNxId.isBlank()) {
            _uiState.update {
                it.copy(
                    errorMessage = "Unable to open this conversation. Please try again.",
                    conversationId = event.conversationId,
                    participantNxId = event.participantNxId,
                    participantName = event.participantName.ifBlank { "Unknown contact" }
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                conversationId = event.conversationId,
                participantNxId = event.participantNxId,
                participantName = event.participantName.ifBlank { "Unknown contact" },
                originalProfileName = event.participantName.ifBlank { "Unknown contact" },
                destinationPhone = event.destinationPhone ?: it.destinationPhone
            )
        }

        viewModelScope.launch {
            try {
                val contact = contactRepository?.getContact(event.participantNxId)
                if (contact != null) {
                    _uiState.update {
                        it.copy(
                            participantName = contact.effectiveDisplayName,
                            originalProfileName = contact.displayName,
                            customNickname = contact.customNickname,
                            deviceContactName = contact.deviceContactName,
                            participantAvatarUrl = contact.effectiveAvatarUrl,
                            deviceContactPhotoUri = contact.deviceContactPhotoUri
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying contact info for ${event.participantNxId}", e)
            }
        }

        viewModelScope.launch {
            try {
                val draft = chatRepository.getDraft(event.conversationId)
                if (draft != null && draft.draftText.isNotBlank()) {
                    _uiState.update { it.copy(inputText = draft.draftText) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore draft: ${e.message}")
            }
        }

        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            try {
                chatRepository.getMessages(event.conversationId).collect { list ->
                    _uiState.update { it.copy(messages = list) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting messages for ${event.conversationId}", e)
            }
        }

        observePinnedMessages(event.conversationId)
        observeTyping(event.conversationId)
        observePresence(event.participantNxId)
        observeScheduledMessages(event.conversationId)
        observeBlockStatus(event.participantNxId)
        markConversationAsRead()
    }

    private fun observeScheduledMessages(conversationId: String) {
        scheduledMessagesJob?.cancel()
        scheduledMessagesJob = viewModelScope.launch {
            try {
                scheduledMessageManager?.observeActiveSchedulesForChat(conversationId)?.collect { list ->
                    _uiState.update { it.copy(scheduledMessages = list) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing scheduled messages for $conversationId", e)
            }
        }
    }

    private fun scheduleCurrentMessage(scheduledAtUtcMillis: Long) {
        val state = _uiState.value
        val text = SafeMessageParser.sanitizeInputText(state.inputText.trim())
        if (text.isBlank() || state.participantNxId.isBlank() || state.conversationId.isBlank()) return

        val replyTarget = state.replyingToMessage
        _uiState.update { it.copy(inputText = "", replyingToMessage = null, showScheduleDialog = false) }

        viewModelScope.launch {
            val result = scheduledMessageManager?.scheduleMessage(
                chatId = state.conversationId,
                recipientId = state.participantNxId,
                content = text,
                scheduledAtUtcMillis = scheduledAtUtcMillis,
                replyToMessageId = replyTarget?.packetId,
                transportType = state.selectedTransport,
                destinationPhone = state.destinationPhone
            )
            result?.onFailure { error ->
                Log.e(TAG, "Failed to schedule message", error)
                _uiState.update { it.copy(errorMessage = error.message ?: "Failed to schedule message") }
            }
        }
    }

    private fun cancelScheduledMessage(id: String) {
        viewModelScope.launch {
            scheduledMessageManager?.cancelScheduledMessage(id)
        }
    }

    private fun observePinnedMessages(conversationId: String) {
        pinnedMessagesJob?.cancel()
        pinnedMessagesJob = viewModelScope.launch {
            try {
                chatRepository.clearExpiredPins(conversationId)
                chatRepository.getPinnedMessages(conversationId).collect { pinned ->
                    _uiState.update { current ->
                        val newIndex = if (pinned.isNotEmpty()) current.currentPinnedIndex.coerceIn(0, pinned.lastIndex) else 0
                        current.copy(pinnedMessages = pinned, currentPinnedIndex = newIndex)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing pinned messages for $conversationId", e)
            }
        }
    }

    private fun observeTyping(conversationId: String) {
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            chatRepository.getTypingEvents().collect { (convId, isTyping) ->
                if (convId == conversationId) {
                    typingExpiryJob?.cancel()
                    _uiState.update { it.copy(isPeerTyping = isTyping) }
                    if (isTyping) {
                        typingExpiryJob = viewModelScope.launch {
                            kotlinx.coroutines.delay(3500)
                            _uiState.update { it.copy(isPeerTyping = false) }
                        }
                    }
                }
            }
        }
    }

    private var presenceJob: kotlinx.coroutines.Job? = null

    private fun observePresence(participantNxId: String) {
        presenceJob?.cancel()
        if (participantNxId.isBlank()) return
        presenceJob = viewModelScope.launch {
            chatRepository.getPresenceEvents().collect { (nxId, isOnline) ->
                if (nxId == participantNxId) {
                    val showOnline = chatPrivacyPrefs?.showOnlineStatus ?: true
                    val presenceMode = chatPrivacyPrefs?.onlinePresenceMode ?: OnlinePrivacyMode.EVERYONE
                    val canView = showOnline && presenceMode != OnlinePrivacyMode.NOBODY
                    _uiState.update { it.copy(isPeerOnline = if (canView) isOnline else false) }
                }
            }
        }
    }

    private fun observeBlockStatus(participantNxId: String) {
        blockStatusJob?.cancel()
        if (participantNxId.isBlank()) return
        blockStatusJob = viewModelScope.launch {
            val initialBlocked = contactRepository?.isUserBlocked(participantNxId)
                ?: chatRepository.isUserBlocked(participantNxId)
            _uiState.update { it.copy(isContactBlocked = initialBlocked) }

            contactRepository?.getBlockedContactsFlow()?.collect { blockedList ->
                val isBlocked = blockedList.any { it.nxId.equals(participantNxId, ignoreCase = true) }
                _uiState.update { it.copy(isContactBlocked = isBlocked) }
            }
        }
    }

    private fun blockCurrentContact(alsoReport: Boolean, deleteChat: Boolean) {
        val peerId = _uiState.value.participantNxId
        val convId = _uiState.value.conversationId
        if (peerId.isBlank()) return

        viewModelScope.launch {
            try {
                contactRepository?.blockUser(peerId)
                if (deleteChat && convId.isNotBlank()) {
                    chatRepository.deleteChat(convId)
                    _uiState.update { it.copy(messages = emptyList(), inputText = "") }
                }
                _uiState.update {
                    it.copy(
                        isContactBlocked = true,
                        showBlockContactDialog = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to block contact $peerId", e)
            }
        }
    }

    private fun unblockCurrentContact() {
        val peerId = _uiState.value.participantNxId
        if (peerId.isBlank()) return

        viewModelScope.launch {
            try {
                contactRepository?.unblockUser(peerId)
                _uiState.update {
                    it.copy(
                        isContactBlocked = false,
                        showUnblockContactDialog = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unblock contact $peerId", e)
            }
        }
    }

    private fun observeConnection() {
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            chatRepository.getConnectionState().collect { state ->
                val isConnected = state is ConnectionState.Connected
                val presenceMode = chatPrivacyPrefs?.onlinePresenceMode ?: OnlinePrivacyMode.EVERYONE
                val showOnlineStatus = chatPrivacyPrefs?.showOnlineStatus ?: true
                transportHandoffJob?.cancel()

                if (isConnected) {
                    // Instant Internet Binding (Priority 1): Zero BLE radio contention
                    _uiState.update { current ->
                        val updatedTransport = if (current.selectedRouteMode == TransportRouteMode.AUTO) {
                            TransportType.INTERNET
                        } else {
                            current.selectedTransport
                        }
                        val canView = showOnlineStatus && presenceMode != OnlinePrivacyMode.NOBODY
                        current.copy(
                            connectionState = state,
                            selectedTransport = updatedTransport,
                            isPeerOnline = if (canView) current.isPeerOnline else false
                        )
                    }
                } else {
                    // Transport Handoff Lock: Minimum 300ms debounce prevents 2.4 GHz antenna jitter
                    transportHandoffJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(300)
                        _uiState.update { current ->
                            val updatedTransport = if (current.selectedRouteMode == TransportRouteMode.AUTO) {
                                TransportType.DTN_MESH
                            } else {
                                current.selectedTransport
                            }
                            current.copy(
                                connectionState = state,
                                selectedTransport = updatedTransport,
                                isPeerOnline = false
                            )
                        }
                    }
                }
            }
        }
    }

    private fun handleInputTextChanged(text: String) {
        val boundedText = SafeMessageParser.sanitizeInputText(text)
        _uiState.update { it.copy(inputText = boundedText) }

        val convId = _uiState.value.conversationId
        if (convId.isNotBlank()) {
            draftSaveJob?.cancel()
            draftSaveJob = viewModelScope.launch {
                kotlinx.coroutines.delay(500)
                chatRepository.saveDraft(convId, boundedText)
            }
        }

        val typingMode = chatPrivacyPrefs?.typingIndicatorMode ?: TypingPrivacyMode.EVERYONE
        if (typingMode == TypingPrivacyMode.NOBODY) return

        val state = _uiState.value
        val participantNxId = state.participantNxId
        val conversationId = state.conversationId
        if (participantNxId.isBlank() || conversationId.isBlank()) return

        if (boundedText.isBlank()) {
            // Text cleared: immediately dismiss typing indicator on peer
            typingDebounceJob?.cancel()
            if (isCurrentlyTypingSent) {
                isCurrentlyTypingSent = false
                chatRepository.sendTypingIndicator(participantNxId, conversationId, false)
            }
            return
        }

        val now = System.currentTimeMillis()
        // First keystroke: immediate typing_start event!
        // Continuous typing: heartbeat pulse dispatched every ~3000ms
        if (!isCurrentlyTypingSent || now - lastTypingSentMs > 3000) {
            isCurrentlyTypingSent = true
            lastTypingSentMs = now
            chatRepository.sendTypingIndicator(participantNxId, conversationId, true)
        }

        // Smart Debounce: 1800ms of inactivity automatically sends typing_stop
        typingDebounceJob?.cancel()
        typingDebounceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1800)
            if (isCurrentlyTypingSent) {
                isCurrentlyTypingSent = false
                chatRepository.sendTypingIndicator(participantNxId, conversationId, false)
            }
        }
    }

    // --- Encrypted SMS fallback (Module 3) ---

    private fun openSmsFallback() {
        val state = _uiState.value
        _uiState.update {
            it.copy(smsFallback = it.smsFallback.copy(isOpen = true, errorMessage = null, statusMessage = null))
        }
        val repo = smsFallbackRepository
        if (repo == null) {
            _uiState.update {
                it.copy(smsFallback = it.smsFallback.copy(errorMessage = "Encrypted SMS fallback is unavailable in this build."))
            }
            return
        }
        val peerNxId = state.participantNxId
        if (peerNxId.isBlank()) return
        viewModelScope.launch {
            try {
                val eligibility = repo.eligibilityFor(
                    peerNxId = peerNxId,
                    conversationId = state.conversationId,
                    destinationPhone = state.destinationPhone.ifBlank { null }
                )
                var status: String? = null
                if (!eligibility.eligible &&
                    eligibility.reason == com.nexa.app.core.sms.SmsBlockReason.SESSION_MISSING &&
                    state.connectionState is ConnectionState.Connected
                ) {
                    // Provisioning is a public-key exchange inside the authenticated E2EE channel.
                    val sent = chatRepository.sendSmsKeyProvisioning(peerNxId)
                    status = if (sent.isSuccess) {
                        "Sent a secure SMS key request. Keep NEXA open on both devices once, then try again."
                    } else {
                        "Encrypted SMS provisioning could not be sent right now."
                    }
                }
                _uiState.update {
                    it.copy(
                        smsFallback = it.smsFallback.copy(eligibility = eligibility, statusMessage = status),
                        smsFallbackState = eligibility.state
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to evaluate encrypted SMS eligibility", e)
                _uiState.update {
                    it.copy(smsFallback = it.smsFallback.copy(errorMessage = "Could not evaluate encrypted SMS availability."))
                }
            }
        }
    }

    private fun prepareSmsFallback() {
        val repo = smsFallbackRepository ?: return
        val state = _uiState.value
        val text = SafeMessageParser.sanitizeInputText(state.inputText.trim())
        if (text.isBlank()) {
            _uiState.update {
                it.copy(smsFallback = it.smsFallback.copy(errorMessage = "Type a text message first. SMS fallback is text only."))
            }
            return
        }
        _uiState.update {
            it.copy(smsFallback = it.smsFallback.copy(isPreparing = true, errorMessage = null, statusMessage = null))
        }
        viewModelScope.launch {
            try {
                when (val outcome = repo.prepareOutbound(
                    peerNxId = state.participantNxId,
                    text = text,
                    destinationPhone = state.destinationPhone.ifBlank { null },
                    requestedConversationId = state.conversationId
                )) {
                    is SmsPrepareOutcome.Ready -> {
                        val plan: SmsOutboundPlan = outcome.plan
                        _uiState.update {
                            it.copy(
                                inputText = "",
                                smsFallback = it.smsFallback.copy(
                                    isPreparing = false,
                                    plan = plan,
                                    segmentCount = plan.segmentCount,
                                    statusMessage = "Packet encrypted locally. Your SMS app will send it."
                                ),
                                smsFallbackState = SmsFallbackState.SMS_READY
                            )
                        }
                    }
                    is SmsPrepareOutcome.Blocked -> _uiState.update {
                        it.copy(
                            smsFallback = it.smsFallback.copy(
                                isPreparing = false,
                                eligibility = outcome.eligibility,
                                errorMessage = outcome.eligibility.userMessage
                            ),
                            smsFallbackState = outcome.eligibility.state
                        )
                    }
                    is SmsPrepareOutcome.Failed -> _uiState.update {
                        it.copy(
                            smsFallback = it.smsFallback.copy(isPreparing = false, errorMessage = outcome.userMessage),
                            smsFallbackState = SmsFallbackState.SMS_FAILED
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare encrypted SMS fallback", e)
                _uiState.update {
                    it.copy(
                        smsFallback = it.smsFallback.copy(isPreparing = false, errorMessage = "Could not prepare the encrypted SMS packet."),
                        smsFallbackState = SmsFallbackState.SMS_FAILED
                    )
                }
            }
        }
    }

    private fun confirmSmsFallbackHandoff() {
        val plan = _uiState.value.smsFallback.plan ?: return
        val repo = smsFallbackRepository ?: return
        _smsComposerRequests.tryEmit(
            SmsComposerRequest(
                destinationNumber = plan.destinationNumber,
                bodyText = plan.bodyText,
                segmentCount = plan.segmentCount
            )
        )
        viewModelScope.launch {
            repo.markHandedOffToSmsApp(plan.packetId)
        }
        _uiState.update {
            it.copy(
                smsFallback = it.smsFallback.copy(
                    statusMessage = "Handed to your SMS app. NEXA cannot confirm whether the carrier sent it."
                ),
                smsFallbackState = SmsFallbackState.SMS_SENDING
            )
        }
    }

    private fun markSmsFallbackSent() {
        val plan = _uiState.value.smsFallback.plan ?: return
        val repo = smsFallbackRepository ?: return
        viewModelScope.launch {
            repo.markSentUnconfirmed(plan.packetId)
        }
        _uiState.update {
            it.copy(
                smsFallback = it.smsFallback.copy(statusMessage = "SMS sent — delivery confirmation unavailable."),
                smsFallbackState = SmsFallbackState.SMS_SENT_UNCONFIRMED
            )
        }
    }

    private fun importSmsPacket() {
        val repo = smsFallbackRepository ?: return
        val state = _uiState.value
        val sender = state.smsFallback.importSender.trim()
        val body = state.smsFallback.importBody.trim()
        if (sender.isBlank() || body.isBlank()) {
            _uiState.update {
                it.copy(smsFallback = it.smsFallback.copy(errorMessage = "Enter the sender number and the NX1 packet."))
            }
            return
        }
        _uiState.update {
            it.copy(smsFallback = it.smsFallback.copy(isImporting = true, errorMessage = null, statusMessage = null))
        }
        viewModelScope.launch {
            try {
                val outcomes = repo.importBody(sender, body)
                val notes = ArrayList<String>(outcomes.size)
                var ackToken: String? = state.smsFallback.ackToken
                var nextState = _uiState.value.smsFallbackState
                outcomes.forEach { outcome ->
                    when (outcome) {
                        is SmsImportOutcome.NotNx1 -> notes.add("No NX1 packet found in that SMS.")
                        is SmsImportOutcome.Incomplete -> notes.add(
                            "Waiting for ${(outcome.totalParts - outcome.receivedParts).coerceAtLeast(1)} more SMS part(s)."
                        )
                        is SmsImportOutcome.Rejected -> notes.add(outcome.userMessage)
                        is SmsImportOutcome.Delivered -> {
                            notes.add(if (outcome.duplicate) "Already imported." else "Decrypted and saved to this conversation.")
                            outcome.ackToken?.let { ackToken = it }
                        }
                        is SmsImportOutcome.AckAccepted -> {
                            notes.add(if (outcome.duplicate) "Delivery ACK already recorded." else "Delivery confirmed by authenticated ACK.")
                            nextState = SmsFallbackState.SMS_DELIVERED_APP_ACK
                        }
                        is SmsImportOutcome.AckRejected -> notes.add("ACK rejected: ${outcome.reason}")
                    }
                }
                _uiState.update {
                    it.copy(
                        smsFallback = it.smsFallback.copy(
                            isImporting = false,
                            importBody = "",
                            ackToken = ackToken,
                            statusMessage = notes.joinToString("\n")
                        ),
                        smsFallbackState = nextState
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import encrypted SMS packet", e)
                _uiState.update {
                    it.copy(smsFallback = it.smsFallback.copy(isImporting = false, errorMessage = "Could not import that SMS."))
                }
            }
        }
    }

    private fun retryFailedMessage(targetTransport: TransportType) {
        val failed = _uiState.value.failedMessageCandidate ?: return
        _uiState.update { it.copy(showSmsFailureSheet = false, failedMessageCandidate = null) }
        NexaTelemetryEngine.recordMessageRetry()

        if (targetTransport == TransportType.DATA_SMS && _uiState.value.destinationPhone.isBlank()) {
            _uiState.update { it.copy(showPhoneDialog = true) }
            return
        }

        viewModelScope.launch {
            val result = chatRepository.retryFailedMessage(
                packetId = failed.packetId,
                transport = targetTransport,
                destinationPhone = _uiState.value.destinationPhone
            )
            result.onFailure { error ->
                NexaTelemetryEngine.recordMessageFailed()
                _uiState.update {
                    it.copy(errorMessage = error.localizedMessage ?: "Failed to retry message")
                }
            }
        }
    }

    private fun saveNickname(nickname: String?) {
        val state = _uiState.value
        val nxId = state.participantNxId
        if (nxId.isBlank()) return

        val sanitized = nickname?.trim()?.takeIf { it.isNotBlank() }
        val resolved = PeerIdentityResolver.resolveIdentityDisplay(
            customNickname = sanitized,
            deviceContactName = state.deviceContactName,
            originalRegisteredName = state.originalProfileName,
            nxId = nxId
        )

        _uiState.update {
            it.copy(
                customNickname = sanitized,
                participantName = resolved.primaryDisplayName,
                showNicknameDialog = false
            )
        }

        viewModelScope.launch {
            contactRepository?.setContactNickname(nxId, sanitized)
        }
    }

    private fun dispatchMessage() {
        val state = _uiState.value
        if (state.isContactBlocked) {
            _uiState.update { it.copy(errorMessage = "Cannot send message to a blocked contact.") }
            return
        }
        val text = SafeMessageParser.sanitizeInputText(state.inputText.trim())
        if (text.isBlank() || state.participantNxId.isBlank()) return

        // If Data SMS mode is selected and destination phone is empty, prompt user for phone
        if (state.selectedTransport == TransportType.DATA_SMS && state.destinationPhone.isBlank()) {
            _uiState.update { it.copy(showPhoneDialog = true) }
            return
        }

        // Message dispatched: dismiss sender typing state immediately
        typingDebounceJob?.cancel()
        if (isCurrentlyTypingSent) {
            isCurrentlyTypingSent = false
            chatRepository.sendTypingIndicator(state.participantNxId, state.conversationId, false)
        }

        val replyTarget = state.replyingToMessage
        _uiState.update { it.copy(inputText = "", replyingToMessage = null) }
        NexaTelemetryEngine.recordMessageSent(state.selectedTransport)

        val convId = state.conversationId
        if (convId.isNotBlank()) {
            draftSaveJob?.cancel()
            viewModelScope.launch {
                chatRepository.clearDraft(convId)
            }
        }

        viewModelScope.launch {
            val result = chatRepository.sendMessage(
                recipientNxId = state.participantNxId,
                text = text,
                transport = state.selectedTransport,
                destinationPhone = state.destinationPhone,
                replyToMessageId = replyTarget?.packetId
            )
            result.onSuccess {
                NexaTelemetryEngine.recordMessageDelivered()
            }.onFailure { error ->
                NexaTelemetryEngine.recordMessageFailed()
                _uiState.update {
                    it.copy(errorMessage = error.localizedMessage ?: "Failed to send message")
                }
            }
        }
    }

    private fun markConversationAsRead() {
        val cid = _uiState.value.conversationId
        if (cid.isNotBlank()) {
            viewModelScope.launch {
                chatRepository.markConversationRead(cid)
            }
        }
    }

    private fun handleVideoSelected(uri: Uri) {
        val repo = mediaProcessingRepository
        if (repo == null) {
            Log.w(TAG, "mediaProcessingRepository is null, skipping video probe")
            return
        }

        viewModelScope.launch {
            val metadata = repo.probeMetadata(uri)
            if (metadata == null) {
                _uiState.update { it.copy(errorMessage = "Unable to read video metadata") }
                return@launch
            }

            val estimatedBytes = VideoMetadataProbe.calculateEstimatedSizeBytes(metadata.durationMs)
            val candidate = VideoCandidate(
                uri = uri,
                metadata = metadata,
                estimatedCompressedBytes = estimatedBytes
            )

            val preference = mediaPrefs?.defaultVideoQuality ?: DefaultVideoQuality.ALWAYS_ASK

            when (preference) {
                DefaultVideoQuality.ALWAYS_ASK -> {
                    _uiState.update {
                        it.copy(
                            pendingVideoCandidate = candidate,
                            showVideoQualityDialog = true
                        )
                    }
                }
                DefaultVideoQuality.COMPRESSED_HD -> {
                    if (repo.shouldCompress(metadata)) {
                        startVideoTranscode(candidate, VideoSendMode.COMPRESSED_HD)
                    } else {
                        // Video is already light (<= 15MB, <= 720p, <= 30 FPS)
                        startVideoTranscode(candidate, VideoSendMode.ORIGINAL_RAW)
                    }
                }
                DefaultVideoQuality.ORIGINAL_RAW -> {
                    startVideoTranscode(candidate, VideoSendMode.ORIGINAL_RAW)
                }
            }
        }
    }

    private fun handleVideoQualitySelected(mode: VideoSendMode, rememberPreference: Boolean) {
        val candidate = _uiState.value.pendingVideoCandidate
        _uiState.update { it.copy(showVideoQualityDialog = false, pendingVideoCandidate = null) }

        if (candidate == null) return

        if (rememberPreference && mediaPrefs != null) {
            mediaPrefs.defaultVideoQuality = when (mode) {
                VideoSendMode.COMPRESSED_HD -> DefaultVideoQuality.COMPRESSED_HD
                VideoSendMode.ORIGINAL_RAW -> DefaultVideoQuality.ORIGINAL_RAW
            }
        }

        val repo = mediaProcessingRepository
        if (repo != null && mode == VideoSendMode.COMPRESSED_HD && !repo.shouldCompress(candidate.metadata)) {
            // Already light enough -> bypass compressor directly
            startVideoTranscode(candidate, VideoSendMode.ORIGINAL_RAW)
        } else {
            startVideoTranscode(candidate, mode)
        }
    }

    private fun startVideoTranscode(candidate: VideoCandidate, mode: VideoSendMode) {
        val repo = mediaProcessingRepository ?: return
        val currentCid = _uiState.value.conversationId
        val currentRecipient = _uiState.value.participantNxId

        transcodeJob?.cancel()
        _uiState.update {
            it.copy(
                transcodeState = TranscodeState(
                    isTranscoding = true,
                    progressPercent = 0,
                    currentUri = candidate.uri
                )
            )
        }

        transcodeJob = viewModelScope.launch {
            try {
                repo.processVideo(candidate.uri, mode).collect { progress ->
                    when (progress) {
                        is CompressionProgress.InProgress -> {
                            _uiState.update {
                                it.copy(
                                    transcodeState = it.transcodeState.copy(
                                        isTranscoding = true,
                                        progressPercent = progress.percent
                                    )
                                )
                            }
                        }
                        is CompressionProgress.Completed -> {
                            _uiState.update {
                                it.copy(transcodeState = TranscodeState(isTranscoding = false))
                            }

                            val outputFile = progress.outputFile
                            val fileName = if (mode == VideoSendMode.COMPRESSED_HD) {
                                "VID_HD_${System.currentTimeMillis()}.mp4"
                            } else {
                                "VID_RAW_${System.currentTimeMillis()}.mp4"
                            }

                            Log.i(
                                TAG,
                                "Video ready for P2P dispatch: ${outputFile.absolutePath} (${outputFile.length()} bytes)"
                            )

                            _videoReadyFlow.emit(
                                VideoReadyForTransfer(
                                    conversationId = currentCid,
                                    recipientNxId = currentRecipient,
                                    file = outputFile,
                                    fileName = fileName,
                                    fileSizeBytes = outputFile.length()
                                )
                            )
                        }
                        is CompressionProgress.Failed -> {
                            Log.e(TAG, "Video transcode failed", progress.exception)
                            _uiState.update {
                                it.copy(
                                    transcodeState = TranscodeState(isTranscoding = false),
                                    errorMessage = "Video optimization failed: ${progress.exception.localizedMessage ?: "Unknown error"}"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in transcode flow", e)
                _uiState.update {
                    it.copy(
                        transcodeState = TranscodeState(isTranscoding = false),
                        errorMessage = "Optimization cancelled or failed: ${e.localizedMessage ?: ""}"
                    )
                }
            }
        }
    }

    private fun cancelVideoTranscode() {
        Log.i(TAG, "Cancelling active video transcode by user request")
        transcodeJob?.cancel()
        transcodeJob = null
        _uiState.update {
            it.copy(
                transcodeState = TranscodeState(isTranscoding = false, progressPercent = 0)
            )
        }
    }

    private fun sendGifMeme(content: String, messageType: MessageType) {
        val state = _uiState.value
        if (state.participantNxId.isBlank()) return
        val replyTarget = state.replyingToMessage
        _uiState.update { it.copy(replyingToMessage = null) }
        viewModelScope.launch {
            chatRepository.sendMediaMessage(
                recipientNxId = state.participantNxId,
                localPath = content,
                messageType = messageType,
                caption = "",
                replyToMessageId = replyTarget?.packetId
            )
        }
    }

    private fun toggleReaction(messageId: String, emoji: String) {
        viewModelScope.launch {
            chatRepository.addReaction(messageId, emoji)
        }
    }

    private fun deleteMessage(message: Message) {
        viewModelScope.launch {
            chatRepository.softDeleteMessage(message.packetId)
        }
    }

    private fun toggleStarMessage(message: Message) {
        viewModelScope.launch {
            chatRepository.starMessage(message.packetId, !message.isStarred)
        }
    }
}
