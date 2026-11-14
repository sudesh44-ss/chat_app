package com.nexa.app.domain.repository

import com.nexa.app.core.network.ConnectionState
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ChatRepository {
    fun getMessages(conversationId: String): Flow<List<Message>>
    fun getConversations(): Flow<List<ChatConversation>>
    suspend fun sendMessage(
        recipientNxId: String,
        text: String,
        transport: TransportType = TransportType.INTERNET,
        destinationPhone: String? = null
    ): Result<Message>

    suspend fun sendMessage(
        recipientNxId: String,
        text: String,
        transport: TransportType,
        destinationPhone: String?,
        replyToMessageId: String?
    ): Result<Message> = sendMessage(recipientNxId, text, transport, destinationPhone)

    suspend fun sendMessage(
        recipientNxId: String,
        text: String,
        transport: TransportType,
        destinationPhone: String?,
        replyToMessageId: String?,
        isScheduledDispatch: Boolean
    ): Result<Message> = sendMessage(recipientNxId, text, transport, destinationPhone, replyToMessageId)
    suspend fun retryFailedMessage(
        packetId: String,
        transport: TransportType,
        destinationPhone: String? = null
    ): Result<Message> = Result.failure(UnsupportedOperationException())
    suspend fun markConversationRead(conversationId: String)
    suspend fun markConversationUnread(conversationId: String) {}
    suspend fun createOrGetConversation(participantNxId: String, participantName: String? = null): ChatConversation
    fun startConnection()
    fun stopConnection()
    fun getConnectionState(): StateFlow<ConnectionState>
    suspend fun handleIncomingDataSmsPacket(packet: NexaWirePacket)
    fun sendTypingIndicator(recipientNxId: String, conversationId: String, isTyping: Boolean)
    fun getTypingEvents(): Flow<Pair<String, Boolean>>
    fun getPinnedMessages(conversationId: String): Flow<List<Message>> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun pinMessage(packetId: String, conversationId: String, recipientNxId: String, durationMillis: Long?): Result<Unit> = Result.success(Unit)
    suspend fun unpinMessage(packetId: String, conversationId: String, recipientNxId: String): Result<Unit> = Result.success(Unit)
    suspend fun clearExpiredPins(conversationId: String) {}
    suspend fun clearChatMessages(conversationId: String): Result<Unit> = Result.success(Unit)
    suspend fun deleteChat(conversationId: String): Result<Unit> = Result.success(Unit)
    suspend fun getMessageById(packetId: String): Message? = null
    suspend fun searchMessagesInConversation(conversationId: String, query: String): List<Message> = emptyList()
    suspend fun getFirstMessageOnDate(conversationId: String, startOfDay: Long, endOfDay: Long): Message? = null
    suspend fun getMessagesOnDate(conversationId: String, startOfDay: Long, endOfDay: Long): List<Message> = emptyList()

    // --- Conversation Organization ---
    suspend fun pinConversation(conversationId: String, isPinned: Boolean, pinPosition: Int = 0) {}
    suspend fun archiveConversation(conversationId: String, isArchived: Boolean) {}
    suspend fun muteConversation(conversationId: String, isMuted: Boolean, mutedUntil: Long = 0L) {}
    fun getArchivedConversations(): Flow<List<ChatConversation>> = kotlinx.coroutines.flow.emptyFlow()

    // --- Message Drafts ---
    fun getDraftFlow(conversationId: String): Flow<com.nexa.app.data.local.entity.ChatDraftEntity?> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun getDraft(conversationId: String): com.nexa.app.data.local.entity.ChatDraftEntity? = null
    suspend fun saveDraft(conversationId: String, draftText: String, replyToMessageId: String? = null, attachmentDraftReference: String? = null) {}
    suspend fun clearDraft(conversationId: String) {}

    // --- Message Reactions ---
    fun getReactionsForMessage(messageId: String): Flow<List<com.nexa.app.data.local.entity.MessageReactionEntity>> = kotlinx.coroutines.flow.emptyFlow()
    fun getReactionsForConversation(conversationId: String): Flow<List<com.nexa.app.data.local.entity.MessageReactionEntity>> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun addReaction(messageId: String, reaction: String): Result<Unit> = Result.success(Unit)
    suspend fun removeReaction(messageId: String): Result<Unit> = Result.success(Unit)

    // --- Soft Delete & Starring ---
    suspend fun softDeleteMessage(packetId: String): Result<Unit> = Result.success(Unit)
    suspend fun starMessage(packetId: String, isStarred: Boolean): Result<Unit> = Result.success(Unit)

    // --- GIF / Meme / Media Messaging ---
    suspend fun sendMediaMessage(
        recipientNxId: String,
        localPath: String,
        messageType: com.nexa.app.domain.model.MessageType,
        caption: String = "",
        replyToMessageId: String? = null
    ): Result<Message> = Result.failure(UnsupportedOperationException())
}
