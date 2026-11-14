package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks members within direct and group conversations.
 * Preserves membership history even if a member leaves or is removed.
 */
@Entity(
    tableName = "chat_members",
    indices = [
        Index(value = ["chatId", "userNxId"], unique = true),
        Index(value = ["chatId"]),
        Index(value = ["userNxId"])
    ]
)
data class ChatMemberEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val chatId: String,
    val userNxId: String,
    val role: String = "MEMBER", // ADMIN, MEMBER
    val joinedAt: Long = System.currentTimeMillis(),
    val leftAt: Long? = null,
    val membershipState: String = "ACTIVE" // ACTIVE, LEFT, REMOVED
)
