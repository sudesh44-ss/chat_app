package com.nexa.app.domain.model

/**
 * The 7 official final backup categories supported by NEXA Backup & Restore.
 */
enum class BackupCategory(val id: String, val displayName: String) {
    CHATS("chats", "Chats & Conversations"),
    IMAGES("images", "Images & Photos"),
    VIDEOS("videos", "Videos"),
    DOCUMENTS("documents", "Documents"),
    AUDIO("audio", "Audio & Voice Notes"),
    OTHER_FILES("other_files", "Other Files"),
    SETTINGS("settings", "Settings & App Data");

    companion object {
        fun fromId(id: String?): BackupCategory? {
            if (id.isNullOrBlank()) return null
            return values().firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
        }
    }
}
