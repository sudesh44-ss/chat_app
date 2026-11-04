package com.nexa.app.core.crypto

import java.nio.ByteBuffer

/**
 * Universal 49-Byte Fixed Overhead Binary Envelope.
 * Layout:
 * [Version: 1B][Ephemeral X25519 PK: 32B][Poly1305 MAC Tag: 16B][Encrypted Ciphertext: NB]
 * Total Overhead: exactly 49 bytes. Fits within a single 140-byte GSM Data SMS PDU without multi-part charges.
 */
data class EncryptedEnvelope(
    val version: Byte = CURRENT_VERSION,
    val ephemeralPublicKey: ByteArray, // 32 Bytes
    val authTag: ByteArray,            // 16 Bytes
    val ciphertext: ByteArray          // N Bytes
) {
    fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_OVERHEAD_SIZE + ciphertext.size)
        buffer.put(version)
        buffer.put(ephemeralPublicKey)
        buffer.put(authTag)
        buffer.put(ciphertext)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptedEnvelope
        if (version != other.version) return false
        if (!ephemeralPublicKey.contentEquals(other.ephemeralPublicKey)) return false
        if (!authTag.contentEquals(other.authTag)) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + ephemeralPublicKey.contentHashCode()
        result = 31 * result + authTag.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    companion object {
        const val CURRENT_VERSION: Byte = 0x01
        const val HEADER_OVERHEAD_SIZE = 1 + 32 + 16 // 49 Bytes fixed overhead

        fun deserialize(bytes: ByteArray): EncryptedEnvelope? {
            if (bytes.size < HEADER_OVERHEAD_SIZE) return null
            val buffer = ByteBuffer.wrap(bytes)
            val version = buffer.get()
            val ephemeralPk = ByteArray(32).also { buffer.get(it) }
            val authTag = ByteArray(16).also { buffer.get(it) }
            val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
            return EncryptedEnvelope(version, ephemeralPk, authTag, ciphertext)
        }
    }
}
