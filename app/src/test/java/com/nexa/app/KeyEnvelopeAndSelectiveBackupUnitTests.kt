package com.nexa.app

import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.core.backup.BackupVaultHeader
import com.nexa.app.core.backup.crypto.KeyEnvelopeEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

class KeyEnvelopeAndSelectiveBackupUnitTests {

    private val secureRandom = SecureRandom()

    @Test
    fun testKeyEnvelopeSealAndUnsealRoundtrip() {
        val dbPassphrase = ByteArray(32).also { secureRandom.nextBytes(it) }
        val vaultMasterKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }

        val sealedEnvelope = KeyEnvelopeEngine.sealPassphrase(
            dbPassphrase = dbPassphrase,
            vaultMasterKeyBytes = vaultMasterKey,
            salt = salt
        )

        assertEquals("Key envelope must be exactly 66 bytes", 66, sealedEnvelope.size)

        // Verify Magic "NXKE"
        val magic = String(sealedEnvelope.copyOfRange(0, 4), Charsets.US_ASCII)
        assertEquals("NXKE", magic)

        // Unseal in RAM
        val recovered = KeyEnvelopeEngine.unsealPassphrase(
            envelopeBytes = sealedEnvelope,
            vaultMasterKeyBytes = vaultMasterKey,
            salt = salt
        )

        assertNotNull(recovered)
        assertEquals(32, recovered.size)
        assertArrayEquals("Recovered passphrase must match original 32-byte key", dbPassphrase, recovered)
    }

    @Test
    fun testKeyEnvelopeRejectsWrongMasterKey() {
        val dbPassphrase = ByteArray(32).also { secureRandom.nextBytes(it) }
        val correctMasterKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val wrongMasterKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }

        val sealedEnvelope = KeyEnvelopeEngine.sealPassphrase(
            dbPassphrase = dbPassphrase,
            vaultMasterKeyBytes = correctMasterKey,
            salt = salt
        )

        try {
            KeyEnvelopeEngine.unsealPassphrase(
                envelopeBytes = sealedEnvelope,
                vaultMasterKeyBytes = wrongMasterKey,
                salt = salt
            )
            fail("Unsealing with wrong master key must throw an exception")
        } catch (expected: Exception) {
            assertTrue(expected is javax.crypto.AEADBadTagException || expected is SecurityException || expected.cause is javax.crypto.AEADBadTagException)
        }
    }

    @Test
    fun testKeyEnvelopeTamperingFailsAuthentication() {
        val dbPassphrase = ByteArray(32).also { secureRandom.nextBytes(it) }
        val vaultMasterKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }

        val sealedEnvelope = KeyEnvelopeEngine.sealPassphrase(
            dbPassphrase = dbPassphrase,
            vaultMasterKeyBytes = vaultMasterKey,
            salt = salt
        )

        // Tamper with one byte in the ciphertext payload
        val tampered = sealedEnvelope.clone()
        tampered[30] = (tampered[30].toInt() xor 0xFF).toByte()

        try {
            KeyEnvelopeEngine.unsealPassphrase(
                envelopeBytes = tampered,
                vaultMasterKeyBytes = vaultMasterKey,
                salt = salt
            )
            fail("Tampered envelope must fail authentication")
        } catch (expected: Exception) {
            // Success: rejected
        }
    }

    @Test
    fun testKeyEnvelopeRejectsInvalidMagicOrTruncation() {
        val dbPassphrase = ByteArray(32).also { secureRandom.nextBytes(it) }
        val vaultMasterKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }

        val sealedEnvelope = KeyEnvelopeEngine.sealPassphrase(
            dbPassphrase = dbPassphrase,
            vaultMasterKeyBytes = vaultMasterKey,
            salt = salt
        )

        // Test truncated envelope (< 66 bytes)
        try {
            KeyEnvelopeEngine.unsealPassphrase(
                envelopeBytes = sealedEnvelope.copyOf(65),
                vaultMasterKeyBytes = vaultMasterKey,
                salt = salt
            )
            fail("Truncated envelope must be rejected")
        } catch (expected: SecurityException) {
            assertTrue(expected.message?.contains("Corrupted or invalid key envelope size") == true)
        }

        // Test bad magic bytes
        val badMagic = sealedEnvelope.clone()
        "FAIL".toByteArray(Charsets.US_ASCII).copyInto(badMagic, 0)
        try {
            KeyEnvelopeEngine.unsealPassphrase(
                envelopeBytes = badMagic,
                vaultMasterKeyBytes = vaultMasterKey,
                salt = salt
            )
            fail("Envelope with bad magic must be rejected")
        } catch (expected: SecurityException) {
            assertTrue(expected.message?.contains("Invalid key envelope magic header") == true)
        }
    }

    @Test
    fun testBackupCategoryConfigSerialization() {
        val config = BackupCategoryConfig(
            includeChats = true,
            includeGroups = false,
            includeContacts = true,
            includeAttachments = true,
            includeImages = true,
            includeVideos = false,
            includeDocuments = true,
            includeAudio = false,
            includeOtherFiles = false,
            includeBookmarks = true,
            includeUserPrefs = true,
            includeAppSettings = false,
            includeDeviceSettings = false
        )

        val jsonStr = config.toJson()
        val deserialized = BackupCategoryConfig.fromJson(jsonStr)

        assertEquals(config.includeChats, deserialized.includeChats)
        assertEquals(config.includeGroups, deserialized.includeGroups)
        assertEquals(config.includeContacts, deserialized.includeContacts)
        assertEquals(config.includeAttachments, deserialized.includeAttachments)
        assertEquals(config.includeImages, deserialized.includeImages)
        assertEquals(config.includeVideos, deserialized.includeVideos)
        assertEquals(config.includeDocuments, deserialized.includeDocuments)
        assertEquals(config.includeAudio, deserialized.includeAudio)
        assertEquals(config.includeOtherFiles, deserialized.includeOtherFiles)
        assertEquals(config.includeBookmarks, deserialized.includeBookmarks)
        assertEquals(config.includeUserPrefs, deserialized.includeUserPrefs)
        assertEquals(config.includeAppSettings, deserialized.includeAppSettings)
        assertEquals(config.includeDeviceSettings, deserialized.includeDeviceSettings)
    }

    @Test
    fun testBackupCategoryMediaClassification() {
        val configOnlyImages = BackupCategoryConfig(
            includeAttachments = true,
            includeImages = true,
            includeVideos = false,
            includeDocuments = false,
            includeAudio = false,
            includeOtherFiles = false
        )

        assertTrue(com.nexa.app.core.backup.VaultBundlePackager.matchesMediaCategory("photo.jpg", configOnlyImages))
        assertTrue(com.nexa.app.core.backup.VaultBundlePackager.matchesMediaCategory("photo.png", configOnlyImages))
        assertTrue(com.nexa.app.core.backup.VaultBundlePackager.matchesMediaCategory("photo.webp", configOnlyImages))
        assertFalse(com.nexa.app.core.backup.VaultBundlePackager.matchesMediaCategory("clip.mp4", configOnlyImages))
        assertFalse(com.nexa.app.core.backup.VaultBundlePackager.matchesMediaCategory("voice.opus", configOnlyImages))
        assertFalse(com.nexa.app.core.backup.VaultBundlePackager.matchesMediaCategory("doc.pdf", configOnlyImages))

        val configOnlyVideos = BackupCategoryConfig(
            includeAttachments = true,
            includeImages = false,
            includeVideos = true,
            includeDocuments = false,
            includeAudio = false,
            includeOtherFiles = false
        )
        assertTrue(com.nexa.app.core.backup.VaultBundlePackager.matchesMediaCategory("clip.mp4", configOnlyVideos))
        assertTrue(com.nexa.app.core.backup.VaultBundlePackager.matchesMediaCategory("clip.mkv", configOnlyVideos))
        assertFalse(com.nexa.app.core.backup.VaultBundlePackager.matchesMediaCategory("photo.jpg", configOnlyVideos))

        val configNoAttachments = BackupCategoryConfig(includeAttachments = false)
        assertFalse(com.nexa.app.core.backup.VaultBundlePackager.matchesMediaCategory("photo.jpg", configNoAttachments))
        assertFalse(com.nexa.app.core.backup.VaultBundlePackager.matchesMediaCategory("clip.mp4", configNoAttachments))
    }

    @Test
    fun testBackupVaultHeaderVersion2Compatibility() {
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val sha256 = ByteArray(32).also { secureRandom.nextBytes(it) }

        // Test Version 2 Header
        val headerV2 = BackupVaultHeader(
            version = BackupVaultHeader.VERSION_2,
            salt = salt,
            iv = iv,
            payloadSha256 = sha256
        )

        val serializedV2 = headerV2.toByteArray()
        assertEquals(71, serializedV2.size)

        val deserializedV2 = BackupVaultHeader.fromByteArray(serializedV2)
        assertNotNull(deserializedV2)
        assertEquals(BackupVaultHeader.VERSION_2, deserializedV2!!.version)
        assertArrayEquals(salt, deserializedV2.salt)

        // Test Version 1 Header compatibility
        val headerV1 = BackupVaultHeader(
            version = BackupVaultHeader.VERSION_1,
            salt = salt,
            iv = iv,
            payloadSha256 = sha256
        )
        val serializedV1 = headerV1.toByteArray()
        val deserializedV1 = BackupVaultHeader.fromByteArray(serializedV1)
        assertNotNull(deserializedV1)
        assertEquals(BackupVaultHeader.VERSION_1, deserializedV1!!.version)

        // Test Unsupported Version (e.g. 99)
        val headerV99Bytes = serializedV2.clone()
        headerV99Bytes[7] = 0
        headerV99Bytes[8] = 0
        headerV99Bytes[9] = 0
        headerV99Bytes[10] = 99
        val deserializedV99 = BackupVaultHeader.fromByteArray(headerV99Bytes)
        org.junit.Assert.assertNull("Unsupported version 99 must return null", deserializedV99)
    }
}
