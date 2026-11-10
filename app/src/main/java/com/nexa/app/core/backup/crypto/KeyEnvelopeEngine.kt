package com.nexa.app.core.backup.crypto

import com.nexa.app.core.crypto.X3dhHandshakeEngine
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Implements Design C: Dual-Layer Cryptographic Key Envelope (`key_envelope.bin`).
 *
 * Wire Structure (66 Bytes Total):
 * ┌──────────────────────┬──────────────────────┬──────────────────────┬───────────────────────────────┐
 * │ Envelope Magic       │ Envelope Version     │ Envelope Nonce / IV  │ Ciphertext Payload + Tag      │
 * │ "NXKE" (ASCII)       │ (Short: 2)           │ (12 Bytes CSPRNG)    │ (32B Ciphertext + 16B GCM Tag)│
 * │ (4 Bytes)            │ (2 Bytes)            │                      │ (48 Bytes Total)              │
 * └──────────────────────┴──────────────────────┴──────────────────────┴───────────────────────────────┘
 * Bytes: 0..3             4..5                   6..17                  18..65
 */
object KeyEnvelopeEngine {

    const val ENVELOPE_MAGIC = "NXKE"
    const val ENVELOPE_VERSION: Short = 2
    const val IV_SIZE = 12
    const val KEY_SIZE = 32
    const val TAG_SIZE = 16
    const val ENVELOPE_SIZE = 4 + 2 + IV_SIZE + KEY_SIZE + TAG_SIZE // 66 Bytes

    private val DOMAIN_INFO = "NEXA_V2_KEY_ENVELOPE_KEY".toByteArray(Charsets.UTF_8)
    private val secureRandom = SecureRandom()

    /**
     * Derives a dedicated 256-bit Key Envelope Key (K_env) from K_vault using HKDF-SHA256
     * with strict domain separation.
     */
    fun deriveEnvelopeKey(vaultMasterKeyBytes: ByteArray, salt: ByteArray): ByteArray {
        return X3dhHandshakeEngine.hkdfExtractAndExpand(
            salt = salt,
            ikm = vaultMasterKeyBytes,
            info = DOMAIN_INFO,
            length = KEY_SIZE
        )
    }

    /**
     * Seals the 32-byte local database passphrase into a 66-byte authenticated binary envelope.
     */
    fun sealPassphrase(
        dbPassphrase: ByteArray,
        vaultMasterKeyBytes: ByteArray,
        salt: ByteArray
    ): ByteArray {
        require(dbPassphrase.size == KEY_SIZE) { "Database passphrase must be exactly $KEY_SIZE bytes" }

        val envelopeKey = deriveEnvelopeKey(vaultMasterKeyBytes, salt)
        val iv = ByteArray(IV_SIZE).also { secureRandom.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(envelopeKey, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_SIZE * 8, iv))

        // Bind magic and version as Additional Authenticated Data (AAD)
        val aadBuffer = ByteBuffer.allocate(6)
        aadBuffer.put(ENVELOPE_MAGIC.toByteArray(Charsets.US_ASCII))
        aadBuffer.putShort(ENVELOPE_VERSION)
        cipher.updateAAD(aadBuffer.array())

        val encryptedPayload = cipher.doFinal(dbPassphrase)

        val outBuffer = ByteBuffer.allocate(ENVELOPE_SIZE)
        outBuffer.put(ENVELOPE_MAGIC.toByteArray(Charsets.US_ASCII))
        outBuffer.putShort(ENVELOPE_VERSION)
        outBuffer.put(iv)
        outBuffer.put(encryptedPayload)

        // Wipe intermediate envelope key from RAM
        java.util.Arrays.fill(envelopeKey, 0.toByte())

        return outBuffer.array()
    }

    /**
     * Unseals the 66-byte binary envelope in volatile RAM and recovers the 32-byte database passphrase.
     * Guaranteed ZERO disk exposure: Operates entirely on RAM byte arrays.
     */
    fun unsealPassphrase(
        envelopeBytes: ByteArray,
        vaultMasterKeyBytes: ByteArray,
        salt: ByteArray
    ): ByteArray {
        if (envelopeBytes.size != ENVELOPE_SIZE) {
            throw SecurityException("Corrupted or invalid key envelope size (${envelopeBytes.size} != $ENVELOPE_SIZE)")
        }

        val buffer = ByteBuffer.wrap(envelopeBytes)
        val magicBytes = ByteArray(4)
        buffer.get(magicBytes)
        val magic = String(magicBytes, Charsets.US_ASCII)
        if (magic != ENVELOPE_MAGIC) {
            throw SecurityException("Invalid key envelope magic header: $magic")
        }

        val version = buffer.short
        if (version != ENVELOPE_VERSION) {
            throw SecurityException("Unsupported key envelope version: $version")
        }

        val iv = ByteArray(IV_SIZE).also { buffer.get(it) }
        val encryptedPayload = ByteArray(KEY_SIZE + TAG_SIZE).also { buffer.get(it) }

        val envelopeKey = deriveEnvelopeKey(vaultMasterKeyBytes, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(envelopeKey, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_SIZE * 8, iv))

        val aadBuffer = ByteBuffer.allocate(6)
        aadBuffer.put(ENVELOPE_MAGIC.toByteArray(Charsets.US_ASCII))
        aadBuffer.putShort(ENVELOPE_VERSION)
        cipher.updateAAD(aadBuffer.array())

        val recoveredPassphrase = try {
            cipher.doFinal(encryptedPayload)
        } finally {
            java.util.Arrays.fill(envelopeKey, 0.toByte())
        }

        if (recoveredPassphrase.size != KEY_SIZE) {
            throw SecurityException("Unsealed database passphrase size invalid (${recoveredPassphrase.size})")
        }

        return recoveredPassphrase
    }
}
