package com.nexa.app.core.crypto

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * High-performance per-chunk AEAD streaming cipher and checksum verifier (Scenario 6).
 * Encrypts 64 KB slices with ChaCha20-Poly1305 and a unique 12-byte deterministic nonce,
 * verifying packet authentication tags and in-flight integrity before flushing to disk.
 */
class ChunkCipherEngine {

    fun deriveNonce(chunkIndex: Int): ByteArray {
        val buffer = ByteBuffer.allocate(12)
        buffer.putLong(0L)
        buffer.putInt(chunkIndex)
        return buffer.array()
    }

    fun encryptChunk(
        key: ByteArray,
        chunkIndex: Int,
        plaintext: ByteArray,
        length: Int
    ): ByteArray {
        val nonce = deriveNonce(chunkIndex)
        val cipher = ChaCha20Poly1305()
        val params = ParametersWithIV(KeyParameter(key), nonce)
        cipher.init(true, params)

        val out = ByteArray(cipher.getOutputSize(length))
        val len = cipher.processBytes(plaintext, 0, length, out, 0)
        cipher.doFinal(out, len)
        return out // Output contains ciphertext + 16-byte Poly1305 MAC tag
    }

    fun decryptChunk(
        key: ByteArray,
        chunkIndex: Int,
        ciphertextWithTag: ByteArray
    ): ByteArray? {
        return try {
            val nonce = deriveNonce(chunkIndex)
            val cipher = ChaCha20Poly1305()
            val params = ParametersWithIV(KeyParameter(key), nonce)
            cipher.init(false, params)

            val out = ByteArray(cipher.getOutputSize(ciphertextWithTag.size))
            val len = cipher.processBytes(ciphertextWithTag, 0, ciphertextWithTag.size, out, 0)
            val finalLen = cipher.doFinal(out, len)
            out.copyOfRange(0, len + finalLen)
        } catch (e: Exception) {
            null // Mismatch or corrupted chunk
        }
    }

    fun computeChecksum(data: ByteArray, length: Int): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data, 0, length)
        return digest.digest()
    }
}
