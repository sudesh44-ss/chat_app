package com.nexa.app.core.dtn

import com.google.crypto.tink.subtle.X25519
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pairwise Rotating Privacy-Preserving Routing Identifier Generator (NEXA Phase C).
 *
 * Replaces deterministic, publicly predictable blind routing tokens:
 *   Old (vulnerable): BlindToken = Truncate16(Blake3(RecipientPublicKey || PublicDaySalt))
 *   New (Phase C):
 *     1. K_route = HKDF-Expand(HKDF-Extract(Salt, PairwiseSecret), "NEXA_PAIRWISE_ROUTING_KEY_V1:" || senderNxId || ":" || recipientNxId, 32)
 *     2. RoutingID = Truncate16(HMAC-SHA256(K_route, "NEXA_ROUTING_EPOCH_V1_" || BigEndianInt64(epoch)))
 *
 * Security Properties:
 * - Unpredictable to outsiders: Requires knowledge of the pairwise shared secret.
 * - Cryptographic key separation: Dedicated KDF context prevents reuse of Double Ratchet message keys.
 * - Directional separation: K_route(A -> B) != K_route(B -> A), preventing cross-direction correlation.
 * - Bounded epoch tolerance: Validates across +/- 1 epoch window (current, previous, next) for clock drift & DTN delay.
 * - Zero plaintext leakage: Relays, observers, and cloud gateway cannot deduce sender or recipient identities.
 */
object PairwiseRoutingTokenGenerator {

    val SALT_ROUTING_KEY: ByteArray = "NEXA_PAIRWISE_ROUTING_SALT_V1".toByteArray(StandardCharsets.UTF_8)
    const val INFO_PREFIX = "NEXA_PAIRWISE_ROUTING_KEY_V1:"
    val HMAC_EPOCH_PREFIX: ByteArray = "NEXA_ROUTING_EPOCH_V1_".toByteArray(StandardCharsets.UTF_8)

    const val EPOCH_DURATION_SECONDS: Long = 86400L // 24-hour epoch
    const val TOKEN_LENGTH_BYTES: Int = 16

    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * Derives a 32-byte pairwise shared secret from a local X25519 private key and remote public key.
     */
    fun derivePairwiseDhSecret(localPrivateKey: ByteArray, remotePublicKey: ByteArray): ByteArray {
        require(localPrivateKey.size == 32) { "Local private key must be 32 bytes" }
        require(remotePublicKey.size == 32) { "Remote public key must be 32 bytes" }
        return X25519.computeSharedSecret(localPrivateKey, remotePublicKey)
    }

    /**
     * Derives a dedicated 32-byte directional routing key using RFC 5869 HKDF-SHA256.
     *
     * @param pairwiseSecret 32-byte pairwise secret (from X25519 ECDH or session secret)
     * @param senderNxId NX-ID of the sending endpoint
     * @param recipientNxId NX-ID of the receiving endpoint
     */
    fun deriveRoutingKey(
        pairwiseSecret: ByteArray,
        senderNxId: String,
        recipientNxId: String
    ): ByteArray {
        require(pairwiseSecret.size == 32) { "Pairwise secret must be 32 bytes" }
        require(senderNxId.isNotBlank()) { "senderNxId cannot be blank" }
        require(recipientNxId.isNotBlank()) { "recipientNxId cannot be blank" }

        val infoString = "$INFO_PREFIX$senderNxId:$recipientNxId"
        val infoBytes = infoString.toByteArray(StandardCharsets.UTF_8)

        val prk = hkdfExtract(SALT_ROUTING_KEY, pairwiseSecret)
        return hkdfExpand(prk, infoBytes, 32)
    }

    /**
     * Generates a 16-byte rotating routing token for the specified epoch.
     *
     * @param routingKey 32-byte directional routing key
     * @param epoch Epoch number (defaults to current epoch)
     */
    fun generateRoutingToken(
        routingKey: ByteArray,
        epoch: Long = currentEpoch()
    ): ByteArray {
        require(routingKey.size == 32) { "Routing key must be 32 bytes" }

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(routingKey, HMAC_ALGORITHM))

        val epochBuf = ByteBuffer.allocate(8).putLong(epoch).array()
        mac.update(HMAC_EPOCH_PREFIX)
        mac.update(epochBuf)
        val fullMac = mac.doFinal()

        val token = ByteArray(TOKEN_LENGTH_BYTES)
        System.arraycopy(fullMac, 0, token, 0, TOKEN_LENGTH_BYTES)
        return token
    }

    /**
     * Generates a 32-character hex-encoded rotating routing token for the specified epoch.
     */
    fun generateRoutingTokenHex(
        routingKey: ByteArray,
        epoch: Long = currentEpoch()
    ): String {
        return generateRoutingToken(routingKey, epoch).toHex()
    }

    /**
     * Calculates the current epoch number based on unix timestamp seconds.
     */
    fun currentEpoch(timestampSec: Long = System.currentTimeMillis() / 1000L): Long {
        return timestampSec / EPOCH_DURATION_SECONDS
    }

    /**
     * Returns the list of active inbound routing tokens within the bounded tolerance window
     * [currentEpoch - 2, currentEpoch - 1, currentEpoch, currentEpoch + 1] (2 Days Ago, Yesterday, Today, Tomorrow).
     * This provides seamless coverage for 24h, 36h, and 48h offline DTN delivery across midnight boundaries.
     */
    fun getActiveInboundTokens(
        routingKey: ByteArray,
        currentEpoch: Long = currentEpoch()
    ): List<ByteArray> {
        return listOf(
            generateRoutingToken(routingKey, currentEpoch),
            generateRoutingToken(routingKey, currentEpoch - 1),
            generateRoutingToken(routingKey, currentEpoch - 2),
            generateRoutingToken(routingKey, currentEpoch + 1)
        )
    }

    /**
     * Returns the hex representations of active inbound routing tokens within the bounded window.
     */
    fun getActiveInboundTokenHexes(
        routingKey: ByteArray,
        currentEpoch: Long = currentEpoch()
    ): List<String> {
        return getActiveInboundTokens(routingKey, currentEpoch).map { it.toHex() }
    }

    /**
     * Constant-time verification of whether a candidate 16-byte token is valid
     * within the bounded tolerance window [epoch - 2, epoch - 1, epoch, epoch + 1].
     */
    fun isTokenInWindow(
        candidateToken: ByteArray,
        routingKey: ByteArray,
        currentEpoch: Long = currentEpoch()
    ): Boolean {
        if (candidateToken.size != TOKEN_LENGTH_BYTES) return false
        val activeTokens = getActiveInboundTokens(routingKey, currentEpoch)
        for (active in activeTokens) {
            if (MessageDigest.isEqual(candidateToken, active)) {
                return true
            }
        }
        return false
    }

    /**
     * Verifies routing token validity against the envelope's creation timestamp
     * with strict bounds checking (rejecting expired envelopes, excessive future skew > 5m,
     * or envelopes exceeding maximum 7-day DTN lifetime).
     */
    fun isTokenInWindowForEnvelope(
        candidateToken: ByteArray,
        routingKey: ByteArray,
        createdAtEpochSec: Long,
        expiresAtEpochSec: Long,
        nowSec: Long = System.currentTimeMillis() / 1000L
    ): Boolean {
        if (candidateToken.size != TOKEN_LENGTH_BYTES || routingKey.size != 32) return false
        if (nowSec > expiresAtEpochSec) return false // TTL expired
        if (createdAtEpochSec > nowSec + DtnConstants.MAX_FUTURE_SKEW_SECONDS) return false // Future skew
        if (nowSec - createdAtEpochSec > DtnConstants.MAX_TTL_SECONDS ||
            expiresAtEpochSec - createdAtEpochSec > DtnConstants.MAX_TTL_SECONDS) return false // Exceeds 7-day max lifetime

        // 1. Direct creation epoch check
        val creationEpoch = currentEpoch(createdAtEpochSec)
        val expectedToken = generateRoutingToken(routingKey, creationEpoch)
        if (MessageDigest.isEqual(candidateToken, expectedToken)) {
            return true
        }

        // 2. Fallback to active tolerance window at current time
        return isTokenInWindow(candidateToken, routingKey, currentEpoch(nowSec))
    }

    /**
     * RFC 5869 HKDF-Extract using HMAC-SHA256.
     */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(actualSalt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    /**
     * RFC 5869 HKDF-Expand using HMAC-SHA256.
     */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * 32) { "Output length exceeds maximum permitted by RFC 5869" }
        val n = (length + 31) / 32
        val okm = ByteArray(length)
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))

        var prev = ByteArray(0)
        var offset = 0

        for (i in 1..n) {
            mac.reset()
            if (prev.isNotEmpty()) {
                mac.update(prev)
            }
            if (info.isNotEmpty()) {
                mac.update(info)
            }
            mac.update(i.toByte())
            prev = mac.doFinal()

            val toCopy = minOf(prev.size, length - offset)
            System.arraycopy(prev, 0, okm, offset, toCopy)
            offset += toCopy
        }

        return okm
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
