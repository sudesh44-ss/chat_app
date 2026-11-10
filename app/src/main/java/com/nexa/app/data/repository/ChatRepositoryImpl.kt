package com.nexa.app.data.repository

import android.util.Log
import com.nexa.app.core.crypto.DoubleRatchetEngine
import com.nexa.app.core.crypto.EncryptedEnvelope
import com.nexa.app.core.network.ConnectionState
import com.nexa.app.core.network.NetworkMonitor
import com.nexa.app.core.network.WebSocketManager
import com.nexa.app.core.notification.NexaNotificationDispatcher
import com.nexa.app.core.proto.AckStatus
import com.nexa.app.core.proto.DeliveryAckPayload
import com.nexa.app.core.proto.EncryptedMessagePayload
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.proto.PacketType
import com.nexa.app.core.security.CryptoKeystoreManager
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.data.local.dao.ChatDao
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.dao.MessageDao
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.entity.ChatEntity
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.data.local.entity.toDomain
import com.nexa.app.data.local.preferences.ChatPrivacyPrefs
import com.nexa.app.data.remote.sms.NexaDataSmsSender
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.TransportType
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.domain.repository.DtnRepository
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.UUID
import com.nexa.app.core.transport.NexaUniversalTransportIngest
import com.nexa.app.core.transport.TransportMedium

class ChatRepositoryImpl(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val userDao: UserDao,
    private val secureStorage: SecureStorage,
    private val keystoreManager: CryptoKeystoreManager,
    private val webSocketManager: WebSocketManager,
    private val dataSmsSender: NexaDataSmsSender,
    private val networkMonitor: NetworkMonitor,
    private val dtnRepository: DtnRepository,
    private val doubleRatchetEngine: DoubleRatchetEngine,
    private val contactDao: ContactDao? = null,
    private val chatPrivacyPrefs: ChatPrivacyPrefs? = null,
    private val notificationDispatcher: NexaNotificationDispatcher? = null
) : ChatRepository {

    var universalTransportIngest: NexaUniversalTransportIngest? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _typingFlow = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 32)
    private val pendingWirePackets = ConcurrentHashMap<String, NexaWirePacket>()

    init {
        observeIncomingPackets()
        observeConnectionState()
    }

    override fun getMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesFlow(conversationId)
            .map { list -> list.map { it.toDomain() } }
            .catch { e ->
                Log.e(TAG, "Error collecting messages for $conversationId", e)
                emit(emptyList())
            }
            .flowOn(Dispatchers.IO)
    }

    override fun getConversations(): Flow<List<ChatConversation>> {
        return chatDao.getChatsFlow()
            .map { list -> list.map { it.toDomain() } }
            .catch { e ->
                Log.e(TAG, "Error collecting conversations from local vault", e)
                emit(emptyList())
            }
            .flowOn(Dispatchers.IO)
    }

    override suspend fun sendMessage(
        recipientNxId: String,
        text: String,
        transport: TransportType,
        destinationPhone: String?
    ): Result<Message> = withContext(Dispatchers.IO) {
        try {
            val myNxId = getMyNxId()
            val packetId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()

            // Intercept Demo Conversations for safe local-only development testing
            if (recipientNxId.startsWith("NX-DEMO")) {
                val existingChat = chatDao.getChatByParticipant(recipientNxId)
                val targetConvId = existingChat?.conversationId ?: deriveConversationId(myNxId, recipientNxId)
                val targetName = existingChat?.participantName ?: recipientNxId
                val demoMsg = MessageEntity(
                    packetId = packetId,
                    conversationId = targetConvId,
                    senderNxId = myNxId,
                    recipientNxId = recipientNxId,
                    plainTextContent = text,
                    status = MessageStatus.DELIVERED.name,
                    timestamp = timestamp,
                    isOutgoing = true,
                    transportType = transport.name
                )
                messageDao.insertMessage(demoMsg)
                chatDao.upsertChat(
                    ChatEntity(
                        conversationId = targetConvId,
                        participantNxId = recipientNxId,
                        participantName = targetName,
                        lastMessageText = text,
                        lastMessageTimestamp = timestamp,
                        unreadCount = 0
                    )
                )
                Log.i(TAG, "Demo message $packetId stored locally as DELIVERED without cloud transmission")
                return@withContext Result.success(demoMsg.toDomain())
            }

            val conversationId = deriveConversationId(myNxId, recipientNxId)

            // 1. Ensure conversation exists in local vault
            createOrGetConversation(recipientNxId)

            // 2. Insert into local SQLCipher Room DB with status PENDING (Outbox Pattern)
            val messageEntity = MessageEntity(
                packetId = packetId,
                conversationId = conversationId,
                senderNxId = myNxId,
                recipientNxId = recipientNxId,
                plainTextContent = text,
                status = MessageStatus.PENDING.name,
                timestamp = timestamp,
                isOutgoing = true,
                transportType = transport.name
            )
            messageDao.insertMessage(messageEntity)

            // Update conversation snippet
            chatDao.upsertChat(
                ChatEntity(
                    conversationId = conversationId,
                    participantNxId = recipientNxId,
                    participantName = recipientNxId,
                    lastMessageText = text,
                    lastMessageTimestamp = timestamp,
                    unreadCount = 0
                )
            )

            var isDispatched = false

            when (transport) {
                TransportType.DTN_MESH -> {
                    // Route via Delay-Tolerant Mesh (Store-Carry-Forward)
                    val contact = contactDao?.getContact(recipientNxId)
                    val recipientPkHex = contact?.publicKeyHex?.takeIf { it.isNotBlank() } ?: "00".repeat(32)
                    val dtnResult = dtnRepository.createAndCacheEnvelope(
                        recipientNxId = recipientNxId,
                        recipientPublicKeyHex = recipientPkHex,
                        plainText = text
                    )
                    if (dtnResult.isSuccess) {
                        isDispatched = true
                        messageDao.updateMessageStatus(packetId, MessageStatus.SENT.name)
                        Log.i(TAG, "Message $packetId encapsulated into DTN envelope and queued for mesh mules")
                    } else {
                        messageDao.updateMessageStatus(packetId, MessageStatus.FAILED.name)
                        return@withContext Result.failure(dtnResult.exceptionOrNull() ?: Exception("DTN encapsulation failed"))
                    }
                }

                TransportType.DATA_SMS -> {
                    // Route via Port 8901 Data SMS (Offline / High-Privacy Fallback)
                    if (!dataSmsSender.isSupported()) {
                        messageDao.updateMessageStatus(packetId, MessageStatus.FAILED.name)
                        val error = UnsupportedOperationException(
                            "Data SMS transport is disabled in this build for Google Play Protect compliance. Please use Internet or DTN Mesh."
                        )
                        Log.w(TAG, "Data SMS transmission blocked for packet $packetId: transport is disabled")
                        return@withContext Result.failure(error)
                    }

                    val targetPhone = destinationPhone?.trim()
                    if (targetPhone.isNullOrBlank()) {
                        throw IllegalArgumentException("Data SMS transport requires a destination phone number")
                    }

                    val rawPlainBytes = text.toByteArray(StandardCharsets.UTF_8)
                    val recipientContact = contactDao?.getContact(recipientNxId)
                    val recipientPubKey = recipientContact?.publicKeyHex?.let { fromHexOrNull(it) }

                    // Double Ratchet E2EE encryption
                    val envelope = doubleRatchetEngine.ratchetEncrypt(recipientNxId, rawPlainBytes, recipientPubKey)

                    val wirePacket = NexaWirePacket(
                        packetId = packetId,
                        type = PacketType.PACKET_MESSAGE,
                        senderNxId = myNxId,
                        recipientNxId = recipientNxId,
                        timestamp = timestamp,
                        message = envelope.toProtobufPayload()
                    )

                    val encodedBytes = wirePacket.encode()
                    val numericMessageId = UUID.fromString(packetId).mostSignificantBits

                    pendingWirePackets[packetId] = wirePacket
                    val smsResult = dataSmsSender.sendDataSms(
                        destinationPhone = targetPhone,
                        messageId = numericMessageId,
                        encryptedPayload = encodedBytes,
                        packetId = packetId
                    )

                    if (smsResult.isSuccess) {
                        pendingWirePackets.remove(packetId)
                        isDispatched = true
                        messageDao.updateMessageStatus(packetId, MessageStatus.SENT.name)
                        Log.d(TAG, "Message $packetId dispatched via Data SMS to $targetPhone with Double Ratchet E2EE")
                    } else {
                        messageDao.updateMessageStatus(packetId, MessageStatus.FAILED.name)
                        val error = smsResult.exceptionOrNull() ?: Exception("Data SMS transmission failed")
                        Log.e(TAG, "Data SMS transmission failed for packet $packetId", error)
                        return@withContext Result.failure(error)
                    }
                }

                TransportType.INTERNET -> {
                    // Route via Primary Transport: Internet WebSocket (Double Ratchet E2EE)
                    val rawPlainBytes = text.toByteArray(StandardCharsets.UTF_8)
                    val recipientContact = contactDao?.getContact(recipientNxId)
                    val recipientPubKey = recipientContact?.publicKeyHex?.let { fromHexOrNull(it) }

                    // Double Ratchet E2EE encryption
                    val envelope = doubleRatchetEngine.ratchetEncrypt(recipientNxId, rawPlainBytes, recipientPubKey)

                    val wirePacket = NexaWirePacket(
                        packetId = packetId,
                        type = PacketType.PACKET_MESSAGE,
                        senderNxId = myNxId,
                        recipientNxId = recipientNxId,
                        timestamp = timestamp,
                        message = envelope.toProtobufPayload()
                    )

                    pendingWirePackets[packetId] = wirePacket
                    isDispatched = webSocketManager.sendPacket(wirePacket)
                    if (isDispatched) {
                        pendingWirePackets.remove(packetId)
                        messageDao.updateMessageStatus(packetId, MessageStatus.SENT.name)
                        Log.d(TAG, "Message $packetId dispatched via WebSocket with Double Ratchet E2EE")
                    }
                }
            }

            Result.success(
                messageEntity.toDomain().copy(
                    status = if (isDispatched) MessageStatus.SENT else MessageStatus.PENDING
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching outgoing message", e)
            Result.failure(e)
        }
    }

    override suspend fun handleIncomingDataSmsPacket(packet: NexaWirePacket) = withContext(Dispatchers.IO) {
        val ingest = universalTransportIngest
        if (ingest != null) {
            ingest.ingestWirePacket(packet, TransportMedium.CELLULAR_DATA_SMS)
            return@withContext
        }
        val myNxId = getMyNxId()
        when (packet.type) {
            PacketType.PACKET_MESSAGE -> {
                val senderNxId = packet.senderNxId
                val payload = packet.message ?: return@withContext
                val conversationId = deriveConversationId(myNxId, senderNxId)

                // 1. Decrypt incoming SMS payload via Double Ratchet
                val envelope = parseEnvelopeFromPayload(payload)
                val decryptedBytes = doubleRatchetEngine.ratchetDecrypt(senderNxId, envelope)

                val decryptedText = if (decryptedBytes != null) {
                    String(decryptedBytes, StandardCharsets.UTF_8)
                } else {
                    Log.w(TAG, "Double Ratchet SMS decryption failed for packet ${packet.packetId} from $senderNxId")
                    "[Encrypted Data SMS - Decryption Failed]"
                }

                if (decryptedBytes != null) {
                    if (decryptedText.startsWith("{") && (decryptedText.contains("\"type\":\"PIN_MESSAGE\"") || decryptedText.contains("\"type\":\"UNPIN_MESSAGE\""))) {
                        handlePinControlPacket(decryptedText)
                        return@withContext
                    }
                    if (decryptedText.startsWith("{") && decryptedText.contains("\"type\":\"PROFILE_UPDATE\"")) {
                        handleProfileUpdateControlPacket(decryptedText)
                        return@withContext
                    }
                }

                val messageStatus = if (decryptedBytes != null) MessageStatus.DELIVERED else MessageStatus.FAILED

                // 2. Persist in local encrypted SQLCipher Room DB
                val messageEntity = MessageEntity(
                    packetId = packet.packetId,
                    conversationId = conversationId,
                    senderNxId = senderNxId,
                    recipientNxId = myNxId,
                    plainTextContent = decryptedText,
                    status = messageStatus.name,
                    timestamp = packet.timestamp,
                    isOutgoing = false,
                    transportType = TransportType.DATA_SMS.name
                )
                messageDao.insertMessage(messageEntity)

                // 3. Update conversation snippet & unread count
                val existingChat = chatDao.getChatById(conversationId)
                val unread = (existingChat?.unreadCount ?: 0) + 1
                chatDao.upsertChat(
                    ChatEntity(
                        conversationId = conversationId,
                        participantNxId = senderNxId,
                        participantName = existingChat?.participantName ?: senderNxId,
                        lastMessageText = decryptedText,
                        lastMessageTimestamp = packet.timestamp,
                        unreadCount = unread
                    )
                )

                // 4. Dispatch privacy-preserving local notification if decrypted successfully
                if (decryptedBytes != null) {
                    val contact = contactDao?.getContact(senderNxId)
                    val resolvedName = contact?.effectiveDisplayName ?: existingChat?.participantName ?: senderNxId
                    notificationDispatcher?.postDirectMessageNotification(
                        conversationId = conversationId,
                        senderNxId = senderNxId,
                        senderDisplayName = resolvedName,
                        decryptedText = decryptedText,
                        messageId = packet.packetId
                    )
                }

                Log.d(TAG, "Incoming Data SMS from $senderNxId stored in local vault (status=$messageStatus)")
            }
            PacketType.PACKET_ACK -> {
                handleIncomingAck(packet)
            }
            else -> Unit
        }
    }

    override suspend fun markConversationRead(conversationId: String): Unit = withContext(Dispatchers.IO) {
        val myNxId = getMyNxId()
        chatDao.resetUnreadCount(conversationId)
        messageDao.markMessagesAsRead(conversationId)

        // Read Receipts Gatekeeper: Silent drop if disabled by user settings (Zero Server Knowledge)
        if (chatPrivacyPrefs?.readReceiptsEnabled == false) {
            Log.d(TAG, "Read receipts suppressed by user privacy gatekeeper (Zero Server Knowledge)")
            return@withContext
        }

        // Dispatch read ACK to participant
        val chat = chatDao.getChatById(conversationId)
        if (chat != null) {
            val latest = messageDao.getLatestMessage(conversationId)
            if (latest != null && !latest.isOutgoing) {
                val readAck = NexaWirePacket(
                    packetId = UUID.randomUUID().toString(),
                    type = PacketType.PACKET_ACK,
                    senderNxId = myNxId,
                    recipientNxId = chat.participantNxId,
                    timestamp = System.currentTimeMillis(),
                    ack = DeliveryAckPayload(
                        originalPacketId = latest.packetId,
                        status = AckStatus.ACK_DEVICE_READ
                    )
                )
                webSocketManager.sendPacket(readAck)
            }
        }
    }

    override suspend fun retryFailedMessage(
        packetId: String,
        transport: TransportType,
        destinationPhone: String?
    ): Result<Message> = withContext(Dispatchers.IO) {
        val existing = messageDao.getMessageByPacketId(packetId)
            ?: return@withContext Result.failure(IllegalArgumentException("Message $packetId not found in local vault"))

        // Update local status back to PENDING before re-dispatching
        messageDao.updateMessageStatus(packetId, MessageStatus.PENDING.name)

        sendMessage(
            recipientNxId = existing.recipientNxId,
            text = existing.plainTextContent,
            transport = transport,
            destinationPhone = destinationPhone
        )
    }

    override suspend fun createOrGetConversation(
        participantNxId: String,
        participantName: String?
    ): ChatConversation = withContext(Dispatchers.IO) {
        val myNxId = getMyNxId()
        val conversationId = deriveConversationId(myNxId, participantNxId)
        val existing = chatDao.getChatById(conversationId) ?: chatDao.getChatByParticipant(participantNxId)
        if (existing != null) {
            return@withContext existing.toDomain()
        }

        val name = participantName ?: participantNxId
        val newChat = ChatEntity(
            conversationId = conversationId,
            participantNxId = participantNxId,
            participantName = name,
            lastMessageText = "Conversation started",
            lastMessageTimestamp = System.currentTimeMillis(),
            unreadCount = 0
        )
        chatDao.upsertChat(newChat)
        newChat.toDomain()
    }

    override fun startConnection() {
        try {
            val myNxId = secureStorage.getActiveNxId() ?: return
            val deviceId = secureStorage.getDeviceId()
            webSocketManager.connect(myNxId, deviceId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start connection", e)
        }
    }

    override fun stopConnection() {
        webSocketManager.disconnect()
    }

    override fun getConnectionState(): StateFlow<ConnectionState> {
        return webSocketManager.connectionState
    }

    override fun sendTypingIndicator(recipientNxId: String, conversationId: String, isTyping: Boolean) {
        webSocketManager.sendTyping(recipientNxId, conversationId, isTyping)
    }

    override fun getTypingEvents(): Flow<Pair<String, Boolean>> = _typingFlow.asSharedFlow()

    override fun getPinnedMessages(conversationId: String): Flow<List<Message>> {
        val now = System.currentTimeMillis()
        return messageDao.getPinnedMessagesFlow(conversationId, now)
            .map { entities -> entities.map { it.toDomain() } }
            .catch { e ->
                Log.e(TAG, "Error collecting pinned messages for $conversationId", e)
                emit(emptyList())
            }
            .flowOn(Dispatchers.IO)
    }

    override suspend fun pinMessage(
        packetId: String,
        conversationId: String,
        recipientNxId: String,
        durationMillis: Long?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val count = messageDao.getPinnedMessagesCount(conversationId, now)
        if (count >= 4) {
            return@withContext Result.failure(
                IllegalStateException("Pin limit reached: You can pin up to 4 messages in this chat. Unpin an existing message to pin another one.")
            )
        }

        val expiresAt = if (durationMillis != null) now + durationMillis else null
        messageDao.updatePinStatus(packetId, isPinned = true, pinnedExpiresAt = expiresAt, pinnedAt = now)

        val controlPayload = org.json.JSONObject().apply {
            put("type", "PIN_MESSAGE")
            put("targetMessageId", packetId)
            put("expiresAt", expiresAt ?: -1L)
            put("pinnedAt", now)
            put("pinnedBy", getMyNxId())
        }.toString()

        sendControlPacket(recipientNxId, controlPayload)
        Result.success(Unit)
    }

    override suspend fun unpinMessage(
        packetId: String,
        conversationId: String,
        recipientNxId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        messageDao.unpinMessage(packetId)

        val controlPayload = org.json.JSONObject().apply {
            put("type", "UNPIN_MESSAGE")
            put("targetMessageId", packetId)
            put("unpinnedBy", getMyNxId())
        }.toString()

        sendControlPacket(recipientNxId, controlPayload)
        Result.success(Unit)
    }

    override suspend fun clearExpiredPins(conversationId: String): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        messageDao.clearExpiredPins(conversationId, now)
        Unit
    }

    private suspend fun sendControlPacket(recipientNxId: String, payloadJson: String) {
        try {
            val myNxId = getMyNxId()
            val packetId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()
            val rawPlainBytes = payloadJson.toByteArray(StandardCharsets.UTF_8)
            val recipientContact = contactDao?.getContact(recipientNxId)
            val recipientPubKey = recipientContact?.publicKeyHex?.let { fromHexOrNull(it) }

            val envelope = doubleRatchetEngine.ratchetEncrypt(recipientNxId, rawPlainBytes, recipientPubKey)
            val wirePacket = NexaWirePacket(
                packetId = packetId,
                type = PacketType.PACKET_MESSAGE,
                senderNxId = myNxId,
                recipientNxId = recipientNxId,
                timestamp = timestamp,
                message = EncryptedMessagePayload(
                    conversationSeq = timestamp,
                    ciphertext = envelope.ciphertext,
                    nonce = envelope.ephemeralPublicKey,
                    authTag = envelope.authTag
                )
            )
            webSocketManager.sendPacket(wirePacket)
            Log.d(TAG, "Dispatched pin control packet ($packetId) to $recipientNxId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dispatch control packet: ${e.message}")
        }
    }

    private suspend fun handlePinControlPacket(jsonString: String) {
        try {
            val json = org.json.JSONObject(jsonString)
            when (json.optString("type")) {
                "PIN_MESSAGE" -> {
                    val targetMessageId = json.getString("targetMessageId")
                    val expiresAtRaw = json.optLong("expiresAt", -1L)
                    val expiresAt = if (expiresAtRaw > 0L) expiresAtRaw else null
                    val pinnedAt = json.optLong("pinnedAt", System.currentTimeMillis())
                    messageDao.updatePinStatus(targetMessageId, isPinned = true, pinnedExpiresAt = expiresAt, pinnedAt = pinnedAt)
                    Log.d(TAG, "Processed remote PIN_MESSAGE for message $targetMessageId (ExpiresAt: $expiresAt)")
                }
                "UNPIN_MESSAGE" -> {
                    val targetMessageId = json.getString("targetMessageId")
                    messageDao.unpinMessage(targetMessageId)
                    Log.d(TAG, "Processed remote UNPIN_MESSAGE for message $targetMessageId")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse PIN control packet: ${e.message}")
        }
    }

    private suspend fun handleProfileUpdateControlPacket(jsonString: String) {
        try {
            val json = org.json.JSONObject(jsonString)
            val nxId = json.getString("nxId")
            val newAvatarUrl = if (json.has("newAvatarUrl") && !json.isNull("newAvatarUrl")) {
                json.getString("newAvatarUrl").takeIf { it.isNotBlank() }
            } else null

            contactDao?.updateAvatarUrl(nxId, newAvatarUrl)
            Log.d(TAG, "Processed remote PROFILE_UPDATE for $nxId: $newAvatarUrl")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse remote PROFILE_UPDATE control packet: ${e.message}")
        }
    }

    private fun observeIncomingPackets() {
        scope.launch {
            webSocketManager.incomingPackets.collect { packet ->
                try {
                    val ingest = universalTransportIngest
                    if (ingest != null) {
                        ingest.ingestWirePacket(packet, TransportMedium.INTERNET_WS)
                    } else {
                        when (packet.type) {
                            PacketType.PACKET_MESSAGE -> handleIncomingMessage(packet)
                            PacketType.PACKET_ACK -> handleIncomingAck(packet)
                            PacketType.PACKET_TYPING -> {
                                packet.typing?.let { t ->
                                    _typingFlow.tryEmit(Pair(t.conversationId, t.isTyping))
                                }
                            }
                            PacketType.PACKET_PING -> { /* Heartbeat handled at network level */ }
                            else -> Unit
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing incoming wire packet: ${packet.packetId}", e)
                }
            }
        }
    }

    private suspend fun handleIncomingMessage(packet: NexaWirePacket) = withContext(Dispatchers.IO) {
        val myNxId = getMyNxId()
        val senderNxId = packet.senderNxId
        val payload = packet.message ?: return@withContext
        val conversationId = deriveConversationId(myNxId, senderNxId)

        // 1. Decrypt payload via Double Ratchet Engine
        val envelope = parseEnvelopeFromPayload(payload)
        val decryptedBytes = doubleRatchetEngine.ratchetDecrypt(senderNxId, envelope)

        val decryptedText = if (decryptedBytes != null) {
            String(decryptedBytes, StandardCharsets.UTF_8)
        } else {
            Log.w(TAG, "Double Ratchet decryption failed for packet ${packet.packetId} from $senderNxId")
            "[Encrypted Message - Verification Failed]"
        }

        if (decryptedBytes != null) {
            if (decryptedText.startsWith("{") && (decryptedText.contains("\"type\":\"PIN_MESSAGE\"") || decryptedText.contains("\"type\":\"UNPIN_MESSAGE\""))) {
                handlePinControlPacket(decryptedText)
                return@withContext
            }
            if (decryptedText.startsWith("{") && decryptedText.contains("\"type\":\"PROFILE_UPDATE\"")) {
                handleProfileUpdateControlPacket(decryptedText)
                return@withContext
            }
        }

        val messageStatus = if (decryptedBytes != null) MessageStatus.DELIVERED else MessageStatus.FAILED

        // 2. Persist in local encrypted Room DB
        val messageEntity = MessageEntity(
            packetId = packet.packetId,
            conversationId = conversationId,
            senderNxId = senderNxId,
            recipientNxId = myNxId,
            plainTextContent = decryptedText,
            status = messageStatus.name,
            timestamp = packet.timestamp,
            isOutgoing = false,
            transportType = TransportType.INTERNET.name
        )
        messageDao.insertMessage(messageEntity)

        // 3. Update conversation list & unread count
        val existingChat = chatDao.getChatById(conversationId)
        val unread = (existingChat?.unreadCount ?: 0) + 1
        chatDao.upsertChat(
            ChatEntity(
                conversationId = conversationId,
                participantNxId = senderNxId,
                participantName = existingChat?.participantName ?: senderNxId,
                lastMessageText = decryptedText,
                lastMessageTimestamp = packet.timestamp,
                unreadCount = unread
            )
        )

        // 4. Dispatch privacy-preserving local notification if decrypted successfully
        if (decryptedBytes != null) {
            val contact = contactDao?.getContact(senderNxId)
            val resolvedName = contact?.effectiveDisplayName ?: existingChat?.participantName ?: senderNxId
            notificationDispatcher?.postDirectMessageNotification(
                conversationId = conversationId,
                senderNxId = senderNxId,
                senderDisplayName = resolvedName,
                decryptedText = decryptedText,
                messageId = packet.packetId
            )
        }

        // 5. Send ACK_DEVICE_DELIVERED back to sender (zero-disk relay) if decryption succeeded
        if (decryptedBytes != null) {
            val deliveryAck = NexaWirePacket(
                packetId = UUID.randomUUID().toString(),
                type = PacketType.PACKET_ACK,
                senderNxId = myNxId,
                recipientNxId = senderNxId,
                timestamp = System.currentTimeMillis(),
                ack = DeliveryAckPayload(
                    originalPacketId = packet.packetId,
                    status = AckStatus.ACK_DEVICE_DELIVERED
                )
            )
            webSocketManager.sendPacket(deliveryAck)
        }
    }

    private suspend fun handleIncomingAck(packet: NexaWirePacket) = withContext(Dispatchers.IO) {
        val ackPayload = packet.ack ?: return@withContext
        val targetPacketId = ackPayload.originalPacketId
        if (targetPacketId.isEmpty()) return@withContext

        val newStatus = when (ackPayload.status) {
            AckStatus.ACK_SERVER_RECEIVED -> MessageStatus.SENT
            AckStatus.ACK_DEVICE_DELIVERED -> MessageStatus.DELIVERED
            AckStatus.ACK_DEVICE_READ -> MessageStatus.READ
            AckStatus.ACK_UNKNOWN -> return@withContext
        }

        // Update local status machine (SENT -> DELIVERED -> READ)
        messageDao.updateMessageStatus(targetPacketId, newStatus.name)
        Log.d(TAG, "Updated message $targetPacketId status to $newStatus via incoming ACK")
    }

    private fun observeConnectionState() {
        scope.launch {
            webSocketManager.connectionState
                .filter { it is ConnectionState.Connected }
                .distinctUntilChanged()
                .collect {
                    drainOutboxQueue()
                }
        }
    }

    private suspend fun drainOutboxQueue() = withContext(Dispatchers.IO) {
        val pendingMessages = messageDao.getPendingOutgoingMessages()
        if (pendingMessages.isEmpty()) return@withContext

        Log.d(TAG, "Draining ${pendingMessages.size} pending messages from Outbox...")
        val myNxId = getMyNxId()
        for (msg in pendingMessages) {
            // Only drain internet messages via WebSocket
            if (msg.transportType == TransportType.INTERNET.name) {
                // Reuse existing ratcheted wire packet if present to PREVENT burning ratchet keys
                val packet = pendingWirePackets[msg.packetId] ?: run {
                    val rawPlainBytes = msg.plainTextContent.toByteArray(StandardCharsets.UTF_8)
                    val recipientContact = contactDao?.getContact(msg.recipientNxId)
                    val recipientPubKey = recipientContact?.publicKeyHex?.let { fromHexOrNull(it) }

                    val envelope = doubleRatchetEngine.ratchetEncrypt(msg.recipientNxId, rawPlainBytes, recipientPubKey)

                    val newPacket = NexaWirePacket(
                        packetId = msg.packetId,
                        type = PacketType.PACKET_MESSAGE,
                        senderNxId = myNxId,
                        recipientNxId = msg.recipientNxId,
                        timestamp = msg.timestamp,
                        message = EncryptedMessagePayload(
                            conversationSeq = msg.timestamp,
                            ciphertext = envelope.ciphertext,
                            nonce = envelope.ephemeralPublicKey,
                            authTag = envelope.authTag
                        )
                    )
                    pendingWirePackets[msg.packetId] = newPacket
                    newPacket
                }

                if (webSocketManager.sendPacket(packet)) {
                    pendingWirePackets.remove(msg.packetId)
                    messageDao.updateMessageStatus(msg.packetId, MessageStatus.SENT.name)
                    Log.d(TAG, "Outbox packet ${msg.packetId} dispatched via WebSocket")
                }
            }
        }
    }

    private fun parseEnvelopeFromPayload(payload: EncryptedMessagePayload): EncryptedEnvelope {
        return EncryptedEnvelope.fromProtobufPayload(payload)
    }

    private fun deriveConversationId(id1: String, id2: String): String {
        return if (id1 < id2) "${id1}_$id2" else "${id2}_$id1"
    }

    override suspend fun clearChatMessages(conversationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            messageDao.deleteMessagesForConversation(conversationId)
            chatDao.clearLastMessageText(conversationId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing chat messages for $conversationId", e)
            Result.failure(e)
        }
    }

    private fun getMyNxId(): String {
        return secureStorage.getActiveNxId() ?: "NX-ANONYMOUS"
    }

    private fun fromHexOrNull(hex: String): ByteArray? {
        return try {
            if (hex.length % 2 != 0) return null
            val data = ByteArray(hex.length / 2)
            var i = 0
            while (i < hex.length) {
                data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
                i += 2
            }
            data
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "ChatRepositoryImpl"
    }
}
