package com.nexa.app.data.repository

import android.content.Context
import android.net.Uri
import com.nexa.app.core.security.DualScanEngine
import com.nexa.app.core.security.DualScanResult
import com.nexa.app.core.security.FileReleaseClassifier
import com.nexa.app.core.security.FileReleasePolicy
import com.nexa.app.core.security.HashEngine
import com.nexa.app.core.security.ReleaseAuthorization
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

    override suspend fun scanOutboundFile(uri: Uri, declaredExtension: String): DualScanResult {
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
    ): DualScanResult = withContext(Dispatchers.IO) {
        val verdict = sandboxSink.finishAndVerify(
            transferId = transferId,
            expectedHash = expectedHash,
            fileName = fileName,
            declaredExt = declaredExt
        )

        val tempFilePath = sandboxSink.getTempFile(transferId).absolutePath

        when (verdict) {
            is DualScanResult.Clean -> {
                quarantineDao.insertLog(
                    QuarantineEntity(
                        fileHash = expectedHash,
                        fileName = fileName,
                        threatReason = null,
                        status = QuarantineStatus.VERIFIED_CLEAN,
                        quarantinedFilePath = tempFilePath
                    )
                )
            }
            is DualScanResult.Malicious -> {
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
            is DualScanResult.Corrupted -> {
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
            is DualScanResult.Inconclusive -> {
                quarantineDao.insertLog(
                    QuarantineEntity(
                        fileHash = expectedHash,
                        fileName = fileName,
                        threatReason = verdict.reason,
                        status = QuarantineStatus.INCONCLUSIVE,
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

    override suspend fun forceOverrideQuarantine(
        fileHash: String,
        fileName: String,
        authorization: ReleaseAuthorization
    ): File? = withContext(Dispatchers.IO) {
        val log = quarantineDao.getLog(fileHash) ?: return@withContext null
        val cleanScan = log.status == QuarantineStatus.VERIFIED_CLEAN
        val failedScan = log.status == QuarantineStatus.ISOLATED
        if (!cleanScan && !failedScan) return@withContext null

        val sourceFile = File(log.quarantinedFilePath)
        if (!sourceFile.isFile ||
            sourceFile.canonicalFile.parentFile != sandboxSink.quarantineDir.canonicalFile
        ) {
            return@withContext null
        }
        val classification = FileReleaseClassifier.classify(sourceFile, fileName)
        val scanStatus = if (cleanScan) "SCAN_PASSED" else "REJECTED_MALICIOUS"
        val scanResult = if (cleanScan) "SAFE" else "MALICIOUS"
        if (!FileReleasePolicy.isAuthorized(classification, scanStatus, scanResult, authorization)) {
            return@withContext null
        }
        if (!HashEngine.computeSha256(sourceFile).equals(fileHash, ignoreCase = true)) {
            quarantineDao.updateStatus(fileHash, QuarantineStatus.INCONCLUSIVE)
            return@withContext null
        }

        val safeFile = sandboxSink.releaseVerifiedQuarantine(sourceFile, fileHash, fileName)
        if (quarantineDao.updateStatus(fileHash, QuarantineStatus.RELEASED) != 1) {
            safeFile.delete()
            return@withContext null
        }
        if (!FileShredder.shred(sourceFile)) {
            android.util.Log.w("SecurityScannerRepository", "Released file but retained a private quarantine copy")
        }
        safeFile
    }

    override fun getQuarantineLogs(): Flow<List<QuarantineEntity>> {
        return quarantineDao.getAllLogs()
    }
}
