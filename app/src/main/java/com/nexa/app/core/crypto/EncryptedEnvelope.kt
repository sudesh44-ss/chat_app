package com.nexa.app.core.crypto

import com.nexa.app.core.proto.DoubleRatchetHeader
import com.nexa.app.core.proto.EncryptedMessagePayload
import java.nio.ByteBuffer

/**
 * Double Ratchet Encrypted Envelope bridging wire representation and cipher inputs.
 * Separates the 32-byte X25519 ratchet public key from the 12-byte AEAD nonce and 16-byte Poly1305 MAC tag.
 */
data class EncryptedEnvelope(
    val version: Byte = CURRENT_VERSION,
    val ephemeralPublicKey: ByteArray, // 32-byte X25519 ratchet public key (DHR)
    val authTag: ByteArray,            // 16-byte Poly1305 integrity tag
    val ciphertext: ByteArray,         // Encrypted payload
    val previousChainLength: Int = 0,  // PN
    val messageIndex: Int = 0,         // N
    val sessionId: String = "",        // Session UUID
    val aeadNonce: ByteArray = ByteArray(0), // 12-byte AEAD nonce
    val oneTimePrekeyId: Long = 0L,
    val x3dhEphemeralPublicKey: ByteArray = ByteArray(0) // Optional 32-byte EK during initial message
) {
    fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_OVERHEAD_SIZE + ciphertext.size)
        buffer.put(version)
        buffer.put(ephemeralPublicKey)
        buffer.put(authTag)
        buffer.put(ciphertext)
        return buffer.array()
    }

    fun toProtobufPayload(): EncryptedMessagePayload {
        val header = DoubleRatchetHeader(
            ratchetPublicKey = ephemeralPublicKey,
            previousChainLength = previousChainLength,
            messageIndex = messageIndex,
            sessionId = sessionId,
            oneTimePrekeyId = oneTimePrekeyId,
            ephemeralPublicKey = x3dhEphemeralPublicKey
        )
        return EncryptedMessagePayload(
            conversationSeq = messageIndex.toLong(),
            ciphertext = ciphertext,
            nonce = ephemeralPublicKey, // 32-byte legacy shim
            authTag = authTag,
            header = header,
            aeadNonce = aeadNonce
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptedEnvelope
        if (version != other.version) return false
        if (!ephemeralPublicKey.contentEquals(other.ephemeralPublicKey)) return false
        if (!authTag.contentEquals(other.authTag)) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (previousChainLength != other.previousChainLength) return false
        if (messageIndex != other.messageIndex) return false
        if (sessionId != other.sessionId) return false
        if (!aeadNonce.contentEquals(other.aeadNonce)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + ephemeralPublicKey.contentHashCode()
        result = 31 * result + authTag.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + previousChainLength
        result = 31 * result + messageIndex
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + aeadNonce.contentHashCode()
        return result
    }

    companion object {
        const val CURRENT_VERSION: Byte = 0x01
        const val HEADER_OVERHEAD_SIZE = 1 + 32 + 16 // 49 Bytes fixed legacy overhead

        fun deserialize(bytes: ByteArray): EncryptedEnvelope? {
            if (bytes.size < HEADER_OVERHEAD_SIZE) return null
            val buffer = ByteBuffer.wrap(bytes)
            val version = buffer.get()
            val ephemeralPk = ByteArray(32).also { buffer.get(it) }
            val authTag = ByteArray(16).also { buffer.get(it) }
            val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
            return EncryptedEnvelope(
                version = version,
                ephemeralPublicKey = ephemeralPk,
                authTag = authTag,
                ciphertext = ciphertext
            )
        }

        fun fromProtobufPayload(payload: EncryptedMessagePayload): EncryptedEnvelope {
            val h = payload.header
            return if (h != null) {
                EncryptedEnvelope(
                    version = CURRENT_VERSION,
                    ephemeralPublicKey = h.ratchetPublicKey,
                    authTag = payload.authTag,
                    ciphertext = payload.ciphertext,
                    previousChainLength = h.previousChainLength,
                    messageIndex = h.messageIndex,
                    sessionId = h.sessionId,
                    aeadNonce = payload.aeadNonce,
                    oneTimePrekeyId = h.oneTimePrekeyId,
                    x3dhEphemeralPublicKey = h.ephemeralPublicKey
                )
            } else {
                // Backward compatibility shim for legacy payloads
                EncryptedEnvelope(
                    version = CURRENT_VERSION,
                    ephemeralPublicKey = payload.nonce,
                    authTag = payload.authTag,
                    ciphertext = payload.ciphertext,
                    previousChainLength = 0,
                    messageIndex = payload.conversationSeq.toInt(),
                    sessionId = "",
                    aeadNonce = ByteArray(0)
                )
            }
        }
    }
}
