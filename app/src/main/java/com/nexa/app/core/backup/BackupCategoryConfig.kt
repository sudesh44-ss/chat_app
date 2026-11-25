package com.nexa.app.core.backup

import com.nexa.app.domain.model.BackupCategory
import org.json.JSONObject

/**
 * Granular user-controlled category configuration for selective backup and restore.
 * Supports the 7 final official backup categories while preserving complete backward
 * compatibility with legacy 13-flag backups and metadata manifests.
 */
data class BackupCategoryConfig(
    // 7 Core Official Categories
    val includeChats: Boolean = true,
    val includeImages: Boolean = true,
    val includeVideos: Boolean = true,
    val includeDocuments: Boolean = true,
    val includeAudio: Boolean = true,
    val includeOtherFiles: Boolean = true,
    val includeSettings: Boolean = true,

    // Legacy mapped properties (with defaults derived from core categories)
    val includeGroups: Boolean = includeChats,
    val includeContacts: Boolean = includeChats,
    val includeAttachments: Boolean = includeImages || includeVideos || includeDocuments || includeAudio || includeOtherFiles,
    val includeBookmarks: Boolean = includeSettings,
    val includeUserPrefs: Boolean = includeSettings,
    val includeAppSettings: Boolean = includeSettings,
    val includeDeviceSettings: Boolean = false // Preserved off by default
) {
    fun hasAnySelected(): Boolean {
        return includeChats || includeImages || includeVideos || includeDocuments ||
                includeAudio || includeOtherFiles || includeSettings
    }

    fun hasAnyMediaSelected(): Boolean {
        return includeImages || includeVideos || includeDocuments || includeAudio || includeOtherFiles
    }

    fun isCategoryEnabled(category: BackupCategory): Boolean {
        return when (category) {
            BackupCategory.CHATS -> includeChats
            BackupCategory.IMAGES -> includeImages
            BackupCategory.VIDEOS -> includeVideos
            BackupCategory.DOCUMENTS -> includeDocuments
            BackupCategory.AUDIO -> includeAudio
            BackupCategory.OTHER_FILES -> includeOtherFiles
            BackupCategory.SETTINGS -> includeSettings
        }
    }

    fun withCategory(category: BackupCategory, enabled: Boolean): BackupCategoryConfig {
        return when (category) {
            BackupCategory.CHATS -> copy(
                includeChats = enabled,
                includeGroups = enabled,
                includeContacts = enabled
            )
            BackupCategory.IMAGES -> copy(includeImages = enabled)
            BackupCategory.VIDEOS -> copy(includeVideos = enabled)
            BackupCategory.DOCUMENTS -> copy(includeDocuments = enabled)
            BackupCategory.AUDIO -> copy(includeAudio = enabled)
            BackupCategory.OTHER_FILES -> copy(includeOtherFiles = enabled)
            BackupCategory.SETTINGS -> copy(
                includeSettings = enabled,
                includeAppSettings = enabled,
                includeUserPrefs = enabled,
                includeBookmarks = enabled
            )
        }
    }

    fun selectAll(): BackupCategoryConfig = allEnabled()
    fun selectNone(): BackupCategoryConfig = allDisabled()

    fun toJson(): String {
        return toJsonObject().toString()
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            // 7 Official Primary Keys
            put("include_chats", includeChats)
            put("include_images", includeImages)
            put("include_videos", includeVideos)
            put("include_documents", includeDocuments)
            put("include_audio", includeAudio)
            put("include_other_files", includeOtherFiles)
            put("include_settings", includeSettings)

            // Legacy keys for backward compatibility
            put("include_groups", includeGroups)
            put("include_contacts", includeContacts)
            put("include_attachments", includeAttachments)
            put("include_bookmarks", includeBookmarks)
            put("include_user_prefs", includeUserPrefs)
            put("include_app_settings", includeAppSettings)
            put("include_device_settings", includeDeviceSettings)
        }
    }

    companion object {
        fun allEnabled(): BackupCategoryConfig = BackupCategoryConfig(
            includeChats = true,
            includeImages = true,
            includeVideos = true,
            includeDocuments = true,
            includeAudio = true,
            includeOtherFiles = true,
            includeSettings = true,
            includeGroups = true,
            includeContacts = true,
            includeAttachments = true,
            includeBookmarks = true,
            includeUserPrefs = true,
            includeAppSettings = true,
            includeDeviceSettings = false
        )

        fun allDisabled(): BackupCategoryConfig = BackupCategoryConfig(
            includeChats = false,
            includeImages = false,
            includeVideos = false,
            includeDocuments = false,
            includeAudio = false,
            includeOtherFiles = false,
            includeSettings = false,
            includeGroups = false,
            includeContacts = false,
            includeAttachments = false,
            includeBookmarks = false,
            includeUserPrefs = false,
            includeAppSettings = false,
            includeDeviceSettings = false
        )

        fun fromJson(jsonStr: String?): BackupCategoryConfig {
            if (jsonStr.isNullOrBlank()) return BackupCategoryConfig()
            return try {
                val json = JSONObject(jsonStr)
                fromJsonObject(json)
            } catch (e: Exception) {
                BackupCategoryConfig()
            }
        }

        fun fromJsonObject(json: JSONObject): BackupCategoryConfig {
            // Read 7 primary keys if present, otherwise fallback to legacy keys
            val chats = json.optBoolean("include_chats", true)
            val images = json.optBoolean("include_images", true)
            val videos = json.optBoolean("include_videos", true)
            val docs = json.optBoolean("include_documents", true)
            val audio = json.optBoolean("include_audio", true)
            val otherFiles = json.optBoolean("include_other_files", true)
            val settings = if (json.has("include_settings")) {
                json.optBoolean("include_settings", true)
            } else {
                json.optBoolean("include_app_settings", true) || json.optBoolean("include_user_prefs", true)
            }

            return BackupCategoryConfig(
                includeChats = chats,
                includeImages = images,
                includeVideos = videos,
                includeDocuments = docs,
                includeAudio = audio,
                includeOtherFiles = otherFiles,
                includeSettings = settings,

                includeGroups = json.optBoolean("include_groups", chats),
                includeContacts = json.optBoolean("include_contacts", chats),
                includeAttachments = json.optBoolean("include_attachments", images || videos || docs || audio || otherFiles),
                includeBookmarks = json.optBoolean("include_bookmarks", settings),
                includeUserPrefs = json.optBoolean("include_user_prefs", settings),
                includeAppSettings = json.optBoolean("include_app_settings", settings),
                includeDeviceSettings = json.optBoolean("include_device_settings", false)
            )
        }
    }
}
