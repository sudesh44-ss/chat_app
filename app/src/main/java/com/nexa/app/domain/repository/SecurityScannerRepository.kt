package com.nexa.app.domain.repository

import android.net.Uri
import com.nexa.app.core.security.ScanResult
import com.nexa.app.data.local.entity.QuarantineEntity
import kotlinx.coroutines.flow.Flow
import java.io.File

interface SecurityScannerRepository {
    suspend fun scanOutboundFile(uri: Uri, declaredExtension: String): ScanResult
    fun startInboundSink(transferId: String, totalBytes: Long): File
    fun writeInboundChunk(transferId: String, chunkData: ByteArray, offset: Long)
    suspend fun finalizeInboundFile(
        transferId: String,
        expectedHash: String,
        fileName: String,
        declaredExt: String
    ): ScanResult
    suspend fun shredQuarantinedFile(fileHash: String): Boolean
    suspend fun forceOverrideQuarantine(fileHash: String, fileName: String): File?
    fun getQuarantineLogs(): Flow<List<QuarantineEntity>>
}
