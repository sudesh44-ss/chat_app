package com.nexa.app.core.transport

import android.util.Log
import com.nexa.app.core.crypto.AckDigestHelper
import com.nexa.app.core.crypto.Ed25519Signer
import com.nexa.app.core.crypto.EncryptedEnvelope
import com.nexa.app.core.dtn.BlindTokenGenerator
import com.nexa.app.core.dtn.CustodyState
import com.nexa.app.core.dtn.DtnConstants
import com.nexa.app.core.dtn.DtnEnvelope
import com.nexa.app.core.dtn.PairwiseRoutingTokenGenerator
import com.nexa.app.core.notification.NexaNotificationDispatcher
import com.nexa.app.core.proto.AckStatus
import com.nexa.app.core.proto.CustodyAckPayload
import com.nexa.app.core.proto.CustodyStatus
import com.nexa.app.core.proto.DeliveryAckPayload
import com.nexa.app.core.proto.EncryptedMessagePayload
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.proto.PacketType
import com.nexa.app.core.crypto.DoubleRatchetEngine
import com.nexa.app.core.security.CryptoKeystoreManager
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.data.local.dao.ChatDao
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.dao.getAllContactsList
import com.nexa.app.data.local.dao.DtnEnvelopeDao
import com.nexa.app.data.local.dao.MessageDao
import com.nexa.app.data.local.dao.MessageDedupDao
import com.nexa.app.data.local.dao.MessageTransportDao
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.entity.ChatEntity
import com.nexa.app.data.local.entity.DtnEnvelopeEntity
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.data.local.entity.MessageDedupEntity
import com.nexa.app.data.local.entity.MessageTransportEntity
import com.nexa.app.domain.model.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Universal Inbound Message Ingestion Pipeline.
 *
 * Ingests incoming wire packets across all three NEXA transports:
 * - Internet WebSocket (INTERNET_WS)
 * - Cellular Binary Data SMS Port 8901 (CELLULAR_DATA_SMS)
 * - Offline Delay-Tolerant Networking Mesh (OFFLINE_MESH)
 *
 * Enforces:
 * 1. Wire envelope validation & magic bytes verification.
 * 2. Persistent pre-decryption cross-transport deduplication gate.
 * 3. Double Ratchet AEAD decryption & Poly1305 MAC identity verification.
 * 4. Local encrypted SQLCipher Room DB atomic persistence with original transport metadata.
 * 5. Multi-Hop DTN Store-and-Forward relay custody for un-owned packets.
 * 6. Local privacy-preserving notification dispatch.
 * 7. Delivery ACK generation and cross-transport routing.
 */
class NexaUniversalTransportIngest(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val userDao: UserDao,
    private val contactDao: ContactDao?,
    private val secureStorage: SecureStorage? = null,
    private val keystoreManager: CryptoKeystoreManager? = null,
    private val doubleRatchetEngine: DoubleRatchetEngine,
    private val envelopeDao: DtnEnvelopeDao? = null,
    private val notificationDispatcher: NexaNotificationDispatcher? = null,
    private val messageDedupDao: MessageDedupDao? = null,
    private val messageTransportDao: MessageTransportDao? = null,
    private val ackDispatcher: (suspend (NexaWirePacket, TransportMedium) -> Unit)? = null
) {

    private val _typingEvents = MutableSharedFlow<Pair<String, Boolean>>(replay = 1, extraBufferCapacity = 64)
    val typingEvents: SharedFlow<Pair<String, Boolean>> = _typingEvents.asSharedFlow()

    var peerSyncHandler: (suspend (NexaWirePacket, TransportMedium) -> Unit)? = null

    /**
     * Ingest a decoded NexaWirePacket from any transport medium.
     */
    suspend fun ingestWirePacket(
        packet: NexaWirePacket,
        medium: TransportMedium
    ): Boolean = withContext(Dispatchers.IO) {
        if (!validatePacket(packet)) {
            Log.w(TAG, "[$medium] Dropped malformed packet with missing packetId or type")
            return@withContext false
        }

        val myNxId = getMyNxId()
        if (!validateRoutingContext(packet, myNxId)) {
            Log.w(TAG, "[$medium] Dropped packet ${packet.packetId}: route validation failed for local recipient $myNxId")
            return@withContext false
        }

        when (packet.type) {
            PacketType.PACKET_ACK -> handleAckPacket(packet, medium)
            PacketType.PACKET_CUSTODY_ACK -> handleCustodyAckPacket(packet, medium)
            PacketType.PACKET_TYPING -> handleTypingPacket(packet, medium)
            PacketType.PACKET_PING -> true
            PacketType.PACKET_MESSAGE -> handleMessagePacket(packet, medium)
            PacketType.PACKET_PEER_SYNC -> handlePeerSyncPacket(packet, medium)
            else -> {
                Log.d(TAG, "[$medium] Ignored non-chat packet type: ${packet.type}")
                false
            }
        }
    }

    private suspend fun handlePeerSyncPacket(packet: NexaWirePacket, medium: TransportMedium): Boolean {
        return peerSyncHandler?.let {
            it.invoke(packet, medium)
            true
        } ?: false
    }

    /**
     * Ingest raw binary bytes from any transport medium. Automatically detects whether the
     * payload is a DtnEnvelope, a Protobuf NexaWirePacket, or an EncryptedEnvelope.
     */
    suspend fun ingestRawBytes(
        rawBytes: ByteArray,
        medium: TransportMedium
    ): Boolean = withContext(Dispatchers.IO) {
        if (rawBytes.isEmpty()) return@withContext false

        // 1. Check if payload is a sealed DtnEnvelope
        val isNxdtMagic = rawBytes.size >= 4 &&
                rawBytes[0] == 0x4E.toByte() &&
                rawBytes[1] == 0x58.toByte() &&
                rawBytes[2] == 0x44.toByte() &&
                rawBytes[3] == 0x54.toByte()

        if (isNxdtMagic) {
            val dtnEnvelope = DtnEnvelope.deserialize(rawBytes)
            return@withContext if (dtnEnvelope != null) {
                processDtnEnvelope(dtnEnvelope, medium)
            } else {
                Log.w(TAG, "[$medium] Ingestion dropped: Malformed or invalid NXDT envelope")
                false
            }
        }

        val dtnEnvelope = DtnEnvelope.deserialize(rawBytes)
        if (dtnEnvelope != null) {
            return@withContext processDtnEnvelope(dtnEnvelope, medium)
        }

        // 2. Check if payload is a Protobuf NexaWirePacket
        try {
            val wirePacket = NexaWirePacket.decode(rawBytes)
            if (wirePacket.type != null && wirePacket.packetId.isNotBlank()) {
                return@withContext ingestWirePacket(wirePacket, medium)
            }
        } catch (_: Exception) {
            // Not a Protobuf wire packet, attempt next decoder
        }

        // 3. Check if payload is a raw 49-byte overhead EncryptedEnvelope
        val encEnvelope = EncryptedEnvelope.deserialize(rawBytes)
        if (encEnvelope != null) {
            val myNxId = getMyNxId()
            val synthesizedPacket = NexaWirePacket(
                packetId = UUID.randomUUID().toString(),
                type = PacketType.PACKET_MESSAGE,
                senderNxId = "UNKNOWN_PEER",
                recipientNxId = myNxId,
                timestamp = System.currentTimeMillis(),
                message = encEnvelope.toProtobufPayload()
            )
            return@withContext ingestWirePacket(synthesizedPacket, medium)
        }

        Log.w(TAG, "[$medium] Ingestion dropped: Unrecognized wire payload format (${rawBytes.size} bytes)")
        false
    }

    private suspend fun handleAckPacket(packet: NexaWirePacket, medium: TransportMedium): Boolean {
        val ack = packet.ack ?: return false
        val targetPacketId = ack.originalPacketId
        if (targetPacketId.isBlank()) return false

        // Security validation for Delivery & Read ACKs
        if (ack.status == AckStatus.ACK_DEVICE_DELIVERED || ack.status == AckStatus.ACK_DEVICE_READ) {
            val existingMsg = messageDao.getMessageByPacketId(targetPacketId)
            if (existingMsg != null) {
                // Anti-spoofing: Recipient mismatch check
                if (ack.recipientNxId.isNotBlank() && ack.recipientNxId != existingMsg.recipientNxId) {
                    Log.e(TAG, "[$medium] SECURITY REJECTION: Delivery ACK recipient mismatch! Expected ${existingMsg.recipientNxId}, got ${ack.recipientNxId}")
                    return false
                }
                // Cryptographic Ed25519 signature verification if signature is present and contact is known
                if (ack.signature.isNotEmpty() && contactDao != null) {
                    val contact = contactDao.getContact(existingMsg.recipientNxId)
                    if (contact != null && contact.publicKeyHex.length == 64) {
                        val digest = AckDigestHelper.computeDeliveryAckDigest(
                            originalPacketId = ack.originalPacketId,
                            recipientNxId = ack.recipientNxId,
                            deviceId = ack.deviceId,
                            status = ack.status,
                            timestamp = ack.timestamp
                        )
                        if (!Ed25519Signer.verify(digest, ack.signature, hexToBytes(contact.publicKeyHex))) {
                            Log.e(TAG, "[$medium] SECURITY REJECTION: Delivery ACK digital signature verification failed for $targetPacketId")
                            return false
                        }
                    }
                }
            }
        }

        val newStatus = when (ack.status) {
            AckStatus.ACK_SERVER_RECEIVED -> MessageStatus.QUEUED
            AckStatus.ACK_DEVICE_DELIVERED -> MessageStatus.DELIVERED
            AckStatus.ACK_DEVICE_READ -> MessageStatus.READ
            AckStatus.ACK_UNKNOWN -> return false
        }

        messageDao.updateMessageStatus(targetPacketId, newStatus.name)
        Log.d(TAG, "[$medium] Updated message $targetPacketId status to $newStatus via validated Delivery ACK")
        return true
    }

    private suspend fun handleCustodyAckPacket(packet: NexaWirePacket, medium: TransportMedium): Boolean {
        val custodyAck = packet.custodyAck ?: return false
        val targetPacketId = custodyAck.originalPacketId
        if (targetPacketId.isBlank()) return false

        // Validate digital signature if contact is known
        if (custodyAck.signature.isNotEmpty() && contactDao != null) {
            val contact = contactDao.getContact(custodyAck.custodianNxId)
            if (contact != null && contact.publicKeyHex.length == 64) {
                val digest = AckDigestHelper.computeCustodyAckDigest(
                    originalPacketId = custodyAck.originalPacketId,
                    custodianNxId = custodyAck.custodianNxId,
                    status = custodyAck.status,
                    timestamp = custodyAck.timestamp
                )
                if (!Ed25519Signer.verify(digest, custodyAck.signature, hexToBytes(contact.publicKeyHex))) {
                    Log.e(TAG, "[$medium] SECURITY REJECTION: Custody ACK signature verification failed from ${custodyAck.custodianNxId}")
                    return false
                }
            }
        }

        // 1. Author's outgoing message custody update
        val outgoingMsg = messageDao.getMessageByPacketId(targetPacketId)
        if (outgoingMsg != null && outgoingMsg.isOutgoing) {
            // Relayed through intermediate custody: MUST NEVER mark as DELIVERED!
            if (outgoingMsg.status == MessageStatus.CREATED.name ||
                outgoingMsg.status == MessageStatus.PENDING.name ||
                outgoingMsg.status == MessageStatus.QUEUED.name ||
                outgoingMsg.status == MessageStatus.SENT.name
            ) {
                messageDao.updateMessageStatus(targetPacketId, MessageStatus.RELAYED.name)
                Log.i(TAG, "[$medium] Outgoing message $targetPacketId custody accepted by ${custodyAck.custodianNxId}. Delivery state: RELAYED")
            }
        }

        // 2. Intermediate relay node custody transfer update
        if (envelopeDao != null) {
            val heldEnvelope = envelopeDao.getEnvelopeById(targetPacketId)
            if (heldEnvelope != null) {
                if (custodyAck.status == CustodyStatus.CUSTODY_ACCEPTED || custodyAck.status == CustodyStatus.CUSTODY_TRANSFERRED) {
                    envelopeDao.markCustodyTransferred(
                        envelopeIdHex = targetPacketId,
                        custodyState = CustodyState.CUSTODY_TRANSFERRED.name,
                        nextHopNodeId = custodyAck.custodianNxId,
                        transferredAt = System.currentTimeMillis()
                    )
                    Log.i(TAG, "[$medium] Custody successfully transferred for DTN envelope $targetPacketId to downstream custodian ${custodyAck.custodianNxId}")
                }
            }
        }

        return true
    }

    private fun handleTypingPacket(packet: NexaWirePacket, medium: TransportMedium): Boolean {
        packet.typing?.let { t ->
            _typingEvents.tryEmit(Pair(t.conversationId, t.isTyping))
        }
        return true
    }

    private suspend fun handleMessagePacket(
        packet: NexaWirePacket,
        medium: TransportMedium
    ): Boolean {
        val myNxId = getMyNxId()
        val senderNxId = packet.senderNxId
        val payload = packet.message ?: run {
            Log.w(TAG, "[$medium] Dropped PACKET_MESSAGE with null message payload")
            return false
        }

        val conversationId = deriveConversationId(myNxId, senderNxId)
        val payloadHash = computePayloadSha256(payload)

        // --- 1. Persistent Cross-Transport Deduplication & Conflict Gatekeeper ---
        val existingDedup = messageDedupDao?.getDedupEntry(packet.packetId)
        if (existingDedup != null) {
            // Check conflict: same packetId, different sender or altered payload hash
            if (existingDedup.senderNxId != senderNxId || existingDedup.payloadHashHex != payloadHash) {
                Log.e(
                    TAG,
                    "[$medium] SECURITY ALERT: Packet collision/conflict detected for packet ${packet.packetId}! Existing (sender=${existingDedup.senderNxId}, hash=${existingDedup.payloadHashHex}) vs incoming (sender=$senderNxId, hash=$payloadHash). Rejecting packet."
                )
                messageDedupDao.updateStatus(packet.packetId, MessageDedupEntity.STATUS_CONFLICT)
                return false
            }

            // Valid duplicate: record transport observation
            messageTransportDao?.recordObservation(
                MessageTransportEntity(
                    packetId = packet.packetId,
                    transportMedium = medium.name,
                    observedAt = System.currentTimeMillis()
                )
            )

            Log.i(
                TAG,
                "[$medium] Deduplication Gatekeeper: Suppressed duplicate packet ${packet.packetId} (first seen via ${existingDedup.firstTransport})"
            )

            // If already processed, ensure delivery ACK is sent (idempotent ACK) but suppress decryption and UI notification
            if (existingDedup.status == MessageDedupEntity.STATUS_PROCESSED) {
                val deliveryAck = createSignedDeliveryAck(packet.packetId, senderNxId)
                ackDispatcher?.invoke(deliveryAck, medium)
            }
            return true
        }

        // Legacy fallback check in messageDao
        val existing = messageDao.getMessageByPacketId(packet.packetId)
        if (existing != null) {
            Log.i(
                TAG,
                "[$medium] Deduplication Gatekeeper: Suppressed duplicate packet ${packet.packetId} (already stored via ${existing.transportType})"
            )
            return true
        }

        // --- 2. Atomic Reservation in message_dedup_index ---
        val reservation = MessageDedupEntity(
            packetId = packet.packetId,
            senderNxId = senderNxId,
            recipientNxId = myNxId,
            conversationId = conversationId,
            payloadHashHex = payloadHash,
            firstObservedAt = System.currentTimeMillis(),
            firstTransport = medium.name,
            status = MessageDedupEntity.STATUS_RESERVED
        )
        val insertResult = messageDedupDao?.insertDedupEntry(reservation) ?: 1L
        if (insertResult == -1L) {
            // Concurrent race won by another thread; record observation and return
            messageTransportDao?.recordObservation(
                MessageTransportEntity(
                    packetId = packet.packetId,
                    transportMedium = medium.name,
                    observedAt = System.currentTimeMillis()
                )
            )
            return true
        }

        // Record initial transport observation
        messageTransportDao?.recordObservation(
            MessageTransportEntity(
                packetId = packet.packetId,
                transportMedium = medium.name,
                observedAt = System.currentTimeMillis()
            )
        )

        // --- 3. Double Ratchet AEAD Decryption & MAC Verification ---
        val envelope = EncryptedEnvelope.fromProtobufPayload(payload)
        val decryptedBytes = doubleRatchetEngine.ratchetDecrypt(senderNxId, envelope)

        val decryptedText = if (decryptedBytes != null) {
            String(decryptedBytes, StandardCharsets.UTF_8)
        } else {
            Log.w(TAG, "[$medium] Double Ratchet decryption failed for packet ${packet.packetId} from $senderNxId")
            "[Encrypted Message - Verification Failed]"
        }

        // --- 4. Remote Control Packets (PIN, PROFILE_UPDATE) ---
        if (decryptedBytes != null) {
            if (decryptedText.startsWith("{") &&
                (decryptedText.contains("\"type\":\"PIN_MESSAGE\"") || decryptedText.contains("\"type\":\"UNPIN_MESSAGE\""))
            ) {
                handlePinControlPacket(decryptedText)
                messageDedupDao?.updateStatus(packet.packetId, MessageDedupEntity.STATUS_PROCESSED)
                return true
            }
            if (decryptedText.startsWith("{") && decryptedText.contains("\"type\":\"PROFILE_UPDATE\"")) {
                handleProfileUpdateControlPacket(decryptedText)
                messageDedupDao?.updateStatus(packet.packetId, MessageDedupEntity.STATUS_PROCESSED)
                return true
            }
        }

        val messageStatus = if (decryptedBytes != null) MessageStatus.DELIVERED else MessageStatus.FAILED

        val parsedMessage = if (decryptedBytes != null) {
            com.nexa.app.core.util.SafeMessageParser.parseIncomingPayload(decryptedText)
        } else {
            com.nexa.app.core.util.SafeMessageParser.DecryptedChatMessage(plainText = decryptedText)
        }

        // --- 5. Atomic Local Persistence with Inbound Transport Metadata ---
        val nextSeq = messageDao.getNextLocalSequence(conversationId)
        val messageEntity = MessageEntity(
            packetId = packet.packetId,
            conversationId = conversationId,
            senderNxId = senderNxId,
            recipientNxId = myNxId,
            plainTextContent = parsedMessage.plainText,
            status = messageStatus.name,
            timestamp = packet.timestamp,
            isOutgoing = false,
            transportType = medium.toDomainTransportType().name,
            replyToMessageId = parsedMessage.replyToMessageId,
            replyToSenderNxId = parsedMessage.replyToSenderNxId,
            replyToSnippet = parsedMessage.replyToSnippet,
            localSequence = nextSeq
        )
        messageDao.insertMessageIfNotExists(messageEntity)

        // Update reservation to PROCESSED
        messageDedupDao?.updateStatus(packet.packetId, MessageDedupEntity.STATUS_PROCESSED)

        // --- 6. Upsert Chat Conversation preserving pin/archive/mute metadata ---
        val existingChat = chatDao.getChatById(conversationId)
        val unread = (existingChat?.unreadCount ?: 0) + 1
        val updated = chatDao.updateLastMessage(
            conversationId = conversationId,
            text = parsedMessage.plainText,
            timestamp = packet.timestamp,
            lastMessageId = packet.packetId,
            lastSenderNxId = senderNxId
        )
        if (updated > 0) {
            chatDao.incrementUnread(conversationId)
        } else {
            chatDao.upsertChat(
                ChatEntity(
                    conversationId = conversationId,
                    participantNxId = senderNxId,
                    participantName = existingChat?.participantName ?: senderNxId,
                    lastMessageText = parsedMessage.plainText,
                    lastMessageTimestamp = packet.timestamp,
                    lastMessageId = packet.packetId,
                    lastMessageSenderNxId = senderNxId,
                    unreadCount = unread
                )
            )
        }

        // --- 7. Privacy-Preserving Local Notification (respects mute status) ---
        val isMuted = existingChat?.isMuted == true && (existingChat.mutedUntil == 0L || existingChat.mutedUntil > System.currentTimeMillis())
        if (decryptedBytes != null && !isMuted) {
            val contact = contactDao?.getContact(senderNxId)
            val resolvedName = contact?.effectiveDisplayName ?: existingChat?.participantName ?: senderNxId
            notificationDispatcher?.postDirectMessageNotification(
                conversationId = conversationId,
                senderNxId = senderNxId,
                senderDisplayName = resolvedName,
                decryptedText = parsedMessage.plainText,
                messageId = packet.packetId
            )

            // --- 8. Dispatch Authenticated Delivery ACK (ACK_DEVICE_DELIVERED) ---
            val deliveryAck = createSignedDeliveryAck(packet.packetId, senderNxId)
            ackDispatcher?.invoke(deliveryAck, medium)
        }

        return true
    }

    private fun computePayloadSha256(payload: EncryptedMessagePayload): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        if (payload.ciphertext.isNotEmpty()) md.update(payload.ciphertext)
        if (payload.authTag.isNotEmpty()) md.update(payload.authTag)
        if (payload.aeadNonce.isNotEmpty()) md.update(payload.aeadNonce)
        payload.header?.let { h ->
            if (h.ratchetPublicKey.isNotEmpty()) md.update(h.ratchetPublicKey)
            if (h.sessionId.isNotEmpty()) md.update(h.sessionId.toByteArray(StandardCharsets.UTF_8))
        }
        return bytesToHex(md.digest())
    }

    private suspend fun processDtnEnvelope(
        envelope: DtnEnvelope,
        medium: TransportMedium
    ): Boolean {
        val nowSec = System.currentTimeMillis() / 1000L
        if (envelope.hopLimit <= 0) {
            Log.d(TAG, "[$medium] Dropped DTN envelope [${envelope.envelopeIdHex.take(8)}]: Hop limit reached zero")
            return false
        }
        if (envelope.expiresAtEpochSec <= nowSec) {
            Log.d(TAG, "[$medium] Dropped DTN envelope [${envelope.envelopeIdHex.take(8)}]: TTL expired")
            return false
        }

        // Cryptographic integrity check: Reject invalid or tampered NXDT envelopes
        if (envelope.protocolVersion == DtnConstants.PROTOCOL_VERSION_1 && !envelope.verifySignature()) {
            Log.w(TAG, "[$medium] Dropped DTN envelope [${envelope.envelopeIdHex.take(8)}]: Cryptographic signature verification failed")
            return false
        }

        val isForMe = isEnvelopeDestinedForMe(envelope)
        return if (isForMe) {
            Log.i(TAG, "[$medium] DTN Envelope [${envelope.envelopeIdHex.take(8)}] matched local Blind Token! Ingesting...")
            val result = ingestRawBytes(envelope.encryptedPayload, TransportMedium.OFFLINE_MESH)
            envelopeDao?.deleteEnvelope(envelope.envelopeIdHex)
            result
        } else {
            // Not for me -> Store in DTN vault for multi-hop store-and-forward relay custody
            if (envelopeDao != null) {
                val decrementedEnvelope = envelope.withDecrementedHop(nowSec)
                if (decrementedEnvelope == null) {
                    Log.d(TAG, "[$medium] Dropped DTN envelope [${envelope.envelopeIdHex.take(8)}]: Hop limit exhausted or invalid")
                    return false
                }
                val entity = DtnEnvelopeEntity(
                    envelopeIdHex = decrementedEnvelope.envelopeIdHex,
                    blindTokenHex = decrementedEnvelope.blindTokenHex,
                    hopLimit = decrementedEnvelope.hopLimit.toInt(),
                    rawEnvelopeBytes = decrementedEnvelope.serialize(),
                    expiresAtEpochSec = decrementedEnvelope.expiresAtEpochSec,
                    custodyState = CustodyState.CUSTODY_ACCEPTED.name,
                    custodianNodeId = getMyNxId()
                )
                val rowId = envelopeDao.insertEnvelope(entity)
                if (rowId > 0) {
                    Log.i(
                        TAG,
                        "[$medium] Mule Custody: Stored DTN envelope [${decrementedEnvelope.envelopeIdHex.take(8)}] with remaining hops: ${decrementedEnvelope.hopLimit}"
                    )
                    // Persisted successfully: Send signed Custody ACK to relaying peer
                    val custodyAck = createSignedCustodyAck(decrementedEnvelope.envelopeIdHex, CustodyStatus.CUSTODY_ACCEPTED)
                    ackDispatcher?.invoke(custodyAck, medium)
                    return true
                } else {
                    Log.w(
                        TAG,
                        "[$medium] Mule Custody: Persistence failed for DTN envelope [${decrementedEnvelope.envelopeIdHex.take(8)}]. Withholding Custody ACK."
                    )
                    return false
                }
            }
            true
        }
    }

    private suspend fun createSignedDeliveryAck(targetPacketId: String, senderNxId: String): NexaWirePacket {
        val myNxId = getMyNxId()
        val now = System.currentTimeMillis()
        val deviceId = secureStorage?.getDeviceId() ?: "DEV-LOCAL"
        val digest = AckDigestHelper.computeDeliveryAckDigest(
            originalPacketId = targetPacketId,
            recipientNxId = myNxId,
            deviceId = deviceId,
            status = AckStatus.ACK_DEVICE_DELIVERED,
            timestamp = now
        )
        val signature = if (keystoreManager != null && secureStorage != null) {
            try {
                val idKeyPair = keystoreManager.getOrCreateIdentityKeyPair(secureStorage)
                keystoreManager.withDecryptedPrivateKey(idKeyPair) { rawPriv ->
                    Ed25519Signer.sign(digest, rawPriv)
                }
            } catch (_: Exception) {
                ByteArray(0)
            }
        } else ByteArray(0)

        return NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_ACK,
            senderNxId = myNxId,
            recipientNxId = senderNxId,
            timestamp = now,
            ack = DeliveryAckPayload(
                originalPacketId = targetPacketId,
                status = AckStatus.ACK_DEVICE_DELIVERED,
                recipientNxId = myNxId,
                deviceId = deviceId,
                timestamp = now,
                signature = signature
            )
        )
    }

    private suspend fun createSignedCustodyAck(originalEnvelopeId: String, status: CustodyStatus): NexaWirePacket {
        val myNxId = getMyNxId()
        val now = System.currentTimeMillis()
        val digest = AckDigestHelper.computeCustodyAckDigest(
            originalPacketId = originalEnvelopeId,
            custodianNxId = myNxId,
            status = status,
            timestamp = now
        )
        val signature = if (keystoreManager != null && secureStorage != null) {
            try {
                val idKeyPair = keystoreManager.getOrCreateIdentityKeyPair(secureStorage)
                keystoreManager.withDecryptedPrivateKey(idKeyPair) { rawPriv ->
                    Ed25519Signer.sign(digest, rawPriv)
                }
            } catch (_: Exception) {
                ByteArray(0)
            }
        } else ByteArray(0)

        return NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_CUSTODY_ACK,
            senderNxId = myNxId,
            recipientNxId = "PEER",
            timestamp = now,
            custodyAck = CustodyAckPayload(
                originalPacketId = originalEnvelopeId,
                custodianNxId = myNxId,
                status = status,
                timestamp = now,
                signature = signature
            )
        )
    }

    private suspend fun isEnvelopeDestinedForMe(envelope: DtnEnvelope): Boolean {
        val user = userDao.getLocalProfile() ?: return false
        val myNxId = user.nxId
        val blindToken = envelope.blindToken
        val nowSec = System.currentTimeMillis() / 1000L

        // 1. Check Pairwise Rotating Routing Tokens (Phase C)
        if (contactDao != null && keystoreManager != null && secureStorage != null) {
            try {
                val dhKeys = keystoreManager.getOrCreateDhIdentityKeyPair(secureStorage)
                val myPriv = keystoreManager.decryptWithMasterKey(dhKeys.iv, dhKeys.encryptedPrivateKey)
                val contacts = contactDao.getAllContactsList()
                for (contact in contacts) {
                    if (contact.publicKeyHex.length == 64) {
                        val contactPub = hexToBytes(contact.publicKeyHex)
                        val pairwiseSecret = PairwiseRoutingTokenGenerator.derivePairwiseDhSecret(myPriv, contactPub)
                        val routingKey = PairwiseRoutingTokenGenerator.deriveRoutingKey(pairwiseSecret, contact.nxId, myNxId)
                        val inWindow = PairwiseRoutingTokenGenerator.isTokenInWindowForEnvelope(
                            candidateToken = blindToken,
                            routingKey = routingKey,
                            createdAtEpochSec = envelope.createdAtEpochSec,
                            expiresAtEpochSec = envelope.expiresAtEpochSec,
                            nowSec = nowSec
                        )
                        if (inWindow) {
                            java.util.Arrays.fill(pairwiseSecret, 0.toByte())
                            java.util.Arrays.fill(myPriv, 0.toByte())

                            // Authenticate Sender Identity (Phase B Security Enforcement)
                            if (envelope.protocolVersion == DtnConstants.PROTOCOL_VERSION_1) {
                                val senderPub = envelope.senderPublicKey
                                if (senderPub == null || senderPub.size != 32) {
                                    Log.w(TAG, "Security Rejection: Inbound NXDT envelope missing senderPublicKey")
                                    return false
                                }
                                if (!bytesToHex(senderPub).equals(contact.publicKeyHex, ignoreCase = true)) {
                                    Log.e(
                                        TAG,
                                        "SECURITY ALERT: Inbound envelope sender key mismatch! Claimed routing peer: ${contact.nxId}. Dropping spoofed envelope."
                                    )
                                    return false
                                }
                            }
                            return true
                        }
                        java.util.Arrays.fill(pairwiseSecret, 0.toByte())
                    }
                }
                java.util.Arrays.fill(myPriv, 0.toByte())
            } catch (e: Exception) {
                Log.w(TAG, "Error checking pairwise routing tokens in ingest: ${e.message}")
            }
        }

        // 2. Legacy fallback
        val pkHex = user.publicKeyHex
        if (pkHex.isNotBlank()) {
            val pkBytes = hexToBytes(pkHex)
            val currentDay = BlindTokenGenerator.currentEpochDay()
            val todayToken = BlindTokenGenerator.generateToken(pkBytes, currentDay)
            val yesterdayToken = BlindTokenGenerator.generateToken(pkBytes, currentDay - 1)
            if (blindToken.contentEquals(todayToken) || blindToken.contentEquals(yesterdayToken)) {
                return true
            }
        }

        return false
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i ushr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }

    private suspend fun handlePinControlPacket(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            when (json.optString("type")) {
                "PIN_MESSAGE" -> {
                    val targetMessageId = json.getString("targetMessageId")
                    val expiresAtRaw = json.optLong("expiresAt", -1L)
                    val expiresAt = if (expiresAtRaw > 0L) expiresAtRaw else null
                    val pinnedAt = json.optLong("pinnedAt", System.currentTimeMillis())
                    messageDao.updatePinStatus(
                        targetMessageId,
                        isPinned = true,
                        pinnedExpiresAt = expiresAt,
                        pinnedAt = pinnedAt
                    )
                    Log.d(TAG, "Processed remote PIN_MESSAGE for message $targetMessageId")
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
            val json = JSONObject(jsonString)
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

    private fun validatePacket(packet: NexaWirePacket): Boolean {
        return packet.packetId.isNotBlank() && packet.type != null
    }

    private fun validateRoutingContext(packet: NexaWirePacket, myNxId: String): Boolean {
        if (packet.senderNxId.isBlank()) {
            Log.w(TAG, "Rejected packet with blank sender_nx_id (recipient=$myNxId)")
            return false
        }

        if (packet.recipientNxId.isNotBlank() &&
            packet.recipientNxId != myNxId &&
            packet.recipientNxId != "PEER" &&
            packet.recipientNxId != "BROADCAST"
        ) {
            Log.w(TAG, "SECURITY REJECTION: Packet ${packet.packetId} is intended for ${packet.recipientNxId}, but local account is $myNxId")
            return false
        }

        if (packet.type == PacketType.PACKET_TYPING) {
            val expectedConversationId = deriveConversationId(myNxId, packet.senderNxId)
            val claimedConversationId = packet.typing?.conversationId
            if (!claimedConversationId.isNullOrBlank() && claimedConversationId != expectedConversationId) {
                Log.w(TAG, "SECURITY REJECTION: Typing packet ${packet.packetId} claimed conversation '$claimedConversationId' but expected '$expectedConversationId'")
                return false
            }
        }

        return true
    }

    private suspend fun getMyNxId(): String {
        return secureStorage?.getActiveNxId() ?: userDao.getActiveNxId() ?: "NX-ANONYMOUS"
    }

    private fun deriveConversationId(id1: String, id2: String): String {
        return if (id1 < id2) "${id1}_$id2" else "${id2}_$id1"
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    companion object {
        private const val TAG = "NexaUniversalIngest"
    }
}
