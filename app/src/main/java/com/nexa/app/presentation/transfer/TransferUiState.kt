package com.nexa.app.presentation.transfer

import android.net.Uri
import com.nexa.app.core.security.SecurityScanResult
import com.nexa.app.data.local.entity.TransferEntity
import com.nexa.app.data.remote.signaling.SignalingMessage
import com.nexa.app.core.security.ReleaseAuthorization

data class PendingFilePreview(
    val conversationId: String,
    val recipientNxId: String,
    val uri: Uri,
    val fileName: String,
    val fileSizeBytes: Long,
    val isScanning: Boolean = true,
    val scanVerdict: SecurityScanResult? = null
)

data class TransferUiState(
    val conversationTransfers: List<TransferEntity> = emptyList(),
    val incomingOffer: SignalingMessage.OfferTransfer? = null,
    val transferSpeeds: Map<Long, Double> = emptyMap(), // transferId -> MB/s
    val transferEtas: Map<Long, Long> = emptyMap(),      // transferId -> ETA in seconds
    val errorMessage: String? = null,
    val pendingPreview: PendingFilePreview? = null
)

sealed interface TransferUiEvent {
    data class InitiateTransfer(
        val conversationId: String,
        val recipientNxId: String,
        val uri: Uri,
        val fileName: String,
        val fileSizeBytes: Long
    ) : TransferUiEvent

    data class SelectFileForPreview(
        val conversationId: String,
        val recipientNxId: String,
        val uri: Uri,
        val fileName: String,
        val fileSizeBytes: Long
    ) : TransferUiEvent

    data object ConfirmSendPreview : TransferUiEvent
    data object DismissPreview : TransferUiEvent

    data class AcceptOffer(val transferId: Long) : TransferUiEvent
    data class DeclineOffer(val transferId: Long) : TransferUiEvent
    data class ReleaseTransfer(
        val transferId: Long,
        val authorization: ReleaseAuthorization
    ) : TransferUiEvent
    data class PauseTransfer(val transferId: Long) : TransferUiEvent
    data class ResumeTransfer(val transferId: Long) : TransferUiEvent
    data class CancelTransfer(val transferId: Long) : TransferUiEvent
    data class ShredQuarantine(val transferId: Long) : TransferUiEvent
    data object ClearIncomingOffer : TransferUiEvent
    data object ClearError : TransferUiEvent
}
