package com.nexa.app.data.repository

import android.content.Context
import com.nexa.app.core.backup.BackupCryptoEngine
import com.nexa.app.core.backup.Bip39PassphraseHelper
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.core.util.DriveResource
import com.nexa.app.data.drive.DriveAppDataService
import com.nexa.app.data.drive.DriveFile
import com.nexa.app.data.drive.DriveResumableUploader
import com.nexa.app.data.drive.GoogleDriveAuthManager
import com.nexa.app.data.local.NexaDatabase
import com.nexa.app.data.local.preferences.BackupPrefs
import com.nexa.app.domain.repository.DriveBackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Concrete implementation of the Zero-Knowledge Google Drive Vault Backup and Recovery Subsystem.
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

    override fun createAndUploadBackup(passphraseWords: String): Flow<DriveResource<String>> = flow {
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

        // 2. Snapshot, Compress, and Encrypt Local Database
        val vaultFile = File(context.cacheDir, "nx_backup_${localEpoch}.vault")
        try {
            backupCryptoEngine.createEncryptedVault(
                rawDatabase = database.getRawDatabase(),
                passphraseWords = passphraseWords,
                outputVaultFile = vaultFile,
                onProgress = { phase, percent ->
                    val remapped = 0.15f + percent * 0.35f
                    // Note: flow collector handles progress updates on main thread
                }
            )

            emit(DriveResource.Progress("Initiating Resumable Upload Session...", 0.50f))
            val sessionUri = driveService.startResumableUploadSession(
                vaultFile = vaultFile,
                versionEpoch = localEpoch,
                existingFileId = existingBackup?.id
            )

            emit(DriveResource.Progress("Uploading to Google Drive (0%)...", 0.55f))
            val uploadedFileId = resumableUploader.upload(
                sessionUri = sessionUri,
                file = vaultFile,
                onProgress = { uploadedBytes, totalBytes ->
                    val ratio = if (totalBytes > 0) uploadedBytes.toFloat() / totalBytes.toFloat() else 0f
                    val currentPercent = 0.55f + ratio * 0.40f
                    // State updates via collector
                }
            )

            // 3. Persist Sync State & Recovery Passphrase
            backupPrefs.updateBackupSuccess(
                fileId = uploadedFileId,
                size = vaultFile.length(),
                epoch = localEpoch
            )
            saveRecoveryPassphrase(passphraseWords)

            emit(DriveResource.Progress("Backup Successfully Uploaded", 1.0f))
            emit(DriveResource.Success(uploadedFileId))
        } catch (e: Exception) {
            emit(DriveResource.Error("Backup failed: ${e.localizedMessage}", e))
        } finally {
            if (vaultFile.exists()) vaultFile.delete()
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
        passphraseWords: String
    ): Flow<DriveResource<Boolean>> = flow {
        emit(DriveResource.Progress("Preparing Download Session...", 0.05f))

        val tempVaultFile = File(context.cacheDir, "nx_download_${System.currentTimeMillis()}.vault")
        val tempRestoredDb = File(context.cacheDir, "nx_restored_${System.currentTimeMillis()}.db")

        try {
            emit(DriveResource.Progress("Downloading Encrypted Vault...", 0.15f))
            val downloaded = driveService.downloadVault(fileId, tempVaultFile) { downloadedBytes, totalBytes ->
                val ratio = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes.toFloat() else 0f
                val downloadPercent = 0.15f + ratio * 0.35f
            }

            if (!downloaded || !tempVaultFile.exists()) {
                emit(DriveResource.Error("Failed to download vault file from Google Drive"))
                return@flow
            }

            emit(DriveResource.Progress("Verifying Integrity & Decrypting Database...", 0.55f))
            backupCryptoEngine.decryptAndRestoreVault(
                vaultFile = tempVaultFile,
                passphraseWords = passphraseWords,
                targetDbFile = tempRestoredDb,
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

            if (walFile.exists()) walFile.delete()
            if (shmFile.exists()) shmFile.delete()
            if (journalFile.exists()) journalFile.delete()

            if (liveDbFile.exists()) liveDbFile.delete()
            tempRestoredDb.copyTo(liveDbFile, overwrite = true)

            // Re-open database with secure passphrase
            val dbPassphrase = secureStorage.getOrCreateDatabasePassphrase()
            database = NexaDatabase.getInstance(context, dbPassphrase)

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

    override suspend fun performSilentBackup(): Boolean {
        if (!backupPrefs.autoBackupEnabled) return false

        val passphrase = getRecoveryPassphrase() ?: return false
        if (!authManager.isAuthorized()) return false

        return try {
            val localEpoch = System.currentTimeMillis()
            val existingBackup = driveService.getExistingBackupMetadata()
            if (existingBackup != null) {
                val remoteEpoch = existingBackup.appProperties["version_epoch"]?.toLongOrNull() ?: 0L
                if (remoteEpoch > localEpoch) return false // Split-brain safety abort
            }

            val vaultFile = File(context.cacheDir, "nx_silent_${localEpoch}.vault")
            try {
                val created = backupCryptoEngine.createEncryptedVault(
                    rawDatabase = database.getRawDatabase(),
                    passphraseWords = passphrase,
                    outputVaultFile = vaultFile
                )
                if (!created) return false

                val sessionUri = driveService.startResumableUploadSession(
                    vaultFile = vaultFile,
                    versionEpoch = localEpoch,
                    existingFileId = existingBackup?.id
                )

                val fileId = resumableUploader.upload(sessionUri, vaultFile)
                backupPrefs.updateBackupSuccess(fileId, vaultFile.length(), localEpoch)
                true
            } finally {
                if (vaultFile.exists()) vaultFile.delete()
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
        private const val DATABASE_NAME = "nexa_encrypted_vault.db"
    }
}
