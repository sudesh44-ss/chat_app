package com.nexa.app.domain.repository

import android.net.Uri
import com.nexa.app.core.security.SecurityVerdict
import com.nexa.app.core.security.ReleaseAuthorization
import com.nexa.app.data.local.entity.FileMetadataEntity
import kotlinx.coroutines.flow.Flow
import java.io.File

interface SecureFileTransferRepository {
    fun observeMetadataForConversation(conversationId: String): Flow<List<FileMetadataEntity>>
    fun observeFileMetadata(fileHash: String): Flow<FileMetadataEntity?>

    suspend fun preDispatchScanAndBroadcast(
        uri: Uri,
        conversationId: String,
        fileName: String,
        fileSizeBytes: Long
    ): Result<FileMetadataEntity>

    suspend fun startOnDemandPull(fileHash: String): Result<Unit>
    suspend fun runGate2QuarantineScan(fileHash: String): SecurityVerdict
    suspend fun releaseQuarantined(fileHash: String, authorization: ReleaseAuthorization): File?
    suspend fun deleteAndShredQuarantine(fileHash: String): Unit
    suspend fun enforceLruCacheLimits(): Long
}
