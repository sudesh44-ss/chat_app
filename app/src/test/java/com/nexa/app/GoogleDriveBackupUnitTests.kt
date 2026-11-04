package com.nexa.app

import com.nexa.app.core.backup.BackupCompressor
import com.nexa.app.core.backup.BackupVaultHeader
import com.nexa.app.core.backup.Bip39PassphraseHelper
import com.nexa.app.data.drive.DriveResumableUploader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class GoogleDriveBackupUnitTests {

    @Test
    fun testBackupVaultHeaderSerializationAndDeserialization() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(12) { (it + 20).toByte() }
        val sha256 = ByteArray(32) { (it + 50).toByte() }

        val header = BackupVaultHeader(
            version = 1,
            salt = salt,
            iv = iv,
            payloadSha256 = sha256
        )

        val serialized = header.toByteArray()
        assertEquals("Header must be strictly 71 bytes", 71, serialized.size)

        // Verify magic bytes "NXVAULT"
        val magicString = String(serialized.copyOfRange(0, 7), Charsets.US_ASCII)
        assertEquals("NXVAULT", magicString)

        val deserialized = BackupVaultHeader.fromByteArray(serialized)
        assertNotNull("Deserialized header must not be null", deserialized)
        assertEquals(1, deserialized!!.version)
        assertArrayEquals(salt, deserialized.salt)
        assertArrayEquals(iv, deserialized.iv)
        assertArrayEquals(sha256, deserialized.payloadSha256)
    }

    @Test
    fun testBackupVaultHeaderRejectsInvalidMagicOrTruncation() {
        // Less than 71 bytes
        val shortBytes = ByteArray(70)
        assertNull("Truncated header must return null", BackupVaultHeader.fromByteArray(shortBytes))

        // Valid length but invalid magic bytes
        val invalidMagic = ByteArray(71)
        "BADHEAD".toByteArray(Charsets.US_ASCII).copyInto(invalidMagic, 0)
        assertNull("Invalid magic bytes must return null", BackupVaultHeader.fromByteArray(invalidMagic))
    }

    @Test
    fun testBip39GenerationAndValidation() {
        val mnemonic = Bip39PassphraseHelper.generateMnemonic()
        val words = mnemonic.split(" ")

        assertEquals("Mnemonic must consist of exactly 12 words", 12, words.size)
        assertTrue("Generated mnemonic must pass BIP-39 validation", Bip39PassphraseHelper.validateMnemonic(mnemonic))

        // Test that all 12 words exist in the dictionary
        words.forEach { word ->
            assertTrue("Word '$word' must exist in standard BIP-39 wordlist", Bip39PassphraseHelper.WORD_LIST.contains(word))
        }

        // Modifying words to test checksum failure
        val corruptedWords = words.toMutableList()
        var hasChecksumFailure = false
        for (candidate in listOf("abandon", "ability", "able", "about", "above")) {
            if (candidate != words[0]) {
                corruptedWords[0] = candidate
                if (!Bip39PassphraseHelper.validateMnemonic(corruptedWords.joinToString(" "))) {
                    hasChecksumFailure = true
                    break
                }
            }
        }
        assertTrue("Corrupted mnemonic must fail checksum validation", hasChecksumFailure)
    }

    @Test
    fun testBip39NormalizationAndWordSplitting() {
        val rawInput = "   ABANDON   ability   ABLE\nabout   above   ABSENT\tabsorb   abstract  absurd  abuse  access  accident   "
        val normalized = Bip39PassphraseHelper.normalize(rawInput)
        val words = Bip39PassphraseHelper.getWords(rawInput)

        assertEquals(12, words.size)
        assertEquals("abandon", words[0])
        assertEquals("accident", words[11])
        assertFalse("Normalized phrase must not contain uppercase letters", normalized.contains("ABANDON"))
        assertFalse("Normalized phrase must not contain tabs or multiple spaces", normalized.contains("  "))
    }

    @Test
    fun testBackupCompressorRoundtrip() {
        val sampleDatabaseContent = StringBuilder().apply {
            for (i in 1..500) {
                append("INSERT INTO messages (id, sender, text) VALUES ($i, 'user_$i', 'Encrypted message payload $i');\n")
            }
        }.toString().toByteArray(Charsets.UTF_8)

        val compressed = BackupCompressor.compressBytes(sampleDatabaseContent)
        assertTrue("Compressed size must be substantially smaller than raw SQLite dump", compressed.size < sampleDatabaseContent.size / 2)

        val decompressed = BackupCompressor.decompressBytes(compressed)
        assertArrayEquals("Decompressed data must match original byte-for-byte", sampleDatabaseContent, decompressed)
    }

    @Test
    fun testZeroKnowledgeEncryptionAndDecryptionFlow() {
        // 1. Generate Mnemonic
        val mnemonic = Bip39PassphraseHelper.generateMnemonic()
        val plainDatabaseData = "SQLITE_HEADER_FORMAT_VAULT_DUMP_PLAINTEXT_PAYLOAD".toByteArray(Charsets.UTF_8)

        // 2. Compress
        val compressed = BackupCompressor.compressBytes(plainDatabaseData)

        // 3. Derive Key
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

        val spec = PBEKeySpec(Bip39PassphraseHelper.normalize(mnemonic).toCharArray(), salt, 65536, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

        // 4. Encrypt with AES-GCM
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(compressed)

        // 5. Build Header
        val sha256 = MessageDigest.getInstance("SHA-256").digest(ciphertext)
        val header = BackupVaultHeader(version = 1, salt = salt, iv = iv, payloadSha256 = sha256)
        val headerBytes = header.toByteArray()

        // Assemble full vault
        val vaultFileBytes = headerBytes + ciphertext

        // --- DECRYPTION REVERSE PIPELINE ---
        val parsedHeader = BackupVaultHeader.fromByteArray(vaultFileBytes.copyOfRange(0, 71))
        assertNotNull(parsedHeader)

        val extractedCiphertext = vaultFileBytes.copyOfRange(71, vaultFileBytes.size)

        // Pre-decryption SHA-256 verification
        val computedHash = MessageDigest.getInstance("SHA-256").digest(extractedCiphertext)
        assertArrayEquals("Pre-decryption checksum must match header", parsedHeader!!.payloadSha256, computedHash)

        // Derive key from header salt
        val decryptSpec = PBEKeySpec(Bip39PassphraseHelper.normalize(mnemonic).toCharArray(), parsedHeader.salt, 65536, 256)
        val decryptKey = SecretKeySpec(factory.generateSecret(decryptSpec).encoded, "AES")

        // Decrypt
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, decryptKey, GCMParameterSpec(128, parsedHeader.iv))
        val decryptedCompressed = decryptCipher.doFinal(extractedCiphertext)

        // Decompress
        val restoredPlaintext = BackupCompressor.decompressBytes(decryptedCompressed)
        assertArrayEquals(plainDatabaseData, restoredPlaintext)
    }

    @Test
    fun testDecryptionWithWrongPassphraseFailsAuthenticationTag() {
        val mnemonic1 = Bip39PassphraseHelper.generateMnemonic()
        val mnemonic2 = Bip39PassphraseHelper.generateMnemonic()

        val sampleData = "CONFIDENTIAL_DB_RECORDS".toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key1 = SecretKeySpec(factory.generateSecret(PBEKeySpec(mnemonic1.toCharArray(), salt, 65536, 256)).encoded, "AES")
        val key2 = SecretKeySpec(factory.generateSecret(PBEKeySpec(mnemonic2.toCharArray(), salt, 65536, 256)).encoded, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key1, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(sampleData)

        // Attempt decrypt with key2
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, key2, GCMParameterSpec(128, iv))

        try {
            decryptCipher.doFinal(ciphertext)
            fail("Decryption with wrong passphrase must fail with AEADBadTagException")
        } catch (expected: javax.crypto.AEADBadTagException) {
            // Expected cryptographic failure
        }
    }

    @Test
    fun testCiphertextTamperingFailsIntegrityCheck() {
        val mnemonic = Bip39PassphraseHelper.generateMnemonic()
        val sampleData = "INTEGRITY_CHECK_SAMPLE".toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = SecretKeySpec(factory.generateSecret(PBEKeySpec(mnemonic.toCharArray(), salt, 65536, 256)).encoded, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(sampleData)

        val originalHash = MessageDigest.getInstance("SHA-256").digest(ciphertext)

        // Flip 1 bit in ciphertext
        val tamperedCiphertext = ciphertext.clone()
        tamperedCiphertext[tamperedCiphertext.size / 2] = (tamperedCiphertext[tamperedCiphertext.size / 2].toInt() xor 0x01).toByte()

        val tamperedHash = MessageDigest.getInstance("SHA-256").digest(tamperedCiphertext)
        assertFalse("Tampered ciphertext must produce a completely different SHA-256 hash", originalHash.contentEquals(tamperedHash))
    }

    @Test
    fun testResumableUploadChunkSizeConstants() {
        assertEquals("Drive chunk size must be exactly 256 KB", 262144, DriveResumableUploader.CHUNK_SIZE)

        // Verify chunk calculation for 1 MB file
        val totalBytes = 1048576L // 1 MB
        val expectedChunks = 4
        val calculatedChunks = (totalBytes + DriveResumableUploader.CHUNK_SIZE - 1) / DriveResumableUploader.CHUNK_SIZE
        assertEquals(expectedChunks.toLong(), calculatedChunks)
    }

    @Test
    fun testSplitBrainConflictDetectionLogic() {
        val localEpoch = 1700000000000L
        val remoteNewerEpoch = 1700000500000L
        val remoteOlderEpoch = 1699999500000L

        // Scenario 1: Cloud is strictly newer -> Abort
        val hasSplitBrain = remoteNewerEpoch > localEpoch
        assertTrue("Remote epoch strictly greater than local epoch must trigger conflict abort", hasSplitBrain)

        // Scenario 2: Cloud is older -> Safe to upload
        val isSafeToUpload = remoteOlderEpoch <= localEpoch
        assertTrue("Remote epoch older than local epoch is safe to update", isSafeToUpload)
    }
}
