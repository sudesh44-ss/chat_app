package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nexa.app.domain.model.ScheduledMessage
import com.nexa.app.domain.model.ScheduledMessageStatus
import com.nexa.app.domain.model.TransportType

@Entity(
    tableName = "scheduled_messages",
    indices = [
        Index(value = ["chatId"]),
        Index(value = ["status"]),
        Index(value = ["scheduledAt"]),
        Index(value = ["messageId"], unique = true)
    ]
)
data class ScheduledMessageEntity(
    @PrimaryKey
    val id: String,
    val messageId: String,
    val chatId: String,
    val senderId: String,
    val recipientId: String,
    val messageType: String = "TEXT",
    val content: String,
    val attachmentReference: String? = null,
    val scheduledAt: Long,
    val createdAt: Long,
    val status: String = ScheduledMessageStatus.SCHEDULED.name,
    val sentAt: Long? = null,
    val cancelledAt: Long? = null,
    val replyToMessageId: String? = null,
    val replyToSenderNxId: String? = null,
    val replyToSnippet: String? = null,
    val transportType: String = "INTERNET",
    val destinationPhone: String? = null,
    val failureReason: String? = null
)

fun ScheduledMessageEntity.toDomain(): ScheduledMessage {
    return ScheduledMessage(
        id = id,
        messageId = messageId,
        chatId = chatId,
        senderId = senderId,
        recipientId = recipientId,
        messageType = messageType,
        content = content,
        attachmentReference = attachmentReference,
        scheduledAt = scheduledAt,
        createdAt = createdAt,
        status = try {
            ScheduledMessageStatus.valueOf(status)
        } catch (e: Exception) {
            ScheduledMessageStatus.SCHEDULED
        },
        sentAt = sentAt,
        cancelledAt = cancelledAt,
        replyToMessageId = replyToMessageId,
        replyToSenderNxId = replyToSenderNxId,
        replyToSnippet = replyToSnippet,
        transportType = try {
            TransportType.valueOf(transportType)
        } catch (e: Exception) {
            TransportType.INTERNET
        },
        destinationPhone = destinationPhone,
        failureReason = failureReason
    )
}

fun ScheduledMessage.toEntity(): ScheduledMessageEntity {
    return ScheduledMessageEntity(
        id = id,
        messageId = messageId,
        chatId = chatId,
        senderId = senderId,
        recipientId = recipientId,
        messageType = messageType,
        content = content,
        attachmentReference = attachmentReference,
        scheduledAt = scheduledAt,
        createdAt = createdAt,
        status = status.name,
        sentAt = sentAt,
        cancelledAt = cancelledAt,
        replyToMessageId = replyToMessageId,
        replyToSenderNxId = replyToSenderNxId,
        replyToSnippet = replyToSnippet,
        transportType = transportType.name,
        destinationPhone = destinationPhone,
        failureReason = failureReason
    )
}
