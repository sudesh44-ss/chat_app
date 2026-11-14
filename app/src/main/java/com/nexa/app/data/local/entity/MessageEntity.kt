package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.MessageType
import com.nexa.app.domain.model.TransportType

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["conversationId", "localSequence"]),
        Index(value = ["conversationId", "timestamp"]),
        Index(value = ["packetId"], unique = true),
        Index(value = ["replyToMessageId"]),
        Index(value = ["messageType"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val packetId: String,
    val conversationId: String,
    val senderNxId: String,
    val recipientNxId: String,
    val plainTextContent: String,  // Decrypted locally; persisted securely in SQLCipher AES-256
    val status: String,            // PENDING, SENT, DELIVERED, READ, FAILED
    val timestamp: Long,
    val isOutgoing: Boolean = false,
    val transportType: String = "INTERNET",
    val isPinned: Boolean = false,
    val pinnedExpiresAt: Long? = null,
    val pinnedAt: Long? = null,
    val replyToMessageId: String? = null,
    val replyToSenderNxId: String? = null,
    val replyToSnippet: String? = null,
    val messageType: String = "TEXT",
    val localSequence: Long = 0L,
    val isDeleted: Boolean = false,
    val isStarred: Boolean = false,
    val editedAt: Long? = null,
    val localState: String = "PERSISTED"
)

fun MessageEntity.toDomain(): Message {
    return Message(
        packetId = packetId,
        conversationId = conversationId,
        senderNxId = senderNxId,
        recipientNxId = recipientNxId,
        plainTextContent = plainTextContent,
        status = MessageStatus.valueOf(status),
        timestamp = timestamp,
        isOutgoing = isOutgoing,
        transportType = try {
            TransportType.valueOf(transportType)
        } catch (e: Exception) {
            TransportType.INTERNET
        },
        isPinned = isPinned,
        pinnedExpiresAt = pinnedExpiresAt,
        pinnedAt = pinnedAt,
        replyToMessageId = replyToMessageId,
        replyToSenderNxId = replyToSenderNxId,
        replyToSnippet = replyToSnippet,
        messageType = try {
            MessageType.valueOf(messageType)
        } catch (e: Exception) {
            MessageType.TEXT
        },
        localSequence = localSequence,
        isDeleted = isDeleted,
        isStarred = isStarred,
        editedAt = editedAt,
        localState = localState
    )
}

fun Message.toEntity(): MessageEntity {
    return MessageEntity(
        packetId = packetId,
        conversationId = conversationId,
        senderNxId = senderNxId,
        recipientNxId = recipientNxId,
        plainTextContent = plainTextContent,
        status = status.name,
        timestamp = timestamp,
        isOutgoing = isOutgoing,
        transportType = transportType.name,
        isPinned = isPinned,
        pinnedExpiresAt = pinnedExpiresAt,
        pinnedAt = pinnedAt,
        replyToMessageId = replyToMessageId,
        replyToSenderNxId = replyToSenderNxId,
        replyToSnippet = replyToSnippet,
        messageType = messageType.name,
        localSequence = localSequence,
        isDeleted = isDeleted,
        isStarred = isStarred,
        editedAt = editedAt,
        localState = localState
    )
}
