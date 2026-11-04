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
    suspend fun markConversationRead(conversationId: String)
    suspend fun createOrGetConversation(participantNxId: String, participantName: String? = null): ChatConversation
    fun startConnection()
    fun stopConnection()
    fun getConnectionState(): StateFlow<ConnectionState>
    suspend fun handleIncomingDataSmsPacket(packet: NexaWirePacket)
    fun sendTypingIndicator(recipientNxId: String, conversationId: String, isTyping: Boolean)
    fun getTypingEvents(): Flow<Pair<String, Boolean>>
}
