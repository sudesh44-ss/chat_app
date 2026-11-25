package com.nexa.app.data.repository

import android.content.Context
import android.util.Log
import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.core.backup.BackupCryptoEngine
import com.nexa.app.core.backup.BackupVaultHeader
import com.nexa.app.core.backup.Bip39PassphraseHelper
import com.nexa.app.core.backup.RestoreEngine
import com.nexa.app.core.backup.RestoreInventory
import com.nexa.app.core.backup.RestoreMode
import com.nexa.app.core.backup.RestorePlan
import com.nexa.app.core.backup.RestoreResultSummary
import com.nexa.app.core.backup.VaultBundlePackager
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.core.util.DriveResource
import com.nexa.app.data.drive.DriveAppDataService
import com.nexa.app.data.drive.DriveFile
import com.nexa.app.data.drive.DriveResumableUploader
import com.nexa.app.data.drive.GoogleDriveAuthManager
import com.nexa.app.data.drive.UploadSessionExpiredException
import com.nexa.app.data.local.NexaDatabase
import com.nexa.app.data.local.preferences.BackupPrefs
import com.nexa.app.data.local.entity.toDomain
import com.nexa.app.domain.model.BackupRestorePoint
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.model.ConversationBackupPolicy
import com.nexa.app.domain.model.ConversationCustomCategories
import com.nexa.app.domain.model.EffectiveChatBackupPolicy
import com.nexa.app.domain.model.RestorePointStatus
import com.nexa.app.domain.repository.DriveBackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Concrete implementation of the Zero-Knowledge Google Drive Cloud Backup Engine,
 * Multi-Point Restoration, and 7-Point Retention Subsystem.
 */
class DriveBackupRepositoryImpl(
    private val context: Context,
    private var database: NexaDatabase,
    private val backupCryptoEngine: BackupCryptoEngine,
    private val driveService: DriveAppDataService,
    private val resumableUploader: DriveResumableUploader,
    private val backupPrefs: BackupPrefs,
    private val secureStorage: SecureStorage,
    private val authManager: GoogleDriveAuthManager
) : DriveBackupRepository {

    private val backupMutex = Mutex()
    private val retentionMutex = Mutex()
    private val restoreEngine = RestoreEngine(context)

    override fun createBackupRestorePoint(
        passphraseWords: String,
        categoryConfig: BackupCategoryConfig,
        isManualBackup: Boolean
    ): Flow<DriveResource<BackupRestorePoint>> = flow {
        emit(DriveResource.Progress("Verifying Google Drive Authorization...", 0.05f))

        if (!categoryConfig.hasAnySelected()) {
            emit(DriveResource.Error("All backup categories are disabled. Cannot create an empty backup."))
            return@flow
        }

        if (!authManager.isAuthorized()) {
            emit(DriveResource.Error("Google Drive is not linked. Please sign in with Google."))
            return@flow
        }

        backupMutex.withLock {
            val localEpoch = System.currentTimeMillis()
            val backupId = BackupRestorePoint.generateBackupId(localEpoch)
            val backupFileName = BackupRestorePoint.getBackupFileName(backupId)

            // 1. Check for active upload session recovery (process death / reboot resilience)
            val activeSession = secureStorage.getActiveUploadSession()
            var recoveredVaultFile: File? = null
            var sessionUriToUse: String? = null
            var epochToUse: Long = localEpoch
            var backupIdToUse: String = backupId
            var backupFileNameToUse: String = backupFileName

            if (activeSession != null && activeSession.isValid()) {
                val candidateFile = File(activeSession.vaultFilePath)
                if (candidateFile.exists() && candidateFile.length() == activeSession.expectedFileSize && candidateFile.length() > 0) {
                    try {
                        emit(DriveResource.Progress("Recovering active upload session...", 0.15f))
                        val confirmedOffset = resumableUploader.queryUploadOffset(activeSession.sessionUri, candidateFile.length())
                        if (confirmedOffset >= 0) {
                            recoveredVaultFile = candidateFile
                            sessionUriToUse = activeSession.sessionUri
                            epochToUse = activeSession.versionEpoch
                            backupIdToUse = activeSession.backupId ?: backupId
                            backupFileNameToUse = candidateFile.name
                            Log.i(TAG, "Successfully recovered active upload session at byte offset $confirmedOffset/${candidateFile.length()}")
                        }
                    } catch (e: UploadSessionExpiredException) {
                        Log.w(TAG, "Previous upload session expired on server, clearing session state", e)
                        secureStorage.clearActiveUploadSession()
                        if (candidateFile.exists()) candidateFile.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to probe existing upload session, will create new snapshot: ${e.message}")
                    }
                } else {
                    secureStorage.clearActiveUploadSession()
                    if (candidateFile.exists()) candidateFile.delete()
                }
            }

            val vaultFile = recoveredVaultFile ?: File(context.cacheDir, backupFileNameToUse)
            var creationResult: com.nexa.app.core.backup.VaultCreationResult? = null

            try {
                if (sessionUriToUse == null) {
                    val dbPassphrase = secureStorage.getOrCreateDatabasePassphrase()
                    emit(DriveResource.Progress("Resolving Per-Chat Backup Policies...", 0.15f))
                    val conversationPolicies = resolveAllEffectivePolicies(categoryConfig, isManualBackup = isManualBackup)

                    emit(DriveResource.Progress("Creating Sanitized Database & Media Vault...", 0.20f))
                    creationResult = backupCryptoEngine.createEncryptedVaultWithResult(
                        rawDatabase = database.getRawDatabase(),
                        passphraseWords = passphraseWords,
                        outputVaultFile = vaultFile,
                        dbPassphrase = dbPassphrase,
                        categoryConfig = categoryConfig,
                        conversationPolicies = conversationPolicies,
                        isManualBackup = isManualBackup,
                        onProgress = { phase, percent ->
                            val remapped = 0.15f + percent * 0.35f
                        }
                    )

                    emit(DriveResource.Progress("Initiating Cloud Upload Session...", 0.50f))
                    val manifest = creationResult.manifest
                    val initialPoint = BackupRestorePoint(
                        backupId = backupIdToUse,
                        createdAt = epochToUse,
                        formatVersion = 2,
                        encryptionVersion = BackupVaultHeader.CURRENT_VERSION,
                        fileName = backupFileNameToUse,
                        driveFileId = "",
                        sizeBytes = vaultFile.length(),
                        totalMessages = manifest?.totalMessages ?: 0,
                        totalConversations = manifest?.totalConversations ?: 0,
                        mediaFilesCount = manifest?.mediaFilesCount ?: 0,
                        categoryConfig = categoryConfig,
                        status = RestorePointStatus.UPLOADING,
                        isLegacy = false,
                        sourceDevice = "Android Device",
                        sha256 = manifest?.databaseSha256
                    )

                    val newSessionUri = driveService.startResumableUploadSession(
                        vaultFile = vaultFile,
                        backupFileName = backupFileNameToUse,
                        appProperties = initialPoint.toAppProperties()
                    )
                    sessionUriToUse = newSessionUri

                    secureStorage.saveActiveUploadSession(
                        sessionUri = newSessionUri,
                        versionEpoch = epochToUse,
                        vaultFilePath = vaultFile.absolutePath,
                        expectedFileSize = vaultFile.length(),
                        lastConfirmedOffset = 0L,
                        backupId = backupIdToUse
                    )
                }

                emit(DriveResource.Progress("Uploading Restore Point to Google Drive...", 0.55f))
                val uploadedFileId = resumableUploader.upload(
                    sessionUri = sessionUriToUse!!,
                    file = vaultFile,
                    onProgress = { uploadedBytes, totalBytes ->
                        val ratio = if (totalBytes > 0) uploadedBytes.toFloat() / totalBytes.toFloat() else 0f
                        val currentPercent = 0.55f + ratio * 0.35f
                    },
                    onChunkUploaded = { confirmedOffset ->
                        secureStorage.updateUploadSessionOffset(confirmedOffset)
                    }
                )

                // 2. Upload Verification Phase
                emit(DriveResource.Progress("Verifying Uploaded Restore Point...", 0.90f))
                val remoteMeta = driveService.getFileMetadata(uploadedFileId)
                if (remoteMeta == null || remoteMeta.size != vaultFile.length() || remoteMeta.size < BackupVaultHeader.HEADER_SIZE) {
                    driveService.updateFileAppProperties(
                        fileId = uploadedFileId,
                        appProperties = mapOf(BackupRestorePoint.KEY_STATUS to RestorePointStatus.FAILED.name)
                    )
                    throw IOException("Remote backup file verification failed: expected size ${vaultFile.length()}, found ${remoteMeta?.size}")
                }

                // Verify header bytes locally from generated file
                FileInputStream(vaultFile).use { fis ->
                    val headerBuf = ByteArray(BackupVaultHeader.HEADER_SIZE)
                    val read = fis.read(headerBuf)
                    if (read < BackupVaultHeader.HEADER_SIZE || BackupVaultHeader.fromByteArray(headerBuf) == null) {
                        driveService.updateFileAppProperties(
                            fileId = uploadedFileId,
                            appProperties = mapOf(BackupRestorePoint.KEY_STATUS to RestorePointStatus.FAILED.name)
                        )
                        throw IOException("Header verification failed: invalid magic or corrupted header bytes")
                    }
                }

                // Mark remote file as VERIFIED
                driveService.updateFileAppProperties(
                    fileId = uploadedFileId,
                    appProperties = mapOf(BackupRestorePoint.KEY_STATUS to RestorePointStatus.VERIFIED.name)
                )

                // 3. Apply 7-Point Retention Policy (Keeps newest 7 verified backups)
                emit(DriveResource.Progress("Applying 7-Point Retention Policy...", 0.95f))
                applyRetentionPolicy()

                // 4. Persist Telemetry & Recovery Passphrase
                secureStorage.clearActiveUploadSession()
                backupPrefs.updateBackupSuccess(
                    fileId = uploadedFileId,
                    size = vaultFile.length(),
                    epoch = epochToUse
                )
                saveRecoveryPassphrase(passphraseWords)

                val manifest = creationResult?.manifest
                val verifiedRestorePoint = BackupRestorePoint(
                    backupId = backupIdToUse,
                    createdAt = epochToUse,
                    formatVersion = 2,
                    encryptionVersion = BackupVaultHeader.CURRENT_VERSION,
                    fileName = backupFileNameToUse,
                    driveFileId = uploadedFileId,
                    sizeBytes = vaultFile.length(),
                    totalMessages = manifest?.totalMessages ?: 0,
                    totalConversations = manifest?.totalConversations ?: 0,
                    mediaFilesCount = manifest?.mediaFilesCount ?: 0,
                    categoryConfig = categoryConfig,
                    status = RestorePointStatus.VERIFIED,
                    isLegacy = false,
                    sourceDevice = "Android Device",
                    sha256 = manifest?.databaseSha256
                )

                emit(DriveResource.Progress("Restore Point Successfully Created & Verified", 1.0f))
                emit(DriveResource.Success(verifiedRestorePoint))
            } catch (e: Exception) {
                if (e is UploadSessionExpiredException) {
                    secureStorage.clearActiveUploadSession()
                }
                emit(DriveResource.Error("Backup failed: ${e.localizedMessage}", e))
            } finally {
                if (secureStorage.getActiveUploadSession() == null && vaultFile.exists()) {
                    vaultFile.delete()
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun createAndUploadBackup(
        passphraseWords: String,
        categoryConfig: BackupCategoryConfig
    ): Flow<DriveResource<String>> = createBackupRestorePoint(passphraseWords, categoryConfig).map { resource ->
        when (resource) {
            is DriveResource.Success -> DriveResource.Success(resource.data.driveFileId)
            is DriveResource.Error -> DriveResource.Error(resource.message, resource.throwable)
            is DriveResource.Progress -> DriveResource.Progress(resource.phase, resource.percent)
            is DriveResource.Idle -> DriveResource.Idle
        }
    }

    override fun getRestorePoints(): Flow<DriveResource<List<BackupRestorePoint>>> = flow {
        emit(DriveResource.Progress("Discovering restore points from Google Drive...", 0.20f))

        if (!authManager.isAuthorized()) {
            emit(DriveResource.Error("Google Drive is not linked. Please sign in with Google."))
            return@flow
        }

        try {
            val points = discoverRestorePoints()
            emit(DriveResource.Success(points))
        } catch (e: Exception) {
            emit(DriveResource.Error("Failed to discover restore points: ${e.localizedMessage}", e))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun discoverRestorePoints(): List<BackupRestorePoint> = withContext(Dispatchers.IO) {
        if (!authManager.isAuthorized()) return@withContext emptyList()
        try {
            val driveFiles = driveService.listBackupFiles()
            driveFiles.map { BackupRestorePoint.fromDriveFile(it) }
                .sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering restore points: ${e.message}", e)
            emptyList()
        }
    }

    override fun checkRemoteBackup(): Flow<DriveResource<DriveFile?>> = flow {
        emit(DriveResource.Progress("Scanning Google Drive appDataFolder...", 0.30f))

        if (!authManager.isAuthorized()) {
            emit(DriveResource.Error("Google Drive is not linked. Please sign in with Google."))
            return@flow
        }

        try {
            // Find newest verified restore point first, with fallback to legacy file
            val allFiles = driveService.listBackupFiles()
            val newestPoint = allFiles
                .map { BackupRestorePoint.fromDriveFile(it) }
                .filter { it.status == RestorePointStatus.VERIFIED }
                .maxByOrNull { it.createdAt }

            val file = if (newestPoint != null) {
                allFiles.firstOrNull { it.id == newestPoint.driveFileId }
            } else {
                driveService.getExistingBackupMetadata()
            }

            emit(DriveResource.Success(file))
        } catch (e: Exception) {
            emit(DriveResource.Error("Failed to locate backup: ${e.localizedMessage}", e))
        }
    }.flowOn(Dispatchers.IO)

    override fun inspectBackup(
        fileId: String,
        passphraseWords: String
    ): Flow<DriveResource<RestoreInventory>> = flow {
        emit(DriveResource.Progress("Preparing Download Session...", 0.05f))

        val remoteMetadata = try {
            driveService.getFileMetadata(fileId) ?: driveService.getExistingBackupMetadata()
        } catch (ignored: Exception) { null }
        val expectedSize = remoteMetadata?.size

        val tempVaultFile = File(context.cacheDir, "nx_inspect_${fileId}.vault")
        val stagingDir = File(context.cacheDir, "restore_staging_${fileId}_${System.currentTimeMillis()}")

        try {
            emit(DriveResource.Progress("Downloading Encrypted Vault...", 0.15f))
            val downloaded = driveService.downloadVault(
                fileId = fileId,
                destinationFile = tempVaultFile,
                expectedTotalBytes = expectedSize,
                expectedEtag = null
            ) { downloadedBytes, totalBytes ->
                val ratio = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes.toFloat() else 0f
                val downloadPercent = 0.15f + ratio * 0.35f
            }

            if (!downloaded || !tempVaultFile.exists() || (expectedSize != null && expectedSize > 0 && tempVaultFile.length() != expectedSize)) {
                emit(DriveResource.Error("Failed to download or verify vault file from Google Drive"))
                return@flow
            }

            emit(DriveResource.Progress("Authenticating & Decrypting Vault...", 0.55f))
            val stagedPackage = backupCryptoEngine.decryptVaultToStaging(
                vaultFile = tempVaultFile,
                passphraseWords = passphraseWords,
                stagingDir = stagingDir,
                onProgress = { phase, percent ->
                    emit(DriveResource.Progress(phase, 0.55f + percent * 0.25f))
                }
            )

            emit(DriveResource.Progress("Analyzing Backup Inventory...", 0.85f))
            val rawDb = runCatching { database.getRawDatabase() }.getOrNull()
            val inventory = restoreEngine.inspectStagedBundle(
                stagingDir = stagingDir,
                liveDatabase = rawDb,
                backupId = fileId,
                unsealedDbPassphrase = stagedPackage.unsealedDbPassphrase,
                onProgress = { phase, percent ->
                    emit(DriveResource.Progress(phase, 0.85f + percent * 0.15f))
                }
            )

            emit(DriveResource.Progress("Inventory Ready", 1.0f))
            emit(DriveResource.Success(inventory))
        } catch (e: Exception) {
            Log.e(TAG, "Failed inspecting backup", e)
            emit(DriveResource.Error("Failed to inspect backup: ${e.localizedMessage}", e))
            if (stagingDir.exists()) stagingDir.deleteRecursively()
        } finally {
            if (tempVaultFile.exists()) tempVaultFile.delete()
        }
    }.flowOn(Dispatchers.IO)

    override fun executeRestorePlan(
        inventory: RestoreInventory,
        plan: RestorePlan
    ): Flow<DriveResource<RestoreResultSummary>> = flow {
        emit(DriveResource.Progress("Preparing Restore Execution...", 0.05f))

        val liveDbFile = context.getDatabasePath(DATABASE_NAME)
        val walFile = File(liveDbFile.path + "-wal")
        val backupLiveDb = File(liveDbFile.parentFile, "$DATABASE_NAME.rollback_bak")
        val backupWal = File(liveDbFile.parentFile, "$DATABASE_NAME-wal.rollback_bak")

        try {
            // Stage safety rollback snapshot of current live database
            if (plan.mode == RestoreMode.REPLACE) {
                emit(DriveResource.Progress("Creating Safety Rollback Snapshot...", 0.10f))
                if (backupLiveDb.exists()) backupLiveDb.delete()
                if (backupWal.exists()) backupWal.delete()
                if (liveDbFile.exists()) liveDbFile.copyTo(backupLiveDb, overwrite = true)
                if (walFile.exists()) walFile.copyTo(backupWal, overwrite = true)
            }

            val rawDb = database.getRawDatabase()
            val summary = restoreEngine.executeRestore(
                inventory = inventory,
                liveDatabase = rawDb,
                plan = plan,
                secureStorage = secureStorage,
                onProgress = { phase, percent ->
                    emit(DriveResource.Progress(phase, 0.15f + percent * 0.80f))
                }
            )

            if (summary.isSuccess) {
                if (backupLiveDb.exists()) backupLiveDb.delete()
                if (backupWal.exists()) backupWal.delete()

                emit(DriveResource.Progress("Restoration Succeeded", 1.0f))
                emit(DriveResource.Success(summary))
            } else {
                emit(DriveResource.Error(summary.errorMessage ?: "Restoration failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Restoration plan execution failed, attempting rollback", e)
            if (plan.mode == RestoreMode.REPLACE && backupLiveDb.exists()) {
                try {
                    NexaDatabase.closeAndResetInstance()
                    backupLiveDb.copyTo(liveDbFile, overwrite = true)
                    backupLiveDb.delete()
                    if (backupWal.exists()) {
                        backupWal.copyTo(walFile, overwrite = true)
                        backupWal.delete()
                    }
                    val dbPassphrase = secureStorage.getOrCreateDatabasePassphrase()
                    database = NexaDatabase.getInstance(context, dbPassphrase)
                } catch (rollbackEx: Exception) {
                    Log.e(TAG, "Rollback failed", rollbackEx)
                }
            }
            emit(DriveResource.Error("Restore execution failed: ${e.localizedMessage}", e))
        } finally {
            if (inventory.stagingDir.exists()) {
                inventory.stagingDir.deleteRecursively()
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun downloadAndRestoreBackup(
        fileId: String,
        passphraseWords: String,
        restoreCategoryConfig: BackupCategoryConfig
    ): Flow<DriveResource<Boolean>> = flow {
        emit(DriveResource.Progress("Preparing Download Session...", 0.05f))

        val remoteMetadata = try {
            driveService.getFileMetadata(fileId) ?: driveService.getExistingBackupMetadata()
        } catch (ignored: Exception) { null }
        val expectedSize = remoteMetadata?.size

        val tempVaultFile = File(context.cacheDir, "nx_download_${fileId}.vault")
        val stagingDir = File(context.cacheDir, "restore_staging_${fileId}_${System.currentTimeMillis()}")

        try {
            emit(DriveResource.Progress("Downloading Encrypted Vault...", 0.15f))
            val downloaded = driveService.downloadVault(
                fileId = fileId,
                destinationFile = tempVaultFile,
                expectedTotalBytes = expectedSize,
                expectedEtag = null
            ) { downloadedBytes, totalBytes ->
                val ratio = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes.toFloat() else 0f
                val downloadPercent = 0.15f + ratio * 0.35f
            }

            if (!downloaded || !tempVaultFile.exists() || (expectedSize != null && expectedSize > 0 && tempVaultFile.length() != expectedSize)) {
                emit(DriveResource.Error("Failed to download or verify vault file from Google Drive"))
                return@flow
            }

            emit(DriveResource.Progress("Verifying Integrity & Decrypting Database...", 0.55f))
            val stagedPackage = backupCryptoEngine.decryptVaultToStaging(
                vaultFile = tempVaultFile,
                passphraseWords = passphraseWords,
                stagingDir = stagingDir,
                onProgress = { phase, percent ->
                    emit(DriveResource.Progress(phase, 0.55f + percent * 0.25f))
                }
            )

            emit(DriveResource.Progress("Analyzing Backup Inventory...", 0.80f))
            val rawDb = database.getRawDatabase()
            val inventory = restoreEngine.inspectStagedBundle(
                stagingDir = stagingDir,
                liveDatabase = rawDb,
                backupId = fileId,
                unsealedDbPassphrase = stagedPackage.unsealedDbPassphrase,
                onProgress = { phase, percent ->
                    emit(DriveResource.Progress(phase, 0.80f + percent * 0.05f))
                }
            )

            val hasLocalChats = try {
                rawDb.query("SELECT COUNT(*) FROM main.chats").use { c ->
                    if (c.moveToFirst()) c.getInt(0) > 0 else false
                }
            } catch (e: Exception) { false }

            val mode = if (hasLocalChats) RestoreMode.MERGE else RestoreMode.REPLACE
            val targetChatIds = inventory.chatItems
                .filter { if (!restoreCategoryConfig.includeGroups) !it.isGroup else true }
                .map { it.conversationId }.toSet()
            val plan = RestorePlan(
                mode = mode,
                selectedCategories = restoreCategoryConfig,
                selectedConversationIds = targetChatIds
            )

            emit(DriveResource.Progress("Applying Restore Plan (${mode.name})...", 0.85f))
            val summary = restoreEngine.executeRestore(
                inventory = inventory,
                liveDatabase = rawDb,
                plan = plan,
                secureStorage = secureStorage,
                onProgress = { phase, percent ->
                    emit(DriveResource.Progress(phase, 0.85f + percent * 0.15f))
                }
            )

            if (summary.isSuccess) {
                saveRecoveryPassphrase(passphraseWords)
                emit(DriveResource.Progress("Database Restored Successfully", 1.0f))
                emit(DriveResource.Success(true))
            } else {
                emit(DriveResource.Error(summary.errorMessage ?: "Restoration failed"))
            }
        } catch (e: Exception) {
            emit(DriveResource.Error("Restore failed: ${e.localizedMessage}", e))
        } finally {
            if (tempVaultFile.exists()) tempVaultFile.delete()
            if (stagingDir.exists()) stagingDir.deleteRecursively()
        }
    }.flowOn(Dispatchers.IO)

    override fun verifyRestorePoint(fileId: String): Flow<DriveResource<Boolean>> = flow {
        emit(DriveResource.Progress("Verifying cloud restore point integrity...", 0.20f))
        try {
            val meta = driveService.getFileMetadata(fileId)
                ?: driveService.getExistingBackupMetadata()?.takeIf { it.id == fileId }

            if (meta == null) {
                emit(DriveResource.Error("Restore point file not found on Google Drive"))
                return@flow
            }
            if (meta.size < BackupVaultHeader.HEADER_SIZE) {
                emit(DriveResource.Error("Restore point file size is invalid (< ${BackupVaultHeader.HEADER_SIZE} bytes header)"))
                return@flow
            }

            // Verify header magic and version via range download
            val tempHeaderFile = File(context.cacheDir, "header_verify_${fileId}.bin")
            try {
                val rangeDownloaded = driveService.downloadVaultRange(
                    fileId = fileId,
                    destinationFile = tempHeaderFile,
                    startByte = 0L,
                    endByte = (BackupVaultHeader.HEADER_SIZE - 1).toLong()
                )
                if (!rangeDownloaded || tempHeaderFile.length() < BackupVaultHeader.HEADER_SIZE) {
                    emit(DriveResource.Error("Failed to read restore point header from cloud"))
                    return@flow
                }
                val headerBytes = tempHeaderFile.readBytes()
                val header = BackupVaultHeader.fromByteArray(headerBytes)
                if (header == null) {
                    emit(DriveResource.Error("Corrupted restore point header: invalid magic or version"))
                    return@flow
                }
            } finally {
                if (tempHeaderFile.exists()) tempHeaderFile.delete()
            }

            emit(DriveResource.Success(true))
        } catch (e: Exception) {
            emit(DriveResource.Error("Integrity verification failed: ${e.localizedMessage}", e))
        }
    }.flowOn(Dispatchers.IO)

    override fun deleteRestorePoint(fileId: String): Flow<DriveResource<Boolean>> = flow {
        emit(DriveResource.Progress("Deleting restore point from Google Drive...", 0.20f))
        try {
            val deleted = driveService.deleteFile(fileId)
            if (deleted) {
                if (backupPrefs.remoteBackupFileId == fileId) {
                    val remaining = discoverRestorePoints().filter {
                        it.status == RestorePointStatus.VERIFIED && it.driveFileId != fileId
                    }
                    val nextNewest = remaining.firstOrNull()
                    backupPrefs.remoteBackupFileId = nextNewest?.driveFileId
                }
                emit(DriveResource.Success(true))
            } else {
                emit(DriveResource.Error("Failed to delete restore point from Google Drive"))
            }
        } catch (e: Exception) {
            emit(DriveResource.Error("Error deleting restore point: ${e.localizedMessage}", e))
        }
    }.flowOn(Dispatchers.IO)

    override fun deleteRemoteBackup(fileId: String): Flow<DriveResource<Boolean>> = deleteRestorePoint(fileId)

    override fun verifyRemoteBackupIntegrity(fileId: String): Flow<DriveResource<Boolean>> = verifyRestorePoint(fileId)

    override suspend fun applyRetentionPolicy(): Int = withContext(Dispatchers.IO) {
        retentionMutex.withLock {
            try {
                val allFiles = driveService.listBackupFiles()
                val points = allFiles.map { BackupRestorePoint.fromDriveFile(it) }
                val verifiedPoints = points
                    .filter { it.status == RestorePointStatus.VERIFIED }
                    .sortedByDescending { it.createdAt }

                var deletedCount = 0
                if (verifiedPoints.size > BackupRestorePoint.MAX_RETENTION_POINTS) {
                    val toDelete = verifiedPoints.drop(BackupRestorePoint.MAX_RETENTION_POINTS)
                    val activeSession = secureStorage.getActiveUploadSession()
                    val activeFileId = activeSession?.existingFileId

                    for (point in toDelete) {
                        if (point.driveFileId == activeFileId) continue
                        val deleted = driveService.deleteFile(point.driveFileId)
                        if (deleted) {
                            deletedCount++
                            Log.i(TAG, "7-Point Retention: purged older restore point ${point.backupId} (${point.driveFileId})")
                        } else {
                            Log.w(TAG, "7-Point Retention: failed to delete file ${point.driveFileId}")
                        }
                    }
                }
                deletedCount
            } catch (e: Exception) {
                Log.e(TAG, "Error applying 7-point retention policy: ${e.message}", e)
                0
            }
        }
    }

    override suspend fun getRestorePointById(fileId: String): BackupRestorePoint? = withContext(Dispatchers.IO) {
        try {
            val driveFile = driveService.getFileMetadata(fileId) ?: return@withContext null
            BackupRestorePoint.fromDriveFile(driveFile)
        } catch (e: Exception) {
            null
        }
    }

    override fun calculateBackupSize(categoryConfig: BackupCategoryConfig): Long {
        return try {
            VaultBundlePackager.calculateCategorySizes(
                context = context,
                rawDatabase = runCatching { database.getRawDatabase() }.getOrNull(),
                categoryConfig = categoryConfig
            ).totalBytes
        } catch (ignored: Exception) {
            0L
        }
    }

    override suspend fun performSilentBackup(): Boolean {
        return performSilentBackup { _, _ -> }
    }

    override suspend fun performSilentBackup(
        onProgress: suspend (phase: String, percent: Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (!backupPrefs.autoBackupEnabled) return@withContext false
        if (backupPrefs.backupMode == com.nexa.app.domain.model.BackupMode.MANUAL_ONLY) return@withContext false

        val passphrase = getActiveBackupCredential() ?: getRecoveryPassphrase() ?: return@withContext false
        if (!authManager.isAuthorized()) return@withContext false

        try {
            var success = false
            onProgress("Preparing automated cloud backup...", 0.05f)
            createBackupRestorePoint(
                passphraseWords = passphrase,
                categoryConfig = backupPrefs.categoryConfig,
                isManualBackup = false
            ).collect { resource ->
                when (resource) {
                    is DriveResource.Progress -> {
                        onProgress(resource.phase, resource.percent)
                    }
                    is DriveResource.Success -> {
                        success = true
                        onProgress("Backup completed successfully", 1.0f)
                    }
                    is DriveResource.Error -> {
                        Log.e(TAG, "Silent background backup error: ${resource.message}")
                    }
                    else -> Unit
                }
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Silent background backup failed: ${e.message}", e)
            false
        }
    }

    override fun getRecoveryPassphrase(): String? {
        return secureStorage.getRecoveryPassphrase()
    }

    override fun generateOrGetRecoveryPassphrase(): String {
        val existing = secureStorage.getRecoveryPassphrase()
        if (!existing.isNullOrBlank()) return existing

        val generated = Bip39PassphraseHelper.generateMnemonic()
        secureStorage.saveRecoveryPassphrase(generated)
        return generated
    }

    override fun saveRecoveryPassphrase(passphrase: String) {
        secureStorage.saveRecoveryPassphrase(passphrase)
    }

    override fun hasCustomBackupPassword(): Boolean {
        return secureStorage.hasCustomBackupPassword()
    }

    override fun setBackupPassword(password: String): Boolean {
        val validation = com.nexa.app.core.backup.BackupPasswordValidator.validate(password, password)
        if (!validation.isValid) return false
        secureStorage.saveCustomBackupPassword(password)
        return true
    }

    override fun verifyBackupPassword(password: String): Boolean {
        return secureStorage.verifyBackupPassword(password)
    }

    override fun changeBackupPassword(currentPasswordOrMnemonic: String, newPassword: String): Boolean {
        val isCurrentPasswordValid = secureStorage.verifyBackupPassword(currentPasswordOrMnemonic)
        val isMnemonicValid = run {
            val savedMnemonic = secureStorage.getRecoveryPassphrase()
            if (savedMnemonic.isNullOrBlank()) false
            else com.nexa.app.core.backup.Bip39PassphraseHelper.normalize(currentPasswordOrMnemonic) ==
                    com.nexa.app.core.backup.Bip39PassphraseHelper.normalize(savedMnemonic)
        }

        if (!isCurrentPasswordValid && !isMnemonicValid) {
            return false
        }

        val validation = com.nexa.app.core.backup.BackupPasswordValidator.validate(newPassword, newPassword)
        if (!validation.isValid) return false

        secureStorage.saveCustomBackupPassword(newPassword)
        return true
    }

    override fun resetBackupPasswordWithMnemonic(mnemonic: String, newPassword: String): Boolean {
        val savedMnemonic = secureStorage.getRecoveryPassphrase() ?: return false
        val normalizedInput = com.nexa.app.core.backup.Bip39PassphraseHelper.normalize(mnemonic)
        val normalizedSaved = com.nexa.app.core.backup.Bip39PassphraseHelper.normalize(savedMnemonic)

        if (!com.nexa.app.core.backup.Bip39PassphraseHelper.validateMnemonic(normalizedInput) || normalizedInput != normalizedSaved) {
            return false
        }

        val validation = com.nexa.app.core.backup.BackupPasswordValidator.validate(newPassword, newPassword)
        if (!validation.isValid) return false

        secureStorage.saveCustomBackupPassword(newPassword)
        return true
    }

    override fun removeBackupPassword() {
        secureStorage.clearCustomBackupPassword()
    }

    override fun getActiveBackupCredential(): String? {
        return secureStorage.getActiveBackupCredential()
    }

    override suspend fun setConversationPriority(conversationId: String, isPriority: Boolean) = withContext(Dispatchers.IO) {
        database.chatDao().setPriorityBackup(conversationId, isPriority)
        val chat = database.chatDao().getChatById(conversationId)
        if (chat != null) {
            val isGroup = chat.chatType.equals("GROUP", ignoreCase = true)
            backupPrefs.setPriorityChat(conversationId, isPriority, isGroup)
        }
    }

    override fun getPriorityConversations(): Flow<List<ChatConversation>> {
        return database.chatDao().getPriorityChatsFlow().map { entities ->
            entities.map { it.toDomain() }
        }.flowOn(Dispatchers.IO)
    }

    override fun getPriorityPersonalConversations(): Flow<List<ChatConversation>> {
        return database.chatDao().getPriorityPersonalChatsFlow().map { entities ->
            entities.map { it.toDomain() }
        }.flowOn(Dispatchers.IO)
    }

    override fun getPriorityGroupConversations(): Flow<List<ChatConversation>> {
        return database.chatDao().getPriorityGroupChatsFlow().map { entities ->
            entities.map { it.toDomain() }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun getPriorityConversationIds(): List<String> = withContext(Dispatchers.IO) {
        database.chatDao().getPriorityChatIds()
    }

    override suspend fun setConversationBackupPolicy(
        conversationId: String,
        policy: ConversationBackupPolicy,
        customCategories: ConversationCustomCategories?
    ) = withContext(Dispatchers.IO) {
        val isPriority = policy == ConversationBackupPolicy.PRIORITY
        database.chatDao().setConversationBackupPolicy(
            conversationId = conversationId,
            policy = policy.name,
            customCategoriesJson = customCategories?.toJson(),
            isPriority = isPriority
        )
        val chat = database.chatDao().getChatById(conversationId)
        if (chat != null) {
            val isGroup = chat.chatType.equals("GROUP", ignoreCase = true)
            backupPrefs.setPriorityChat(conversationId, isPriority, isGroup)
        }
    }

    override fun getAllPersonalConversations(): Flow<List<ChatConversation>> {
        return database.chatDao().getAllPersonalChatsFlow().map { entities ->
            entities.map { it.toDomain() }
        }.flowOn(Dispatchers.IO)
    }

    override fun getAllGroupConversations(): Flow<List<ChatConversation>> {
        return database.chatDao().getAllGroupChatsFlow().map { entities ->
            entities.map { it.toDomain() }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun resolveAllEffectivePolicies(
        categoryConfig: BackupCategoryConfig,
        isManualBackup: Boolean
    ): Map<String, EffectiveChatBackupPolicy> = withContext(Dispatchers.IO) {
        val chats = database.chatDao().getAllChatsForBackup()
        chats.associate { entity ->
            val domain = entity.toDomain()
            domain.conversationId to EffectiveChatBackupPolicy.resolve(
                conversationId = domain.conversationId,
                policy = domain.backupPolicy,
                customCategories = domain.customBackupCategories,
                isPriorityFlag = entity.isPriorityBackup,
                globalConfig = categoryConfig,
                isManualBackup = isManualBackup
            )
        }
    }

    override fun getPriorityChatCountFlow(): Flow<Int> = database.chatDao().getPriorityChatCountFlow()

    override fun getExcludedChatCountFlow(): Flow<Int> = database.chatDao().getExcludedChatCountFlow()

    override fun getCustomPolicyChatCountFlow(): Flow<Int> = database.chatDao().getCustomPolicyChatCountFlow()

    companion object {
        private const val TAG = "DriveBackupRepository"
        private const val DATABASE_NAME = "nexa_encrypted_vault.db"
    }
}
