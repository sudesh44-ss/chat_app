package com.nexa.app.core.media

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedMediaResult(
    val encryptedFile: File,
    val mediaKey: ByteArray,
    val iv: ByteArray,
    val cipherHash: String,
    val sizeBytes: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedMediaResult
        if (encryptedFile != other.encryptedFile) return false
        if (!mediaKey.contentEquals(other.mediaKey)) return false
        if (!iv.contentEquals(other.iv)) return false
        if (cipherHash != other.cipherHash) return false
        if (sizeBytes != other.sizeBytes) return false
        return true
    }

    override fun hashCode(): Int {
        var result = encryptedFile.hashCode()
        result = 31 * result + mediaKey.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + cipherHash.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        return result
    }
}

data class MediaAttachmentEnvelope(
    val mediaId: String,
    val objectKey: String,
    val cipherHash: String,
    val mediaKeyBase64: String,
    val ivBase64: String,
    val sizeBytes: Long
)

/**
 * Production-Grade Blind Media Vault Engine for NEXA.
 * Enforces Zero-Cloud Plaintext:
 * 1. Encrypts media client-side with AES-256-GCM before uploading to cloud object storage.
 * 2. R2/S3 storage receives ONLY an unreadable encrypted blob (.enc).
 * 3. The 256-bit media key is transported strictly inside Double Ratchet E2EE message envelopes.
 * 4. Streaming cipher operations: Zero RAM OOM even on 100 MB videos.
 */
object BlindMediaVaultEngine {

    private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val KEY_SIZE_BYTES = 32 // 256-bit AES
    private const val IV_SIZE_BYTES = 12  // 96-bit standard GCM IV
    private const val BUFFER_SIZE = 64 * 1024 // 64 KB streaming buffer

    const val MAX_CLOUD_FALLBACK_BYTES = 100 * 1024 * 1024L // 100 MB max for cloud fallback

    /**
     * Generates a single-use cryptographically secure 256-bit AES media key.
     */
    fun generateMediaKey(): ByteArray {
        val key = ByteArray(KEY_SIZE_BYTES)
        SecureRandom().nextBytes(key)
        return key
    }

    /**
     * Generates a 96-bit random IV for AES-GCM.
     */
    fun generateIv(): ByteArray {
        val iv = ByteArray(IV_SIZE_BYTES)
        SecureRandom().nextBytes(iv)
        return iv
    }

    /**
     * Encrypts a source media file into an isolated .enc ciphertext file using streaming AES-GCM.
     * Computes the SHA-256 hash of the ciphertext for integrity verification.
     */
    fun encryptMediaStream(
        sourceFile: File,
        destinationEncryptedFile: File,
        mediaKey: ByteArray = generateMediaKey(),
        iv: ByteArray = generateIv()
    ): EncryptedMediaResult {
        require(sourceFile.exists()) { "Source media file does not exist: ${sourceFile.absolutePath}" }

        val secretKeySpec = SecretKeySpec(mediaKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, gcmSpec)

        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytesWritten = 0L

        FileInputStream(sourceFile).use { fis ->
            FileOutputStream(destinationEncryptedFile).use { fos ->
                CipherOutputStream(fos, cipher).use { cos ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        cos.write(buffer, 0, bytesRead)
                        totalBytesWritten += bytesRead
                    }
                }
            }
        }

        // Compute ciphertext SHA-256 hash
        FileInputStream(destinationEncryptedFile).use { fis ->
            val buf = ByteArray(BUFFER_SIZE)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }

        val cipherHash = digest.digest().joinToString("") { "%02x".format(it) }

        return EncryptedMediaResult(
            encryptedFile = destinationEncryptedFile,
            mediaKey = mediaKey,
            iv = iv,
            cipherHash = cipherHash,
            sizeBytes = destinationEncryptedFile.length()
        )
    }

    /**
     * Decrypts a downloaded .enc ciphertext file into a safe local destination file.
     * Verifies cryptographic authentication tag; fails if tampered.
     */
    fun decryptMediaStream(
        encryptedSourceFile: File,
        destinationPlainFile: File,
        mediaKey: ByteArray,
        iv: ByteArray,
        expectedCipherHash: String? = null
    ): Boolean {
        require(encryptedSourceFile.exists()) { "Encrypted file does not exist" }

        // 1. Verify ciphertext hash if provided
        if (!expectedCipherHash.isNullOrBlank()) {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(encryptedSourceFile).use { fis ->
                val buf = ByteArray(BUFFER_SIZE)
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    digest.update(buf, 0, n)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(expectedCipherHash, ignoreCase = true)) {
                return false // Hash mismatch / corrupted download
            }
        }

        // 2. Streaming AES-GCM decryption
        return try {
            val secretKeySpec = SecretKeySpec(mediaKey, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, gcmSpec)

            FileInputStream(encryptedSourceFile).use { fis ->
                CipherInputStream(fis, cipher).use { cis ->
                    FileOutputStream(destinationPlainFile).use { fos ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (cis.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            // Decryption failure (tag mismatch, key mismatch, corrupted bytes)
            if (destinationPlainFile.exists()) destinationPlainFile.delete()
            false
        }
    }
}
