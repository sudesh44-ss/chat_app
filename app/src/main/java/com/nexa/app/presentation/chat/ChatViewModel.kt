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
import com.nexa.app.domain.model.Message
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
    val selectedMessageForAction: Message? = null
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
}

class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val mediaProcessingRepository: MediaProcessingRepository? = null,
    private val mediaPrefs: MediaPrefs? = null,
    private val chatPrivacyPrefs: ChatPrivacyPrefs? = null,
    private val contactRepository: ContactRepository? = null
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _videoReadyFlow = MutableSharedFlow<VideoReadyForTransfer>(extraBufferCapacity = 8)
    val videoReadyFlow: SharedFlow<VideoReadyForTransfer> = _videoReadyFlow.asSharedFlow()

    private var messagesJob: Job? = null
    private var connectionJob: Job? = null
    private var transportHandoffJob: Job? = null
    private var transcodeJob: Job? = null
    private var typingJob: Job? = null
    private var typingExpiryJob: Job? = null
    private var typingDebounceJob: Job? = null
    private var pinnedMessagesJob: Job? = null
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
        }
    }

    private fun initializeConversation(event: ChatUiEvent.Init) {
        _uiState.update {
            it.copy(
                conversationId = event.conversationId,
                participantNxId = event.participantNxId,
                participantName = event.participantName,
                originalProfileName = event.participantName,
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
        markConversationAsRead()
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

    private fun observeConnection() {
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            chatRepository.getConnectionState().collect { state ->
                val isConnected = state is ConnectionState.Connected
                val presenceMode = chatPrivacyPrefs?.onlinePresenceMode ?: OnlinePrivacyMode.EVERYONE
                transportHandoffJob?.cancel()

                if (isConnected) {
                    // Instant Internet Binding (Priority 1): Zero BLE radio contention
                    _uiState.update { current ->
                        val updatedTransport = if (current.selectedRouteMode == TransportRouteMode.AUTO) {
                            TransportType.INTERNET
                        } else {
                            current.selectedTransport
                        }
                        current.copy(
                            connectionState = state,
                            selectedTransport = updatedTransport,
                            isPeerOnline = if (presenceMode != OnlinePrivacyMode.NOBODY) true else false
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

        _uiState.update { it.copy(inputText = "") }
        NexaTelemetryEngine.recordMessageSent(state.selectedTransport)

        viewModelScope.launch {
            val result = chatRepository.sendMessage(
                recipientNxId = state.participantNxId,
                text = text,
                transport = state.selectedTransport,
                destinationPhone = state.destinationPhone
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
}
