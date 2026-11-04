package com.nexa.app.domain.repository

import android.net.Uri
import com.nexa.app.data.local.entity.TransferEntity
import com.nexa.app.data.remote.signaling.SignalingMessage
import kotlinx.coroutines.flow.Flow

interface P2PTransferRepository {
    fun observeTransfer(transferId: Long): Flow<TransferEntity?>
    fun observeTransfersForConversation(conversationId: String): Flow<List<TransferEntity>>
    fun observeIncomingOffers(): Flow<SignalingMessage.OfferTransfer>

    suspend fun initiateTransfer(
        conversationId: String,
        recipientNxId: String,
        fileUri: Uri,
        fileName: String,
        fileSizeBytes: Long
    ): Result<Long>

    suspend fun acceptTransfer(transferId: Long): Result<Unit>
    suspend fun declineTransfer(transferId: Long): Result<Unit>
    suspend fun pauseTransfer(transferId: Long): Result<Unit>
    suspend fun resumeTransfer(transferId: Long): Result<Unit>
    suspend fun cancelTransfer(transferId: Long): Result<Unit>
}
