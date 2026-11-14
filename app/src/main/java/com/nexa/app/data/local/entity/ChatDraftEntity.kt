package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists unsent conversation drafts locally in SQLCipher.
 * Drafts persist across process termination and configuration changes.
 */
@Entity(tableName = "chat_drafts")
data class ChatDraftEntity(
    @PrimaryKey
    val conversationId: String,
    val draftText: String,
    val attachmentDraftReference: String? = null,
    val replyToMessageId: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
