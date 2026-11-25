package com.nexa.app.domain.model

import org.json.JSONObject

/**
 * Granular category toggles for a conversation with [ConversationBackupPolicy.CUSTOM].
 * Allows selective backup of specific content types for an individual chat.
 */
data class ConversationCustomCategories(
    val includeMessages: Boolean = true,
    val includeImages: Boolean = true,
    val includeVideos: Boolean = true,
    val includeDocuments: Boolean = true,
    val includeAudio: Boolean = true,
    val includeOtherFiles: Boolean = true
) {
    fun hasAnySelected(): Boolean {
        return includeMessages || includeImages || includeVideos || includeDocuments || includeAudio || includeOtherFiles
    }

    fun hasAnyMediaSelected(): Boolean {
        return includeImages || includeVideos || includeDocuments || includeAudio || includeOtherFiles
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("includeMessages", includeMessages)
            put("includeImages", includeImages)
            put("includeVideos", includeVideos)
            put("includeDocuments", includeDocuments)
            put("includeAudio", includeAudio)
            put("includeOtherFiles", includeOtherFiles)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String?): ConversationCustomCategories {
            if (jsonStr.isNullOrBlank()) return ConversationCustomCategories()
            return try {
                val obj = JSONObject(jsonStr)
                ConversationCustomCategories(
                    includeMessages = obj.optBoolean("includeMessages", true),
                    includeImages = obj.optBoolean("includeImages", true),
                    includeVideos = obj.optBoolean("includeVideos", true),
                    includeDocuments = obj.optBoolean("includeDocuments", true),
                    includeAudio = obj.optBoolean("includeAudio", true),
                    includeOtherFiles = obj.optBoolean("includeOtherFiles", true)
                )
            } catch (e: Exception) {
                ConversationCustomCategories()
            }
        }
    }
}
