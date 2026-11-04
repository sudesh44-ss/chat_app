package com.nexa.app.data.repository

import android.content.Context
import android.net.Uri
import com.nexa.app.core.security.DualScanEngine
import com.nexa.app.core.security.ScanResult
import com.nexa.app.core.util.FileShredder
import com.nexa.app.data.local.dao.QuarantineDao
import com.nexa.app.data.local.entity.QuarantineEntity
import com.nexa.app.data.local.entity.QuarantineStatus
import com.nexa.app.data.remote.transfer.QuarantineSandboxSink
import com.nexa.app.domain.repository.SecurityScannerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Concrete implementation coordinating the on-device Dual-Gate scanning engine,
 * isolated quarantine sink, and Room SQLCipher security audit log.
 */
class SecurityScannerRepositoryImpl(
    private val context: Context,
    private val dualScanEngine: DualScanEngine,
    private val sandboxSink: QuarantineSandboxSink,
    private val quarantineDao: QuarantineDao
) : SecurityScannerRepository {

    override suspend fun scanOutboundFile(uri: Uri, declaredExtension: String): ScanResult {
        return dualScanEngine.scanOutbound(uri, declaredExtension)
    }

    override fun startInboundSink(transferId: String, totalBytes: Long): File {
        return sandboxSink.startSink(transferId, totalBytes)
    }

    override fun writeInboundChunk(transferId: String, chunkData: ByteArray, offset: Long) {
        sandboxSink.writeChunk(transferId, chunkData, offset)
    }

    override suspend fun finalizeInboundFile(
        transferId: String,
        expectedHash: String,
        fileName: String,
        declaredExt: String
    ): ScanResult = withContext(Dispatchers.IO) {
        val verdict = sandboxSink.finishAndVerify(
            transferId = transferId,
            expectedHash = expectedHash,
            fileName = fileName,
            declaredExt = declaredExt
        )

        val tempFilePath = sandboxSink.getTempFile(transferId).absolutePath

        when (verdict) {
            is ScanResult.Clean -> {
                quarantineDao.insertLog(
                    QuarantineEntity(
                        fileHash = expectedHash,
                        fileName = fileName,
                        threatReason = null,
                        status = QuarantineStatus.VERIFIED_CLEAN,
                        quarantinedFilePath = verdict.hash // Safe file path
                    )
                )
            }
            is ScanResult.Malicious -> {
                quarantineDao.insertLog(
                    QuarantineEntity(
                        fileHash = expectedHash,
                        fileName = fileName,
                        threatReason = verdict.reason,
                        status = QuarantineStatus.ISOLATED,
                        quarantinedFilePath = tempFilePath
                    )
                )
            }
            is ScanResult.Corrupted -> {
                quarantineDao.insertLog(
                    QuarantineEntity(
                        fileHash = expectedHash,
                        fileName = fileName,
                        threatReason = verdict.message,
                        status = QuarantineStatus.SHREDDED,
                        quarantinedFilePath = tempFilePath
                    )
                )
            }
        }

        verdict
    }

    override suspend fun shredQuarantinedFile(fileHash: String): Boolean = withContext(Dispatchers.IO) {
        val log = quarantineDao.getLog(fileHash)
        val file = if (log != null) File(log.quarantinedFilePath) else sandboxSink.getTempFile(fileHash)
        val shredded = FileShredder.shred(file)
        quarantineDao.updateStatus(fileHash, QuarantineStatus.SHREDDED)
        shredded
    }

    override suspend fun forceOverrideQuarantine(fileHash: String, fileName: String): File? = withContext(Dispatchers.IO) {
        val safeFile = sandboxSink.forcePromote(fileHash, fileName)
        quarantineDao.updateStatus(fileHash, QuarantineStatus.OVERRIDDEN_SAFE)
        safeFile
    }

    override fun getQuarantineLogs(): Flow<List<QuarantineEntity>> {
        return quarantineDao.getAllLogs()
    }
}
