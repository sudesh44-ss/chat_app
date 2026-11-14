package com.nexa.app.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nexa.app.core.security.MagicByteDetector
import com.nexa.app.core.security.MalwareBloomFilter
import com.nexa.app.core.security.ArchiveScanVerdict
import com.nexa.app.core.security.ArchiveScanner
import com.nexa.app.core.security.FileClassification
import com.nexa.app.core.security.FileReleaseClassifier
import com.nexa.app.core.security.FileReleasePolicy
import com.nexa.app.core.security.ReleaseAuthorization
import com.nexa.app.core.security.FileSecurityManager
import com.nexa.app.core.security.SecurityVerdict
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.core.security.YaraScannerBridge
import com.nexa.app.core.swarm.SwarmConstants
import com.nexa.app.data.local.dao.SwarmCacheDao
import com.nexa.app.data.local.entity.FileMetadataEntity
import com.nexa.app.data.local.entity.FileStatus
import com.nexa.app.data.remote.swarm.P2PSwarmCoordinator
import com.nexa.app.data.remote.swarm.QuarantineSandbox
import com.nexa.app.domain.repository.SecureFileTransferRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class SecureFileTransferRepositoryImpl(
    private val context: Context,
    private val swarmCacheDao: SwarmCacheDao,
    private val quarantineSandbox: QuarantineSandbox,
    private val swarmCoordinator: P2PSwarmCoordinator,
    private val secureStorage: SecureStorage,
    private val malwareBloomFilter: MalwareBloomFilter = MalwareBloomFilter(),
    private val yaraScannerBridge: YaraScannerBridge = YaraScannerBridge()
) : SecureFileTransferRepository {

    override fun observeMetadataForConversation(conversationId: String): Flow<List<FileMetadataEntity>> {
        return swarmCacheDao.observeForConversation(conversationId)
    }

    override fun observeFileMetadata(fileHash: String): Flow<FileMetadataEntity?> {
        return swarmCacheDao.observeByHash(fileHash)
    }

    override suspend fun preDispatchScanAndBroadcast(
        uri: Uri,
        conversationId: String,
        fileName: String,
        fileSizeBytes: Long
    ): Result<FileMetadataEntity> = withContext(Dispatchers.IO) {
        try {
            val extension = fileName.substringAfterLast('.', "")

            // 1. Gate 1: Magic Byte Inspection
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return@withContext Result.failure(IllegalStateException("Cannot open file descriptor"))
            val fis = FileInputStream(pfd.fileDescriptor)
            val verdict = MagicByteDetector.inspect(fis, extension)
            fis.close()
            pfd.close()

            if (verdict != SecurityVerdict.CLEAN) {
                return@withContext Result.failure(
                    SecurityException("Gate 1 Threat Blocked: File flagged as $verdict")
                )
            }

            // 2. Calculate Blake3 / SHA-256 Hash
            val fileHash = computeFileHash(uri)

            // 3. Known Threat Bloom Filter Check
            if (malwareBloomFilter.containsHash(fileHash)) {
                return@withContext Result.failure(
                    SecurityException("Gate 1 Threat Blocked: Hash matches known malicious database")
                )
            }

            // 4. Generate & Broadcast Metadata Envelope (~1 KB, 0 bytes file payload)
            val myNxId = secureStorage.getActiveNxId() ?: "NX-ANONYMOUS"
            val totalChunks = ((fileSizeBytes + SwarmConstants.CHUNK_SIZE - 1) / SwarmConstants.CHUNK_SIZE).toInt()

            val entity = FileMetadataEntity(
                fileHash = fileHash,
                conversationId = conversationId,
                senderNxId = myNxId,
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                totalChunks = totalChunks,
                blurHash = null,
                status = FileStatus.VERIFIED_SAFE, // Sender holds the original file
                localFilePath = uri.toString(),
                lastAccessedTimestamp = System.currentTimeMillis()
            )

            swarmCacheDao.upsertMetadata(entity)
            swarmCoordinator.broadcastMetadata(entity)
            swarmCoordinator.advertiseSeeding(fileHash, myNxId, totalChunks)

            Log.i(TAG, "Gate 1 Clean! Broadcasted metadata envelope for $fileName ($fileHash)")
            Result.success(entity)
        } catch (e: Exception) {
            Log.e(TAG, "Error in Gate 1 Pre-Dispatch scan", e)
            Result.failure(e)
        }
    }

    override suspend fun startOnDemandPull(fileHash: String): Result<Unit> = withContext(Dispatchers.IO) {
        val metadata = swarmCacheDao.getByHash(fileHash)
            ?: return@withContext Result.failure(IllegalArgumentException("Metadata not found for hash $fileHash"))
        if (!fileHash.matches(Regex("(?i)[0-9a-f]{64}")) ||
            metadata.fileSizeBytes !in 1..FileSecurityManager.MAX_FILE_SIZE_BYTES
        ) {
            return@withContext Result.failure(SecurityException("Invalid file transfer metadata"))
        }

        val myNxId = secureStorage.getActiveNxId() ?: "NX-ANONYMOUS"

        // 1. Transition state to DOWNLOADING
        swarmCacheDao.updateStatus(fileHash, FileStatus.DOWNLOADING)

        // 2. Allocate sparse temp storage in sandbox
        val raf = quarantineSandbox.allocateTempStorage(fileHash, metadata.fileSizeBytes)
        raf.close()

        // 3. Query active seeders from swarm
        swarmCoordinator.querySeeders(fileHash, myNxId)
        Log.i(TAG, "Initiated on-demand swarm pull for $fileHash (${metadata.fileName})")

        Result.success(Unit)
    }

    override suspend fun runGate2QuarantineScan(fileHash: String): SecurityVerdict = withContext(Dispatchers.IO) {
        val metadata = swarmCacheDao.getByHash(fileHash) ?: return@withContext SecurityVerdict.DANGEROUS_EXECUTABLE
        val tempFile = quarantineSandbox.getTempFile(fileHash)

        if (!tempFile.isFile || tempFile.length() != metadata.fileSizeBytes) {
            return@withContext SecurityVerdict.DANGEROUS_EXECUTABLE
        }

        swarmCacheDao.updateStatus(fileHash, FileStatus.IN_QUARANTINE)

        // 1. Verify Full Hash (Tamper/Corruption Check)
        val calculatedHash = computeDiskFileHash(tempFile)
        if (!calculatedHash.equals(fileHash, ignoreCase = true)) {
            Log.w(TAG, "Gate 2 Failed: Hash mismatch! Expected: $fileHash, Got: $calculatedHash")
            swarmCacheDao.updateStatus(fileHash, FileStatus.QUARANTINED_THREAT)
            return@withContext SecurityVerdict.SPOOFED_EXTENSION
        }

        val classification = FileReleaseClassifier.classify(tempFile, metadata.fileName)
        val extension = metadata.fileName.substringAfterLast('.', "").lowercase()
        when (classification) {
            FileClassification.APK -> {
                if (extension != "apk" || ArchiveScanner.scanAndroidPackage(tempFile) is ArchiveScanVerdict.Blocked) {
                    swarmCacheDao.updateStatus(fileHash, FileStatus.QUARANTINED_THREAT, tempFile.absolutePath)
                    return@withContext SecurityVerdict.DANGEROUS_EXECUTABLE
                }
            }
            FileClassification.EXECUTABLE -> {
                yaraScannerBridge.scanFile(tempFile)
                swarmCacheDao.updateStatus(fileHash, FileStatus.QUARANTINED_THREAT, tempFile.absolutePath)
                return@withContext SecurityVerdict.DANGEROUS_EXECUTABLE
            }
            FileClassification.UNSUPPORTED -> {
                swarmCacheDao.updateStatus(fileHash, FileStatus.SCAN_INCONCLUSIVE, tempFile.absolutePath)
                return@withContext SecurityVerdict.SPOOFED_EXTENSION
            }
            FileClassification.NORMAL -> {
                val magicVerdict = FileInputStream(tempFile).use { MagicByteDetector.inspect(it, extension) }
                if (magicVerdict != SecurityVerdict.CLEAN) {
                    Log.w(TAG, "Gate 2 rejected file type or signature")
                    swarmCacheDao.updateStatus(fileHash, FileStatus.QUARANTINED_THREAT, tempFile.absolutePath)
                    return@withContext magicVerdict
                }
                if (extension == "zip" && ArchiveScanner.scanArchive(tempFile) is ArchiveScanVerdict.Blocked) {
                    swarmCacheDao.updateStatus(fileHash, FileStatus.QUARANTINED_THREAT, tempFile.absolutePath)
                    return@withContext SecurityVerdict.DANGEROUS_EXECUTABLE
                }
            }
        }

        // 3. LibYARA Heuristic Pattern Scan
        val yaraResult = yaraScannerBridge.scanFile(tempFile)
        if (!yaraResult.isClean) {
            Log.w(TAG, "Gate 2 Failed: YARA rule matched: ${yaraResult.ruleName}")
            swarmCacheDao.updateStatus(fileHash, FileStatus.QUARANTINED_THREAT, tempFile.absolutePath)
            return@withContext SecurityVerdict.DANGEROUS_EXECUTABLE
        }

        swarmCacheDao.updateStatus(fileHash, FileStatus.SCAN_PASSED, tempFile.absolutePath)
        Log.i(TAG, "Receiver scan passed for file hash ${fileHash.take(12)}; file remains quarantined pending release")
        SecurityVerdict.CLEAN
    }

    override suspend fun releaseQuarantined(
        fileHash: String,
        authorization: ReleaseAuthorization
    ): File? = withContext(Dispatchers.IO) {
        val metadata = swarmCacheDao.getByHash(fileHash) ?: return@withContext null
        val tempFile = quarantineSandbox.getTempFile(fileHash)
        if (!tempFile.isFile) return@withContext null

        val classification = FileReleaseClassifier.classify(tempFile, metadata.fileName)
        val scanStatus = when (metadata.status) {
            FileStatus.SCAN_PASSED -> "SCAN_PASSED"
            FileStatus.QUARANTINED_THREAT -> "REJECTED_MALICIOUS"
            else -> return@withContext null
        }
        val scanResult = if (scanStatus == "SCAN_PASSED") "SAFE" else "MALICIOUS"
        if (!FileReleasePolicy.isAuthorized(classification, scanStatus, scanResult, authorization)) {
            return@withContext null
        }
        if (computeDiskFileHash(tempFile).equals(fileHash, ignoreCase = true).not()) {
            swarmCacheDao.updateStatus(fileHash, FileStatus.SCAN_INCONCLUSIVE, tempFile.absolutePath)
            return@withContext null
        }

        enforceLruCacheLimits()
        val safeFile = quarantineSandbox.promoteToSafeStorage(fileHash, metadata.fileName)
        val releasedStatus = if (scanStatus == "SCAN_PASSED") {
            FileStatus.VERIFIED_SAFE
        } else {
            FileStatus.OVERRIDDEN_RELEASED
        }
        if (swarmCacheDao.updateStatus(fileHash, releasedStatus, safeFile.absolutePath) != 1) {
            safeFile.delete()
            return@withContext null
        }
        try {
            quarantineSandbox.shredQuarantine(fileHash)
        } catch (e: Exception) {
            Log.w(TAG, "Released file but retained a private quarantine copy")
        }
        if (releasedStatus == FileStatus.VERIFIED_SAFE) {
            val myNxId = secureStorage.getActiveNxId() ?: "NX-ANONYMOUS"
            swarmCoordinator.advertiseSeeding(fileHash, myNxId, metadata.totalChunks)
        }
        safeFile
    }

    override suspend fun deleteAndShredQuarantine(fileHash: String): Unit = withContext(Dispatchers.IO) {
        quarantineSandbox.shredQuarantine(fileHash)
        swarmCacheDao.updateStatus(fileHash, FileStatus.METADATA_ONLY, null)
    }

    override suspend fun enforceLruCacheLimits(): Long = withContext(Dispatchers.IO) {
        var currentVaultBytes = quarantineSandbox.calculateSafeVaultSizeBytes()
        if (currentVaultBytes > SwarmConstants.MAX_CACHE_BYTES) {
            Log.i(TAG, "LRU limit reached ($currentVaultBytes bytes > 2GB). Pruning unpinned seeder files...")
            val oldest = swarmCacheDao.getSeedingFilesOldestFirst()
            for (fileMeta in oldest) {
                if (currentVaultBytes <= SwarmConstants.PRUNE_TARGET_BYTES) break
                val path = fileMeta.localFilePath
                if (path != null) {
                    val deleted = quarantineSandbox.deleteSafeFile(path)
                    if (deleted) {
                        currentVaultBytes -= fileMeta.fileSizeBytes
                        swarmCacheDao.updateStatus(fileMeta.fileHash, FileStatus.METADATA_ONLY, null)
                        Log.d(TAG, "Pruned cached seeder file: ${fileMeta.fileName} (chat card retained)")
                    }
                }
            }
        }
        currentVaultBytes
    }

    private fun computeFileHash(uri: Uri): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            val fis = FileInputStream(pfd?.fileDescriptor)
            val buffer = ByteArray(16384)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
            fis.close()
            pfd?.close()
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "fast_hash_${System.currentTimeMillis()}"
        }
    }

    private fun computeDiskFileHash(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val fis = FileInputStream(file)
        val buffer = ByteArray(16384)
        var read: Int
        while (fis.read(buffer).also { read = it } != -1) {
            md.update(buffer, 0, read)
        }
        fis.close()
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "SecureFileTransferRepo"
    }
}
