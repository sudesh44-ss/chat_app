package com.nexa.app.core.proto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

enum class PacketType(val value: Int) {
    PACKET_UNKNOWN(0),
    PACKET_MESSAGE(1),
    PACKET_ACK(2),
    PACKET_PING(3),
    PACKET_AUTH_CHALLENGE(4),
    PACKET_AUTH_RESPONSE(5),
    PACKET_AUTH_SUCCESS(6),
    PACKET_AUTH_FAILURE(7),
    PACKET_TYPING(10),
    PACKET_DEVICE_SYNC(11),
    PACKET_SIGNALING(12),
    PACKET_REGISTER_ROUTING_TOKENS(13),
    PACKET_PEER_SYNC(14),
    PACKET_CUSTODY_ACK(15),
    PACKET_PRESENCE(16),
    PACKET_MESSAGE_EDIT(17),
    PACKET_MESSAGE_DELETE(18);

    companion object {
        fun fromValue(v: Int): PacketType = entries.find { it.value == v } ?: PACKET_UNKNOWN
    }
}

enum class AckStatus(val value: Int) {
    ACK_UNKNOWN(0),
    ACK_SERVER_RECEIVED(1),
    ACK_DEVICE_DELIVERED(2),
    ACK_DEVICE_READ(3);

    companion object {
        fun fromValue(v: Int): AckStatus = entries.find { it.value == v } ?: ACK_UNKNOWN
    }
}

enum class CustodyStatus(val value: Int) {
    CUSTODY_STATUS_UNSPECIFIED(0),
    CUSTODY_ACCEPTED(1),
    CUSTODY_TRANSFERRED(2),
    CUSTODY_REJECTED(3),
    CUSTODY_EXPIRED(4);

    companion object {
        fun fromValue(v: Int): CustodyStatus = entries.find { it.value == v } ?: CUSTODY_STATUS_UNSPECIFIED
    }
}

data class DoubleRatchetHeader(
    val ratchetPublicKey: ByteArray = ByteArray(0),     // Exactly 32 bytes: sender's current X25519 ratchet public key (DHR)
    val previousChainLength: Int = 0,                    // PN: Number of messages in the previous sending chain
    val messageIndex: Int = 0,                           // N: Message index in the current sending chain
    val sessionId: String = "",                          // Canonical UUID identifying the pairwise conversation session
    val oneTimePrekeyId: Long = 0L,                      // Optional: OPK ID if this message initiates a new X3DH session
    val ephemeralPublicKey: ByteArray = ByteArray(0)     // Optional: 32-byte ephemeral public key (EK) for initial X3DH message
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DoubleRatchetHeader
        if (!ratchetPublicKey.contentEquals(other.ratchetPublicKey)) return false
        if (previousChainLength != other.previousChainLength) return false
        if (messageIndex != other.messageIndex) return false
        if (sessionId != other.sessionId) return false
        if (oneTimePrekeyId != other.oneTimePrekeyId) return false
        if (!ephemeralPublicKey.contentEquals(other.ephemeralPublicKey)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = ratchetPublicKey.contentHashCode()
        result = 31 * result + previousChainLength
        result = 31 * result + messageIndex
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + oneTimePrekeyId.hashCode()
        result = 31 * result + ephemeralPublicKey.contentHashCode()
        return result
    }
}

data class EncryptedMessagePayload(
    val conversationSeq: Long = 0L,
    val ciphertext: ByteArray = ByteArray(0),
    val nonce: ByteArray = ByteArray(0),
    val authTag: ByteArray = ByteArray(0),
    val header: DoubleRatchetHeader? = null,
    val aeadNonce: ByteArray = ByteArray(0)
) {
    fun hasHeader(): Boolean = header != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedMessagePayload
        if (conversationSeq != other.conversationSeq) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (!authTag.contentEquals(other.authTag)) return false
        if (header != other.header) return false
        if (!aeadNonce.contentEquals(other.aeadNonce)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = conversationSeq.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + authTag.contentHashCode()
        result = 31 * result + (header?.hashCode() ?: 0)
        result = 31 * result + aeadNonce.contentHashCode()
        return result
    }
}

data class DeliveryAckPayload(
    val originalPacketId: String = "",
    val status: AckStatus = AckStatus.ACK_UNKNOWN,
    val recipientNxId: String = "",
    val deviceId: String = "",
    val timestamp: Long = 0L,
    val signature: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeliveryAckPayload
        if (originalPacketId != other.originalPacketId) return false
        if (status != other.status) return false
        if (recipientNxId != other.recipientNxId) return false
        if (deviceId != other.deviceId) return false
        if (timestamp != other.timestamp) return false
        if (!signature.contentEquals(other.signature)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = originalPacketId.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + recipientNxId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

data class CustodyAckPayload(
    val originalPacketId: String = "",
    val custodianNxId: String = "",
    val status: CustodyStatus = CustodyStatus.CUSTODY_STATUS_UNSPECIFIED,
    val timestamp: Long = 0L,
    val signature: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CustodyAckPayload
        if (originalPacketId != other.originalPacketId) return false
        if (custodianNxId != other.custodianNxId) return false
        if (status != other.status) return false
        if (timestamp != other.timestamp) return false
        if (!signature.contentEquals(other.signature)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = originalPacketId.hashCode()
        result = 31 * result + custodianNxId.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

data class AuthChallengePayload(
    val challengeNonce: ByteArray = ByteArray(0),
    val timestamp: Long = 0L,
    val expiresAt: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AuthChallengePayload
        if (!challengeNonce.contentEquals(other.challengeNonce)) return false
        if (timestamp != other.timestamp) return false
        if (expiresAt != other.expiresAt) return false
        return true
    }

    override fun hashCode(): Int {
        var result = challengeNonce.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + expiresAt.hashCode()
        return result
    }
}

data class AuthResponsePayload(
    val signature: ByteArray = ByteArray(0),
    val publicKey: ByteArray = ByteArray(0),
    val timestamp: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AuthResponsePayload
        if (!signature.contentEquals(other.signature)) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (timestamp != other.timestamp) return false
        return true
    }

    override fun hashCode(): Int {
        var result = signature.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

data class TypingPayload(
    val conversationId: String = "",
    val isTyping: Boolean = false
)

data class DeviceSyncPayload(
    val syncId: String = "",
    val sourceDeviceId: String = "",
    val sequenceNumber: Long = 0L,
    val encryptedPayload: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeviceSyncPayload
        if (syncId != other.syncId) return false
        if (sourceDeviceId != other.sourceDeviceId) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (!encryptedPayload.contentEquals(other.encryptedPayload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = syncId.hashCode()
        result = 31 * result + sourceDeviceId.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        return result
    }
}

data class SignalingPayload(
    val transferId: Long = 0L,
    val action: String = "",
    val data: String = ""
)

data class RoutingTokenRegistrationPayload(
    val tokens: List<String> = emptyList()
)

enum class PeerSyncAction(val value: Int) {
    SYNC_UNKNOWN(0),
    SYNC_HELLO(1),
    SYNC_HELLO_ACK(2),
    SYNC_INVENTORY_REQ(3),
    SYNC_INVENTORY_RESP(4),
    SYNC_PULL_REQ(5),
    SYNC_PULL_RESP(6),
    SYNC_ACK(7);

    companion object {
        fun fromValue(v: Int): PeerSyncAction = entries.find { it.value == v } ?: SYNC_UNKNOWN
    }
}

data class PeerSyncPayload(
    val action: PeerSyncAction = PeerSyncAction.SYNC_UNKNOWN,
    val sessionId: String = "",
    val conversationId: String = "",
    val cursorTimestamp: Long = 0L,
    val cursorSequence: Long = 0L,
    val messageIds: List<String> = emptyList(),
    val envelopes: List<ByteArray> = emptyList(),
    val syncTimestamp: Long = 0L,
    val errorMessage: String = ""
)

enum class PresenceStatus(val value: Int) {
    PRESENCE_OFFLINE(0),
    PRESENCE_ONLINE(1),
    PRESENCE_HIDDEN(2);

    companion object {
        fun fromValue(v: Int): PresenceStatus = entries.find { it.value == v } ?: PRESENCE_OFFLINE
    }
}

data class PresencePayload(
    val nxId: String = "",
    val status: PresenceStatus = PresenceStatus.PRESENCE_OFFLINE,
    val timestamp: Long = 0L
)

data class MessageEditPayload(
    val targetPacketId: String = "",
    val newCiphertext: ByteArray = ByteArray(0),
    val authTag: ByteArray = ByteArray(0),
    val nonce: ByteArray = ByteArray(0),
    val aeadNonce: ByteArray = ByteArray(0),
    val editedAt: Long = 0L,
    val header: DoubleRatchetHeader? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageEditPayload
        if (targetPacketId != other.targetPacketId) return false
        if (!newCiphertext.contentEquals(other.newCiphertext)) return false
        if (!authTag.contentEquals(other.authTag)) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (!aeadNonce.contentEquals(other.aeadNonce)) return false
        if (editedAt != other.editedAt) return false
        if (header != other.header) return false
        return true
    }

    override fun hashCode(): Int {
        var result = targetPacketId.hashCode()
        result = 31 * result + newCiphertext.contentHashCode()
        result = 31 * result + authTag.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + aeadNonce.contentHashCode()
        result = 31 * result + editedAt.hashCode()
        result = 31 * result + (header?.hashCode() ?: 0)
        return result
    }
}

data class MessageDeletePayload(
    val targetPacketId: String = "",
    val deleteType: String = "FOR_EVERYONE",
    val deletedAt: Long = 0L
)

data class NexaWirePacket(
    val packetId: String = "",
    val type: PacketType = PacketType.PACKET_UNKNOWN,
    val senderNxId: String = "",
    val recipientNxId: String = "",
    val timestamp: Long = 0L,
    val message: EncryptedMessagePayload? = null,
    val ack: DeliveryAckPayload? = null,
    val authChallenge: AuthChallengePayload? = null,
    val authResponse: AuthResponsePayload? = null,
    val typing: TypingPayload? = null,
    val deviceSync: DeviceSyncPayload? = null,
    val signaling: SignalingPayload? = null,
    val routingTokens: RoutingTokenRegistrationPayload? = null,
    val peerSync: PeerSyncPayload? = null,
    val custodyAck: CustodyAckPayload? = null,
    val presence: PresencePayload? = null,
    val messageEdit: MessageEditPayload? = null,
    val messageDelete: MessageDeletePayload? = null,
    val groupId: String = ""
) {
    /**
     * Serializes this packet into standard binary Protobuf wire format.
     */
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()

        // Field 1: packet_id (string, wire type 2)
        if (packetId.isNotEmpty()) {
            writeTag(out, 1, 2)
            writeLengthDelimited(out, packetId.toByteArray(Charsets.UTF_8))
        }

        // Field 2: type (enum, wire type 0)
        if (type != PacketType.PACKET_UNKNOWN) {
            writeTag(out, 2, 0)
            writeVarint(out, type.value.toLong())
        }

        // Field 3: sender_nx_id (string, wire type 2)
        if (senderNxId.isNotEmpty()) {
            writeTag(out, 3, 2)
            writeLengthDelimited(out, senderNxId.toByteArray(Charsets.UTF_8))
        }

        // Field 4: recipient_nx_id (string, wire type 2)
        if (recipientNxId.isNotEmpty()) {
            writeTag(out, 4, 2)
            writeLengthDelimited(out, recipientNxId.toByteArray(Charsets.UTF_8))
        }

        // Field 5: timestamp (int64, wire type 0)
        if (timestamp != 0L) {
            writeTag(out, 5, 0)
            writeVarint(out, timestamp)
        }

        // Field 6: message (EncryptedMessagePayload, wire type 2)
        message?.let { msg ->
            val msgOut = ByteArrayOutputStream()
            if (msg.conversationSeq != 0L) {
                writeTag(msgOut, 1, 0)
                writeVarint(msgOut, msg.conversationSeq)
            }
            if (msg.ciphertext.isNotEmpty()) {
                writeTag(msgOut, 2, 2)
                writeLengthDelimited(msgOut, msg.ciphertext)
            }
            if (msg.nonce.isNotEmpty()) {
                writeTag(msgOut, 3, 2)
                writeLengthDelimited(msgOut, msg.nonce)
            }
            if (msg.authTag.isNotEmpty()) {
                writeTag(msgOut, 4, 2)
                writeLengthDelimited(msgOut, msg.authTag)
            }
            msg.header?.let { h ->
                val hOut = ByteArrayOutputStream()
                if (h.ratchetPublicKey.isNotEmpty()) {
                    writeTag(hOut, 1, 2)
                    writeLengthDelimited(hOut, h.ratchetPublicKey)
                }
                if (h.previousChainLength != 0) {
                    writeTag(hOut, 2, 0)
                    writeVarint(hOut, h.previousChainLength.toLong())
                }
                if (h.messageIndex != 0) {
                    writeTag(hOut, 3, 0)
                    writeVarint(hOut, h.messageIndex.toLong())
                }
                if (h.sessionId.isNotEmpty()) {
                    writeTag(hOut, 4, 2)
                    writeLengthDelimited(hOut, h.sessionId.toByteArray(Charsets.UTF_8))
                }
                if (h.oneTimePrekeyId != 0L) {
                    writeTag(hOut, 5, 0)
                    writeVarint(hOut, h.oneTimePrekeyId)
                }
                if (h.ephemeralPublicKey.isNotEmpty()) {
                    writeTag(hOut, 6, 2)
                    writeLengthDelimited(hOut, h.ephemeralPublicKey)
                }
                writeTag(msgOut, 5, 2)
                writeLengthDelimited(msgOut, hOut.toByteArray())
            }
            if (msg.aeadNonce.isNotEmpty()) {
                writeTag(msgOut, 6, 2)
                writeLengthDelimited(msgOut, msg.aeadNonce)
            }

            writeTag(out, 6, 2)
            writeLengthDelimited(out, msgOut.toByteArray())
        }

        // Field 7: ack (DeliveryAckPayload, wire type 2)
        ack?.let { a ->
            val ackOut = ByteArrayOutputStream()
            if (a.originalPacketId.isNotEmpty()) {
                writeTag(ackOut, 1, 2)
                writeLengthDelimited(ackOut, a.originalPacketId.toByteArray(Charsets.UTF_8))
            }
            if (a.status != AckStatus.ACK_UNKNOWN) {
                writeTag(ackOut, 2, 0)
                writeVarint(ackOut, a.status.value.toLong())
            }
            if (a.recipientNxId.isNotEmpty()) {
                writeTag(ackOut, 3, 2)
                writeLengthDelimited(ackOut, a.recipientNxId.toByteArray(Charsets.UTF_8))
            }
            if (a.deviceId.isNotEmpty()) {
                writeTag(ackOut, 4, 2)
                writeLengthDelimited(ackOut, a.deviceId.toByteArray(Charsets.UTF_8))
            }
            if (a.timestamp != 0L) {
                writeTag(ackOut, 5, 0)
                writeVarint(ackOut, a.timestamp)
            }
            if (a.signature.isNotEmpty()) {
                writeTag(ackOut, 6, 2)
                writeLengthDelimited(ackOut, a.signature)
            }

            writeTag(out, 7, 2)
            writeLengthDelimited(out, ackOut.toByteArray())
        }

        // Field 8: auth_challenge (AuthChallengePayload, wire type 2)
        authChallenge?.let { ac ->
            val acOut = ByteArrayOutputStream()
            if (ac.challengeNonce.isNotEmpty()) {
                writeTag(acOut, 1, 2)
                writeLengthDelimited(acOut, ac.challengeNonce)
            }
            if (ac.timestamp != 0L) {
                writeTag(acOut, 2, 0)
                writeVarint(acOut, ac.timestamp)
            }
            if (ac.expiresAt != 0L) {
                writeTag(acOut, 3, 0)
                writeVarint(acOut, ac.expiresAt)
            }

            writeTag(out, 8, 2)
            writeLengthDelimited(out, acOut.toByteArray())
        }

        // Field 9: auth_response (AuthResponsePayload, wire type 2)
        authResponse?.let { ar ->
            val arOut = ByteArrayOutputStream()
            if (ar.signature.isNotEmpty()) {
                writeTag(arOut, 1, 2)
                writeLengthDelimited(arOut, ar.signature)
            }
            if (ar.publicKey.isNotEmpty()) {
                writeTag(arOut, 2, 2)
                writeLengthDelimited(arOut, ar.publicKey)
            }
            if (ar.timestamp != 0L) {
                writeTag(arOut, 3, 0)
                writeVarint(arOut, ar.timestamp)
            }

            writeTag(out, 9, 2)
            writeLengthDelimited(out, arOut.toByteArray())
        }

        // Field 10: typing (TypingPayload, wire type 2)
        typing?.let { t ->
            val tOut = ByteArrayOutputStream()
            if (t.conversationId.isNotEmpty()) {
                writeTag(tOut, 1, 2)
                writeLengthDelimited(tOut, t.conversationId.toByteArray(Charsets.UTF_8))
            }
            writeTag(tOut, 2, 0)
            writeVarint(tOut, if (t.isTyping) 1L else 0L)

            writeTag(out, 10, 2)
            writeLengthDelimited(out, tOut.toByteArray())
        }

        // Field 11: device_sync (DeviceSyncPayload, wire type 2)
        deviceSync?.let { ds ->
            val dsOut = ByteArrayOutputStream()
            if (ds.syncId.isNotEmpty()) {
                writeTag(dsOut, 1, 2)
                writeLengthDelimited(dsOut, ds.syncId.toByteArray(Charsets.UTF_8))
            }
            if (ds.sourceDeviceId.isNotEmpty()) {
                writeTag(dsOut, 2, 2)
                writeLengthDelimited(dsOut, ds.sourceDeviceId.toByteArray(Charsets.UTF_8))
            }
            if (ds.sequenceNumber != 0L) {
                writeTag(dsOut, 3, 0)
                writeVarint(dsOut, ds.sequenceNumber)
            }
            if (ds.encryptedPayload.isNotEmpty()) {
                writeTag(dsOut, 4, 2)
                writeLengthDelimited(dsOut, ds.encryptedPayload)
            }

            writeTag(out, 11, 2)
            writeLengthDelimited(out, dsOut.toByteArray())
        }

        // Field 12: signaling (SignalingPayload, wire type 2)
        signaling?.let { sig ->
            val sigOut = ByteArrayOutputStream()
            if (sig.transferId != 0L) {
                writeTag(sigOut, 1, 0)
                writeVarint(sigOut, sig.transferId)
            }
            if (sig.action.isNotEmpty()) {
                writeTag(sigOut, 2, 2)
                writeLengthDelimited(sigOut, sig.action.toByteArray(Charsets.UTF_8))
            }
            if (sig.data.isNotEmpty()) {
                writeTag(sigOut, 3, 2)
                writeLengthDelimited(sigOut, sig.data.toByteArray(Charsets.UTF_8))
            }

            writeTag(out, 12, 2)
            writeLengthDelimited(out, sigOut.toByteArray())
        }

        // Field 13: routing_tokens (RoutingTokenRegistrationPayload, wire type 2)
        routingTokens?.let { rt ->
            val rtOut = ByteArrayOutputStream()
            rt.tokens.forEach { token ->
                if (token.isNotEmpty()) {
                    writeTag(rtOut, 1, 2)
                    writeLengthDelimited(rtOut, token.toByteArray(Charsets.UTF_8))
                }
            }
            writeTag(out, 13, 2)
            writeLengthDelimited(out, rtOut.toByteArray())
        }

        // Field 14: peer_sync (PeerSyncPayload, wire type 2)
        peerSync?.let { ps ->
            val psOut = ByteArrayOutputStream()
            if (ps.action != PeerSyncAction.SYNC_UNKNOWN) {
                writeTag(psOut, 1, 0)
                writeVarint(psOut, ps.action.value.toLong())
            }
            if (ps.sessionId.isNotEmpty()) {
                writeTag(psOut, 2, 2)
                writeLengthDelimited(psOut, ps.sessionId.toByteArray(Charsets.UTF_8))
            }
            if (ps.conversationId.isNotEmpty()) {
                writeTag(psOut, 3, 2)
                writeLengthDelimited(psOut, ps.conversationId.toByteArray(Charsets.UTF_8))
            }
            if (ps.cursorTimestamp != 0L) {
                writeTag(psOut, 4, 0)
                writeVarint(psOut, ps.cursorTimestamp)
            }
            if (ps.cursorSequence != 0L) {
                writeTag(psOut, 5, 0)
                writeVarint(psOut, ps.cursorSequence)
            }
            ps.messageIds.forEach { mid ->
                if (mid.isNotEmpty()) {
                    writeTag(psOut, 6, 2)
                    writeLengthDelimited(psOut, mid.toByteArray(Charsets.UTF_8))
                }
            }
            ps.envelopes.forEach { env ->
                if (env.isNotEmpty()) {
                    writeTag(psOut, 7, 2)
                    writeLengthDelimited(psOut, env)
                }
            }
            if (ps.syncTimestamp != 0L) {
                writeTag(psOut, 8, 0)
                writeVarint(psOut, ps.syncTimestamp)
            }
            if (ps.errorMessage.isNotEmpty()) {
                writeTag(psOut, 9, 2)
                writeLengthDelimited(psOut, ps.errorMessage.toByteArray(Charsets.UTF_8))
            }

            writeTag(out, 14, 2)
            writeLengthDelimited(out, psOut.toByteArray())
        }

        // Field 15: custody_ack (CustodyAckPayload, wire type 2)
        custodyAck?.let { ca ->
            val caOut = ByteArrayOutputStream()
            if (ca.originalPacketId.isNotEmpty()) {
                writeTag(caOut, 1, 2)
                writeLengthDelimited(caOut, ca.originalPacketId.toByteArray(Charsets.UTF_8))
            }
            if (ca.custodianNxId.isNotEmpty()) {
                writeTag(caOut, 2, 2)
                writeLengthDelimited(caOut, ca.custodianNxId.toByteArray(Charsets.UTF_8))
            }
            if (ca.status != CustodyStatus.CUSTODY_STATUS_UNSPECIFIED) {
                writeTag(caOut, 3, 0)
                writeVarint(caOut, ca.status.value.toLong())
            }
            if (ca.timestamp != 0L) {
                writeTag(caOut, 4, 0)
                writeVarint(caOut, ca.timestamp)
            }
            if (ca.signature.isNotEmpty()) {
                writeTag(caOut, 5, 2)
                writeLengthDelimited(caOut, ca.signature)
            }

            writeTag(out, 15, 2)
            writeLengthDelimited(out, caOut.toByteArray())
        }

        // Field 16: presence (PresencePayload, wire type 2)
        presence?.let { pr ->
            val prOut = ByteArrayOutputStream()
            if (pr.nxId.isNotEmpty()) {
                writeTag(prOut, 1, 2)
                writeLengthDelimited(prOut, pr.nxId.toByteArray(Charsets.UTF_8))
            }
            if (pr.status != PresenceStatus.PRESENCE_OFFLINE) {
                writeTag(prOut, 2, 0)
                writeVarint(prOut, pr.status.value.toLong())
            }
            if (pr.timestamp != 0L) {
                writeTag(prOut, 3, 0)
                writeVarint(prOut, pr.timestamp)
            }
            writeTag(out, 16, 2)
            writeLengthDelimited(out, prOut.toByteArray())
        }

        // Field 17: message_edit (MessageEditPayload, wire type 2)
        messageEdit?.let { me ->
            val meOut = ByteArrayOutputStream()
            if (me.targetPacketId.isNotEmpty()) {
                writeTag(meOut, 1, 2)
                writeLengthDelimited(meOut, me.targetPacketId.toByteArray(Charsets.UTF_8))
            }
            if (me.newCiphertext.isNotEmpty()) {
                writeTag(meOut, 2, 2)
                writeLengthDelimited(meOut, me.newCiphertext)
            }
            if (me.authTag.isNotEmpty()) {
                writeTag(meOut, 3, 2)
                writeLengthDelimited(meOut, me.authTag)
            }
            if (me.nonce.isNotEmpty()) {
                writeTag(meOut, 4, 2)
                writeLengthDelimited(meOut, me.nonce)
            }
            if (me.aeadNonce.isNotEmpty()) {
                writeTag(meOut, 5, 2)
                writeLengthDelimited(meOut, me.aeadNonce)
            }
            if (me.editedAt != 0L) {
                writeTag(meOut, 6, 0)
                writeVarint(meOut, me.editedAt)
            }
            me.header?.let { h ->
                val hOut = ByteArrayOutputStream()
                if (h.ratchetPublicKey.isNotEmpty()) {
                    writeTag(hOut, 1, 2)
                    writeLengthDelimited(hOut, h.ratchetPublicKey)
                }
                if (h.previousChainLength != 0) {
                    writeTag(hOut, 2, 0)
                    writeVarint(hOut, h.previousChainLength.toLong())
                }
                if (h.messageIndex != 0) {
                    writeTag(hOut, 3, 0)
                    writeVarint(hOut, h.messageIndex.toLong())
                }
                if (h.sessionId.isNotEmpty()) {
                    writeTag(hOut, 4, 2)
                    writeLengthDelimited(hOut, h.sessionId.toByteArray(Charsets.UTF_8))
                }
                if (h.oneTimePrekeyId != 0L) {
                    writeTag(hOut, 5, 0)
                    writeVarint(hOut, h.oneTimePrekeyId)
                }
                if (h.ephemeralPublicKey.isNotEmpty()) {
                    writeTag(hOut, 6, 2)
                    writeLengthDelimited(hOut, h.ephemeralPublicKey)
                }
                writeTag(meOut, 7, 2)
                writeLengthDelimited(meOut, hOut.toByteArray())
            }
            writeTag(out, 17, 2)
            writeLengthDelimited(out, meOut.toByteArray())
        }

        // Field 18: message_delete (MessageDeletePayload, wire type 2)
        messageDelete?.let { md ->
            val mdOut = ByteArrayOutputStream()
            if (md.targetPacketId.isNotEmpty()) {
                writeTag(mdOut, 1, 2)
                writeLengthDelimited(mdOut, md.targetPacketId.toByteArray(Charsets.UTF_8))
            }
            if (md.deleteType.isNotEmpty()) {
                writeTag(mdOut, 2, 2)
                writeLengthDelimited(mdOut, md.deleteType.toByteArray(Charsets.UTF_8))
            }
            if (md.deletedAt != 0L) {
                writeTag(mdOut, 3, 0)
                writeVarint(mdOut, md.deletedAt)
            }
            writeTag(out, 18, 2)
            writeLengthDelimited(out, mdOut.toByteArray())
        }

        // Field 19: group_id (string, wire type 2)
        if (groupId.isNotEmpty()) {
            writeTag(out, 19, 2)
            writeLengthDelimited(out, groupId.toByteArray(Charsets.UTF_8))
        }

        return out.toByteArray()
    }

    companion object {
        const val MAX_FIELD_SIZE = 4 * 1024 * 1024 // 4 MB safety bound against OOM
        const val MAX_PACKET_SIZE = 4 * 1024 * 1024 // 4 MB safety bound against malformed envelopes

        /**
         * Deserializes a binary Protobuf wire packet into NexaWirePacket.
         */
        fun decode(bytes: ByteArray): NexaWirePacket {
            if (bytes.size > MAX_PACKET_SIZE) {
                throw IllegalArgumentException("Malformed wire packet: size ${bytes.size} exceeds maximum allowable bound $MAX_PACKET_SIZE")
            }
            val input = ByteArrayInputStream(bytes)
            var packetId = ""
            var type = PacketType.PACKET_UNKNOWN
            var senderNxId = ""
            var recipientNxId = ""
            var timestamp = 0L
            var message: EncryptedMessagePayload? = null
            var ack: DeliveryAckPayload? = null
            var authChallenge: AuthChallengePayload? = null
            var authResponse: AuthResponsePayload? = null
            var typing: TypingPayload? = null
            var deviceSync: DeviceSyncPayload? = null
            var signaling: SignalingPayload? = null
            var routingTokens: RoutingTokenRegistrationPayload? = null
            var peerSync: PeerSyncPayload? = null
            var custodyAck: CustodyAckPayload? = null
            var presence: PresencePayload? = null
            var messageEdit: MessageEditPayload? = null
            var messageDelete: MessageDeletePayload? = null
            var groupId = ""

            while (input.available() > 0) {
                val tag = readVarint(input)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                when (fieldNum) {
                    1 -> packetId = String(readLengthDelimited(input), Charsets.UTF_8)
                    2 -> type = PacketType.fromValue(readVarint(input).toInt())
                    3 -> senderNxId = String(readLengthDelimited(input), Charsets.UTF_8)
                    4 -> recipientNxId = String(readLengthDelimited(input), Charsets.UTF_8)
                    5 -> timestamp = readVarint(input)
                    6 -> message = decodeMessagePayload(readLengthDelimited(input))
                    7 -> ack = decodeAckPayload(readLengthDelimited(input))
                    8 -> authChallenge = decodeAuthChallengePayload(readLengthDelimited(input))
                    9 -> authResponse = decodeAuthResponsePayload(readLengthDelimited(input))
                    10 -> typing = decodeTypingPayload(readLengthDelimited(input))
                    11 -> deviceSync = decodeDeviceSyncPayload(readLengthDelimited(input))
                    12 -> signaling = decodeSignalingPayload(readLengthDelimited(input))
                    13 -> routingTokens = decodeRoutingTokenRegistrationPayload(readLengthDelimited(input))
                    14 -> peerSync = decodePeerSyncPayload(readLengthDelimited(input))
                    15 -> custodyAck = decodeCustodyAckPayload(readLengthDelimited(input))
                    16 -> presence = decodePresencePayload(readLengthDelimited(input))
                    17 -> messageEdit = decodeMessageEditPayload(readLengthDelimited(input))
                    18 -> messageDelete = decodeMessageDeletePayload(readLengthDelimited(input))
                    19 -> groupId = String(readLengthDelimited(input), Charsets.UTF_8)
                    else -> skipField(input, wireType)
                }
            }

            return NexaWirePacket(
                packetId = packetId,
                type = type,
                senderNxId = senderNxId,
                recipientNxId = recipientNxId,
                timestamp = timestamp,
                message = message,
                ack = ack,
                authChallenge = authChallenge,
                authResponse = authResponse,
                typing = typing,
                deviceSync = deviceSync,
                signaling = signaling,
                routingTokens = routingTokens,
                peerSync = peerSync,
                custodyAck = custodyAck,
                presence = presence,
                messageEdit = messageEdit,
                messageDelete = messageDelete,
                groupId = groupId
            )
        }

        private fun decodePeerSyncPayload(bytes: ByteArray): PeerSyncPayload {
            val input = ByteArrayInputStream(bytes)
            var action = PeerSyncAction.SYNC_UNKNOWN
            var sessionId = ""
            var conversationId = ""
            var cursorTimestamp = 0L
            var cursorSequence = 0L
            val messageIds = mutableListOf<String>()
            val envelopes = mutableListOf<ByteArray>()
            var syncTimestamp = 0L
            var errorMessage = ""

            while (input.available() > 0) {
                val tag = readVarint(input)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()
                when (fieldNum) {
                    1 -> action = PeerSyncAction.fromValue(readVarint(input).toInt())
                    2 -> sessionId = String(readLengthDelimited(input), Charsets.UTF_8)
                    3 -> conversationId = String(readLengthDelimited(input), Charsets.UTF_8)
                    4 -> cursorTimestamp = readVarint(input)
                    5 -> cursorSequence = readVarint(input)
                    6 -> messageIds.add(String(readLengthDelimited(input), Charsets.UTF_8))
                    7 -> envelopes.add(readLengthDelimited(input))
                    8 -> syncTimestamp = readVarint(input)
                    9 -> errorMessage = String(readLengthDelimited(input), Charsets.UTF_8)
                    else -> skipField(input, wireType)
                }
            }
            return PeerSyncPayload(
                action = action,
                sessionId = sessionId,
                conversationId = conversationId,
                cursorTimestamp = cursorTimestamp,
                cursorSequence = cursorSequence,
                messageIds = messageIds,
                envelopes = envelopes,
                syncTimestamp = syncTimestamp,
                errorMessage = errorMessage
            )
        }

        private fun decodeRoutingTokenRegistrationPayload(bytes: ByteArray): RoutingTokenRegistrationPayload {
            val input = ByteArrayInputStream(bytes)
            val tokens = mutableListOf<String>()
            while (input.available() > 0) {
                val tag = readVarint(input)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()
                when (fieldNum) {
                    1 -> tokens.add(String(readLengthDelimited(input), Charsets.UTF_8))
                    else -> skipField(input, wireType)
                }
            }
            return RoutingTokenRegistrationPayload(tokens)
        }

        private fun decodeMessagePayload(bytes: ByteArray): EncryptedMessagePayload {
            val input = ByteArrayInputStream(bytes)
            var seq = 0L
            var ct = ByteArray(0)
            var nonce = ByteArray(0)
            var authTag = ByteArray(0)
            var header: DoubleRatchetHeader? = null
            var aeadNonce = ByteArray(0)

            while (input.available() > 0) {
                val tag = readVarint(input)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                when (fieldNum) {
                    1 -> seq = readVarint(input)
                    2 -> ct = readLengthDelimited(input)
                    3 -> nonce = readLengthDelimited(input)
                    4 -> authTag = readLengthDelimited(input)
                    5 -> header = decodeDoubleRatchetHeader(readLengthDelimited(input))
                    6 -> aeadNonce = readLengthDelimited(input)
                    else -> skipField(input, wireType)
                }
            }
            return EncryptedMessagePayload(seq, ct, nonce, authTag, header, aeadNonce)
        }

        private fun decodeDoubleRatchetHeader(bytes: ByteArray): DoubleRatchetHeader {
            val input = ByteArrayInputStream(bytes)
            var ratchetPk = ByteArray(0)
            var pn = 0
            var n = 0
            var sessionId = ""
            var opkId = 0L
            var ek = ByteArray(0)

            while (input.available() > 0) {
                val tag = readVarint(input)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                when (fieldNum) {
                    1 -> ratchetPk = readLengthDelimited(input)
                    2 -> pn = readVarint(input).toInt()
                    3 -> n = readVarint(input).toInt()
                    4 -> sessionId = String(readLengthDelimited(input), Charsets.UTF_8)
                    5 -> opkId = readVarint(input)
                    6 -> ek = readLengthDelimited(input)
                    else -> skipField(input, wireType)
                }
            }
            return DoubleRatchetHeader(ratchetPk, pn, n, sessionId, opkId, ek)
        }

        private fun decodeAckPayload(bytes: ByteArray): DeliveryAckPayload {
            val input = ByteArrayInputStream(bytes)
            var origId = ""
            var status = AckStatus.ACK_UNKNOWN
            var recipientNxId = ""
            var deviceId = ""
            var timestamp = 0L
            var signature = ByteArray(0)

            while (input.available() > 0) {
                val tag = readVarint(input)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                when (fieldNum) {
                    1 -> origId = String(readLengthDelimited(input), Charsets.UTF_8)
                    2 -> status = AckStatus.fromValue(readVarint(input).toInt())
                    3 -> recipientNxId = String(readLengthDelimited(input), Charsets.UTF_8)
                    4 -> deviceId = String(readLengthDelimited(input), Charsets.UTF_8)
                    5 -> timestamp = readVarint(input)
                    6 -> signature = readLengthDelimited(input)
                    else -> skipField(input, wireType)
                }
            }
            return DeliveryAckPayload(
                originalPacketId = origId,
                status = status,
                recipientNxId = recipientNxId,
                deviceId = deviceId,
                timestamp = timestamp,
                signature = signature
            )
        }

        private fun decodeCustodyAckPayload(bytes: ByteArray): CustodyAckPayload {
            val input = ByteArrayInputStream(bytes)
            var origId = ""
            var custodianNxId = ""
            var status = CustodyStatus.CUSTODY_STATUS_UNSPECIFIED
            var timestamp = 0L
            var signature = ByteArray(0)

            while (input.available() > 0) {
                val tag = readVarint(input)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                when (fieldNum) {
                    1 -> origId = String(readLengthDelimited(input), Charsets.UTF_8)
                    2 -> custodianNxId = String(readLengthDelimited(input), Charsets.UTF_8)
                    3 -> status = CustodyStatus.fromValue(readVarint(input).toInt())
                    4 -> timestamp = readVarint(input)
                    5 -> signature = readLengthDelimited(input)
                    else -> skipField(input, wireType)
                }
            }
            return CustodyAckPayload(
                originalPacketId = origId,
                custodianNxId = custodianNxId,
                status = status,
                timestamp = timestamp,
                signature = signature
            )
        }

        private fun decodeAuthChallengePayload(bytes: ByteArray): AuthChallengePayload {
            val input = ByteArrayInputStream(bytes)
            var nonce = ByteArray(0)
            var ts = 0L
            var exp = 0L

            while (input.available() > 0) {
                val tag = readVarint(input)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                when (fieldNum) {
                    1 -> nonce = readLengthDelimited(input)
                    2 -> ts = readVarint(input)
                    3 -> exp = readVarint(input)
                    else -> skipField(input, wireType)
                }
            }
            return AuthChallengePayload(nonce, ts, exp)
        }

        private fun decodeAuthResponsePayload(bytes: ByteArray): AuthResponsePayload {
            val input = ByteArrayInputStream(bytes)
            var sig = ByteArray(0)
            var pub = ByteArray(0)
            var ts = 0L

            while (input.available() > 0) {
                val tag = readVarint(input)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                when (fieldNum) {
                    1 -> sig = readLengthDelimited(input)
                    2 -> pub = readLengthDelimited(input)
                    3 -> ts = readVarint(input)
                    else -> skipField(input, wireType)
                }
            }
            return AuthResponsePayload(sig, pub, ts)
        }

        private fun decodeTypingPayload(bytes: ByteArray): TypingPayload {
            val input = ByteArrayInputStream(bytes)
            var conversationId = ""
            var isTyping = false

            while (input.available() > 0) {
                val tag = readVarint(input)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                when (fieldNum) {
                    1 -> conversationId = String(readLengthDelimited(input), Charsets.UTF_8)
                    2 -> isTyping = readVarint(input) != 0L
                    else -> skipField(input, wireType)
                }
            }
            return TypingPayload(conversationId, isTyping)
        }

        private fun decodeDeviceSyncPayload(bytes: ByteArray): DeviceSyncPayload {
            val input = ByteArrayInputStream(bytes)
            var syncId = ""
            var sourceDeviceId = ""
            var seq = 0L
            var encrypted = ByteArray(0)

            while (input.available() > 0) {
                val tag = readVarint(input)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                when (fieldNum) {
                    1 -> syncId = String(readLengthDelimited(input), Charsets.UTF_8)
                    2 -> sourceDeviceId = String(readLengthDelimited(input), Charsets.UTF_8)
                    3 -> seq = readVarint(input)
                    4 -> encrypted = readLengthDelimited(input)
                    else -> skipField(input, wireType)
                }
            }
            return DeviceSyncPayload(syncId, sourceDeviceId, seq, encrypted)
        }

        private fun decodeSignalingPayload(bytes: ByteArray): SignalingPayload {
            val input = ByteArrayInputStream(bytes)
            var transferId = 0L
            var action = ""
            var data = ""

            while (input.available() > 0) {
                val tag = readVarint(input)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                when (fieldNum) {
                    1 -> transferId = readVarint(input)
                    2 -> action = String(readLengthDelimited(input), Charsets.UTF_8)
                    3 -> data = String(readLengthDelimited(input), Charsets.UTF_8)
                    else -> skipField(input, wireType)
                }
            }
            return SignalingPayload(transferId, action, data)
        }

        private fun decodePresencePayload(bytes: ByteArray): PresencePayload {
            val input = ByteArrayInputStream(bytes)
            var nxId = ""
            var status = PresenceStatus.PRESENCE_OFFLINE
            var timestamp = 0L

            while (input.available() > 0) {
                val tag = readVarint(input)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                when (fieldNum) {
                    1 -> nxId = String(readLengthDelimited(input), Charsets.UTF_8)
                    2 -> status = PresenceStatus.fromValue(readVarint(input).toInt())
                    3 -> timestamp = readVarint(input)
                    else -> skipField(input, wireType)
                }
            }
            return PresencePayload(nxId, status, timestamp)
        }

        private fun decodeMessageEditPayload(bytes: ByteArray): MessageEditPayload {
            val input = ByteArrayInputStream(bytes)
            var targetPacketId = ""
            var newCiphertext = ByteArray(0)
            var authTag = ByteArray(0)
            var nonce = ByteArray(0)
            var aeadNonce = ByteArray(0)
            var editedAt = 0L
            var header: DoubleRatchetHeader? = null

            while (input.available() > 0) {
                val tag = readVarint(input)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                when (fieldNum) {
                    1 -> targetPacketId = String(readLengthDelimited(input), Charsets.UTF_8)
                    2 -> newCiphertext = readLengthDelimited(input)
                    3 -> authTag = readLengthDelimited(input)
                    4 -> nonce = readLengthDelimited(input)
                    5 -> aeadNonce = readLengthDelimited(input)
                    6 -> editedAt = readVarint(input)
                    7 -> header = decodeDoubleRatchetHeader(readLengthDelimited(input))
                    else -> skipField(input, wireType)
                }
            }
            return MessageEditPayload(
                targetPacketId = targetPacketId,
                newCiphertext = newCiphertext,
                authTag = authTag,
                nonce = nonce,
                aeadNonce = aeadNonce,
                editedAt = editedAt,
                header = header
            )
        }

        private fun decodeMessageDeletePayload(bytes: ByteArray): MessageDeletePayload {
            val input = ByteArrayInputStream(bytes)
            var targetPacketId = ""
            var deleteType = "FOR_EVERYONE"
            var deletedAt = 0L

            while (input.available() > 0) {
                val tag = readVarint(input)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                when (fieldNum) {
                    1 -> targetPacketId = String(readLengthDelimited(input), Charsets.UTF_8)
                    2 -> deleteType = String(readLengthDelimited(input), Charsets.UTF_8)
                    3 -> deletedAt = readVarint(input)
                    else -> skipField(input, wireType)
                }
            }
            return MessageDeletePayload(
                targetPacketId = targetPacketId,
                deleteType = deleteType,
                deletedAt = deletedAt
            )
        }

        private fun writeTag(out: OutputStream, fieldNum: Int, wireType: Int) {
            val tag = ((fieldNum shl 3) or (wireType and 0x07)).toLong()
            writeVarint(out, tag)
        }

        private fun writeVarint(out: OutputStream, value: Long) {
            var v = value
            while (true) {
                if ((v and 0x7FL.inv()) == 0L) {
                    out.write(v.toInt())
                    return
                } else {
                    out.write(((v.toInt() and 0x7F) or 0x80))
                    v = v ushr 7
                }
            }
        }

        private fun readVarint(input: InputStream): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                val b = input.read()
                if (b == -1) break
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return result
        }

        private fun writeLengthDelimited(out: OutputStream, data: ByteArray) {
            writeVarint(out, data.size.toLong())
            out.write(data)
        }

        private fun readLengthDelimited(input: InputStream): ByteArray {
            val length = readVarint(input).toInt()
            if (length < 0 || length > MAX_FIELD_SIZE) {
                throw IllegalArgumentException("Malformed wire packet: field length $length violates bounds [0, $MAX_FIELD_SIZE]")
            }
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(buf, read, length - read)
                if (count == -1) break
                read += count
            }
            if (read < length) {
                throw IllegalArgumentException("Malformed wire packet: expected $length bytes but stream truncated at $read")
            }
            return buf
        }

        private fun skipField(input: InputStream, wireType: Int) {
            when (wireType) {
                0 -> readVarint(input)
                1 -> input.skip(8)
                2 -> {
                    val len = readVarint(input)
                    if (len < 0 || len > MAX_FIELD_SIZE) {
                        throw IllegalArgumentException("Malformed wire packet: skip length $len violates bounds")
                    }
                    input.skip(len)
                }
                5 -> input.skip(4)
            }
        }
    }
}
