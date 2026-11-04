package com.nexa.app.core.dtn

import java.nio.ByteBuffer
import java.util.UUID

/**
 * Sealed Binary DTN Envelope for Store-Carry-Forward routing.
 * Intermediate data mules cannot inspect payload, sender identity, or true recipient address.
 */
data class DtnEnvelope(
    val envelopeId: ByteArray,       // 16 bytes: Random UUID identifier
    val blindToken: ByteArray,       // 16 bytes: Ephemeral Blake3 hash of recipient public key + day salt
    val hopLimit: Byte,              // 1 byte: Decremented on each physical hop (Default: 7)
    val expiresAtEpochSec: Long,     // 8 bytes: Expiry epoch seconds (Default: Current + 48 hours)
    val encryptedPayload: ByteArray  // Variable: XChaCha20-Poly1305 ciphertext (Message + Sender Identity)
) {
    init {
        require(envelopeId.size == 16) { "envelopeId must be exactly 16 bytes" }
        require(blindToken.size == 16) { "blindToken must be exactly 16 bytes" }
    }

    val envelopeIdHex: String
        get() = envelopeId.toHex()

    val blindTokenHex: String
        get() = blindToken.toHex()

    /**
     * Serializes this envelope into compact binary representation:
     * [EnvelopeID: 16B][BlindToken: 16B][HopLimit: 1B][ExpiresAt: 8B][PayloadSize: 4B][Payload: NB]
     * Total = 45 + payload.size bytes.
     */
    fun serialize(): ByteArray {
        val totalSize = DtnConstants.ENVELOPE_HEADER_SIZE + encryptedPayload.size
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.put(envelopeId)
        buffer.put(blindToken)
        buffer.put(hopLimit)
        buffer.putLong(expiresAtEpochSec)
        buffer.putInt(encryptedPayload.size)
        buffer.put(encryptedPayload)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DtnEnvelope
        if (!envelopeId.contentEquals(other.envelopeId)) return false
        if (!blindToken.contentEquals(other.blindToken)) return false
        if (hopLimit != other.hopLimit) return false
        if (expiresAtEpochSec != other.expiresAtEpochSec) return false
        if (!encryptedPayload.contentEquals(other.encryptedPayload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = envelopeId.contentHashCode()
        result = 31 * result + blindToken.contentHashCode()
        result = 31 * result + hopLimit
        result = 31 * result + expiresAtEpochSec.hashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        return result
    }

    companion object {
        fun createNew(
            blindToken: ByteArray,
            encryptedPayload: ByteArray,
            hopLimit: Byte = DtnConstants.DEFAULT_HOP_LIMIT,
            ttlSeconds: Long = DtnConstants.DEFAULT_TTL_SECONDS
        ): DtnEnvelope {
            val uuid = UUID.randomUUID()
            val envId = ByteBuffer.allocate(16)
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array()

            val nowSec = System.currentTimeMillis() / 1000L
            val expiresAt = nowSec + ttlSeconds

            return DtnEnvelope(
                envelopeId = envId,
                blindToken = blindToken,
                hopLimit = hopLimit,
                expiresAtEpochSec = expiresAt,
                encryptedPayload = encryptedPayload
            )
        }

        /**
         * Deserializes a binary byte array into a DtnEnvelope.
         * Returns null if bytes are malformed or buffer underflows.
         */
        fun deserialize(bytes: ByteArray): DtnEnvelope? {
            if (bytes.size < DtnConstants.ENVELOPE_HEADER_SIZE) return null

            return try {
                val buffer = ByteBuffer.wrap(bytes)
                val envId = ByteArray(16).also { buffer.get(it) }
                val token = ByteArray(16).also { buffer.get(it) }
                val hops = buffer.get()
                val expiry = buffer.long
                val payloadSize = buffer.int

                if (payloadSize < 0 || buffer.remaining() < payloadSize) {
                    return null
                }

                val payload = ByteArray(payloadSize).also { buffer.get(it) }
                DtnEnvelope(
                    envelopeId = envId,
                    blindToken = token,
                    hopLimit = hops,
                    expiresAtEpochSec = expiry,
                    encryptedPayload = payload
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

private fun ByteArray.toHex(): String {
    val hexChars = "0123456789abcdef"
    val result = StringBuilder(size * 2)
    for (b in this) {
        val i = b.toInt() and 0xFF
        result.append(hexChars[i ushr 4])
        result.append(hexChars[i and 0x0F])
    }
    return result.toString()
}
