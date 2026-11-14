package com.nexa.app.domain.model

enum class ScheduledMessageStatus {
    SCHEDULED,
    PENDING,
    SENDING,
    SENT,
    CANCELLED,
    FAILED
}

data class ScheduledMessage(
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
    val status: ScheduledMessageStatus = ScheduledMessageStatus.SCHEDULED,
    val sentAt: Long? = null,
    val cancelledAt: Long? = null,
    val replyToMessageId: String? = null,
    val replyToSenderNxId: String? = null,
    val replyToSnippet: String? = null,
    val transportType: TransportType = TransportType.INTERNET,
    val destinationPhone: String? = null,
    val failureReason: String? = null
)
