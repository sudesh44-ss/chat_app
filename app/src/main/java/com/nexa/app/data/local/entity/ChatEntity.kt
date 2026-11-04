package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.nexa.app.domain.model.ChatConversation

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey
    val conversationId: String,
    val participantNxId: String,
    val participantName: String,
    val lastMessageText: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int = 0
)

fun ChatEntity.toDomain(): ChatConversation {
    return ChatConversation(
        conversationId = conversationId,
        participantNxId = participantNxId,
        participantName = participantName,
        lastMessageText = lastMessageText,
        lastMessageTimestamp = lastMessageTimestamp,
        unreadCount = unreadCount
    )
}

fun ChatConversation.toEntity(): ChatEntity {
    return ChatEntity(
        conversationId = conversationId,
        participantNxId = participantNxId,
        participantName = participantName,
        lastMessageText = lastMessageText,
        lastMessageTimestamp = lastMessageTimestamp,
        unreadCount = unreadCount
    )
}
