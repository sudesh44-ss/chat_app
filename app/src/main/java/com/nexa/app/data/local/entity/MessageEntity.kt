package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.TransportType

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["packetId"], unique = true)
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
    val isOutgoing: Boolean,
    val transportType: String = "INTERNET"
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
        }
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
        transportType = transportType.name
    )
}
