package com.nexa.app.core.sms.nx1

import com.nexa.app.core.crypto.ChaChaAeadCipher
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Independent offline SMS cryptography.
 *
 * This state is completely separate from the Internet Double Ratchet. It is derived from a
 * per-device X25519 "SMS provisioning" keypair whose public half is exchanged through the
 * existing authenticated E2EE Internet channel. The derived session root is never persisted.
 *
 * Key schedule (HKDF-SHA256, RFC 5869):
 *   root           = HKDF(salt = "NEXA_SMS_V1_SALT", ikm = X25519(localPriv, peerPub),
 *                         info = "NEXA_SMS_V1|ROOT|<keyVersion>|<sortedNxIds>", 32)
 *   message key    = HKDF(salt = "NEXA_SMS_V1_DIR", ikm = root,
 *                         info = "NEXA_SMS_V1|MSG|<senderNxId>|<recipientNxId>", 32)
 *   ack key        = HKDF(salt = "NEXA_SMS_V1_ACK", ikm = root,
 *                         info = "NEXA_SMS_V1|ACK|<senderNxId>|<recipientNxId>", 32)
 *
 * Because the info strings are keyed by the (sender, recipient) pair, the sender's message key
 * equals the recipient's message key for that direction only — direction keys are independent.
 *
 * Payload layout (opaque to the SMS network):
 *   [0]        format version (0x01)
 *   [1..2]     key version (uint16 BE)
 *   [3..10]    send counter (uint64 BE) — also the nonce counter
 *   [11..22]   12-byte ChaCha20-Poly1305 nonce
 *   [23..]     ciphertext || 16-byte Poly1305 tag
 */
object Nx1Crypto {
    const val FORMAT_VERSION: Byte = 0x01
    const val HEADER_SIZE = 1 + 2 + 8 + 12
    const val TAG_SIZE = 16
    const val NONCE_SIZE = 12

    val ROOT_SALT = "NEXA_SMS_V1_SALT".toByteArray(StandardCharsets.US_ASCII)
    val DIR_SALT = "NEXA_SMS_V1_DIR".toByteArray(StandardCharsets.US_ASCII)
    val ACK_SALT = "NEXA_SMS_V1_ACK".toByteArray(StandardCharsets.US_ASCII)
}

data class Nx1DirectionKeys(
    val keyVersion: Int,
    val sendingKey: ByteArray,
    val receivingKey: ByteArray,
    val ackSendingKey: ByteArray,
    val ackReceivingKey: ByteArray
) {
    fun wipe() {
        java.util.Arrays.fill(sendingKey, 0)
        java.util.Arrays.fill(receivingKey, 0)
        java.util.Arrays.fill(ackSendingKey, 0)
        java.util.Arrays.fill(ackReceivingKey, 0)
    }
}

sealed class Nx1DecryptResult {
    data class Success(val plaintext: ByteArray, val counter: Long) : Nx1DecryptResult()
    data class Failure(val reason: Nx1DecryptFailure) : Nx1DecryptResult()
}

enum class Nx1DecryptFailure {
    MALFORMED,
    OVERSIZED,
    KEY_VERSION_MISMATCH,
    AUTHENTICATION_FAILED
}

object Nx1KeySchedule {

    fun canonicalPair(nxIdA: String, nxIdB: String): String =
        if (nxIdA <= nxIdB) "$nxIdA|$nxIdB" else "$nxIdB|$nxIdA"

    fun deriveSessionRoot(
        sharedSecret: ByteArray,
        localNxId: String,
        peerNxId: String,
        keyVersion: Int
    ): ByteArray {
        require(sharedSecret.size == 32) { "X25519 shared secret must be 32 bytes" }
        require(keyVersion > 0) { "keyVersion must be positive" }
        val info = "NEXA_SMS_V1|ROOT|$keyVersion|${canonicalPair(localNxId, peerNxId)}"
            .toByteArray(StandardCharsets.US_ASCII)
        return hkdf(Nx1Crypto.ROOT_SALT, sharedSecret, info, 32)
    }

    fun deriveDirectionKeys(root: ByteArray, localNxId: String, peerNxId: String, keyVersion: Int): Nx1DirectionKeys {
        require(root.size == 32) { "SMS session root must be 32 bytes" }
        fun messageKey(sender: String, recipient: String): ByteArray = hkdf(
            salt = Nx1Crypto.DIR_SALT,
            ikm = root,
            info = "NEXA_SMS_V1|MSG|$sender|$recipient".toByteArray(StandardCharsets.US_ASCII),
            length = 32
        )
        fun ackKey(sender: String, recipient: String): ByteArray = hkdf(
            salt = Nx1Crypto.ACK_SALT,
            ikm = root,
            info = "NEXA_SMS_V1|ACK|$sender|$recipient".toByteArray(StandardCharsets.US_ASCII),
            length = 32
        )
        return Nx1DirectionKeys(
            keyVersion = keyVersion,
            sendingKey = messageKey(localNxId, peerNxId),
            receivingKey = messageKey(peerNxId, localNxId),
            ackSendingKey = ackKey(localNxId, peerNxId),
            ackReceivingKey = ackKey(peerNxId, localNxId)
        )
    }

    /** Nonce = 4-byte key version || 8-byte counter. Unique for every encryption under a key. */
    fun deriveNonce(keyVersion: Int, counter: Long): ByteArray {
        require(keyVersion in 1..0xFFFF) { "key version out of range" }
        require(counter >= 0) { "counter must be non-negative" }
        val nonce = ByteArray(Nx1Crypto.NONCE_SIZE)
        nonce[0] = ((keyVersion ushr 24) and 0xFF).toByte()
        nonce[1] = ((keyVersion ushr 16) and 0xFF).toByte()
        nonce[2] = ((keyVersion ushr 8) and 0xFF).toByte()
        nonce[3] = (keyVersion and 0xFF).toByte()
        for (i in 0 until 8) {
            nonce[4 + i] = ((counter ushr (56 - 8 * i)) and 0xFF).toByte()
        }
        return nonce
    }

    /** AAD binds packet id, sender identity, recipient identity, key version and counter. */
    fun buildMessageAad(
        messageId: String,
        senderNxId: String,
        recipientNxId: String,
        keyVersion: Int,
        counter: Long
    ): ByteArray = "NX1|1|$messageId|$senderNxId|$recipientNxId|$keyVersion|$counter"
        .toByteArray(StandardCharsets.US_ASCII)

    fun buildAckTag(
        ackKey: ByteArray,
        messageId: String,
        ackSenderNxId: String,
        ackRecipientNxId: String,
        counter: Long
    ): ByteArray = hmac(
        ackKey,
        "NX1|ACK|$messageId|$ackSenderNxId|$ackRecipientNxId|$counter"
            .toByteArray(StandardCharsets.US_ASCII)
    ).copyOf(16)

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArrayOutputStream(length)
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            val input = ByteArray(previous.size + info.size + 1)
            System.arraycopy(previous, 0, input, 0, previous.size)
            System.arraycopy(info, 0, input, previous.size, info.size)
            input[input.size - 1] = counter.toByte()
            previous = hmac(prk, input)
            out.write(previous)
            counter++
        }
        val result = out.toByteArray().copyOf(length)
        java.util.Arrays.fill(prk, 0)
        java.util.Arrays.fill(previous, 0)
        return result
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

object Nx1Cipher {

    /**
     * Encrypts [plaintext] into one NX1 part payload.
     * Throws only on programmer error (oversized plaintext / invalid counter).
     */
    fun encryptPart(
        keys: Nx1DirectionKeys,
        counter: Long,
        messageId: String,
        senderNxId: String,
        recipientNxId: String,
        plaintext: ByteArray
    ): ByteArray {
        require(counter >= 0) { "counter must be non-negative" }
        require(plaintext.size <= Nx1.MAX_PLAINTEXT_BYTES) { "plaintext exceeds NX1 plaintext limit" }

        val keyVersion = keys.keyVersion
        val nonce = Nx1KeySchedule.deriveNonce(keyVersion, counter)
        val aad = Nx1KeySchedule.buildMessageAad(messageId, senderNxId, recipientNxId, keyVersion, counter)
        val (ciphertext, tag) = ChaChaAeadCipher.encrypt(
            key = keys.sendingKey,
            nonce = nonce,
            plaintext = plaintext,
            associatedData = aad
        )

        val payload = ByteArray(Nx1Crypto.HEADER_SIZE + ciphertext.size + Nx1Crypto.TAG_SIZE)
        payload[0] = Nx1Crypto.FORMAT_VERSION
        payload[1] = ((keyVersion ushr 8) and 0xFF).toByte()
        payload[2] = (keyVersion and 0xFF).toByte()
        for (i in 0 until 8) {
            payload[3 + i] = ((counter ushr (56 - 8 * i)) and 0xFF).toByte()
        }
        System.arraycopy(nonce, 0, payload, 11, Nx1Crypto.NONCE_SIZE)
        System.arraycopy(ciphertext, 0, payload, Nx1Crypto.HEADER_SIZE, ciphertext.size)
        System.arraycopy(tag, 0, payload, Nx1Crypto.HEADER_SIZE + ciphertext.size, Nx1Crypto.TAG_SIZE)
        return payload
    }

    /**
     * Decrypts and authenticates a complete reassembled NX1 part payload.
     * Never throws and never returns plaintext on any validation or authentication failure.
     */
    fun decryptPart(
        keys: Nx1DirectionKeys,
        messageId: String,
        senderNxId: String,
        recipientNxId: String,
        payload: ByteArray
    ): Nx1DecryptResult {
        if (payload.size < Nx1Crypto.HEADER_SIZE + Nx1Crypto.TAG_SIZE) {
            return Nx1DecryptResult.Failure(Nx1DecryptFailure.MALFORMED)
        }
        if (payload.size > Nx1.MAX_CIPHERTEXT_BYTES) {
            return Nx1DecryptResult.Failure(Nx1DecryptFailure.OVERSIZED)
        }
        if (payload[0] != Nx1Crypto.FORMAT_VERSION) {
            return Nx1DecryptResult.Failure(Nx1DecryptFailure.MALFORMED)
        }

        val keyVersion = (((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF))
        if (keyVersion != keys.keyVersion) {
            return Nx1DecryptResult.Failure(Nx1DecryptFailure.KEY_VERSION_MISMATCH)
        }

        var counter = 0L
        for (i in 0 until 8) {
            counter = (counter shl 8) or (payload[3 + i].toLong() and 0xFF)
        }
        if (counter < 0) return Nx1DecryptResult.Failure(Nx1DecryptFailure.MALFORMED)

        val nonce = payload.copyOfRange(11, 11 + Nx1Crypto.NONCE_SIZE)
        val expectedNonce = Nx1KeySchedule.deriveNonce(keyVersion, counter)
        if (!Nx1KeySchedule.constantTimeEquals(nonce, expectedNonce)) {
            return Nx1DecryptResult.Failure(Nx1DecryptFailure.AUTHENTICATION_FAILED)
        }

        val ciphertext = payload.copyOfRange(Nx1Crypto.HEADER_SIZE, payload.size - Nx1Crypto.TAG_SIZE)
        val tag = payload.copyOfRange(payload.size - Nx1Crypto.TAG_SIZE, payload.size)
        val aad = Nx1KeySchedule.buildMessageAad(messageId, senderNxId, recipientNxId, keyVersion, counter)

        val plaintext = ChaChaAeadCipher.decrypt(keys.receivingKey, nonce, ciphertext, tag, aad)
            ?: return Nx1DecryptResult.Failure(Nx1DecryptFailure.AUTHENTICATION_FAILED)

        return Nx1DecryptResult.Success(plaintext, counter)
    }

    fun createAck(
        keys: Nx1DirectionKeys,
        ackCounter: Long,
        messageId: String,
        ackSenderNxId: String,
        ackRecipientNxId: String
    ): Nx1AckPacket {
        val tag = Nx1KeySchedule.buildAckTag(
            ackKey = keys.ackSendingKey,
            messageId = messageId,
            ackSenderNxId = ackSenderNxId,
            ackRecipientNxId = ackRecipientNxId,
            counter = ackCounter
        )
        return Nx1AckPacket(messageId = messageId, counter = ackCounter, authTag = tag)
    }

    fun verifyAck(
        keys: Nx1DirectionKeys,
        ack: Nx1AckPacket,
        ackSenderNxId: String,
        ackRecipientNxId: String
    ): Boolean {
        val expected = Nx1KeySchedule.buildAckTag(
            ackKey = keys.ackReceivingKey,
            messageId = ack.messageId,
            ackSenderNxId = ackSenderNxId,
            ackRecipientNxId = ackRecipientNxId,
            counter = ack.counter
        )
        return Nx1KeySchedule.constantTimeEquals(expected, ack.authTag)
    }
}
