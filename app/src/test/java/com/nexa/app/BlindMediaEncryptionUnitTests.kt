package com.nexa.app

import com.nexa.app.core.media.BlindMediaVaultEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Verification Suite for NEXA Blind Media Vault:
 * 1. Client-Side AES-256-GCM Streaming Encryption
 * 2. Exact-Byte Decryption Restoration
 * 3. GCM Poly1305 / Auth-Tag Tampering Rejection
 * 4. Zero-Plaintext Cloud Leakage Verification
 */
class BlindMediaEncryptionUnitTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun streamingEncryption_producesOpaqueCiphertext_andRestoresExactPlaintext() {
        // 1. Prepare raw sample media file
        val rawPlaintext = "NEXA Ultra-HD Video Sample Content Bytes 1234567890".repeat(500)
        val sourceFile = tempFolder.newFile("sample_video.mp4").apply {
            writeText(rawPlaintext, StandardCharsets.UTF_8)
        }

        val encryptedFile = File(tempFolder.root, "sample_video.enc")
        val restoredFile = File(tempFolder.root, "restored_video.mp4")

        // 2. Client-Side Encryption
        val encResult = BlindMediaVaultEngine.encryptMediaStream(
            sourceFile = sourceFile,
            destinationEncryptedFile = encryptedFile
        )

        assertTrue(encryptedFile.exists())
        assertTrue("Ciphertext file must be non-empty", encResult.sizeBytes > 0)
        assertFalse("Ciphertext must NEVER contain raw plaintext string", encryptedFile.readText(StandardCharsets.ISO_8859_1).contains("NEXA Ultra-HD"))
        assertEquals(32, encResult.mediaKey.size)
        assertEquals(12, encResult.iv.size)
        assertTrue(encResult.cipherHash.isNotBlank())

        // 3. Client-Side Decryption with E2EE transported key
        val decryptSuccess = BlindMediaVaultEngine.decryptMediaStream(
            encryptedSourceFile = encryptedFile,
            destinationPlainFile = restoredFile,
            mediaKey = encResult.mediaKey,
            iv = encResult.iv,
            expectedCipherHash = encResult.cipherHash
        )

        assertTrue("Decryption must succeed with matching key & tag", decryptSuccess)
        assertTrue(restoredFile.exists())
        assertEquals(rawPlaintext, restoredFile.readText(StandardCharsets.UTF_8))
        assertArrayEquals(sourceFile.readBytes(), restoredFile.readBytes())
    }

    @Test
    fun tamperingDetection_failsDecryption_whenCiphertextIsModified() {
        val rawContent = "Sensitive Photo Content"
        val sourceFile = tempFolder.newFile("photo.jpg").apply {
            writeText(rawContent, StandardCharsets.UTF_8)
        }
        val encryptedFile = File(tempFolder.root, "photo.enc")
        val restoredFile = File(tempFolder.root, "restored_photo.jpg")

        val encResult = BlindMediaVaultEngine.encryptMediaStream(sourceFile, encryptedFile)

        // Attacker flips one byte in the encrypted blob on cloud storage
        val bytes = encryptedFile.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0xFF).toByte()
        encryptedFile.writeBytes(bytes)

        val decryptSuccess = BlindMediaVaultEngine.decryptMediaStream(
            encryptedSourceFile = encryptedFile,
            destinationPlainFile = restoredFile,
            mediaKey = encResult.mediaKey,
            iv = encResult.iv
        )

        assertFalse("Tampered ciphertext must be rejected by GCM tag verification", decryptSuccess)
        assertFalse("Corrupted destination file must be cleaned up", restoredFile.exists())
    }

    @Test
    fun wrongKey_failsDecryption_preventingUnauthorizedAccess() {
        val sourceFile = tempFolder.newFile("doc.pdf").apply {
            writeText("Top Secret Document", StandardCharsets.UTF_8)
        }
        val encryptedFile = File(tempFolder.root, "doc.enc")
        val restoredFile = File(tempFolder.root, "restored_doc.pdf")

        val encResult = BlindMediaVaultEngine.encryptMediaStream(sourceFile, encryptedFile)

        val wrongKey = BlindMediaVaultEngine.generateMediaKey()
        assertNotEquals(encResult.mediaKey, wrongKey)

        val decryptSuccess = BlindMediaVaultEngine.decryptMediaStream(
            encryptedSourceFile = encryptedFile,
            destinationPlainFile = restoredFile,
            mediaKey = wrongKey,
            iv = encResult.iv
        )

        assertFalse("Decryption with wrong key must fail", decryptSuccess)
    }
}
