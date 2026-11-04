package com.nexa.app.core.crypto

import com.google.crypto.tink.subtle.ChaCha20Poly1305
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * ChaCha20-Poly1305 Symmetric Channel Encryptor.
 * Provides authenticated encryption with associated data (AEAD) for compact wire packets.
 */
class ChannelEncryptor(keyBytes: ByteArray) {

    private val cipher: ChaCha20Poly1305

    init {
        val symmetricKey = if (keyBytes.size == 32) {
            keyBytes
        } else {
            // Derive a deterministic 32-byte key using SHA-256
            MessageDigest.getInstance("SHA-256").digest(keyBytes)
        }
        cipher = ChaCha20Poly1305(symmetricKey)
    }

    /**
     * Encrypts plaintext bytes using ChaCha20-Poly1305.
     * The resulting ciphertext contains the prepended 12-byte nonce followed by ciphertext and 16-byte Poly1305 tag.
     */
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        return cipher.encrypt(plaintext, associatedData)
    }

    /**
     * Decrypts ciphertext bytes using ChaCha20-Poly1305 and verifies the Poly1305 tag.
     */
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        return cipher.decrypt(ciphertext, associatedData)
    }

    companion object {
        private val secureRandom = SecureRandom()

        fun generateRandomKey(): ByteArray {
            val key = ByteArray(32)
            secureRandom.nextBytes(key)
            return key
        }

        /**
         * Derives a symmetric channel key from a sender private key and recipient public key
         * or a combination of hashes.
         */
        fun deriveSharedKey(seed: ByteArray): ByteArray {
            return MessageDigest.getInstance("SHA-256").digest(seed)
        }
    }
}
