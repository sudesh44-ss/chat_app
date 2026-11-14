package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persists emoji reactions per message.
 * Enforces per-user uniqueness on (messageId, userNxId).
 */
@Entity(
    tableName = "message_reactions",
    indices = [
        Index(value = ["messageId", "userNxId"], unique = true),
        Index(value = ["messageId"])
    ]
)
data class MessageReactionEntity(
    @PrimaryKey
    val reactionId: String,
    val messageId: String,
    val userNxId: String,
    val reaction: String,
    val createdAt: Long = System.currentTimeMillis()
)
