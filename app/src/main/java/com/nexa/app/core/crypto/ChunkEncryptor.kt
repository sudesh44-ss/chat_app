package com.nexa.app.core.crypto

import com.google.crypto.tink.subtle.ChaCha20Poly1305
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * ChaCha20-Poly1305 per-chunk AEAD encryptor.
 * Protects 64 KB binary file slices streamed across WebRTC DataChannels.
 */
class ChunkEncryptor(keyBytes: ByteArray) {

    private val cipher: ChaCha20Poly1305

    init {
        val symmetricKey = if (keyBytes.size == 32) {
            keyBytes
        } else {
            MessageDigest.getInstance("SHA-256").digest(keyBytes)
        }
        cipher = ChaCha20Poly1305(symmetricKey)
    }

    /**
     * Encrypts a 64 KB chunk slice. Returns [12-byte IV + Ciphertext + 16-byte Poly1305 Tag].
     */
    fun encryptChunk(chunkData: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        return cipher.encrypt(chunkData, associatedData)
    }

    /**
     * Decrypts and authenticates an incoming encrypted chunk slice.
     */
    fun decryptChunk(encryptedData: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        return cipher.decrypt(encryptedData, associatedData)
    }

    companion object {
        private val secureRandom = SecureRandom()

        fun generateSessionKey(): ByteArray {
            val key = ByteArray(32)
            secureRandom.nextBytes(key)
            return key
        }
    }
}
