package com.nexa.app.core.crypto

import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.model.HandshakeEnvelope
import com.nexa.app.core.model.HandshakePayload
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class PrekeyBundle(
    val nxId: String,
    val identityKeyHex: String,           // X25519 DH Public Key
    val signingKeyHex: String,            // Ed25519 Signing Public Key
    val signedPrekeyHex: String,          // X25519 Signed Prekey
    val signedPrekeySignatureHex: String, // Signature of SPK
    val oneTimePrekeyHex: String? = null, // Optional One-Time Prekey
    val oneTimePrekeyId: String? = null
)

/**
 * Extended Triple Diffie-Hellman (X3DH) Handshake Engine.
 * Provides mutual identity authentication, forward secrecy, and anti-impersonation.
 */
object X3dhHandshakeEngine {

    private val secureRandom = SecureRandom()

    /**
     * Builds an outbound X3DH handshake envelope (Request or Accept packet).
     */
    fun buildInitialEnvelope(
        senderNxId: String,
        senderDisplayName: String,
        introMessage: String?,
        senderIdentityPrivateKey: ByteArray,  // 32-byte X25519 DH private key
        senderIdentityPublicKeyHex: String,   // 32-byte X25519 DH public key (hex)
        senderSigningPrivateKey: ByteArray,   // 32-byte Ed25519 signing private key
        recipientPrekey: PrekeyBundle,
        powNonce: Long,
        powTimestamp: Long,
        isAccept: Boolean = false
    ): HandshakeEnvelope {
        // 1. Generate Ephemeral X25519 Keypair (EK_A)
        val ephemeralPrivateKey = X25519.generatePrivateKey()
        val ephemeralPublicKey = X25519.publicFromPrivate(ephemeralPrivateKey)
        val ephemeralPublicKeyHex = toHex(ephemeralPublicKey)

        // 2. Perform X3DH Diffie-Hellman operations
        val recipientIk = fromHex(recipientPrekey.identityKeyHex)
        val recipientSpk = fromHex(recipientPrekey.signedPrekeyHex)

        val dh1 = X25519.computeSharedSecret(senderIdentityPrivateKey, recipientSpk)
        val dh2 = X25519.computeSharedSecret(ephemeralPrivateKey, recipientIk)
        val dh3 = X25519.computeSharedSecret(ephemeralPrivateKey, recipientSpk)

        val dhOutput = ByteArrayOutputStream()
        dhOutput.write(dh1)
        dhOutput.write(dh2)
        dhOutput.write(dh3)

        if (!recipientPrekey.oneTimePrekeyHex.isNullOrBlank()) {
            val recipientOpk = fromHex(recipientPrekey.oneTimePrekeyHex)
            val dh4 = X25519.computeSharedSecret(ephemeralPrivateKey, recipientOpk)
            dhOutput.write(dh4)
        }

        // 3. Derive 256-bit Master Key via HKDF-SHA256
        val masterSecret = hkdfExtractAndExpand(
            salt = "NEXA_X3DH_SALT_V1".toByteArray(Charsets.UTF_8),
            ikm = dhOutput.toByteArray(),
            info = "NEXA_HANDSHAKE_ENVELOPE".toByteArray(Charsets.UTF_8),
            length = 32
        )

        // 4. Construct Plaintext Inner Payload
        val payload = HandshakePayload(
            senderNxId = senderNxId,
            displayName = senderDisplayName,
            introMessage = introMessage,
            timestamp = System.currentTimeMillis(),
            ephemeralPublicKeyHex = ephemeralPublicKeyHex,
            isAccept = isAccept
        )
        val payloadBytes = payload.toBytes()

        // 5. Sign Plaintext with Ed25519 Identity Key (Anti-Impersonation)
        val signature = Ed25519Signer.sign(payloadBytes, senderSigningPrivateKey)
        val signatureHex = toHex(signature)

        // 6. Encrypt Payload with ChaCha20-Poly1305
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val (ciphertext, authTag) = ChaChaAeadCipher.encrypt(masterSecret, iv, payloadBytes)
        val combinedCiphertext = ciphertext + authTag

        return HandshakeEnvelope(
            senderNxId = senderNxId,
            ephemeralPublicKeyHex = ephemeralPublicKeyHex,
            identityPublicKeyHex = senderIdentityPublicKeyHex,
            oneTimePrekeyId = recipientPrekey.oneTimePrekeyId,
            saltHex = toHex("NEXA_X3DH_SALT_V1".toByteArray(Charsets.UTF_8)),
            ivHex = toHex(iv),
            ciphertextHex = toHex(combinedCiphertext),
            signatureHex = signatureHex,
            powTimestamp = powTimestamp,
            powNonce = powNonce,
            isAccept = isAccept
        )
    }

    /**
     * Decrypts, verifies Ed25519 digital signature, and unpacks an inbound handshake envelope.
     * Returns null if tampering, tag mismatch, or impersonation occurs.
     */
    fun processInboundEnvelope(
        envelope: HandshakeEnvelope,
        recipientIdentityPrivateKey: ByteArray, // 32-byte X25519 DH private key
        recipientSignedPrivateKey: ByteArray,   // 32-byte X25519 DH private key
        senderSigningPublicKey: ByteArray,      // 32-byte Ed25519 public key
        recipientOneTimePrivateKeys: Map<String, ByteArray> = emptyMap()
    ): HandshakePayload? {
        return try {
            val senderIk = fromHex(envelope.identityPublicKeyHex)
            val senderEk = fromHex(envelope.ephemeralPublicKeyHex)

            // 1. Recipient X3DH Diffie-Hellman operations
            val dh1 = X25519.computeSharedSecret(recipientSignedPrivateKey, senderIk)
            val dh2 = X25519.computeSharedSecret(recipientIdentityPrivateKey, senderEk)
            val dh3 = X25519.computeSharedSecret(recipientSignedPrivateKey, senderEk)

            val dhOutput = ByteArrayOutputStream()
            dhOutput.write(dh1)
            dhOutput.write(dh2)
            dhOutput.write(dh3)

            val opkId = envelope.oneTimePrekeyId
            if (!opkId.isNullOrBlank() && recipientOneTimePrivateKeys.containsKey(opkId)) {
                val opkPriv = recipientOneTimePrivateKeys[opkId]!!
                val dh4 = X25519.computeSharedSecret(opkPriv, senderEk)
                dhOutput.write(dh4)
            }

            // 2. Derive 256-bit Master Key via HKDF
            val masterSecret = hkdfExtractAndExpand(
                salt = "NEXA_X3DH_SALT_V1".toByteArray(Charsets.UTF_8),
                ikm = dhOutput.toByteArray(),
                info = "NEXA_HANDSHAKE_ENVELOPE".toByteArray(Charsets.UTF_8),
                length = 32
            )

            // 3. Decrypt Payload via ChaCha20-Poly1305
            val combinedCiphertext = fromHex(envelope.ciphertextHex)
            if (combinedCiphertext.size < 16) return null

            val ciphertext = combinedCiphertext.copyOfRange(0, combinedCiphertext.size - 16)
            val authTag = combinedCiphertext.copyOfRange(combinedCiphertext.size - 16, combinedCiphertext.size)
            val iv = fromHex(envelope.ivHex)

            val decryptedPayloadBytes = ChaChaAeadCipher.decrypt(masterSecret, iv, ciphertext, authTag)
                ?: return null // Tag verification failed or tampered

            // 4. Validate Ed25519 Signature (Anti-Impersonation Check)
            val signature = fromHex(envelope.signatureHex)
            val isAuthentic = Ed25519Signer.verify(decryptedPayloadBytes, signature, senderSigningPublicKey)
            if (!isAuthentic) {
                return null // Impersonation signature mismatch
            }

            // 5. Parse and Return Decrypted Payload
            HandshakePayload.fromBytes(decryptedPayloadBytes)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Standard RFC 5869 HKDF Extract and Expand using HMAC-SHA256.
     */
    fun hkdfExtractAndExpand(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01.toByte())
        val okm = mac.doFinal()
        return okm.copyOfRange(0, length)
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun fromHex(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
