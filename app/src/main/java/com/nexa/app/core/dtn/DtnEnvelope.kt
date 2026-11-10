package com.nexa.app.core.dtn

import com.nexa.app.core.crypto.Ed25519Signer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID

/**
 * Versioned, Authenticated NXDT Envelope for Store-Carry-Forward Delay-Tolerant Networking.
 *
 * Binary Layout (NXDT v1, Total Header + Signature = 200 bytes):
 * - Magic (4B): ASCII "NXDT" (0x4E, 0x58, 0x44, 0x54)
 * - Version (1B): 0x01
 * - Envelope Type (1B): 0x01 (Standard), 0x02 (Ack), 0x03 (Control)
 * - Algorithm Suite (1B): 0x01 (Ed25519 + SHA-256)
 * - Flags (1B): 0x00
 * - Mutable Relay Metadata (12B):
 *     - hopCount (1B, uint8)
 *     - hopLimit (1B, uint8)
 *     - relayTimestamp (8B, int64BE)
 *     - relayFlags (2B, uint16BE)
 * - Immutable Authenticated Header (116B):
 *     - messageUuid (16B)
 *     - senderPublicKey (32B)
 *     - blindToken (16B)
 *     - createdAtEpochSec (8B, int64BE)
 *     - expiresAtEpochSec (8B, int64BE)
 *     - ciphertextLength (4B, uint32BE)
 *     - ciphertextSha256 (32B)
 * - Sender Signature (64B): Ed25519 signature over Canonical Signing Data (124B)
 * - Encrypted Payload (NB): XChaCha20 / Double Ratchet ciphertext
 *
 * Canonical Signing Data (124B):
 *   Magic (4B) + Version (1B) + EnvelopeType (1B) + AlgorithmSuite (1B) + Flags (1B) +
 *   MessageUuid (16B) + SenderPublicKey (32B) + BlindToken (16B) +
 *   CreatedAtEpochSec (8B) + ExpiresAtEpochSec (8B) +
 *   CiphertextLength (4B) + CiphertextSha256 (32B)
 *
 * Note: Mutable relay metadata (hopCount, hopLimit, relayTimestamp, relayFlags) is intentionally
 * excluded from the canonical signature input, permitting relays to decrement hops without breaking
 * the cryptographic sender signature.
 */
data class DtnEnvelope(
    val protocolVersion: Byte = DtnConstants.PROTOCOL_VERSION_1,
    val envelopeType: Byte = DtnConstants.TYPE_STANDARD_DATA,
    val algorithmSuite: Byte = DtnConstants.SUITE_ED25519_SHA256,
    val flags: Byte = 0,
    val hopCount: Byte = 0,
    val hopLimit: Byte = DtnConstants.DEFAULT_HOP_LIMIT,
    val relayTimestamp: Long = 0L,
    val relayFlags: Short = 0,
    val envelopeId: ByteArray,              // 16 bytes: messageUuid
    val senderPublicKey: ByteArray?,        // 32 bytes: Ed25519 public key (present in v1)
    val blindToken: ByteArray,              // 16 bytes: Recipient routing token
    val createdAtEpochSec: Long = System.currentTimeMillis() / 1000L,
    val expiresAtEpochSec: Long,            // 8 bytes: Expiry epoch seconds
    val ciphertextSha256: ByteArray? = null,// 32 bytes: SHA-256 of payload (present in v1)
    val signature: ByteArray? = null,       // 64 bytes: Ed25519 signature (present in v1)
    val encryptedPayload: ByteArray         // Variable: Ciphertext
) {
    /**
     * Backward-compatible secondary constructor for legacy unsigned envelopes.
     */
    constructor(
        envelopeId: ByteArray,
        blindToken: ByteArray,
        hopLimit: Byte,
        expiresAtEpochSec: Long,
        encryptedPayload: ByteArray
    ) : this(
        protocolVersion = DtnConstants.PROTOCOL_VERSION_LEGACY,
        envelopeType = DtnConstants.TYPE_STANDARD_DATA,
        algorithmSuite = 0,
        flags = 0,
        hopCount = 0,
        hopLimit = hopLimit,
        relayTimestamp = 0L,
        relayFlags = 0,
        envelopeId = envelopeId,
        senderPublicKey = null,
        blindToken = blindToken,
        createdAtEpochSec = 0L,
        expiresAtEpochSec = expiresAtEpochSec,
        ciphertextSha256 = null,
        signature = null,
        encryptedPayload = encryptedPayload
    )

    init {
        require(envelopeId.size == 16) { "envelopeId must be exactly 16 bytes" }
        require(blindToken.size == 16) { "blindToken must be exactly 16 bytes" }
    }

    val envelopeIdHex: String
        get() = envelopeId.toHex()

    val blindTokenHex: String
        get() = blindToken.toHex()

    val isLegacy: Boolean
        get() = protocolVersion == DtnConstants.PROTOCOL_VERSION_LEGACY

    val isAuthenticated: Boolean
        get() = protocolVersion == DtnConstants.PROTOCOL_VERSION_1 && signature != null && verifySignature()

    /**
     * Serializes this envelope into its canonical binary representation:
     * - If protocolVersion == 1: 200B header + signature + encryptedPayload.size
     * - If protocolVersion == 0: 45B legacy header + encryptedPayload.size
     */
    fun serialize(): ByteArray {
        return if (protocolVersion == DtnConstants.PROTOCOL_VERSION_1) {
            val totalSize = DtnConstants.NXDT_HEADER_SIZE + encryptedPayload.size
            val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
            // 0..3: Magic
            buf.put(DtnConstants.MAGIC_BYTES)
            // 4..7: Protocol attributes
            buf.put(protocolVersion)
            buf.put(envelopeType)
            buf.put(algorithmSuite)
            buf.put(flags)
            // 8..19: Mutable Relay Metadata
            buf.put(hopCount)
            buf.put(hopLimit)
            buf.putLong(relayTimestamp)
            buf.putShort(relayFlags)
            // 20..135: Immutable Authenticated Header
            buf.put(envelopeId)
            buf.put(senderPublicKey ?: ByteArray(32))
            buf.put(blindToken)
            buf.putLong(createdAtEpochSec)
            buf.putLong(expiresAtEpochSec)
            buf.putInt(encryptedPayload.size)
            buf.put(ciphertextSha256 ?: sha256(encryptedPayload))
            // 136..199: Sender Signature
            buf.put(signature ?: ByteArray(64))
            // 200..: Encrypted Payload
            buf.put(encryptedPayload)
            buf.array()
        } else {
            val totalSize = DtnConstants.LEGACY_ENVELOPE_HEADER_SIZE + encryptedPayload.size
            val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
            buf.put(envelopeId)
            buf.put(blindToken)
            buf.put(hopLimit)
            buf.putLong(expiresAtEpochSec)
            buf.putInt(encryptedPayload.size)
            buf.put(encryptedPayload)
            buf.array()
        }
    }

    /**
     * Builds the exact 124-byte Canonical Signing Data covered by the Ed25519 signature.
     */
    fun getCanonicalSigningData(): ByteArray {
        val buf = ByteBuffer.allocate(DtnConstants.CANONICAL_SIGNING_DATA_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(DtnConstants.MAGIC_BYTES)
        buf.put(protocolVersion)
        buf.put(envelopeType)
        buf.put(algorithmSuite)
        buf.put(flags)
        buf.put(envelopeId) // 16B
        buf.put(senderPublicKey ?: ByteArray(32)) // 32B
        buf.put(blindToken) // 16B
        buf.putLong(createdAtEpochSec)
        buf.putLong(expiresAtEpochSec)
        buf.putInt(encryptedPayload.size)
        buf.put(ciphertextSha256 ?: sha256(encryptedPayload)) // 32B
        return buf.array()
    }

    /**
     * Verifies the cryptographic integrity and Ed25519 signature of an NXDT envelope.
     * Returns true if the envelope is a valid, untampered NXDT v1 envelope.
     * For legacy envelopes (v0), returns false.
     */
    fun verifySignature(): Boolean {
        if (protocolVersion != DtnConstants.PROTOCOL_VERSION_1) return false
        val pubKey = senderPublicKey ?: return false
        val sig = signature ?: return false
        val expectedHash = ciphertextSha256 ?: return false

        if (pubKey.size != 32 || sig.size != 64 || expectedHash.size != 32) return false

        // 1. Verify ciphertext SHA-256 binding
        val actualHash = sha256(encryptedPayload)
        if (!MessageDigest.isEqual(expectedHash, actualHash)) {
            return false
        }

        // 2. Verify Ed25519 signature over Canonical Signing Data
        val canonical = getCanonicalSigningData()
        return Ed25519Signer.verify(canonical, sig, pubKey)
    }

    /**
     * Creates a new envelope copy with updated hop count, remaining hop limit, and relay timestamp.
     * Preserves the immutable header and sender signature intact.
     * Returns null if hop limit is exhausted or invalid.
     */
    fun withDecrementedHop(nowSec: Long = System.currentTimeMillis() / 1000L): DtnEnvelope? {
        if (hopLimit <= 0) return null
        val newHopCount = (hopCount + 1).toByte()
        val newHopLimit = (hopLimit - 1).toByte()
        if (newHopCount > DtnConstants.MAX_HOP_LIMIT) return null

        return this.copy(
            hopCount = newHopCount,
            hopLimit = newHopLimit,
            relayTimestamp = nowSec
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DtnEnvelope
        if (protocolVersion != other.protocolVersion) return false
        if (envelopeType != other.envelopeType) return false
        if (algorithmSuite != other.algorithmSuite) return false
        if (flags != other.flags) return false
        if (hopCount != other.hopCount) return false
        if (hopLimit != other.hopLimit) return false
        if (relayTimestamp != other.relayTimestamp) return false
        if (relayFlags != other.relayFlags) return false
        if (!envelopeId.contentEquals(other.envelopeId)) return false
        if (!Arrays.equals(senderPublicKey, other.senderPublicKey)) return false
        if (!blindToken.contentEquals(other.blindToken)) return false
        if (createdAtEpochSec != other.createdAtEpochSec) return false
        if (expiresAtEpochSec != other.expiresAtEpochSec) return false
        if (!Arrays.equals(ciphertextSha256, other.ciphertextSha256)) return false
        if (!Arrays.equals(signature, other.signature)) return false
        if (!encryptedPayload.contentEquals(other.encryptedPayload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = protocolVersion.toInt()
        result = 31 * result + envelopeType
        result = 31 * result + algorithmSuite
        result = 31 * result + flags
        result = 31 * result + hopCount
        result = 31 * result + hopLimit
        result = 31 * result + relayTimestamp.hashCode()
        result = 31 * result + relayFlags
        result = 31 * result + envelopeId.contentHashCode()
        result = 31 * result + (senderPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + blindToken.contentHashCode()
        result = 31 * result + createdAtEpochSec.hashCode()
        result = 31 * result + expiresAtEpochSec.hashCode()
        result = 31 * result + (ciphertextSha256?.contentHashCode() ?: 0)
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        result = 31 * result + encryptedPayload.contentHashCode()
        return result
    }

    companion object {
        /**
         * Creates and cryptographically signs a new versioned NXDT v1 envelope.
         */
        fun createAuthenticated(
            blindToken: ByteArray,
            encryptedPayload: ByteArray,
            senderPrivateKey: ByteArray,
            senderPublicKey: ByteArray? = null,
            envelopeType: Byte = DtnConstants.TYPE_STANDARD_DATA,
            hopLimit: Byte = DtnConstants.DEFAULT_HOP_LIMIT,
            ttlSeconds: Long = DtnConstants.DEFAULT_TTL_SECONDS,
            customUuid: ByteArray? = null,
            createdAtEpochSec: Long? = null
        ): DtnEnvelope {
            require(senderPrivateKey.size == 32) { "Sender private key seed must be 32 bytes" }
            require(blindToken.size == 16) { "Blind token must be 16 bytes" }

            val pubKey = senderPublicKey ?: Ed25519Signer.derivePublicKey(senderPrivateKey)
            require(pubKey.size == 32) { "Sender public key must be 32 bytes" }

            val envId = customUuid ?: run {
                val uuid = UUID.randomUUID()
                ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                    .putLong(uuid.mostSignificantBits)
                    .putLong(uuid.leastSignificantBits)
                    .array()
            }
            require(envId.size == 16) { "Envelope ID must be 16 bytes" }

            val nowSec = createdAtEpochSec ?: (System.currentTimeMillis() / 1000L)
            val expiresAt = nowSec + ttlSeconds
            val cipherHash = sha256(encryptedPayload)

            val canonical = ByteBuffer.allocate(DtnConstants.CANONICAL_SIGNING_DATA_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .put(DtnConstants.MAGIC_BYTES)
                .put(DtnConstants.PROTOCOL_VERSION_1)
                .put(envelopeType)
                .put(DtnConstants.SUITE_ED25519_SHA256)
                .put(0.toByte()) // flags
                .put(envId)
                .put(pubKey)
                .put(blindToken)
                .putLong(nowSec)
                .putLong(expiresAt)
                .putInt(encryptedPayload.size)
                .put(cipherHash)
                .array()

            val sig = Ed25519Signer.sign(canonical, senderPrivateKey)

            return DtnEnvelope(
                protocolVersion = DtnConstants.PROTOCOL_VERSION_1,
                envelopeType = envelopeType,
                algorithmSuite = DtnConstants.SUITE_ED25519_SHA256,
                flags = 0,
                hopCount = 0,
                hopLimit = hopLimit,
                relayTimestamp = 0L,
                relayFlags = 0,
                envelopeId = envId,
                senderPublicKey = pubKey,
                blindToken = blindToken,
                createdAtEpochSec = nowSec,
                expiresAtEpochSec = expiresAt,
                ciphertextSha256 = cipherHash,
                signature = sig,
                encryptedPayload = encryptedPayload
            )
        }

        /**
         * Test Simulation ONLY: Creates an authenticated NXDT v1 envelope using an ephemeral key.
         * FORBIDDEN for production messaging: Production messages must call createAuthenticated()
         * with the device's authoritative Ed25519 identity key.
         */
        @Deprecated(
            message = "Use createAuthenticated with the device's authoritative identity key. Ephemeral keys must not be used for production messaging.",
            level = DeprecationLevel.WARNING
        )
        @androidx.annotation.VisibleForTesting
        fun createNew(
            blindToken: ByteArray,
            encryptedPayload: ByteArray,
            hopLimit: Byte = DtnConstants.DEFAULT_HOP_LIMIT,
            ttlSeconds: Long = DtnConstants.DEFAULT_TTL_SECONDS
        ): DtnEnvelope {
            val ephemeralPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return createAuthenticated(
                blindToken = blindToken,
                encryptedPayload = encryptedPayload,
                senderPrivateKey = ephemeralPriv,
                hopLimit = hopLimit,
                ttlSeconds = ttlSeconds
            )
        }

        /**
         * Deserializes a binary byte array into a DtnEnvelope.
         * Validates magic, lengths, and bounds.
         * By default enforces Ed25519 signature verification on NXDT v1 envelopes.
         */
        fun deserialize(bytes: ByteArray, verifySignature: Boolean = true): DtnEnvelope? {
            if (bytes.size < DtnConstants.LEGACY_ENVELOPE_HEADER_SIZE) return null

            // Check for "NXDT" Magic (0x4E, 0x58, 0x44, 0x54)
            val isNxdt = bytes.size >= 4 &&
                    bytes[0] == 0x4E.toByte() &&
                    bytes[1] == 0x58.toByte() &&
                    bytes[2] == 0x44.toByte() &&
                    bytes[3] == 0x54.toByte()

            if (isNxdt) {
                if (bytes.size < DtnConstants.NXDT_HEADER_SIZE) return null
                if (bytes.size > DtnConstants.MAX_PACKET_SIZE) return null

                return try {
                    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    val magic = ByteArray(4).also { buf.get(it) }
                    val version = buf.get()
                    if (version != DtnConstants.PROTOCOL_VERSION_1) {
                        return null // Unsupported version
                    }
                    val envType = buf.get()
                    val suite = buf.get()
                    if (suite != DtnConstants.SUITE_ED25519_SHA256) {
                        return null // Unsupported algorithm suite
                    }
                    val flags = buf.get()

                    // Mutable Relay Metadata
                    val hopCount = buf.get()
                    val hopLimit = buf.get()
                    val relayTimestamp = buf.long
                    val relayFlags = buf.short

                    // Validate hop boundaries
                    if (hopLimit < 0 || hopLimit > DtnConstants.MAX_HOP_LIMIT) return null
                    if (hopCount < 0 || hopCount > DtnConstants.MAX_HOP_LIMIT) return null

                    // Immutable Header Fields
                    val envId = ByteArray(16).also { buf.get(it) }
                    val senderPub = ByteArray(32).also { buf.get(it) }
                    val token = ByteArray(16).also { buf.get(it) }
                    val createdAt = buf.long
                    val expiresAt = buf.long
                    val payloadSize = buf.int

                    if (payloadSize < 0 || payloadSize > DtnConstants.MAX_PAYLOAD_SIZE) {
                        return null
                    }
                    val expectedRemaining = 32 + 64 + payloadSize // hash (32) + sig (64) + payload
                    if (buf.remaining() != expectedRemaining) {
                        return null
                    }

                    val cipherHash = ByteArray(32).also { buf.get(it) }
                    val sig = ByteArray(64).also { buf.get(it) }
                    val payload = ByteArray(payloadSize).also { buf.get(it) }

                    // Validate ciphertext SHA-256 payload binding
                    val actualHash = sha256(payload)
                    if (!MessageDigest.isEqual(cipherHash, actualHash)) {
                        return null
                    }

                    // Timestamp bounds check
                    if (expiresAt < createdAt) return null

                    val envelope = DtnEnvelope(
                        protocolVersion = version,
                        envelopeType = envType,
                        algorithmSuite = suite,
                        flags = flags,
                        hopCount = hopCount,
                        hopLimit = hopLimit,
                        relayTimestamp = relayTimestamp,
                        relayFlags = relayFlags,
                        envelopeId = envId,
                        senderPublicKey = senderPub,
                        blindToken = token,
                        createdAtEpochSec = createdAt,
                        expiresAtEpochSec = expiresAt,
                        ciphertextSha256 = cipherHash,
                        signature = sig,
                        encryptedPayload = payload
                    )

                    if (verifySignature && !envelope.verifySignature()) {
                        return null
                    }

                    envelope
                } catch (e: Exception) {
                    null
                }
            } else {
                // Legacy v0 fallback
                return try {
                    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    val envId = ByteArray(16).also { buf.get(it) }
                    val token = ByteArray(16).also { buf.get(it) }
                    val hops = buf.get()
                    val expiry = buf.long
                    val payloadSize = buf.int

                    if (payloadSize < 0 || buf.remaining() != payloadSize) {
                        return null
                    }

                    val payload = ByteArray(payloadSize).also { buf.get(it) }
                    DtnEnvelope(
                        protocolVersion = DtnConstants.PROTOCOL_VERSION_LEGACY,
                        envelopeType = DtnConstants.TYPE_STANDARD_DATA,
                        algorithmSuite = 0,
                        flags = 0,
                        hopCount = 0,
                        hopLimit = hops,
                        relayTimestamp = 0L,
                        relayFlags = 0,
                        envelopeId = envId,
                        senderPublicKey = null,
                        blindToken = token,
                        createdAtEpochSec = 0L,
                        expiresAtEpochSec = expiry,
                        ciphertextSha256 = null,
                        signature = null,
                        encryptedPayload = payload
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }

        private fun sha256(data: ByteArray): ByteArray {
            return MessageDigest.getInstance("SHA-256").digest(data)
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
