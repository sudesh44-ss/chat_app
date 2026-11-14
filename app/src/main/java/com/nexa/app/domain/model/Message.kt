package com.nexa.app.domain.model

enum class TransportType {
    INTERNET,
    DATA_SMS,
    DTN_MESH
}

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    GIF,
    MEME,
    AUDIO,
    DOCUMENT,
    FILE,
    LOCATION,
    CONTACT,
    VOICE,
    STICKER,
    SYSTEM
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
    val transportType: TransportType = TransportType.INTERNET,
    val isPinned: Boolean = false,
    val pinnedExpiresAt: Long? = null,
    val pinnedAt: Long? = null,
    val replyToMessageId: String? = null,
    val replyToSenderNxId: String? = null,
    val replyToSnippet: String? = null,
    val messageType: MessageType = MessageType.TEXT,
    val localSequence: Long = 0L,
    val isDeleted: Boolean = false,
    val isStarred: Boolean = false,
    val editedAt: Long? = null,
    val localState: String = "PERSISTED"
)

data class ChatConversation(
    val conversationId: String,
    val participantNxId: String,
    val participantName: String = "",
    val lastMessageText: String = "",
    val lastMessageTimestamp: Long = 0L,
    val unreadCount: Int = 0,
    val chatType: String = if (
        conversationId.contains("group", ignoreCase = true) ||
        participantNxId.contains("GROUP", ignoreCase = true) ||
        participantName.contains("group", ignoreCase = true)
    ) "GROUP" else "DIRECT",
    val title: String? = null,
    val avatarUrl: String? = null,
    val lastMessageId: String? = null,
    val lastMessageSenderNxId: String? = null,
    val isPinned: Boolean = false,
    val pinPosition: Int = 0,
    val pinnedAt: Long? = null,
    val isArchived: Boolean = false,
    val archivedAt: Long? = null,
    val isMuted: Boolean = false,
    val mutedUntil: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val hasManualUnread: Boolean = false
) {
    val isGroup: Boolean
        get() = chatType.equals("GROUP", ignoreCase = true) ||
            conversationId.contains("group", ignoreCase = true) ||
            participantNxId.contains("GROUP", ignoreCase = true) ||
            participantName.contains("group", ignoreCase = true)
}
