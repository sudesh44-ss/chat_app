package com.nexa.app.core.backup

import org.json.JSONObject

/**
 * Granular user-controlled category configuration for selective backup and restore.
 */
data class BackupCategoryConfig(
    // Communication Data
    val includeChats: Boolean = true,
    val includeGroups: Boolean = true,
    val includeContacts: Boolean = true,
    val includeAttachments: Boolean = true,

    // Media & Files
    val includeImages: Boolean = true,
    val includeVideos: Boolean = true,
    val includeDocuments: Boolean = true,
    val includeAudio: Boolean = true,
    val includeOtherFiles: Boolean = true,

    // Application Data
    val includeBookmarks: Boolean = true,
    val includeUserPrefs: Boolean = true,
    val includeAppSettings: Boolean = true,
    val includeDeviceSettings: Boolean = false // Off by default to preserve device-specific settings
) {
    fun hasAnySelected(): Boolean {
        return includeChats || includeGroups || includeContacts || includeAttachments ||
                includeImages || includeVideos || includeDocuments || includeAudio || includeOtherFiles ||
                includeBookmarks || includeUserPrefs || includeAppSettings || includeDeviceSettings
    }

    fun hasAnyMediaSelected(): Boolean {
        return includeAttachments && (includeImages || includeVideos || includeDocuments || includeAudio || includeOtherFiles)
    }

    fun toJson(): String {
        return toJsonObject().toString()
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("include_chats", includeChats)
            put("include_groups", includeGroups)
            put("include_contacts", includeContacts)
            put("include_attachments", includeAttachments)
            put("include_images", includeImages)
            put("include_videos", includeVideos)
            put("include_documents", includeDocuments)
            put("include_audio", includeAudio)
            put("include_other_files", includeOtherFiles)
            put("include_bookmarks", includeBookmarks)
            put("include_user_prefs", includeUserPrefs)
            put("include_app_settings", includeAppSettings)
            put("include_device_settings", includeDeviceSettings)
        }
    }

    companion object {
        fun allEnabled(): BackupCategoryConfig = BackupCategoryConfig(
            includeChats = true,
            includeGroups = true,
            includeContacts = true,
            includeAttachments = true,
            includeImages = true,
            includeVideos = true,
            includeDocuments = true,
            includeAudio = true,
            includeOtherFiles = true,
            includeBookmarks = true,
            includeUserPrefs = true,
            includeAppSettings = true,
            includeDeviceSettings = true
        )

        fun allDisabled(): BackupCategoryConfig = BackupCategoryConfig(
            includeChats = false,
            includeGroups = false,
            includeContacts = false,
            includeAttachments = false,
            includeImages = false,
            includeVideos = false,
            includeDocuments = false,
            includeAudio = false,
            includeOtherFiles = false,
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
            return BackupCategoryConfig(
                includeChats = json.optBoolean("include_chats", true),
                includeGroups = json.optBoolean("include_groups", true),
                includeContacts = json.optBoolean("include_contacts", true),
                includeAttachments = json.optBoolean("include_attachments", true),
                includeImages = json.optBoolean("include_images", true),
                includeVideos = json.optBoolean("include_videos", true),
                includeDocuments = json.optBoolean("include_documents", true),
                includeAudio = json.optBoolean("include_audio", true),
                includeOtherFiles = json.optBoolean("include_other_files", true),
                includeBookmarks = json.optBoolean("include_bookmarks", true),
                includeUserPrefs = json.optBoolean("include_user_prefs", true),
                includeAppSettings = json.optBoolean("include_app_settings", true),
                includeDeviceSettings = json.optBoolean("include_device_settings", false)
            )
        }
    }
}
