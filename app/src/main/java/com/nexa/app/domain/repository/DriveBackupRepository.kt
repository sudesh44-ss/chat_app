package com.nexa.app.domain.repository

import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.core.backup.RestoreInventory
import com.nexa.app.core.backup.RestorePlan
import com.nexa.app.core.backup.RestoreResultSummary
import com.nexa.app.core.util.DriveResource
import com.nexa.app.data.drive.DriveFile
import com.nexa.app.domain.model.BackupRestorePoint
import com.nexa.app.domain.model.ConversationBackupPolicy
import com.nexa.app.domain.model.ConversationCustomCategories
import com.nexa.app.domain.model.EffectiveChatBackupPolicy
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository contract for Zero-Knowledge Google Drive Backup and Multi-Device Restoration.
 */
interface DriveBackupRepository {

    /**
     * Executes atomic snapshot, key envelope sealing, compression, AES-256-GCM encryption,
     * split-brain check, and 256 KB chunked resumable upload to Google Drive `appDataFolder`.
     */
    fun createAndUploadBackup(
        passphraseWords: String,
        categoryConfig: BackupCategoryConfig = BackupCategoryConfig()
    ): Flow<DriveResource<String>>

    /**
     * Executes atomic snapshot, sanitization, key envelope sealing, streaming AES-256-GCM encryption,
     * upload to Google Drive `appDataFolder` with a unique restore point ID, remote verification,
     * and 7-point retention policy enforcement.
     */
    fun createBackupRestorePoint(
        passphraseWords: String,
        categoryConfig: BackupCategoryConfig = BackupCategoryConfig(),
        isManualBackup: Boolean = true
    ): Flow<DriveResource<BackupRestorePoint>> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Discovers all available backup restore points from Google Drive `appDataFolder`,
     * including modern multi-point backups and legacy single-file backups, sorted newest to oldest.
     */
    fun getRestorePoints(): Flow<DriveResource<List<BackupRestorePoint>>> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Synchronously queries and reconstructs all available restore points from Google Drive.
     */
    suspend fun discoverRestorePoints(): List<BackupRestorePoint> = emptyList()

    /**
     * Verifies the remote integrity and structure of a specific restore point.
     */
    fun verifyRestorePoint(fileId: String): Flow<DriveResource<Boolean>> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Deletes a specific restore point from Google Drive.
     */
    fun deleteRestorePoint(fileId: String): Flow<DriveResource<Boolean>> = deleteRemoteBackup(fileId)

    /**
     * Enforces the 7-verified-point retention policy. Keeps the 7 newest verified backups
     * and purges older backups. Returns the count of deleted files.
     */
    suspend fun applyRetentionPolicy(): Int = 0

    /**
     * Retrieves metadata for a specific restore point by Drive file ID.
     */
    suspend fun getRestorePointById(fileId: String): BackupRestorePoint? = null

    /**
     * Queries the hidden `appDataFolder` for an existing backup vault and returns its metadata.
     */
    fun checkRemoteBackup(): Flow<DriveResource<DriveFile?>>

    /**
     * Downloads the remote encrypted vault, verifies the SHA-256 integrity tag, derives key,
     * unseals Key Envelope in RAM, decrypts, and performs an atomic database swap.
     */
    fun downloadAndRestoreBackup(
        fileId: String,
        passphraseWords: String,
        restoreCategoryConfig: BackupCategoryConfig = BackupCategoryConfig()
    ): Flow<DriveResource<Boolean>>

    /**
     * Downloads the remote encrypted vault, verifies integrity, derives key, and extracts
     * a complete [RestoreInventory] for inspection and selective category/chat configuration.
     */
    fun inspectBackup(
        fileId: String,
        passphraseWords: String
    ): Flow<DriveResource<RestoreInventory>> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Executes the configured [RestorePlan] against the live database, performing non-destructive
     * merge or scoped replacement, restoring media and portable settings.
     */
    fun executeRestorePlan(
        inventory: RestoreInventory,
        plan: RestorePlan
    ): Flow<DriveResource<RestoreResultSummary>> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Deletes the cloud backup vault from Google Drive `appDataFolder`.
     */
    fun deleteRemoteBackup(fileId: String): Flow<DriveResource<Boolean>>

    /**
     * Verifies remote backup file integrity and header validity.
     */
    fun verifyRemoteBackupIntegrity(fileId: String): Flow<DriveResource<Boolean>>

    /**
     * Calculates estimated backup payload size based on selected category configuration.
     */
    fun calculateBackupSize(categoryConfig: BackupCategoryConfig): Long

    /**
     * Executes an unprompted background backup triggered by WorkManager.
     * Respects Wi-Fi, charging, and split-brain conflict constraints.
     */
    suspend fun performSilentBackup(): Boolean = performSilentBackup { _, _ -> }

    /**
     * Executes an unprompted background backup with real engine progress reporting.
     */
    suspend fun performSilentBackup(
        onProgress: suspend (phase: String, percent: Float) -> Unit
    ): Boolean = false

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

    // --- Custom Backup Password Foundation (Part 8) ---

    /**
     * Checks if a custom backup password is currently configured.
     */
    fun hasCustomBackupPassword(): Boolean = false

    /**
     * Validates and sets a custom backup password, encrypted in Android Keystore.
     */
    fun setBackupPassword(password: String): Boolean = false

    /**
     * Verifies an entered backup password against the stored Keystore-backed PBKDF2 hash.
     */
    fun verifyBackupPassword(password: String): Boolean = false

    /**
     * Changes the custom backup password by authenticating the current password or recovery mnemonic.
     */
    fun changeBackupPassword(currentPasswordOrMnemonic: String, newPassword: String): Boolean = false

    /**
     * Resets the custom backup password using the verified 12-word recovery mnemonic.
     */
    fun resetBackupPasswordWithMnemonic(mnemonic: String, newPassword: String): Boolean = false

    /**
     * Clears the custom backup password, reverting future backups to use the 12-word recovery mnemonic.
     */
    fun removeBackupPassword() {}

    /**
     * Returns the active credential for backups (custom password if configured, else recovery mnemonic).
     */
    fun getActiveBackupCredential(): String? = null

    // --- Chat Priority Foundation ---

    /**
     * Toggles priority status for a conversation for the Chat Priority backup mode.
     */
    suspend fun setConversationPriority(conversationId: String, isPriority: Boolean) {}

    /**
     * Reactive stream of all priority conversations.
     */
    fun getPriorityConversations(): Flow<List<com.nexa.app.domain.model.ChatConversation>> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Reactive stream of priority personal 1-to-1 conversations.
     */
    fun getPriorityPersonalConversations(): Flow<List<com.nexa.app.domain.model.ChatConversation>> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Reactive stream of priority group conversations.
     */
    fun getPriorityGroupConversations(): Flow<List<com.nexa.app.domain.model.ChatConversation>> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Fetches the list of conversation IDs marked as priority.
     */
    suspend fun getPriorityConversationIds(): List<String> = emptyList()

    /**
     * Sets individual conversation backup policy and optional custom categories.
     */
    suspend fun setConversationBackupPolicy(
        conversationId: String,
        policy: ConversationBackupPolicy,
        customCategories: ConversationCustomCategories? = null
    ) {}

    /**
     * Reactive stream of all personal conversations for priority selection.
     */
    fun getAllPersonalConversations(): Flow<List<com.nexa.app.domain.model.ChatConversation>> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Reactive stream of all group conversations for priority selection.
     */
    fun getAllGroupConversations(): Flow<List<com.nexa.app.domain.model.ChatConversation>> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Resolves all effective per-chat backup policies against the global category configuration.
     */
    suspend fun resolveAllEffectivePolicies(
        categoryConfig: BackupCategoryConfig,
        isManualBackup: Boolean
    ): Map<String, EffectiveChatBackupPolicy> = emptyMap()

    /**
     * Flow emitting count of conversations marked as priority.
     */
    fun getPriorityChatCountFlow(): Flow<Int> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Flow emitting count of conversations excluded from backup.
     */
    fun getExcludedChatCountFlow(): Flow<Int> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Flow emitting count of conversations with custom category policies.
     */
    fun getCustomPolicyChatCountFlow(): Flow<Int> = kotlinx.coroutines.flow.emptyFlow()
}
