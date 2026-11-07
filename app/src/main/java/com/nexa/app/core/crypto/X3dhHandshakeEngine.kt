package com.nexa.app.core.crypto

import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.model.HandshakeEnvelope
import com.nexa.app.core.model.HandshakePayload
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class PrekeyBundle(
    val nxId: String,
    val deviceId: String = "",
    val identityKeyHex: String,           // X25519 DH Public Key (32 bytes hex)
    val signingKeyHex: String,            // Ed25519 Signing Public Key (32 bytes hex)
    val signedPrekeyHex: String,          // X25519 Signed Prekey (32 bytes hex)
    val signedPrekeyId: Int = 1,
    val signedPrekeySignatureHex: String, // 64-byte Ed25519 signature of SPK
    val signedPrekeyTimestamp: Long = 0L,
    val identityBindingSignatureHex: String = "",
    val identityBindingTimestamp: Long = 0L,
    val oneTimePrekeyHex: String? = null, // Optional One-Time Prekey (32 bytes hex)
    val oneTimePrekeyId: String? = null
)

/**
 * Signal-Compliant Extended Triple Diffie-Hellman (X3DH) Handshake Engine.
 * Conforms strictly to Signal X3DH Specification (Revision 1, 2016-11-04).
 *
 * Curve: Curve25519 (X25519, RFC 7748)
 * Signatures: Ed25519 (RFC 8032)
 * KDF: RFC 5869 HKDF-SHA256 with F prefix (32 bytes 0xFF), 32-byte zero salt, and info "NexaX3DH".
 */
object X3dhHandshakeEngine {

    const val INFO_STRING = "NexaX3DH"
    const val MAGIC_BIND_IDENTITY = "NXBI" // 0x4E, 0x58, 0x42, 0x49
    const val MAGIC_SIGN_SPK = "NXSP"      // 0x4E, 0x58, 0x53, 0x50
    const val PROTOCOL_VERSION_1: Byte = 0x01
    const val TAG_BIND_IDENTITY: Byte = 0x01
    const val TAG_SIGN_SPK: Byte = 0x02

    private val secureRandom = SecureRandom()
    private val PREFIX_F = ByteArray(32) { 0xFF.toByte() }
    private val ZERO_SALT_32 = ByteArray(32) { 0x00.toByte() }

    /**
     * Computes the Signal X3DH Master Shared Secret ($SK$) for Initiator (Alice).
     *
     * DH1 = X25519(sk_ik_a, pk_spk_b)
     * DH2 = X25519(sk_ek_a, pk_ik_b)
     * DH3 = X25519(sk_ek_a, pk_spk_b)
     * DH4 = X25519(sk_ek_a, pk_opk_b)  [if OPK present]
     * KM = DH1 || DH2 || DH3 || [DH4]
     * IKM = F (32 bytes 0xFF) || KM
     * PRK = HMAC-SHA256(Salt=0x00*32, IKM)
     * SK = HKDF-Expand(PRK, "NexaX3DH", 32)
     */
    fun deriveInitiatorMasterSecret(
        senderIdentityPrivateKey: ByteArray,  // 32-byte X25519 sk_ik_a
        ephemeralPrivateKey: ByteArray,        // 32-byte X25519 sk_ek_a
        recipientIdentityPublicKey: ByteArray, // 32-byte X25519 pk_ik_b
        recipientSignedPublicKey: ByteArray,   // 32-byte X25519 pk_spk_b
        recipientOneTimePublicKey: ByteArray? = null // Optional 32-byte X25519 pk_opk_b
    ): ByteArray {
        require(senderIdentityPrivateKey.size == 32) { "Initiator identity private key must be 32 bytes" }
        require(ephemeralPrivateKey.size == 32) { "Ephemeral private key must be 32 bytes" }
        require(recipientIdentityPublicKey.size == 32) { "Recipient identity public key must be 32 bytes" }
        require(recipientSignedPublicKey.size == 32) { "Recipient signed public key must be 32 bytes" }
        if (recipientOneTimePublicKey != null) {
            require(recipientOneTimePublicKey.size == 32) { "Recipient OPK public key must be 32 bytes" }
        }

        val dh1 = X25519.computeSharedSecret(senderIdentityPrivateKey, recipientSignedPublicKey)
        val dh2 = X25519.computeSharedSecret(ephemeralPrivateKey, recipientIdentityPublicKey)
        val dh3 = X25519.computeSharedSecret(ephemeralPrivateKey, recipientSignedPublicKey)

        val kmOut = ByteArrayOutputStream()
        kmOut.write(dh1)
        kmOut.write(dh2)
        kmOut.write(dh3)

        if (recipientOneTimePublicKey != null && recipientOneTimePublicKey.isNotEmpty()) {
            val dh4 = X25519.computeSharedSecret(ephemeralPrivateKey, recipientOneTimePublicKey)
            kmOut.write(dh4)
        }

        val km = kmOut.toByteArray()
        val ikm = PREFIX_F + km

        return hkdfExtractAndExpand(
            salt = ZERO_SALT_32,
            ikm = ikm,
            info = INFO_STRING.toByteArray(StandardCharsets.UTF_8),
            length = 32
        )
    }

    /**
     * Computes the Signal X3DH Master Shared Secret ($SK$) for Responder (Bob).
     *
     * DH1 = X25519(sk_spk_b, pk_ik_a)
     * DH2 = X25519(sk_ik_b, pk_ek_a)
     * DH3 = X25519(sk_spk_b, pk_ek_a)
     * DH4 = X25519(sk_opk_b, pk_ek_a)  [if OPK present]
     */
    fun deriveResponderMasterSecret(
        recipientIdentityPrivateKey: ByteArray, // 32-byte X25519 sk_ik_b
        recipientSignedPrivateKey: ByteArray,   // 32-byte X25519 sk_spk_b
        recipientOneTimePrivateKey: ByteArray? = null, // Optional 32-byte X25519 sk_opk_b
        senderIdentityPublicKey: ByteArray,     // 32-byte X25519 pk_ik_a
        senderEphemeralPublicKey: ByteArray     // 32-byte X25519 pk_ek_a
    ): ByteArray {
        require(recipientIdentityPrivateKey.size == 32) { "Recipient identity private key must be 32 bytes" }
        require(recipientSignedPrivateKey.size == 32) { "Recipient signed private key must be 32 bytes" }
        require(senderIdentityPublicKey.size == 32) { "Sender identity public key must be 32 bytes" }
        require(senderEphemeralPublicKey.size == 32) { "Sender ephemeral public key must be 32 bytes" }
        if (recipientOneTimePrivateKey != null) {
            require(recipientOneTimePrivateKey.size == 32) { "Recipient OPK private key must be 32 bytes" }
        }

        val dh1 = X25519.computeSharedSecret(recipientSignedPrivateKey, senderIdentityPublicKey)
        val dh2 = X25519.computeSharedSecret(recipientIdentityPrivateKey, senderEphemeralPublicKey)
        val dh3 = X25519.computeSharedSecret(recipientSignedPrivateKey, senderEphemeralPublicKey)

        val kmOut = ByteArrayOutputStream()
        kmOut.write(dh1)
        kmOut.write(dh2)
        kmOut.write(dh3)

        if (recipientOneTimePrivateKey != null && recipientOneTimePrivateKey.isNotEmpty()) {
            val dh4 = X25519.computeSharedSecret(recipientOneTimePrivateKey, senderEphemeralPublicKey)
            kmOut.write(dh4)
        }

        val km = kmOut.toByteArray()
        val ikm = PREFIX_F + km

        return hkdfExtractAndExpand(
            salt = ZERO_SALT_32,
            ikm = ikm,
            info = INFO_STRING.toByteArray(StandardCharsets.UTF_8),
            length = 32
        )
    }

    /**
     * Builds the canonical binary TLV attestation for binding an X25519 DH identity key
     * to an Ed25519 device signing identity key.
     */
    fun encodeIdentityBinding(
        nxId: String,
        deviceId: String,
        signingPub: ByteArray,
        dhPub: ByteArray,
        timestamp: Long
    ): ByteArray {
        require(signingPub.size == 32) { "Signing public key must be 32 bytes" }
        require(dhPub.size == 32) { "DH public key must be 32 bytes" }

        val nxIdBytes = nxId.trim().toByteArray(StandardCharsets.UTF_8)
        val devIdBytes = deviceId.trim().toByteArray(StandardCharsets.UTF_8)

        val buffer = ByteBuffer.allocate(4 + 1 + 1 + 8 + 2 + nxIdBytes.size + 2 + devIdBytes.size + 32 + 32)
        buffer.put(MAGIC_BIND_IDENTITY.toByteArray(StandardCharsets.US_ASCII))
        buffer.put(PROTOCOL_VERSION_1)
        buffer.put(TAG_BIND_IDENTITY)
        buffer.putLong(timestamp)
        buffer.putShort(nxIdBytes.size.toShort())
        buffer.put(nxIdBytes)
        buffer.putShort(devIdBytes.size.toShort())
        buffer.put(devIdBytes)
        buffer.put(signingPub)
        buffer.put(dhPub)
        return buffer.array()
    }

    /**
     * Verifies the Ed25519 signature over the device identity binding attestation.
     */
    fun verifyIdentityBindingSignature(
        signingPubHex: String,
        dhPubHex: String,
        signatureHex: String,
        nxId: String,
        deviceId: String,
        timestamp: Long
    ): Boolean {
        return try {
            val signingPub = fromHex(signingPubHex)
            val dhPub = fromHex(dhPubHex)
            val signature = fromHex(signatureHex)

            if (signingPub.size != 32 || dhPub.size != 32 || signature.size != 64) return false

            // 1. Primary check: Canonical Length-Prefixed Binary TLV Encoding
            val canonical = encodeIdentityBinding(nxId, deviceId, signingPub, dhPub, timestamp)
            if (Ed25519Signer.verify(canonical, signature, signingPub)) {
                return true
            }

            // 2. Backward compatibility fallback: Colon-delimited format
            val delimited = "NEXA_BIND_IK_V1:$nxId:$deviceId:${dhPubHex.lowercase()}:$timestamp"
                .toByteArray(StandardCharsets.UTF_8)
            if (Ed25519Signer.verify(delimited, signature, signingPub)) {
                return true
            }

            // 3. Fallback: Raw public key bytes
            Ed25519Signer.verify(dhPub, signature, signingPub)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Builds the canonical binary TLV attestation for certifying an X25519 Signed Prekey ($SPK$).
     */
    fun encodeSignedPrekey(
        spkId: Int,
        nxId: String,
        deviceId: String,
        spkPub: ByteArray,
        timestamp: Long
    ): ByteArray {
        require(spkPub.size == 32) { "SPK public key must be 32 bytes" }

        val nxIdBytes = nxId.trim().toByteArray(StandardCharsets.UTF_8)
        val devIdBytes = deviceId.trim().toByteArray(StandardCharsets.UTF_8)

        val buffer = ByteBuffer.allocate(4 + 1 + 1 + 8 + 4 + 2 + nxIdBytes.size + 2 + devIdBytes.size + 32)
        buffer.put(MAGIC_SIGN_SPK.toByteArray(StandardCharsets.US_ASCII))
        buffer.put(PROTOCOL_VERSION_1)
        buffer.put(TAG_SIGN_SPK)
        buffer.putLong(timestamp)
        buffer.putInt(spkId)
        buffer.putShort(nxIdBytes.size.toShort())
        buffer.put(nxIdBytes)
        buffer.putShort(devIdBytes.size.toShort())
        buffer.put(devIdBytes)
        buffer.put(spkPub)
        return buffer.array()
    }

    /**
     * Verifies the Ed25519 signature over the Signed Prekey ($SPK$).
     */
    fun verifySignedPrekeySignature(
        signingPubHex: String,
        spkPubHex: String,
        signatureHex: String,
        spkId: Int,
        nxId: String,
        deviceId: String,
        timestamp: Long
    ): Boolean {
        return try {
            val signingPub = fromHex(signingPubHex)
            val spkPub = fromHex(spkPubHex)
            val signature = fromHex(signatureHex)

            if (signingPub.size != 32 || spkPub.size != 32 || signature.size != 64) return false

            // 1. Primary check: Canonical Length-Prefixed Binary TLV Encoding
            val canonical = encodeSignedPrekey(spkId, nxId, deviceId, spkPub, timestamp)
            if (Ed25519Signer.verify(canonical, signature, signingPub)) {
                return true
            }

            // 2. Backward compatibility fallback: Colon-delimited format
            val delimited = "NEXA_SPK_SIG_V1:$nxId:$deviceId:$spkId:${spkPubHex.lowercase()}:$timestamp"
                .toByteArray(StandardCharsets.UTF_8)
            if (Ed25519Signer.verify(delimited, signature, signingPub)) {
                return true
            }

            // 3. Fallback: Raw public key bytes
            Ed25519Signer.verify(spkPub, signature, signingPub)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Builds an outbound X3DH handshake envelope (Request or Accept packet)
     * using the standard Signal X3DH derivation.
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
        val ephemeralPrivateKey = X25519.generatePrivateKey()
        val ephemeralPublicKey = X25519.publicFromPrivate(ephemeralPrivateKey)
        val ephemeralPublicKeyHex = toHex(ephemeralPublicKey)

        val recipientIk = fromHex(recipientPrekey.identityKeyHex)
        val recipientSpk = fromHex(recipientPrekey.signedPrekeyHex)
        val recipientOpk = recipientPrekey.oneTimePrekeyHex?.takeIf { it.isNotBlank() }?.let { fromHex(it) }

        // Derive Signal standard 256-bit Master Secret
        val masterSecret = deriveInitiatorMasterSecret(
            senderIdentityPrivateKey = senderIdentityPrivateKey,
            ephemeralPrivateKey = ephemeralPrivateKey,
            recipientIdentityPublicKey = recipientIk,
            recipientSignedPublicKey = recipientSpk,
            recipientOneTimePublicKey = recipientOpk
        )

        val payload = HandshakePayload(
            senderNxId = senderNxId,
            displayName = senderDisplayName,
            introMessage = introMessage,
            timestamp = System.currentTimeMillis(),
            ephemeralPublicKeyHex = ephemeralPublicKeyHex,
            isAccept = isAccept
        )
        val payloadBytes = payload.toBytes()

        val signature = Ed25519Signer.sign(payloadBytes, senderSigningPrivateKey)
        val signatureHex = toHex(signature)

        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val (ciphertext, authTag) = ChaChaAeadCipher.encrypt(masterSecret, iv, payloadBytes)
        val combinedCiphertext = ciphertext + authTag

        return HandshakeEnvelope(
            senderNxId = senderNxId,
            ephemeralPublicKeyHex = ephemeralPublicKeyHex,
            identityPublicKeyHex = senderIdentityPublicKeyHex,
            oneTimePrekeyId = recipientPrekey.oneTimePrekeyId,
            saltHex = toHex(ZERO_SALT_32),
            ivHex = toHex(iv),
            ciphertextHex = toHex(combinedCiphertext),
            signatureHex = signatureHex,
            powTimestamp = powTimestamp,
            powNonce = powNonce,
            isAccept = isAccept
        )
    }

    /**
     * Decrypts, verifies Ed25519 digital signature, and unpacks an inbound handshake envelope
     * using the standard Signal X3DH derivation.
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

            val opkId = envelope.oneTimePrekeyId
            val opkPriv = if (!opkId.isNullOrBlank() && recipientOneTimePrivateKeys.containsKey(opkId)) {
                recipientOneTimePrivateKeys[opkId]
            } else null

            // Derive Signal standard 256-bit Master Secret
            val masterSecret = deriveResponderMasterSecret(
                recipientIdentityPrivateKey = recipientIdentityPrivateKey,
                recipientSignedPrivateKey = recipientSignedPrivateKey,
                recipientOneTimePrivateKey = opkPriv,
                senderIdentityPublicKey = senderIk,
                senderEphemeralPublicKey = senderEk
            )

            val combinedCiphertext = fromHex(envelope.ciphertextHex)
            if (combinedCiphertext.size < 16) return null

            val ciphertext = combinedCiphertext.copyOfRange(0, combinedCiphertext.size - 16)
            val authTag = combinedCiphertext.copyOfRange(combinedCiphertext.size - 16, combinedCiphertext.size)
            val iv = fromHex(envelope.ivHex)

            val decryptedPayloadBytes = ChaChaAeadCipher.decrypt(masterSecret, iv, ciphertext, authTag)
                ?: return null // Tag verification failed or tampered

            val signature = fromHex(envelope.signatureHex)
            val isAuthentic = Ed25519Signer.verify(decryptedPayloadBytes, signature, senderSigningPublicKey)
            if (!isAuthentic) {
                return null // Impersonation signature mismatch
            }

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
        val effectiveSalt = if (salt.isEmpty()) ZERO_SALT_32 else salt
        mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        return hkdfExpand(prk, info, length)
    }

    /**
     * Multi-block RFC 5869 HKDF Expand supporting lengths up to 255 * 32 bytes.
     */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val numBlocks = (length + 31) / 32
        val out = ByteArrayOutputStream()
        var currentBlock = ByteArray(0)
        for (i in 1..numBlocks) {
            mac.reset()
            if (currentBlock.isNotEmpty()) {
                mac.update(currentBlock)
            }
            if (info.isNotEmpty()) {
                mac.update(info)
            }
            mac.update(i.toByte())
            currentBlock = mac.doFinal()
            out.write(currentBlock)
        }
        return out.toByteArray().copyOfRange(0, length)
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        require(len % 2 == 0) { "Hex string length must be even" }
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
