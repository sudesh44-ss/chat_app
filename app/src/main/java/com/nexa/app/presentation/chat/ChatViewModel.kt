package com.nexa.app.presentation.chat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.core.media.CompressionProgress
import com.nexa.app.core.media.VideoMetadata
import com.nexa.app.core.media.VideoMetadataProbe
import com.nexa.app.core.network.ConnectionState
import com.nexa.app.data.local.preferences.DefaultVideoQuality
import com.nexa.app.data.local.preferences.MediaPrefs
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.TransportType
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.domain.repository.MediaProcessingRepository
import com.nexa.app.domain.repository.VideoSendMode
import kotlinx.coroutines.Job
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
    val isSending: Boolean = false,
    val showPhoneDialog: Boolean = false,
    val errorMessage: String? = null,
    val isPeerTyping: Boolean = false,
    // Video compression & quality selection state
    val pendingVideoCandidate: VideoCandidate? = null,
    val showVideoQualityDialog: Boolean = false,
    val transcodeState: TranscodeState = TranscodeState()
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
    data object ToggleTransport : ChatUiEvent
    data class SetDestinationPhone(val phone: String) : ChatUiEvent
    data class SetPhoneDialogVisible(val visible: Boolean) : ChatUiEvent
    data object SendMessage : ChatUiEvent
    data object MarkAsRead : ChatUiEvent
    data object ClearError : ChatUiEvent

    // Video transcode & dispatch events
    data class OnVideoSelected(val uri: Uri) : ChatUiEvent
    data class OnSelectVideoQuality(val mode: VideoSendMode, val rememberPreference: Boolean) : ChatUiEvent
    data object DismissVideoQualityDialog : ChatUiEvent
    data object CancelVideoTranscode : ChatUiEvent
}

class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val mediaProcessingRepository: MediaProcessingRepository? = null,
    private val mediaPrefs: MediaPrefs? = null
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
    private var transcodeJob: Job? = null
    private var typingJob: Job? = null
    private var typingExpiryJob: Job? = null
    private var lastTypingSentMs: Long = 0L

    init {
        observeConnection()
    }

    fun onEvent(event: ChatUiEvent) {
        when (event) {
            is ChatUiEvent.Init -> initializeConversation(event)
            is ChatUiEvent.OnInputTextChanged -> {
                _uiState.update { it.copy(inputText = event.text) }
                val now = System.currentTimeMillis()
                if (event.text.isNotEmpty() && now - lastTypingSentMs > 2500) {
                    lastTypingSentMs = now
                    val state = _uiState.value
                    if (state.participantNxId.isNotBlank() && state.conversationId.isNotBlank()) {
                        chatRepository.sendTypingIndicator(state.participantNxId, state.conversationId, true)
                    }
                }
            }
            is ChatUiEvent.SetTransport -> _uiState.update { it.copy(selectedTransport = event.transport) }
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

            // Video compression & quality flow
            is ChatUiEvent.OnVideoSelected -> handleVideoSelected(event.uri)
            is ChatUiEvent.OnSelectVideoQuality -> handleVideoQualitySelected(event.mode, event.rememberPreference)
            is ChatUiEvent.DismissVideoQualityDialog -> _uiState.update {
                it.copy(showVideoQualityDialog = false, pendingVideoCandidate = null)
            }
            is ChatUiEvent.CancelVideoTranscode -> cancelVideoTranscode()
        }
    }

    private fun initializeConversation(event: ChatUiEvent.Init) {
        _uiState.update {
            it.copy(
                conversationId = event.conversationId,
                participantNxId = event.participantNxId,
                participantName = event.participantName,
                destinationPhone = event.destinationPhone ?: it.destinationPhone
            )
        }

        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            chatRepository.getMessages(event.conversationId).collect { list ->
                _uiState.update { it.copy(messages = list) }
            }
        }

        observeTyping(event.conversationId)
        markConversationAsRead()
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
                _uiState.update { it.copy(connectionState = state) }
            }
        }
    }

    private fun dispatchMessage() {
        val state = _uiState.value
        val text = state.inputText.trim()
        if (text.isBlank() || state.participantNxId.isBlank()) return

        // If Data SMS mode is selected and destination phone is empty, prompt user for phone
        if (state.selectedTransport == TransportType.DATA_SMS && state.destinationPhone.isBlank()) {
            _uiState.update { it.copy(showPhoneDialog = true) }
            return
        }

        _uiState.update { it.copy(inputText = "") }

        viewModelScope.launch {
            val result = chatRepository.sendMessage(
                recipientNxId = state.participantNxId,
                text = text,
                transport = state.selectedTransport,
                destinationPhone = state.destinationPhone
            )
            result.onFailure { error ->
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
