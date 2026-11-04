package com.nexa.app.domain.model

enum class TransportType {
    INTERNET,
    DATA_SMS,
    DTN_MESH
}

data class Message(
    val packetId: String,
    val conversationId: String,
    val senderNxId: String,
    val recipientNxId: String,
    val plainTextContent: String,
    val status: MessageStatus,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val transportType: TransportType = TransportType.INTERNET
)

data class ChatConversation(
    val conversationId: String,
    val participantNxId: String,
    val participantName: String,
    val lastMessageText: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int = 0
)
