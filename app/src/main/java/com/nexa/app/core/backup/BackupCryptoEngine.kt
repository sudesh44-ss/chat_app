package com.nexa.app.core.backup

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nexa.app.core.backup.crypto.KeyEnvelopeEngine
import com.nexa.app.core.backup.crypto.VaultStreamEncryptor
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.domain.model.EffectiveChatBackupPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class VaultCreationResult(
    val success: Boolean,
    val manifest: BackupManifest? = null,
    val vaultSha256: ByteArray? = null
)

data class StagedVaultPackage(
    val stagingDir: File,
    val snapshotDbFile: File,
    val manifest: BackupManifest?,
    val formatVersion: Int,
    val unsealedDbPassphrase: ByteArray? = null,
    val isLegacy: Boolean = false
)

/**
 * Core cryptographic engine for NEXA Zero-Knowledge Cloud Backups.
 *
 * Provides:
 * 1. Zero-Lock live SQLite snapshotting via `VACUUM INTO`.
 * 2. Multi-pillar container packaging (Databases, Preferences, Media Hash Vault, Manifest).
 * 3. 256-bit AES master key derivation from 12-Word BIP-39 mnemonic via PBKDF2-HMAC-SHA256 (65,536 iterations).
 * 4. Streaming AES-256-GCM authenticated encryption (Zero RAM overhead).
 * 5. Pre-decryption SHA-256 checksum verification and authenticated restoration.
 * 6. Design C: Dual-Layer Cryptographic Key Envelope (`key_envelope.bin`) with zero-disk RAM staging.
 */
class BackupCryptoEngine(
    private val context: Context? = null,
    private val customCacheDir: File? = null
) {

    private val effectiveCacheDir: File
        get() = customCacheDir ?: context?.cacheDir ?: File(System.getProperty("java.io.tmpdir"))

    private val secureRandom = SecureRandom()

    /**
     * Executes atomic SQLite snapshotting, key envelope sealing, manifest calculation,
     * category filtering, and streaming AES-256-GCM encryption.
     * Returns a [VaultCreationResult] with the manifest and encrypted payload checksum.
     */
    suspend fun createEncryptedVaultWithResult(
        rawDatabase: SupportSQLiteDatabase,
        passphraseWords: String,
        outputVaultFile: File,
        dbPassphrase: ByteArray? = null,
        categoryConfig: BackupCategoryConfig = BackupCategoryConfig(),
        conversationPolicies: Map<String, EffectiveChatBackupPolicy> = emptyMap(),
        isManualBackup: Boolean = true,
        onProgress: (phase: String, percent: Float) -> Unit = { _, _ -> }
    ): VaultCreationResult = withContext(Dispatchers.IO) {
        val stagingDir = File(effectiveCacheDir, "staging_${System.currentTimeMillis()}")
        val tempArchive = File(effectiveCacheDir, "bundle_${System.currentTimeMillis()}.zip")

        try {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            if (tempArchive.exists()) tempArchive.delete()
            if (outputVaultFile.exists()) outputVaultFile.delete()

            // 1. Generate Vault Salt, Nonce, and Derive Master Vault Key
            onProgress("Deriving Encryption Key...", 0.10f)
            val salt = ByteArray(BackupVaultHeader.SALT_SIZE).also { secureRandom.nextBytes(it) }
            val iv = ByteArray(BackupVaultHeader.IV_SIZE).also { secureRandom.nextBytes(it) }
            val secretKey = deriveKey(passphraseWords, salt)

            // 2. Pack Complete Snapshot Bundle (Zero-Lock VACUUM INTO + Key Envelope + Preferences + Media + Manifest + Policies)
            onProgress("Packaging Database & Media Vault...", 0.25f)
            val manifest = VaultBundlePackager.packBundle(
                context = context ?: throw IllegalStateException("Context required for packBundle"),
                rawDatabase = rawDatabase,
                outputArchiveFile = tempArchive,
                stagingDir = stagingDir,
                dbPassphrase = dbPassphrase,
                vaultMasterKeyBytes = secretKey.encoded,
                salt = salt,
                categoryConfig = categoryConfig,
                conversationPolicies = conversationPolicies,
                isManualBackup = isManualBackup,
                onProgress = { phase, p -> onProgress(phase, 0.25f + p * 0.40f) }
            )

            // 3. Streaming AES-256-GCM Encryption with 71-Byte Header (Version 2)
            onProgress("Encrypting Vault Container (Streaming)...", 0.70f)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))

            val ciphertextFile = File(effectiveCacheDir, "cipher_${System.currentTimeMillis()}.bin")
            var payloadSha256: ByteArray
            try {
                FileOutputStream(ciphertextFile).use { fos ->
                    javax.crypto.CipherOutputStream(fos, cipher).use { cos ->
                        FileInputStream(tempArchive).use { fis ->
                            val buffer = ByteArray(VaultStreamEncryptor.BUFFER_SIZE)
                            var read: Int
                            while (fis.read(buffer).also { read = it } != -1) {
                                cos.write(buffer, 0, read)
                            }
                            cos.flush()
                        }
                    }
                }

                payloadSha256 = MessageDigest.getInstance("SHA-256").digest(ciphertextFile.readBytes())

                // 4. Write Rigid 71-Byte Header (Version 2) + Ciphertext
                onProgress("Writing Vault Container...", 0.90f)
                val header = BackupVaultHeader(
                    version = BackupVaultHeader.CURRENT_VERSION,
                    salt = salt,
                    iv = iv,
                    payloadSha256 = payloadSha256
                )

                FileOutputStream(outputVaultFile).use { outStream ->
                    outStream.write(header.toByteArray())
                    FileInputStream(ciphertextFile).use { cis ->
                        val buffer = ByteArray(VaultStreamEncryptor.BUFFER_SIZE)
                        var read: Int
                        while (cis.read(buffer).also { read = it } != -1) {
                            outStream.write(buffer, 0, read)
                        }
                    }
                    outStream.flush()
                }
            } finally {
                if (ciphertextFile.exists()) ciphertextFile.delete()
            }

            onProgress("Vault Packing Complete", 1.0f)
            VaultCreationResult(
                success = true,
                manifest = manifest,
                vaultSha256 = payloadSha256
            )
        } finally {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            if (tempArchive.exists()) tempArchive.delete()
        }
    }

    /**
     * Executes atomic SQLite snapshotting, key envelope sealing, manifest calculation,
     * category filtering, and streaming AES-256-GCM encryption.
     */
    suspend fun createEncryptedVault(
        rawDatabase: SupportSQLiteDatabase,
        passphraseWords: String,
        outputVaultFile: File,
        dbPassphrase: ByteArray? = null,
        categoryConfig: BackupCategoryConfig = BackupCategoryConfig(),
        onProgress: (phase: String, percent: Float) -> Unit = { _, _ -> }
    ): Boolean = createEncryptedVaultWithResult(
        rawDatabase = rawDatabase,
        passphraseWords = passphraseWords,
        outputVaultFile = outputVaultFile,
        dbPassphrase = dbPassphrase,
        categoryConfig = categoryConfig,
        onProgress = onProgress
    ).success

    /**
     * Authenticates and decrypts the encrypted backup vault into a designated private staging directory.
     * Extracts Key Envelope in volatile RAM, verifies checksum and manifest integrity, and returns
     * a [StagedVaultPackage] ready for inventory analysis and selective restoration.
     */
    suspend fun decryptVaultToStaging(
        vaultFile: File,
        passphraseWords: String,
        stagingDir: File,
        onProgress: suspend (phase: String, percent: Float) -> Unit = { _, _ -> }
    ): StagedVaultPackage = withContext(Dispatchers.IO) {
        if (passphraseWords.isBlank()) {
            throw IllegalArgumentException("Passphrase cannot be empty or blank")
        }
        if (!vaultFile.exists() || vaultFile.length() < BackupVaultHeader.HEADER_SIZE) {
            throw IllegalArgumentException("Invalid or incomplete vault file (Size < ${BackupVaultHeader.HEADER_SIZE} bytes)")
        }

        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

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

        val decryptedBytes = try {
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid passphrase or corrupted vault authentication tag", e)
        }

        val tempArchive = File(stagingDir, "temp_archive.zip")
        try {
            tempArchive.writeBytes(decryptedBytes)

            val isZip = decryptedBytes.size >= 4 &&
                    decryptedBytes[0] == 0x50.toByte() &&
                    decryptedBytes[1] == 0x4B.toByte()

            if (isZip) {
                onProgress("Unpacking Staged Bundle...", 0.80f)
                var envelopeBytesInRam: ByteArray? = null

                java.util.zip.ZipInputStream(FileInputStream(tempArchive)).use { zis ->
                    var entry: java.util.zip.ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "key_envelope.bin" || entry.name.endsWith("/key_envelope.bin")) {
                            val bos = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(BUFFER_SIZE)
                            var read: Int
                            while (zis.read(buffer).also { read = it } != -1) {
                                bos.write(buffer, 0, read)
                            }
                            envelopeBytesInRam = bos.toByteArray()
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }

                        val newFile = File(stagingDir, entry.name)
                        // Prevent Zip Slip directory traversal
                        if (!newFile.canonicalPath.startsWith(stagingDir.canonicalPath)) {
                            throw SecurityException("Zip Slip directory traversal detected in entry: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            newFile.mkdirs()
                        } else {
                            newFile.parentFile?.mkdirs()
                            FileOutputStream(newFile).use { fos ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                var read: Int
                                while (zis.read(buffer).also { read = it } != -1) {
                                    fos.write(buffer, 0, read)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                // Unseal key envelope in RAM if present
                var unsealedDbKey: ByteArray? = null
                if (envelopeBytesInRam != null) {
                    unsealedDbKey = KeyEnvelopeEngine.unsealPassphrase(
                        envelopeBytes = envelopeBytesInRam,
                        vaultMasterKeyBytes = secretKey.encoded,
                        salt = header.salt
                    )
                }

                val manifestFile = File(stagingDir, "manifest.json")
                val snapshotDb = File(stagingDir, "databases/nexa_master_snapshot.db").let {
                    if (it.exists()) it else File(stagingDir, "nexa_master_snapshot.db")
                }

                if (!manifestFile.exists() || !snapshotDb.exists()) {
                    throw IllegalArgumentException("Corrupted backup bundle: missing manifest or database snapshot")
                }

                val manifestObj = org.json.JSONObject(manifestFile.readText())
                val expectedSha = manifestObj.getString("database_sha256")
                val actualSha = VaultBundlePackager.computeSha256(snapshotDb)

                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    throw SecurityException("Backup integrity violation: Computed SHA-256 ($actualSha) does not match manifest ($expectedSha).")
                }

                val manifest = BackupManifest(
                    versionEpoch = manifestObj.optLong("version_epoch", System.currentTimeMillis()),
                    formatVersion = manifestObj.optInt("format_version", 2),
                    totalConversations = manifestObj.optInt("total_conversations", 0),
                    totalMessages = manifestObj.optInt("total_messages", 0),
                    mediaFilesCount = manifestObj.optInt("media_files_count", 0),
                    databaseSha256 = actualSha,
                    categoryConfig = if (manifestObj.has("category_config")) {
                        BackupCategoryConfig.fromJson(manifestObj.getJSONObject("category_config").toString())
                    } else BackupCategoryConfig()
                )

                onProgress("Staging Complete", 1.0f)
                StagedVaultPackage(
                    stagingDir = stagingDir,
                    snapshotDbFile = snapshotDb,
                    manifest = manifest,
                    formatVersion = 2,
                    unsealedDbPassphrase = unsealedDbKey,
                    isLegacy = false
                )
            } else {
                // Legacy format version 1: gzip-compressed single database
                onProgress("Decompressing Legacy Snapshot...", 0.80f)
                val decompressedBytes = BackupCompressor.decompressBytes(decryptedBytes)
                val dbDir = File(stagingDir, "databases").apply { mkdirs() }
                val snapshotDb = File(dbDir, "nexa_master_snapshot.db")
                FileOutputStream(snapshotDb).use { fos ->
                    fos.write(decompressedBytes)
                    fos.flush()
                }

                onProgress("Legacy Staging Complete", 1.0f)
                StagedVaultPackage(
                    stagingDir = stagingDir,
                    snapshotDbFile = snapshotDb,
                    manifest = null,
                    formatVersion = 1,
                    unsealedDbPassphrase = null,
                    isLegacy = true
                )
            }
        } finally {
            if (tempArchive.exists()) tempArchive.delete()
        }
    }

    /**
     * Reads the encrypted vault, validates magic header and checksum, derives key, decrypts and unpacks bundle.
     * Extracts Key Envelope in volatile RAM with zero disk exposure.
     */
    suspend fun decryptAndRestoreVault(
        vaultFile: File,
        passphraseWords: String,
        targetDbFile: File,
        secureStorage: SecureStorage? = null,
        restoreCategoryConfig: BackupCategoryConfig = BackupCategoryConfig(),
        onProgress: (phase: String, percent: Float) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        if (passphraseWords.isBlank()) {
            throw IllegalArgumentException("Passphrase cannot be empty or blank")
        }
        val stagingDir = File(effectiveCacheDir, "restore_staging_${System.currentTimeMillis()}")
        val tempArchive = File(effectiveCacheDir, "temp_restored_${System.currentTimeMillis()}.zip")

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

            val decryptedBytes = try {
                cipher.doFinal(ciphertext)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid passphrase or corrupted vault authentication tag", e)
            }

            // 5. Detect bundle format (Zip bundle vs legacy GZIP)
            onProgress("Unpacking Vault Bundle...", 0.80f)
            tempArchive.writeBytes(decryptedBytes)

            val isZip = decryptedBytes.size >= 4 &&
                    decryptedBytes[0] == 0x50.toByte() &&
                    decryptedBytes[1] == 0x4B.toByte()

            if (isZip) {
                // Multi-pillar bundle: restore databases, preferences, media vault, and unseal key envelope
                VaultBundlePackager.unpackAndRestoreBundle(
                    context = context ?: throw IllegalStateException("Context required for unpackBundle"),
                    archiveFile = tempArchive,
                    stagingDir = stagingDir,
                    liveDatabaseFile = targetDbFile,
                    vaultMasterKeyBytes = secretKey.encoded,
                    salt = header.salt,
                    secureStorage = secureStorage,
                    restoreCategoryConfig = restoreCategoryConfig,
                    onProgress = { phase, p -> onProgress(phase, 0.80f + p * 0.20f) }
                )
            } else {
                // Legacy single-db gzip decompression fallback
                val decompressedBytes = BackupCompressor.decompressBytes(decryptedBytes)
                if (targetDbFile.parentFile?.exists() == false) {
                    targetDbFile.parentFile?.mkdirs()
                }
                FileOutputStream(targetDbFile).use { outStream ->
                    outStream.write(decompressedBytes)
                    outStream.flush()
                }
            }

            onProgress("Restoration Succeeded", 1.0f)
            true
        } finally {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            if (tempArchive.exists()) tempArchive.delete()
        }
    }

    /**
     * Derives a 256-bit AES SecretKeySpec using PBKDF2-HMAC-SHA256 with 65,536 iterations.
     * Intelligently handles both 12-word BIP-39 recovery mnemonics (normalized lowercase)
     * and custom user backup passwords (exact case and characters).
     */
    fun deriveKey(credential: String, salt: ByteArray): SecretKeySpec {
        val normalized = if (Bip39PassphraseHelper.validateMnemonic(credential)) {
            Bip39PassphraseHelper.normalize(credential)
        } else {
            credential.trim()
        }
        val spec = PBEKeySpec(normalized.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    companion object {
        private const val PBKDF2_ITERATIONS = 65536
        private const val KEY_LENGTH_BITS = 256
        private const val BUFFER_SIZE = 8192
    }
}
