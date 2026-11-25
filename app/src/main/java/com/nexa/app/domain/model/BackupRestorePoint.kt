package com.nexa.app.domain.model

import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.data.drive.DriveFile
import org.json.JSONObject

/**
 * Status of an individual backup restore point in Google Drive.
 */
enum class RestorePointStatus {
    PREPARING,
    UPLOADING,
    VERIFYING,
    VERIFIED,
    FAILED,
    INCOMPLETE
}

/**
 * Immutable domain model representing a verified, independent backup restore point
 * stored inside Google Drive's isolated `appDataFolder`.
 */
data class BackupRestorePoint(
    val backupId: String,
    val createdAt: Long,
    val formatVersion: Int = 2,
    val encryptionVersion: Int = 2,
    val fileName: String,
    val driveFileId: String,
    val sizeBytes: Long,
    val totalMessages: Int = 0,
    val totalConversations: Int = 0,
    val mediaFilesCount: Int = 0,
    val categoryConfig: BackupCategoryConfig = BackupCategoryConfig(),
    val status: RestorePointStatus = RestorePointStatus.VERIFIED,
    val isLegacy: Boolean = false,
    val sourceDevice: String = "Android Device",
    val label: String? = null,
    val sha256: String? = null
) {
    /**
     * Converts restore point metadata into Google Drive `appProperties` key-value pairs.
     * Guaranteed ZERO PII (no message texts, contacts, phone numbers, or credentials).
     */
    fun toAppProperties(): Map<String, String> {
        val map = mutableMapOf(
            KEY_BACKUP_ID to backupId,
            KEY_CREATED_AT to createdAt.toString(),
            KEY_FORMAT_VERSION to formatVersion.toString(),
            KEY_ENCRYPTION_VERSION to encryptionVersion.toString(),
            KEY_STATUS to status.name,
            KEY_TOTAL_MESSAGES to totalMessages.toString(),
            KEY_TOTAL_CONVERSATIONS to totalConversations.toString(),
            KEY_MEDIA_FILES_COUNT to mediaFilesCount.toString(),
            KEY_CATEGORY_CONFIG to categoryConfig.toJson(),
            KEY_SOURCE_DEVICE to sourceDevice,
            KEY_IS_LEGACY to isLegacy.toString(),
            KEY_VERSION_EPOCH to createdAt.toString()
        )
        if (!label.isNullOrBlank()) {
            map[KEY_LABEL] = label
        }
        if (!sha256.isNullOrBlank()) {
            map[KEY_SHA256] = sha256
        }
        return map
    }

    companion object {
        const val KEY_BACKUP_ID = "backup_id"
        const val KEY_CREATED_AT = "created_at"
        const val KEY_VERSION_EPOCH = "version_epoch"
        const val KEY_FORMAT_VERSION = "format_version"
        const val KEY_ENCRYPTION_VERSION = "encryption_version"
        const val KEY_STATUS = "status"
        const val KEY_TOTAL_MESSAGES = "total_messages"
        const val KEY_TOTAL_CONVERSATIONS = "total_conversations"
        const val KEY_MEDIA_FILES_COUNT = "media_files_count"
        const val KEY_CATEGORY_CONFIG = "category_config"
        const val KEY_SOURCE_DEVICE = "source_device"
        const val KEY_IS_LEGACY = "is_legacy"
        const val KEY_LABEL = "label"
        const val KEY_SHA256 = "sha256"

        const val BACKUP_FILE_PREFIX = "nexa_backup_"
        const val BACKUP_FILE_SUFFIX = ".vault"
        const val LEGACY_BACKUP_FILE_NAME = "nx_secure_backup.vault"
        const val MAX_RETENTION_POINTS = 7

        /**
         * Generates a unique, collision-safe, privacy-preserving backup ID.
         * Example: "bp_1732380000000_a1b2c3d4"
         */
        fun generateBackupId(epoch: Long = System.currentTimeMillis()): String {
            val randomSuffix = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            return "bp_${epoch}_$randomSuffix"
        }

        /**
         * Resolves the remote Google Drive filename for a given backup ID.
         */
        fun getBackupFileName(backupId: String): String {
            return "${BACKUP_FILE_PREFIX}${backupId}${BACKUP_FILE_SUFFIX}"
        }

        /**
         * Reconstructs a [BackupRestorePoint] from a remote [DriveFile] discovered in `appDataFolder`.
         * Transparently supports both modern multi-point backups and legacy single-file backups.
         */
        fun fromDriveFile(driveFile: DriveFile): BackupRestorePoint {
            val props = driveFile.appProperties
            val isLegacy = driveFile.name == LEGACY_BACKUP_FILE_NAME || props[KEY_IS_LEGACY] == "true"
            val backupId = props[KEY_BACKUP_ID]
                ?: if (isLegacy) {
                    "legacy_backup_${props[KEY_VERSION_EPOCH] ?: driveFile.id.take(8)}"
                } else {
                    driveFile.name.removePrefix(BACKUP_FILE_PREFIX).removeSuffix(BACKUP_FILE_SUFFIX)
                }

            val epoch = props[KEY_CREATED_AT]?.toLongOrNull()
                ?: props[KEY_VERSION_EPOCH]?.toLongOrNull()
                ?: System.currentTimeMillis()

            val formatVer = props[KEY_FORMAT_VERSION]?.toIntOrNull() ?: if (isLegacy) 1 else 2
            val encVer = props[KEY_ENCRYPTION_VERSION]?.toIntOrNull() ?: if (isLegacy) 1 else 2

            val statusStr = props[KEY_STATUS]
            val status = when {
                !statusStr.isNullOrBlank() -> {
                    try {
                        RestorePointStatus.valueOf(statusStr.trim().uppercase())
                    } catch (e: Exception) {
                        RestorePointStatus.VERIFIED
                    }
                }
                driveFile.size >= 71L -> RestorePointStatus.VERIFIED
                else -> RestorePointStatus.INCOMPLETE
            }

            val msgs = props[KEY_TOTAL_MESSAGES]?.toIntOrNull() ?: 0
            val convs = props[KEY_TOTAL_CONVERSATIONS]?.toIntOrNull() ?: 0
            val media = props[KEY_MEDIA_FILES_COUNT]?.toIntOrNull() ?: 0
            val catConfig = BackupCategoryConfig.fromJson(props[KEY_CATEGORY_CONFIG])
            val dev = props[KEY_SOURCE_DEVICE] ?: "Android Device"
            val label = props[KEY_LABEL]
            val sha = props[KEY_SHA256]

            return BackupRestorePoint(
                backupId = backupId,
                createdAt = epoch,
                formatVersion = formatVer,
                encryptionVersion = encVer,
                fileName = driveFile.name,
                driveFileId = driveFile.id,
                sizeBytes = driveFile.size,
                totalMessages = msgs,
                totalConversations = convs,
                mediaFilesCount = media,
                categoryConfig = catConfig,
                status = status,
                isLegacy = isLegacy,
                sourceDevice = dev,
                label = label,
                sha256 = sha
            )
        }
    }
}
