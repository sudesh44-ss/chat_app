package com.nexa.app.domain.repository

import com.nexa.app.core.crypto.EncryptedEnvelope
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.data.transport.OutboundRoute
import kotlinx.coroutines.flow.Flow

interface SecureMessageRepository {
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    suspend fun sendSecureMessage(
        recipientNxId: String,
        text: String,
        targetRoute: OutboundRoute,
        recipientPhone: String? = null,
        replyToMessageId: String? = null
    ): Result<EncryptedEnvelope>

    suspend fun receiveAndDecryptEnvelope(
        senderNxId: String,
        envelope: EncryptedEnvelope
    ): Result<String>

    suspend fun getSafetyNumbers(contactNxId: String): String
}
