package com.nexa.app.data.repository.sms

import android.util.Log
import androidx.room.withTransaction
import com.nexa.app.core.sms.SmsBlockReason
import com.nexa.app.core.sms.SmsEligibility
import com.nexa.app.core.sms.SmsEligibilityEvaluator
import com.nexa.app.core.sms.SmsEligibilityInput
import com.nexa.app.core.sms.SmsConversationGuard
import com.nexa.app.core.sms.SmsFailureClassifier
import com.nexa.app.core.sms.SmsFallbackState
import com.nexa.app.core.sms.SmsHandoffState
import com.nexa.app.core.sms.SmsImportOutcome
import com.nexa.app.core.sms.SmsOutboundPlan
import com.nexa.app.core.sms.SmsPrepareOutcome
import com.nexa.app.core.sms.SmsSessionStatus
import com.nexa.app.core.sms.maskPhoneNumber
import com.nexa.app.core.sms.nx1.Nx1
import com.nexa.app.core.sms.nx1.Nx1Base64Url
import com.nexa.app.core.sms.nx1.Nx1Cipher
import com.nexa.app.core.sms.nx1.Nx1Codec
import com.nexa.app.core.sms.nx1.Nx1DecryptResult
import com.nexa.app.core.sms.nx1.Nx1InboundPart
import com.nexa.app.core.sms.nx1.Nx1MessagePacket
import com.nexa.app.core.sms.nx1.Nx1ParseResult
import com.nexa.app.core.sms.nx1.SmsAssemblyStatus
import com.nexa.app.core.sms.nx1.SmsMultipartAssembler
import com.nexa.app.core.sms.nx1.SmsSegmentCalculator
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.data.local.NexaDatabase
import com.nexa.app.data.local.dao.ChatDao
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.dao.MessageDao
import com.nexa.app.data.local.dao.SmsInboundPartDao
import com.nexa.app.data.local.dao.SmsPacketDao
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.dao.getAllContactsList
import com.nexa.app.data.local.entity.ChatEntity
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.data.local.entity.SmsInboundPartEntity
import com.nexa.app.data.local.entity.SmsPacketEntity
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.TransportType
import com.nexa.app.domain.repository.SmsFallbackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Encrypted SMS fallback transport (Mode A — zero-permission external composer).
 *
 * Responsibilities:
 *  - capability/eligibility enforcement (1-to-1 only, accepted contact, phone available, session ready)
 *  - independent NX1 encryption and packetisation
 *  - local-first persistence into the normal NX-ID conversation (same `messages` table)
 *  - authenticated application ACK generation and verification
 *  - strict identity resolution for inbound SMS
 *
 * It never sends SMS itself and never performs a network call.
 */
class SmsFallbackRepositoryImpl(
    private val database: NexaDatabase,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val contactDao: ContactDao,
    private val userDao: UserDao,
    private val secureStorage: SecureStorage?,
    private val sessionManager: SmsSessionManager,
    private val packetDao: SmsPacketDao,
    private val partDao: SmsInboundPartDao,
    private val assembler: SmsMultipartAssembler,
    private val internetAvailable: () -> Boolean = { false },
    private val meshAvailable: () -> Boolean = { false },
    private val smsCapable: () -> Boolean = { true },
    private val maxSegmentsPerMessage: Int = SmsSegmentCalculator.DEFAULT_SEGMENT_SAFETY_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis
) : SmsFallbackRepository {

    override suspend fun localProvisioningPublicKey(): String = sessionManager.localProvisioningPublicKeyHex()

    override suspend fun sessionStatus(peerNxId: String): SmsSessionStatus = sessionManager.status(peerNxId)

    override suspend fun registerPeerProvisioningKey(peerNxId: String, peerPublicKeyHex: String) {
        if (peerPublicKeyHex.length != 64) {
            Log.w(TAG, "Ignoring malformed SMS provisioning key for $peerNxId")
            return
        }
        val contact = contactDao.getContact(peerNxId)
        val status = contact?.status
        if (status != ContactStatus.MUTUAL_FRIEND && status != ContactStatus.CONTACT_VERIFIED) {
            Log.w(TAG, "Ignoring SMS provisioning key from non-accepted contact $peerNxId")
            return
        }
        sessionManager.registerPeerProvisioningKey(peerNxId, peerPublicKeyHex)
    }

    override suspend fun revokeSession(peerNxId: String) = sessionManager.revoke(peerNxId)

    override suspend fun eligibilityFor(
        peerNxId: String,
        conversationId: String,
        destinationPhone: String?
    ): SmsEligibility = withContext(Dispatchers.IO) {
        val contact = contactDao.getContact(peerNxId)
        val phone = resolvePhoneNumber(contact, destinationPhone)
        val session = sessionManager.status(peerNxId)
        val isGroup = SmsConversationGuard.isGroupConversation(conversationId, peerNxId)
        SmsEligibilityEvaluator.evaluate(
            SmsEligibilityInput(
                isGroupConversation = isGroup,
                contactStatusName = contact?.status?.name,
                hasPeerPhoneNumber = !phone.isNullOrBlank(),
                phoneNumberVisibleToUser = !phone.isNullOrBlank(),
                sessionReady = session.ready,
                sessionExpired = session.expired,
                sessionRevoked = session.revoked,
                simAvailable = smsCapable(),
                internetAvailable = internetAvailable(),
                meshAvailable = meshAvailable()
            )
        )
    }

    override suspend fun prepareOutbound(
        peerNxId: String,
        text: String,
        destinationPhone: String?,
        requestedConversationId: String?
    ): SmsPrepareOutcome = withContext(Dispatchers.IO) {
        val myNxId = myNxId()
        // Use the caller's real conversation id so group chats cannot be written into a direct
        // thread (and vice versa). Only fall back to the derived id when none is supplied.
        val conversationId = requestedConversationId?.takeIf { it.isNotBlank() }
            ?: deriveConversationId(myNxId, peerNxId)
        val trimmed = text.trim()

        if (SmsConversationGuard.isGroupConversation(conversationId, peerNxId)) {
            return@withContext SmsPrepareOutcome.Blocked(
                SmsEligibility(
                    eligible = false,
                    state = SmsFallbackState.SMS_GROUP_DISABLED,
                    reason = SmsBlockReason.GROUP_CHAT,
                    userMessage = "Encrypted SMS is only available in one-to-one chats."
                )
            )
        }

        if (trimmed.isEmpty()) {
            return@withContext SmsPrepareOutcome.Failed(
                "Only text can be sent over encrypted SMS.",
                SmsFailureClassifier.modeAUnconfirmed()
            )
        }
        if (trimmed.toByteArray(StandardCharsets.UTF_8).size > Nx1.MAX_PLAINTEXT_BYTES) {
            return@withContext blockedPayloadTooLarge()
        }

        val eligibility = eligibilityFor(peerNxId, conversationId, destinationPhone)
        if (!eligibility.eligible) {
            return@withContext SmsPrepareOutcome.Blocked(eligibility)
        }

        val contact = contactDao.getContact(peerNxId)
        val destination = resolvePhoneNumber(contact, destinationPhone)
        if (destination.isNullOrBlank()) {
            return@withContext SmsPrepareOutcome.Blocked(
                SmsEligibility(
                    false,
                    SmsFallbackState.SMS_PHONE_UNAVAILABLE,
                    SmsBlockReason.PHONE_NUMBER_UNAVAILABLE,
                    "This contact has no phone number available for SMS fallback."
                )
            )
        }

        val keys = sessionManager.loadDirectionKeys(peerNxId)
            ?: return@withContext SmsPrepareOutcome.Blocked(
                SmsEligibility(
                    false,
                    SmsFallbackState.SMS_SESSION_NOT_READY,
                    SmsBlockReason.SESSION_MISSING,
                    SmsEligibilityEvaluator.SESSION_NOT_READY_MESSAGE
                )
            )

        val counter = sessionManager.allocateSendCounter(peerNxId)
            ?: return@withContext SmsPrepareOutcome.Blocked(
                SmsEligibility(
                    false,
                    SmsFallbackState.SMS_SESSION_NOT_READY,
                    SmsBlockReason.SESSION_MISSING,
                    SmsEligibilityEvaluator.SESSION_NOT_READY_MESSAGE
                )
            )

        val messageId = Nx1Codec.newMessageId()
        val packetId = UUID.randomUUID().toString()
        val timestamp = clock()

        val plaintext = trimmed.toByteArray(StandardCharsets.UTF_8)
        val payload = Nx1Cipher.encryptPart(
            keys = keys,
            counter = counter,
            messageId = messageId,
            senderNxId = myNxId,
            recipientNxId = peerNxId,
            plaintext = plaintext
        )
        keys.wipe()

        val slices = slicePayload(payload)
        if (slices.isEmpty()) return@withContext blockedPayloadTooLarge()
        if (slices.size > Nx1.MAX_PARTS) return@withContext blockedPayloadTooLarge()

        val tokens = slices.mapIndexed { index, slice ->
            Nx1Codec.encodeMessage(
                Nx1MessagePacket(
                    messageId = messageId,
                    partIndex = index,
                    totalParts = slices.size,
                    payload = slice
                )
            )
        }
        val body = tokens.joinToString("\n")
        val segmentCount = SmsSegmentCalculator.segmentCount(body)

        if (segmentCount > maxSegmentsPerMessage) {
            return@withContext SmsPrepareOutcome.Failed(
                "This message needs $segmentCount SMS parts (limit $maxSegmentsPerMessage). " +
                    "Shorten it, or use Internet messaging.",
                SmsFailureClassifier.modeAUnconfirmed()
            )
        }

        database.withTransaction {
            val nextSeq = messageDao.getNextLocalSequence(conversationId)
            messageDao.insertMessageIfNotExists(
                MessageEntity(
                    packetId = packetId,
                    conversationId = conversationId,
                    senderNxId = myNxId,
                    recipientNxId = peerNxId,
                    plainTextContent = trimmed,
                    status = MessageStatus.PENDING.name,
                    timestamp = timestamp,
                    isOutgoing = true,
                    transportType = TransportType.DATA_SMS.name,
                    localSequence = nextSeq
                )
            )

            val existingChat = chatDao.getChatById(conversationId)
            val updated = chatDao.updateLastMessage(
                conversationId = conversationId,
                text = trimmed,
                timestamp = timestamp,
                lastMessageId = packetId,
                lastSenderNxId = myNxId
            )
            if (updated == 0) {
                chatDao.upsertChat(
                    ChatEntity(
                        conversationId = conversationId,
                        participantNxId = peerNxId,
                        participantName = existingChat?.participantName
                            ?: contact?.effectiveDisplayName
                            ?: peerNxId,
                        lastMessageText = trimmed,
                        lastMessageTimestamp = timestamp,
                        lastMessageId = packetId,
                        lastMessageSenderNxId = myNxId,
                        unreadCount = 0
                    )
                )
            }

            packetDao.upsert(
                SmsPacketEntity(
                    messageId = messageId,
                    packetId = packetId,
                    peerNxId = peerNxId,
                    direction = SmsPacketEntity.DIRECTION_OUTBOUND,
                    keyVersion = keys.keyVersion,
                    sendCounter = counter,
                    totalParts = slices.size,
                    segmentCount = segmentCount,
                    handoffState = SmsHandoffState.QUEUED,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    deliveryChannel = SmsPacketEntity.CHANNEL_COMPOSER
                )
            )
        }

        Log.i(
            TAG,
            "Prepared SMS fallback packet (parts=${slices.size}, segments=$segmentCount, keyVersion=${keys.keyVersion}) for conversation"
        )

        SmsPrepareOutcome.Ready(
            SmsOutboundPlan(
                packetId = packetId,
                messageId = messageId,
                peerNxId = peerNxId,
                conversationId = conversationId,
                destinationNumber = destination,
                maskedDestination = maskPhoneNumber(destination),
                bodyText = body,
                parts = tokens,
                partCount = slices.size,
                segmentCount = segmentCount,
                keyVersion = keys.keyVersion,
                requiresConfirmation = segmentCount > 1
            )
        )
    }

    override suspend fun markHandedOffToSmsApp(packetId: String) = withContext(Dispatchers.IO) {
        val packet = packetDao.getByPacketId(packetId) ?: return@withContext
        packetDao.updateState(packet.messageId, SmsHandoffState.HANDED_OFF, clock(), null)
    }

    override suspend fun markSentUnconfirmed(packetId: String) = withContext(Dispatchers.IO) {
        val packet = packetDao.getByPacketId(packetId) ?: return@withContext
        packetDao.updateState(packet.messageId, SmsHandoffState.SENT_UNCONFIRMED, clock(), null)
        messageDao.updateMessageStatus(packetId, MessageStatus.SENT.name)
    }

    override suspend fun markFailed(packetId: String, failureKind: String, userMessage: String) =
        withContext(Dispatchers.IO) {
            val packet = packetDao.getByPacketId(packetId) ?: return@withContext
            packetDao.updateState(packet.messageId, SmsHandoffState.FAILED, clock(), failureKind)
            messageDao.updateMessageStatus(packetId, MessageStatus.FAILED.name)
            Log.w(TAG, "SMS fallback marked FAILED ($failureKind) for packet $packetId")
        }

    override suspend fun cancelQueued(packetId: String) = withContext(Dispatchers.IO) {
        val packet = packetDao.getByPacketId(packetId) ?: return@withContext
        packetDao.updateState(packet.messageId, SmsHandoffState.CANCELLED, clock(), null)
        messageDao.updateMessageStatus(packetId, MessageStatus.FAILED.name)
    }

    override suspend fun importBody(senderAddress: String, body: String): List<SmsImportOutcome> =
        withContext(Dispatchers.IO) {
            val tokens = Nx1Codec.extractTokens(body)
            if (tokens.isEmpty()) return@withContext listOf(SmsImportOutcome.NotNx1)

            val contact = findContactByPhoneAddress(senderAddress)
                ?: return@withContext tokens.map {
                    SmsImportOutcome.Rejected(
                        "UNKNOWN_SENDER",
                        "This number is not a verified NEXA contact. No conversation was created."
                    )
                }

            if (contact.status != ContactStatus.MUTUAL_FRIEND && contact.status != ContactStatus.CONTACT_VERIFIED) {
                return@withContext tokens.map {
                    SmsImportOutcome.Rejected("CONTACT_NOT_ACCEPTED", "This contact is not an accepted NEXA contact.")
                }
            }
            if (contact.publicKeyHex.length != 64) {
                return@withContext tokens.map {
                    SmsImportOutcome.Rejected("UNVERIFIED_IDENTITY", "This contact has no verified cryptographic identity.")
                }
            }

            val results = ArrayList<SmsImportOutcome>(tokens.size)
            for (token in tokens) {
                results.add(handleToken(token, contact))
            }
            results
        }

    private suspend fun handleToken(token: String, contact: ContactEntity): SmsImportOutcome {
        val parsed = Nx1Codec.parse(token)
        val myNxId = myNxId()

        when (parsed) {
            is Nx1ParseResult.Invalid -> {
                Log.w(TAG, "Rejected malformed NX1 token: ${parsed.reason}")
                return SmsImportOutcome.Rejected(parsed.reason, "This SMS is not a valid NEXA encrypted message.")
            }

            is Nx1ParseResult.Ack -> return handleAck(parsed.packet, contact, myNxId)

            is Nx1ParseResult.Message -> {
                val packet = parsed.packet
                val accepted = assembler.accept(
                    Nx1InboundPart(
                        messageId = packet.messageId,
                        senderNxId = contact.nxId,
                        partIndex = packet.partIndex,
                        totalParts = packet.totalParts,
                        payload = packet.payload,
                        receivedAt = clock()
                    )
                )
                when (accepted.status) {
                    SmsAssemblyStatus.INCOMPLETE -> return SmsImportOutcome.Incomplete(
                        packet.messageId,
                        accepted.receivedParts,
                        accepted.totalParts
                    )
                    SmsAssemblyStatus.DUPLICATE -> return SmsImportOutcome.Incomplete(
                        packet.messageId,
                        accepted.receivedParts,
                        accepted.totalParts
                    )
                    SmsAssemblyStatus.REJECTED -> return SmsImportOutcome.Rejected(
                        accepted.reason ?: "REASSEMBLY_REJECTED",
                        "This SMS could not be assembled safely."
                    )
                    SmsAssemblyStatus.COMPLETE -> Unit
                }

                val assembled = accepted.assembled
                    ?: return SmsImportOutcome.Rejected("EMPTY_PAYLOAD", "This SMS carried no payload.")

                val keys = sessionManager.loadDirectionKeys(contact.nxId)
                    ?: return SmsImportOutcome.Rejected(
                        "SESSION_MISSING",
                        SmsEligibilityEvaluator.SESSION_NOT_READY_MESSAGE
                    )

                val decrypted = Nx1Cipher.decryptPart(
                    keys = keys,
                    messageId = packet.messageId,
                    senderNxId = contact.nxId,
                    recipientNxId = myNxId,
                    payload = assembled
                )
                if (decrypted !is Nx1DecryptResult.Success) {
                    val reason = (decrypted as? Nx1DecryptResult.Failure)?.reason?.name ?: "DECRYPTION_FAILED"
                    keys.wipe()
                    Log.w(TAG, "Rejected NX1 packet: authentication failed ($reason)")
                    return SmsImportOutcome.Rejected(reason, "This SMS failed authentication and was discarded.")
                }

                if (!sessionManager.acceptInboundCounter(contact.nxId, decrypted.counter)) {
                    keys.wipe()
                    return SmsImportOutcome.Rejected("REPLAYED_PACKET", "This SMS was already received.")
                }

                val ackCounter = sessionManager.allocateAckCounter(contact.nxId)
                val ackPacket = ackCounter?.let {
                    Nx1Cipher.createAck(
                        keys = keys,
                        ackCounter = it,
                        messageId = packet.messageId,
                        ackSenderNxId = myNxId,
                        ackRecipientNxId = contact.nxId
                    )
                }
                keys.wipe()

                val plainText = String(decrypted.plaintext, StandardCharsets.UTF_8)
                val packetId = inboundPacketId(contact.nxId, packet.messageId)

                val duplicate = messageDao.getMessageByPacketId(packetId) != null
                if (!duplicate) {
                    val conversationId = deriveConversationId(myNxId, contact.nxId)
                    val timestamp = clock()
                    database.withTransaction {
                        val nextSeq = messageDao.getNextLocalSequence(conversationId)
                        messageDao.insertMessageIfNotExists(
                            MessageEntity(
                                packetId = packetId,
                                conversationId = conversationId,
                                senderNxId = contact.nxId,
                                recipientNxId = myNxId,
                                plainTextContent = plainText,
                                status = MessageStatus.DELIVERED.name,
                                timestamp = timestamp,
                                isOutgoing = false,
                                transportType = TransportType.DATA_SMS.name,
                                localSequence = nextSeq
                            )
                        )
                        val existingChat = chatDao.getChatById(conversationId)
                        val updated = chatDao.updateLastMessage(
                            conversationId = conversationId,
                            text = plainText,
                            timestamp = timestamp,
                            lastMessageId = packetId,
                            lastSenderNxId = contact.nxId
                        )
                        if (updated > 0) {
                            chatDao.incrementUnread(conversationId)
                        } else {
                            chatDao.upsertChat(
                                ChatEntity(
                                    conversationId = conversationId,
                                    participantNxId = contact.nxId,
                                    participantName = existingChat?.participantName
                                        ?: contact.effectiveDisplayName,
                                    lastMessageText = plainText,
                                    lastMessageTimestamp = timestamp,
                                    lastMessageId = packetId,
                                    lastMessageSenderNxId = contact.nxId,
                                    unreadCount = 1
                                )
                            )
                        }
                        packetDao.upsert(
                            SmsPacketEntity(
                                messageId = packet.messageId,
                                packetId = packetId,
                                peerNxId = contact.nxId,
                                direction = SmsPacketEntity.DIRECTION_INBOUND,
                                keyVersion = keys.keyVersion,
                                sendCounter = decrypted.counter,
                                totalParts = packet.totalParts,
                                segmentCount = accepted.totalParts,
                                handoffState = SmsHandoffState.DELIVERED_APP_ACK,
                                createdAt = timestamp,
                                updatedAt = timestamp,
                                deliveryChannel = SmsPacketEntity.CHANNEL_NONE
                            )
                        )
                        partDao.deleteMessage(packet.messageId, contact.nxId)
                    }
                } else {
                    partDao.deleteMessage(packet.messageId, contact.nxId)
                }

                return SmsImportOutcome.Delivered(
                    packetId = packetId,
                    peerNxId = contact.nxId,
                    plainText = plainText,
                    ackToken = ackPacket?.let { Nx1Codec.encodeAck(it) },
                    duplicate = duplicate
                )
            }
        }
    }

    private suspend fun handleAck(
        ack: com.nexa.app.core.sms.nx1.Nx1AckPacket,
        contact: ContactEntity,
        myNxId: String
    ): SmsImportOutcome {
        val record = packetDao.getByMessageId(ack.messageId)
            ?: return SmsImportOutcome.AckRejected("UNKNOWN_MESSAGE")
        if (record.direction != SmsPacketEntity.DIRECTION_OUTBOUND || record.peerNxId != contact.nxId) {
            return SmsImportOutcome.AckRejected("IDENTITY_MISMATCH")
        }

        val keys = sessionManager.loadDirectionKeys(contact.nxId)
            ?: return SmsImportOutcome.AckRejected("SESSION_MISSING")
        val verified = Nx1Cipher.verifyAck(
            keys = keys,
            ack = ack,
            ackSenderNxId = contact.nxId,
            ackRecipientNxId = myNxId
        )
        keys.wipe()
        if (!verified) {
            Log.w(TAG, "Rejected forged/foreign SMS ACK for message ${ack.messageId.take(4)}…")
            return SmsImportOutcome.AckRejected("INVALID_ACK_TAG")
        }

        if (record.handoffState == SmsHandoffState.DELIVERED_APP_ACK) {
            return SmsImportOutcome.AckAccepted(record.packetId, ack.messageId, duplicate = true)
        }
        if (!sessionManager.acceptInboundAckCounter(contact.nxId, ack.counter)) {
            return SmsImportOutcome.AckRejected("REPLAYED_ACK")
        }

        packetDao.updateState(ack.messageId, SmsHandoffState.DELIVERED_APP_ACK, clock(), null)
        messageDao.updateMessageStatus(record.packetId, MessageStatus.DELIVERED.name)
        return SmsImportOutcome.AckAccepted(record.packetId, ack.messageId, duplicate = false)
    }

    private suspend fun findContactByPhoneAddress(address: String): ContactEntity? {
        val normalized = normalizeDigits(address)
        if (normalized.isBlank()) return null
        val contacts = contactDao.getAllContactsList()
        return contacts.firstOrNull { contact ->
            val candidate = normalizeDigits(contact.phoneNumber)
            candidate.isNotBlank() && (
                candidate == normalized ||
                    candidate.takeLast(10) == normalized.takeLast(10)
                )
        }
    }

    private fun resolvePhoneNumber(contact: ContactEntity?, explicit: String?): String? {
        val explicitClean = explicit?.trim()?.takeIf { it.isNotBlank() }
        if (explicitClean != null) return explicitClean
        val stored = contact?.phoneNumber?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (contact.shouldShowPhoneNumber) stored else null
    }

    private fun inboundPacketId(senderNxId: String, messageId: String): String = "nx1-$senderNxId-$messageId"

    private fun normalizeDigits(raw: String?): String =
        raw?.filter { it.isDigit() } ?: ""

    private fun slicePayload(payload: ByteArray): List<ByteArray> {
        // A part's base64url body plus token framing must stay within MAX_PART_TOKEN_CHARS.
        val framingChars = Nx1.PREFIX.length + 1 + 1 + 1 + Nx1.MESSAGE_ID_CHARS + 1 + 4 + 1
        val maxBase64Chars = Nx1.MAX_PART_TOKEN_CHARS - framingChars
        val maxBytesPerPart = (maxBase64Chars * 3) / 4
        if (maxBytesPerPart <= 0) return emptyList()
        if (payload.isEmpty()) return emptyList()

        val slices = ArrayList<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(payload.size, offset + maxBytesPerPart)
            slices.add(payload.copyOfRange(offset, end))
            offset = end
        }
        return slices
    }

    private fun blockedPayloadTooLarge(): SmsPrepareOutcome = SmsPrepareOutcome.Blocked(
        SmsEligibility(
            false,
            SmsFallbackState.SMS_SESSION_NOT_READY,
            SmsBlockReason.PAYLOAD_TOO_LARGE,
            "Message is too long for encrypted SMS. Shorten it or use Internet messaging."
        )
    )

    private suspend fun myNxId(): String =
        secureStorage?.getActiveNxId() ?: userDao.getActiveNxId() ?: "NX-ANONYMOUS"

    private fun deriveConversationId(id1: String, id2: String): String =
        if (id1 < id2) "${id1}_$id2" else "${id2}_$id1"

    companion object {
        private const val TAG = "SmsFallbackRepository"
    }
}
