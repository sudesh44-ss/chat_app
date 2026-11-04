package com.nexa.app.core.crypto

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

/**
 * Symmetric ChaCha20-Poly1305 AEAD Cipher Engine.
 * Consumes 3x less battery on ARM mobile processors than AES-GCM without hardware acceleration,
 * returning separate ciphertext and 16-byte Poly1305 integrity tags.
 */
object ChaChaAeadCipher {

    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = ChaCha20Poly1305()
        val params = ParametersWithIV(KeyParameter(key), nonce)
        cipher.init(true, params)

        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)

        val ciphertext = out.copyOfRange(0, out.size - 16)
        val tag = out.copyOfRange(out.size - 16, out.size)
        return Pair(ciphertext, tag)
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray): ByteArray? {
        return try {
            val cipher = ChaCha20Poly1305()
            val params = ParametersWithIV(KeyParameter(key), nonce)
            cipher.init(false, params)

            val combined = ciphertext + tag
            val out = ByteArray(cipher.getOutputSize(combined.size))
            val len = cipher.processBytes(combined, 0, combined.size, out, 0)
            val finalLen = cipher.doFinal(out, len)
            val totalLen = len + finalLen
            out.copyOfRange(0, totalLen)
        } catch (e: Exception) {
            null // Safe discard on tampering or tag mismatch
        }
    }
}
