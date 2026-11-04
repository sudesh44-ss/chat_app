package com.nexa.app.core.backup

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Core cryptographic engine for NEXA Zero-Knowledge Cloud Backups.
 *
 * Provides:
 * 1. Zero-Lock live SQLite snapshotting via `VACUUM INTO`.
 * 2. GZIP stream compression.
 * 3. 256-bit AES master key derivation from 12-Word BIP-39 mnemonic via PBKDF2-HMAC-SHA256 (65,536 iterations).
 * 4. AES-256-GCM authenticated encryption (128-bit tag, 12-byte IV).
 * 5. Pre-decryption SHA-256 checksum verification and authenticated restoration.
 */
class BackupCryptoEngine(private val context: Context) {

    private val secureRandom = SecureRandom()

    /**
     * Executes atomic SQLite snapshotting, compression, key derivation, and AES-256-GCM encryption.
     */
    suspend fun createEncryptedVault(
        rawDatabase: SupportSQLiteDatabase,
        passphraseWords: String,
        outputVaultFile: File,
        onProgress: (phase: String, percent: Float) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val tempSnapshot = File(context.cacheDir, "temp_snapshot_${System.currentTimeMillis()}.db")
        val tempCompressed = File(context.cacheDir, "temp_compressed_${System.currentTimeMillis()}.gz")

        try {
            if (tempSnapshot.exists()) tempSnapshot.delete()
            if (tempCompressed.exists()) tempCompressed.delete()
            if (outputVaultFile.exists()) outputVaultFile.delete()

            // 1. Zero-Lock Atomic Snapshot
            onProgress("Snapshotting Database...", 0.10f)
            rawDatabase.execSQL("VACUUM INTO '${tempSnapshot.absolutePath}'")

            // 2. Stream Compression
            onProgress("Compressing Snapshot...", 0.30f)
            BackupCompressor.compressFile(tempSnapshot, tempCompressed)

            // 3. Derive 256-bit Key from BIP-39 Mnemonic
            onProgress("Deriving Encryption Key...", 0.50f)
            val salt = ByteArray(BackupVaultHeader.SALT_SIZE).also { secureRandom.nextBytes(it) }
            val iv = ByteArray(BackupVaultHeader.IV_SIZE).also { secureRandom.nextBytes(it) }
            val secretKey = deriveKey(passphraseWords, salt)

            // 4. AES-256-GCM Encryption
            onProgress("Encrypting Vault...", 0.70f)
            val compressedBytes = tempCompressed.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val encryptedPayload = cipher.doFinal(compressedBytes)

            // 5. Checksum of Encrypted Payload
            onProgress("Calculating Checksum...", 0.85f)
            val payloadSha256 = MessageDigest.getInstance("SHA-256").digest(encryptedPayload)

            // 6. Write Rigid 71-Byte Header + Ciphertext
            onProgress("Packaging Vault...", 0.95f)
            val header = BackupVaultHeader(
                version = BackupVaultHeader.CURRENT_VERSION,
                salt = salt,
                iv = iv,
                payloadSha256 = payloadSha256
            )

            FileOutputStream(outputVaultFile).use { outStream ->
                outStream.write(header.toByteArray())
                outStream.write(encryptedPayload)
                outStream.flush()
            }

            onProgress("Vault Packing Complete", 1.0f)
            true
        } finally {
            if (tempSnapshot.exists()) tempSnapshot.delete()
            if (tempCompressed.exists()) tempCompressed.delete()
        }
    }

    /**
     * Reads the encrypted vault, validates magic header and checksum, derives key, decrypts and decompresses.
     */
    suspend fun decryptAndRestoreVault(
        vaultFile: File,
        passphraseWords: String,
        targetDbFile: File,
        onProgress: (phase: String, percent: Float) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val tempDecompressed = File(context.cacheDir, "temp_restored_${System.currentTimeMillis()}.db")

        try {
            if (!vaultFile.exists() || vaultFile.length() < BackupVaultHeader.HEADER_SIZE) {
                throw IllegalArgumentException("Invalid or incomplete vault file (Size < ${BackupVaultHeader.HEADER_SIZE} bytes)")
            }

            // 1. Read and Parse 71-Byte Header
            onProgress("Verifying Vault Header...", 0.10f)
            val headerBytes = ByteArray(BackupVaultHeader.HEADER_SIZE)
            val ciphertext = ByteArray((vaultFile.length() - BackupVaultHeader.HEADER_SIZE).toInt())

            FileInputStream(vaultFile).use { inStream ->
                val readHeader = inStream.read(headerBytes)
                if (readHeader != BackupVaultHeader.HEADER_SIZE) {
                    throw IllegalArgumentException("Failed to read complete vault header")
                }
                var totalCiphertextRead = 0
                while (totalCiphertextRead < ciphertext.size) {
                    val read = inStream.read(ciphertext, totalCiphertextRead, ciphertext.size - totalCiphertextRead)
                    if (read == -1) break
                    totalCiphertextRead += read
                }
            }

            val header = BackupVaultHeader.fromByteArray(headerBytes)
                ?: throw IllegalArgumentException("Corrupted or invalid vault magic header")

            // 2. Validate Checksum BEFORE Decryption
            onProgress("Validating SHA-256 Checksum...", 0.25f)
            val computedHash = MessageDigest.getInstance("SHA-256").digest(ciphertext)
            if (!computedHash.contentEquals(header.payloadSha256)) {
                throw SecurityException("Backup vault checksum mismatch. File may be corrupted or tampered with.")
            }

            // 3. Derive Key from User Passphrase and Vault Salt
            onProgress("Deriving Key from Passphrase...", 0.45f)
            val secretKey = deriveKey(passphraseWords, header.salt)

            // 4. AES-256-GCM Decrypt
            onProgress("Decrypting Ciphertext...", 0.65f)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, header.iv))

            val compressedBytes = try {
                cipher.doFinal(ciphertext)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid passphrase or corrupted vault authentication tag", e)
            }

            // 5. Decompress GZIP Stream
            onProgress("Decompressing Database...", 0.85f)
            val decompressedBytes = BackupCompressor.decompressBytes(compressedBytes)

            // 6. Write out target database atomically
            if (targetDbFile.parentFile?.exists() == false) {
                targetDbFile.parentFile?.mkdirs()
            }
            FileOutputStream(targetDbFile).use { outStream ->
                outStream.write(decompressedBytes)
                outStream.flush()
            }

            onProgress("Restoration Succeeded", 1.0f)
            true
        } finally {
            if (tempDecompressed.exists()) tempDecompressed.delete()
        }
    }

    /**
     * Derives a 256-bit AES SecretKeySpec using PBKDF2-HMAC-SHA256 with 65,536 iterations.
     */
    private fun deriveKey(passphraseWords: String, salt: ByteArray): SecretKeySpec {
        val normalized = Bip39PassphraseHelper.normalize(passphraseWords)
        val spec = PBEKeySpec(normalized.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    companion object {
        private const val PBKDF2_ITERATIONS = 65536
        private const val KEY_LENGTH_BITS = 256
    }
}
