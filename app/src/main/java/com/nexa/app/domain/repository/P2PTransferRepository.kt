package com.nexa.app.domain.repository

import android.net.Uri
import com.nexa.app.data.local.entity.TransferEntity
import com.nexa.app.data.remote.signaling.SignalingMessage
import java.io.File
import kotlinx.coroutines.flow.Flow
import com.nexa.app.core.security.ReleaseAuthorization

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

    suspend fun shredTransferQuarantine(transferId: Long): Result<Unit>
    suspend fun releaseTransfer(transferId: Long, authorization: ReleaseAuthorization): Result<File>
    suspend fun getVerifiedSafeFile(transferId: Long): File?
}
