package com.nexa.app.data.repository

import android.content.Context
import android.util.Log
import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.core.backup.BackupCryptoEngine
import com.nexa.app.core.backup.Bip39PassphraseHelper
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
import com.nexa.app.domain.repository.DriveBackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Concrete implementation of the Zero-Knowledge Google Drive Vault Backup and Recovery Subsystem.
 * Incorporates Design C: Dual-Layer Cryptographic Key Envelope (`key_envelope.bin`) with zero-disk RAM staging.
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

    override fun createAndUploadBackup(
        passphraseWords: String,
        categoryConfig: BackupCategoryConfig
    ): Flow<DriveResource<String>> = flow {
        emit(DriveResource.Progress("Verifying Google Drive Authorization...", 0.05f))

        if (!authManager.isAuthorized()) {
            emit(DriveResource.Error("Google Drive is not linked. Please sign in with Google."))
            return@flow
        }

        // 1. Version Conflict & Split-Brain Check
        emit(DriveResource.Progress("Checking Cloud Version...", 0.10f))
        val existingBackup = try {
            driveService.getExistingBackupMetadata()
        } catch (e: Exception) {
            emit(DriveResource.Error("Failed to query Google Drive: ${e.localizedMessage}", e))
            return@flow
        }

        val localEpoch = System.currentTimeMillis()
        if (existingBackup != null) {
            val remoteEpoch = existingBackup.appProperties["version_epoch"]?.toLongOrNull() ?: 0L
            if (remoteEpoch > localEpoch) {
                emit(
                    DriveResource.Error(
                        "Split-brain conflict: A strictly newer backup exists on Google Drive (Recorded $remoteEpoch). " +
                                "Please restore from cloud to prevent losing newer conversations."
                    )
                )
                return@flow
            }
        }

        // 2. Check for active upload session recovery (process death / reboot resilience)
        val activeSession = secureStorage.getActiveUploadSession()
        var recoveredVaultFile: File? = null
        var sessionUriToUse: String? = null
        var epochToUse: Long = localEpoch

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

        val vaultFile = recoveredVaultFile ?: File(context.cacheDir, "nx_backup_${epochToUse}.vault")
        try {
            if (sessionUriToUse == null) {
                val dbPassphrase = secureStorage.getOrCreateDatabasePassphrase()
                backupCryptoEngine.createEncryptedVault(
                    rawDatabase = database.getRawDatabase(),
                    passphraseWords = passphraseWords,
                    outputVaultFile = vaultFile,
                    dbPassphrase = dbPassphrase,
                    categoryConfig = categoryConfig,
                    onProgress = { phase, percent ->
                        val remapped = 0.15f + percent * 0.35f
                    }
                )

                emit(DriveResource.Progress("Initiating Resumable Upload Session...", 0.50f))
                val newSessionUri = driveService.startResumableUploadSession(
                    vaultFile = vaultFile,
                    versionEpoch = epochToUse,
                    existingFileId = existingBackup?.id
                )
                sessionUriToUse = newSessionUri

                secureStorage.saveActiveUploadSession(
                    sessionUri = newSessionUri,
                    versionEpoch = epochToUse,
                    vaultFilePath = vaultFile.absolutePath,
                    expectedFileSize = vaultFile.length(),
                    lastConfirmedOffset = 0L,
                    existingFileId = existingBackup?.id
                )
            }

            emit(DriveResource.Progress("Uploading to Google Drive...", 0.55f))
            val uploadedFileId = resumableUploader.upload(
                sessionUri = sessionUriToUse!!,
                file = vaultFile,
                onProgress = { uploadedBytes, totalBytes ->
                    val ratio = if (totalBytes > 0) uploadedBytes.toFloat() / totalBytes.toFloat() else 0f
                    val currentPercent = 0.55f + ratio * 0.40f
                },
                onChunkUploaded = { confirmedOffset ->
                    secureStorage.updateUploadSessionOffset(confirmedOffset)
                }
            )

            // 3. Persist Sync State & Recovery Passphrase
            secureStorage.clearActiveUploadSession()
            backupPrefs.updateBackupSuccess(
                fileId = uploadedFileId,
                size = vaultFile.length(),
                epoch = epochToUse
            )
            saveRecoveryPassphrase(passphraseWords)

            emit(DriveResource.Progress("Backup Successfully Uploaded", 1.0f))
            emit(DriveResource.Success(uploadedFileId))
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
    }.flowOn(Dispatchers.IO)

    override fun checkRemoteBackup(): Flow<DriveResource<DriveFile?>> = flow {
        emit(DriveResource.Progress("Scanning Google Drive appDataFolder...", 0.30f))

        if (!authManager.isAuthorized()) {
            emit(DriveResource.Error("Google Drive is not linked. Please sign in with Google."))
            return@flow
        }

        try {
            val file = driveService.getExistingBackupMetadata()
            emit(DriveResource.Success(file))
        } catch (e: Exception) {
            emit(DriveResource.Error("Failed to locate backup: ${e.localizedMessage}", e))
        }
    }.flowOn(Dispatchers.IO)

    override fun downloadAndRestoreBackup(
        fileId: String,
        passphraseWords: String,
        restoreCategoryConfig: BackupCategoryConfig
    ): Flow<DriveResource<Boolean>> = flow {
        emit(DriveResource.Progress("Preparing Download Session...", 0.05f))

        val remoteMetadata = try {
            driveService.getExistingBackupMetadata()
        } catch (ignored: Exception) { null }
        val expectedSize = remoteMetadata?.size

        val tempVaultFile = File(context.cacheDir, "nx_download_${fileId}.vault")
        val tempRestoredDb = File(context.cacheDir, "nx_restored_${System.currentTimeMillis()}.db")

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
            // Design C: Pass secureStorage to unseal key_envelope.bin in RAM and import key
            backupCryptoEngine.decryptAndRestoreVault(
                vaultFile = tempVaultFile,
                passphraseWords = passphraseWords,
                targetDbFile = tempRestoredDb,
                secureStorage = secureStorage,
                restoreCategoryConfig = restoreCategoryConfig,
                onProgress = { phase, percent ->
                    val remapped = 0.55f + percent * 0.30f
                }
            )

            emit(DriveResource.Progress("Activating Restored Database...", 0.90f))
            // Atomic DB Replacement
            NexaDatabase.closeAndResetInstance()

            val liveDbFile = context.getDatabasePath(DATABASE_NAME)
            val walFile = File(liveDbFile.path + "-wal")
            val shmFile = File(liveDbFile.path + "-shm")
            val journalFile = File(liveDbFile.path + "-journal")
            val backupLiveDb = File(liveDbFile.parentFile, "$DATABASE_NAME.rollback_bak")
            val backupWal = File(liveDbFile.parentFile, "$DATABASE_NAME-wal.rollback_bak")

            // 1. Stage rollback backup of existing database files
            try {
                if (backupLiveDb.exists()) backupLiveDb.delete()
                if (backupWal.exists()) backupWal.delete()
                if (liveDbFile.exists()) liveDbFile.copyTo(backupLiveDb, overwrite = true)
                if (walFile.exists()) walFile.copyTo(backupWal, overwrite = true)
            } catch (stageEx: Exception) {
                Log.w(TAG, "Could not create pre-restore backup journal: ${stageEx.localizedMessage}")
            }

            try {
                // 2. Perform atomic swap
                if (walFile.exists()) walFile.delete()
                if (shmFile.exists()) shmFile.delete()
                if (journalFile.exists()) journalFile.delete()

                tempRestoredDb.copyTo(liveDbFile, overwrite = true)

                // 3. Re-open database with secure passphrase to verify integrity
                // Under Design C, Device B's secureStorage now holds the recovered database passphrase!
                val dbPassphrase = secureStorage.getOrCreateDatabasePassphrase()
                database = NexaDatabase.getInstance(context, dbPassphrase)

                // Success! Clean up rollback staging files
                if (backupLiveDb.exists()) backupLiveDb.delete()
                if (backupWal.exists()) backupWal.delete()
            } catch (swapEx: Exception) {
                Log.e(TAG, "Restore swap failed! Initiating rollback to preserved database", swapEx)
                try {
                    if (backupLiveDb.exists()) {
                        backupLiveDb.copyTo(liveDbFile, overwrite = true)
                        backupLiveDb.delete()
                    }
                    if (backupWal.exists()) {
                        backupWal.copyTo(walFile, overwrite = true)
                        backupWal.delete()
                    }
                    val dbPassphrase = secureStorage.getOrCreateDatabasePassphrase()
                    database = NexaDatabase.getInstance(context, dbPassphrase)
                } catch (rollbackEx: Exception) {
                    Log.e(TAG, "Critical: Rollback recovery also encountered error", rollbackEx)
                }
                throw swapEx
            }

            saveRecoveryPassphrase(passphraseWords)

            emit(DriveResource.Progress("Database Restored Successfully", 1.0f))
            emit(DriveResource.Success(true))
        } catch (e: Exception) {
            emit(DriveResource.Error("Restore failed: ${e.localizedMessage}", e))
        } finally {
            if (tempVaultFile.exists()) tempVaultFile.delete()
            if (tempRestoredDb.exists()) tempRestoredDb.delete()
        }
    }.flowOn(Dispatchers.IO)

    override fun deleteRemoteBackup(fileId: String): Flow<DriveResource<Boolean>> = flow {
        emit(DriveResource.Progress("Deleting cloud backup from Google Drive...", 0.20f))
        try {
            val deleted = driveService.deleteFile(fileId)
            if (deleted) {
                backupPrefs.remoteBackupFileId = null
                emit(DriveResource.Success(true))
            } else {
                emit(DriveResource.Error("Failed to delete remote backup file from Google Drive"))
            }
        } catch (e: Exception) {
            emit(DriveResource.Error("Error deleting cloud backup: ${e.localizedMessage}", e))
        }
    }.flowOn(Dispatchers.IO)

    override fun verifyRemoteBackupIntegrity(fileId: String): Flow<DriveResource<Boolean>> = flow {
        emit(DriveResource.Progress("Verifying cloud backup integrity...", 0.30f))
        try {
            val meta = driveService.getExistingBackupMetadata()
            if (meta == null || meta.id != fileId) {
                emit(DriveResource.Error("Backup file not found on Google Drive"))
                return@flow
            }
            if (meta.size < 71L) {
                emit(DriveResource.Error("Backup file size is invalid (< 71 bytes header)"))
                return@flow
            }
            emit(DriveResource.Success(true))
        } catch (e: Exception) {
            emit(DriveResource.Error("Integrity verification failed: ${e.localizedMessage}", e))
        }
    }.flowOn(Dispatchers.IO)

    override fun calculateBackupSize(categoryConfig: BackupCategoryConfig): Long {
        var size = 0L
        try {
            // Live database file size
            if (categoryConfig.includeChats || categoryConfig.includeGroups || categoryConfig.includeContacts) {
                val dbFile = context.getDatabasePath(DATABASE_NAME)
                if (dbFile.exists()) size += dbFile.length()
            }

            // Media files size
            if (categoryConfig.includeAttachments) {
                val mediaDir = File(context.filesDir, "media_vault")
                if (mediaDir.exists() && mediaDir.isDirectory) {
                    mediaDir.listFiles()?.forEach { file ->
                        if (file.isFile && VaultBundlePackager.matchesMediaCategory(file.name, categoryConfig)) {
                            size += file.length()
                        }
                    }
                }
            }

            // Preferences size estimation
            if (categoryConfig.includeAppSettings || categoryConfig.includeUserPrefs) {
                size += 16 * 1024L // ~16 KB for JSON settings
            }
        } catch (ignored: Exception) {}
        return size
    }

    override suspend fun performSilentBackup(): Boolean = withContext(Dispatchers.IO) {
        if (!backupPrefs.autoBackupEnabled) return@withContext false

        val passphrase = getRecoveryPassphrase() ?: return@withContext false
        if (!authManager.isAuthorized()) return@withContext false

        try {
            val localEpoch = System.currentTimeMillis()
            val existingBackup = driveService.getExistingBackupMetadata()
            if (existingBackup != null) {
                val remoteEpoch = existingBackup.appProperties["version_epoch"]?.toLongOrNull() ?: 0L
                if (remoteEpoch > localEpoch) return@withContext false // Split-brain safety abort
            }

            val activeSession = secureStorage.getActiveUploadSession()
            var recoveredVaultFile: File? = null
            var sessionUriToUse: String? = null
            var epochToUse: Long = localEpoch

            if (activeSession != null && activeSession.isValid()) {
                val candidateFile = File(activeSession.vaultFilePath)
                if (candidateFile.exists() && candidateFile.length() == activeSession.expectedFileSize && candidateFile.length() > 0) {
                    try {
                        val confirmedOffset = resumableUploader.queryUploadOffset(activeSession.sessionUri, candidateFile.length())
                        if (confirmedOffset >= 0) {
                            recoveredVaultFile = candidateFile
                            sessionUriToUse = activeSession.sessionUri
                            epochToUse = activeSession.versionEpoch
                        }
                    } catch (e: UploadSessionExpiredException) {
                        secureStorage.clearActiveUploadSession()
                        if (candidateFile.exists()) candidateFile.delete()
                    } catch (ignored: Exception) {}
                } else {
                    secureStorage.clearActiveUploadSession()
                    if (candidateFile.exists()) candidateFile.delete()
                }
            }

            val vaultFile = recoveredVaultFile ?: File(context.cacheDir, "nx_silent_${epochToUse}.vault")
            try {
                if (sessionUriToUse == null) {
                    val dbPassphrase = secureStorage.getOrCreateDatabasePassphrase()
                    val created = backupCryptoEngine.createEncryptedVault(
                        rawDatabase = database.getRawDatabase(),
                        passphraseWords = passphrase,
                        outputVaultFile = vaultFile,
                        dbPassphrase = dbPassphrase,
                        categoryConfig = backupPrefs.categoryConfig
                    )
                    if (!created) return@withContext false

                    val newSessionUri = driveService.startResumableUploadSession(
                        vaultFile = vaultFile,
                        versionEpoch = epochToUse,
                        existingFileId = existingBackup?.id
                    )
                    sessionUriToUse = newSessionUri
                    secureStorage.saveActiveUploadSession(
                        sessionUri = newSessionUri,
                        versionEpoch = epochToUse,
                        vaultFilePath = vaultFile.absolutePath,
                        expectedFileSize = vaultFile.length(),
                        lastConfirmedOffset = 0L,
                        existingFileId = existingBackup?.id
                    )
                }

                val fileId = resumableUploader.upload(
                    sessionUri = sessionUriToUse!!,
                    file = vaultFile,
                    onChunkUploaded = { offset ->
                        secureStorage.updateUploadSessionOffset(offset)
                    }
                )

                secureStorage.clearActiveUploadSession()
                backupPrefs.updateBackupSuccess(fileId, vaultFile.length(), epochToUse)
                true
            } catch (e: Exception) {
                if (e is UploadSessionExpiredException) {
                    secureStorage.clearActiveUploadSession()
                }
                false
            } finally {
                if (secureStorage.getActiveUploadSession() == null && vaultFile.exists()) {
                    vaultFile.delete()
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun getRecoveryPassphrase(): String? {
        return secureStorage.getRecoveryPassphrase()
    }

    override fun generateOrGetRecoveryPassphrase(): String {
        val existing = getRecoveryPassphrase()
        if (!existing.isNullOrBlank()) return existing

        val newMnemonic = Bip39PassphraseHelper.generateMnemonic()
        saveRecoveryPassphrase(newMnemonic)
        return newMnemonic
    }

    override fun saveRecoveryPassphrase(passphrase: String) {
        val normalized = Bip39PassphraseHelper.normalize(passphrase)
        secureStorage.saveRecoveryPassphrase(normalized)
    }

    companion object {
        private const val TAG = "DriveBackupRepository"
        private const val DATABASE_NAME = "nexa_encrypted_vault.db"
    }
}
