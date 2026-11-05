package com.nexa.app.core.backup.crypto

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * High-performance, streaming Zero-Knowledge AES-256-GCM Vault Encryptor.
 * Streams data through 64 KB buffers with a fixed 39-byte binary header.
 * Provides Zero-RAM footprint even on multi-gigabyte backup bundles.
 */
object VaultStreamEncryptor {

    const val MAGIC_HEADER = "NXVAULT"
    const val CURRENT_VERSION = 1
    const val SALT_SIZE = 16
    const val NONCE_SIZE = 12
    const val TAG_LENGTH_BITS = 128
    const val BUFFER_SIZE = 64 * 1024 // 64 KB streaming buffer

    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_SIZE)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun generateNonce(): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        SecureRandom().nextBytes(nonce)
        return nonce
    }

    fun deriveMasterKey(passphrase: String, salt: ByteArray): SecretKey {
        val normalized = passphrase.trim().lowercase().replace(Regex("\\s+"), " ")
        val spec = PBEKeySpec(normalized.toCharArray(), salt, 65536, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Streams raw bytes through AES-256-GCM cipher into an authenticated encrypted vault.
     * Memory overhead is strictly capped at 64 KB.
     */
    fun encryptVault(
        rawSourceFile: File,
        encryptedOutputFile: File,
        secretKey: SecretKey,
        salt: ByteArray,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) {
        val nonce = generateNonce()
        val totalBytes = rawSourceFile.length()

        FileOutputStream(encryptedOutputFile).use { fos ->
            // 1. Write Header: Magic (7B) + Version (4B) + Salt (16B) + Nonce (12B) = 39 Bytes
            val headerBuffer = ByteBuffer.allocate(7 + 4 + SALT_SIZE + NONCE_SIZE)
            headerBuffer.put(MAGIC_HEADER.toByteArray(Charsets.US_ASCII))
            headerBuffer.putInt(CURRENT_VERSION)
            headerBuffer.put(salt)
            headerBuffer.put(nonce)
            fos.write(headerBuffer.array())

            // 2. Initialize AES-256-GCM
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(TAG_LENGTH_BITS, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

            // 3. Stream through CipherOutputStream
            var totalProcessed = 0L
            CipherOutputStream(fos, cipher).use { cos ->
                FileInputStream(rawSourceFile).use { fis ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        cos.write(buffer, 0, bytesRead)
                        totalProcessed += bytesRead
                        onProgress(totalProcessed, totalBytes)
                    }
                    cos.flush()
                }
            }
        }
    }

    /**
     * Streams encrypted vault file through AES-256-GCM decryptor.
     * Throws IllegalArgumentException on wrong passphrase or tampering (GCM Tag Mismatch).
     */
    fun decryptVault(
        encryptedSourceFile: File,
        decryptedOutputFile: File,
        passphrase: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Boolean {
        val totalBytes = encryptedSourceFile.length()
        FileInputStream(encryptedSourceFile).use { fis ->
            // 1. Read & Validate Header
            val headerBytes = ByteArray(7 + 4 + SALT_SIZE + NONCE_SIZE)
            val headerRead = fis.read(headerBytes)
            if (headerRead != headerBytes.size) {
                throw IllegalArgumentException("Not a valid NEXA backup vault: Header truncated")
            }

            val buffer = ByteBuffer.wrap(headerBytes)
            val magic = ByteArray(7).also { buffer.get(it) }
            if (String(magic, Charsets.US_ASCII) != MAGIC_HEADER) {
                throw IllegalArgumentException("Not a valid NEXA backup vault: Magic mismatch")
            }

            val version = buffer.int
            val salt = ByteArray(SALT_SIZE).also { buffer.get(it) }
            val nonce = ByteArray(NONCE_SIZE).also { buffer.get(it) }

            // 2. Derive key using embedded Salt
            val secretKey = deriveMasterKey(passphrase, salt)

            // 3. Initialize AES-256-GCM Decryptor
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, nonce))

            // 4. Stream decrypt to destination
            try {
                var totalProcessed = headerBytes.size.toLong()
                CipherInputStream(fis, cipher).use { cis ->
                    FileOutputStream(decryptedOutputFile).use { fos ->
                        val chunk = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (cis.read(chunk).also { read = it } != -1) {
                            fos.write(chunk, 0, read)
                            totalProcessed += read
                            onProgress(totalProcessed, totalBytes)
                        }
                        fos.flush()
                    }
                }
                return true
            } catch (e: Exception) {
                if (decryptedOutputFile.exists()) decryptedOutputFile.delete()
                throw IllegalArgumentException("Incorrect Passphrase or Tampered Vault: GCM Auth Tag failed", e)
            }
        }
    }
}
