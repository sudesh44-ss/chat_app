package com.nexa.app.presentation.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.core.webrtc.FileChunk
import com.nexa.app.data.local.entity.TransferEntity
import com.nexa.app.domain.repository.P2PTransferRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TransferViewModel(
    private val p2pTransferRepository: P2PTransferRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    private var transfersJob: Job? = null
    private var offersJob: Job? = null

    // Tracking for speed and ETA calculation
    private val lastChunkMap = mutableMapOf<Long, Int>()
    private val lastTimestampMap = mutableMapOf<Long, Long>()

    init {
        observeIncomingOffers()
    }

    fun bindConversation(conversationId: String) {
        transfersJob?.cancel()
        transfersJob = viewModelScope.launch {
            p2pTransferRepository.observeTransfersForConversation(conversationId).collect { list ->
                calculateSpeedsAndEtas(list)
                _uiState.update { it.copy(conversationTransfers = list) }
            }
        }
    }

    fun onEvent(event: TransferUiEvent) {
        when (event) {
            is TransferUiEvent.InitiateTransfer -> {
                viewModelScope.launch {
                    val result = p2pTransferRepository.initiateTransfer(
                        conversationId = event.conversationId,
                        recipientNxId = event.recipientNxId,
                        fileUri = event.uri,
                        fileName = event.fileName,
                        fileSizeBytes = event.fileSizeBytes
                    )
                    result.onFailure { error ->
                        _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "Failed to initiate file transfer") }
                    }
                }
            }

            is TransferUiEvent.AcceptOffer -> {
                viewModelScope.launch {
                    val result = p2pTransferRepository.acceptTransfer(event.transferId)
                    result.onSuccess {
                        _uiState.update { it.copy(incomingOffer = null) }
                    }.onFailure { error ->
                        _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "Failed to accept transfer") }
                    }
                }
            }

            is TransferUiEvent.DeclineOffer -> {
                viewModelScope.launch {
                    p2pTransferRepository.declineTransfer(event.transferId)
                    _uiState.update { it.copy(incomingOffer = null) }
                }
            }

            is TransferUiEvent.PauseTransfer -> {
                viewModelScope.launch {
                    p2pTransferRepository.pauseTransfer(event.transferId)
                }
            }

            is TransferUiEvent.ResumeTransfer -> {
                viewModelScope.launch {
                    val result = p2pTransferRepository.resumeTransfer(event.transferId)
                    result.onFailure { error ->
                        _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "Failed to resume transfer") }
                    }
                }
            }

            is TransferUiEvent.CancelTransfer -> {
                viewModelScope.launch {
                    p2pTransferRepository.cancelTransfer(event.transferId)
                }
            }

            is TransferUiEvent.ClearIncomingOffer -> {
                _uiState.update { it.copy(incomingOffer = null) }
            }

            is TransferUiEvent.ClearError -> {
                _uiState.update { it.copy(errorMessage = null) }
            }
        }
    }

    private fun observeIncomingOffers() {
        offersJob?.cancel()
        offersJob = viewModelScope.launch {
            p2pTransferRepository.observeIncomingOffers().collect { offer ->
                _uiState.update { it.copy(incomingOffer = offer) }
            }
        }
    }

    private fun calculateSpeedsAndEtas(transfers: List<TransferEntity>) {
        val now = System.currentTimeMillis()
        val speeds = _uiState.value.transferSpeeds.toMutableMap()
        val etas = _uiState.value.transferEtas.toMutableMap()

        for (transfer in transfers) {
            val transferId = transfer.transferId
            if (transfer.status == "TRANSFERRING") {
                val lastChunk = lastChunkMap[transferId] ?: transfer.chunksCompleted
                val lastTime = lastTimestampMap[transferId] ?: now

                val deltaChunks = transfer.chunksCompleted - lastChunk
                val deltaTimeMs = now - lastTime

                if (deltaTimeMs >= 1000 && deltaChunks > 0) {
                    val bytesTransferred = deltaChunks.toLong() * FileChunk.DEFAULT_CHUNK_SIZE
                    val speedBytesPerSec = (bytesTransferred.toDouble() / (deltaTimeMs.toDouble() / 1000.0))
                    val speedMBps = speedBytesPerSec / (1024.0 * 1024.0)

                    val remainingChunks = (transfer.totalChunks - transfer.chunksCompleted).coerceAtLeast(0)
                    val remainingBytes = remainingChunks.toLong() * FileChunk.DEFAULT_CHUNK_SIZE
                    val etaSec = if (speedBytesPerSec > 0) (remainingBytes / speedBytesPerSec).toLong() else 0L

                    speeds[transferId] = speedMBps
                    etas[transferId] = etaSec

                    lastChunkMap[transferId] = transfer.chunksCompleted
                    lastTimestampMap[transferId] = now
                }
            } else {
                speeds.remove(transferId)
                etas.remove(transferId)
                lastChunkMap.remove(transferId)
                lastTimestampMap.remove(transferId)
            }
        }

        _uiState.update {
            it.copy(
                transferSpeeds = speeds,
                transferEtas = etas
            )
        }
    }
}
