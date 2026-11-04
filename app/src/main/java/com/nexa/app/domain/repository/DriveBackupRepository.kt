package com.nexa.app.domain.repository

import com.nexa.app.core.util.DriveResource
import com.nexa.app.data.drive.DriveFile
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository contract for Zero-Knowledge Google Drive Backup and Multi-Device Restoration.
 */
interface DriveBackupRepository {

    /**
     * Executes atomic snapshot, compression, AES-256-GCM encryption, split-brain check,
     * and 256 KB chunked resumable upload to Google Drive `appDataFolder`.
     */
    fun createAndUploadBackup(passphraseWords: String): Flow<DriveResource<String>>

    /**
     * Queries the hidden `appDataFolder` for an existing backup vault and returns its metadata.
     */
    fun checkRemoteBackup(): Flow<DriveResource<DriveFile?>>

    /**
     * Downloads the remote encrypted vault, verifies the SHA-256 integrity tag, derives key,
     * decrypts, decompresses, and performs an atomic database swap.
     */
    fun downloadAndRestoreBackup(fileId: String, passphraseWords: String): Flow<DriveResource<Boolean>>

    /**
     * Executes an unprompted background backup triggered by WorkManager.
     * Respects Wi-Fi, charging, and split-brain conflict constraints.
     */
    suspend fun performSilentBackup(): Boolean

    /**
     * Returns the user's saved 12-word recovery passphrase if already configured.
     */
    fun getRecoveryPassphrase(): String?

    /**
     * Generates a new BIP-39 12-word mnemonic or retrieves the existing one from Keystore.
     */
    fun generateOrGetRecoveryPassphrase(): String

    /**
     * Securely saves the 12-word recovery passphrase.
     */
    fun saveRecoveryPassphrase(passphrase: String)
}
