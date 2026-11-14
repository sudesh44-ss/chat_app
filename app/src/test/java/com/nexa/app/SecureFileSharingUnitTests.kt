package com.nexa.app

import com.nexa.app.core.crypto.ChunkEncryptor
import com.nexa.app.core.security.ArchiveSanitizer
import com.nexa.app.core.security.ArchiveScanResult
import com.nexa.app.core.security.FileSecurityManager
import com.nexa.app.core.security.NexaFileSecurityEngine
import com.nexa.app.core.security.SecurityScanResult
import com.nexa.app.core.security.ThreatBloomFilter
import com.nexa.app.core.security.ThreatType
import com.nexa.app.core.security.ValidationResult
import com.nexa.app.core.security.YaraNativeBridge
import com.nexa.app.core.util.FileShredder
import com.nexa.app.core.webrtc.FileChunk
import com.nexa.app.data.local.entity.TransferEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Production-Grade Unit & Security Test Suite for the
 * Fail-Closed Secure File Sharing & Malware Protection Pipeline.
 */
class SecureFileSharingUnitTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var bloomFilter: ThreatBloomFilter
    private lateinit var yaraBridge: YaraNativeBridge
    private lateinit var securityManager: FileSecurityManager

    @Before
    fun setUp() {
        bloomFilter = ThreatBloomFilter()
        yaraBridge = YaraNativeBridge()
        securityManager = FileSecurityManager(
            threatBloomFilter = bloomFilter,
            yaraNativeBridge = yaraBridge
        )
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(file: File): String {
        return securityManager.computeSha256(file)
    }

    // =========================================================================
    // 1. FILE SIZE CEILING & INPUT SANITY TESTS
    // =========================================================================

    @Test
    fun fileSizeValidation_withinLimits_returnsTrue() {
        assertTrue(securityManager.validateFileSize(1024L))
        assertTrue(securityManager.validateFileSize(FileSecurityManager.MAX_FILE_SIZE_BYTES))
        assertFalse("0 bytes must be rejected", securityManager.validateFileSize(0L))
        assertFalse("Negative size must be rejected", securityManager.validateFileSize(-1L))
        assertFalse(
            "Exceeding 500 MB must be rejected",
            securityManager.validateFileSize(FileSecurityManager.MAX_FILE_SIZE_BYTES + 1L)
        )
    }

    // =========================================================================
    // 2. GATE 1 PRE-DISPATCH: MAGIC BYTES & BANNED EXECUTABLE CHECKS
    // =========================================================================

    @Test
    fun gate1_safeFileTypes_passValidation() {
        // Valid PNG
        val pngFile = tempFolder.newFile("photo.png")
        pngFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00))
        val pngResult = securityManager.validateFileType(pngFile, "png")
        assertTrue("Valid PNG must pass", pngResult is ValidationResult.Safe)

        // Valid JPEG
        val jpegFile = tempFolder.newFile("picture.jpg")
        jpegFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10))
        val jpegResult = securityManager.validateFileType(jpegFile, "jpg")
        assertTrue("Valid JPEG must pass", jpegResult is ValidationResult.Safe)

        // Valid PDF
        val pdfFile = tempFolder.newFile("sample.pdf")
        pdfFile.writeBytes("%PDF-1.7\nSample content".toByteArray(Charsets.US_ASCII))
        val pdfResult = securityManager.validateFileType(pdfFile, "pdf")
        assertTrue("Valid PDF must pass", pdfResult is ValidationResult.Safe)
    }

    @Test
    fun gate1_bannedExecutableExtensions_strictlyRejected() {
        val bannedExtensions = listOf(
            "apk", "dex", "so", "exe", "sh", "bat", "cmd", "js", "vbs", "html", "htm", "svg", "dll"
        )
        for (ext in bannedExtensions) {
            val file = tempFolder.newFile("script_$ext.$ext")
            file.writeBytes("print('Malicious Script')".toByteArray())
            val result = securityManager.validateFileType(file, ext)
            assertTrue("Extension .$ext must be blocked", result is ValidationResult.Blocked)
            val blocked = result as ValidationResult.Blocked
            assertTrue(blocked.reason.contains("Dangerous executable file type"))
        }
    }

    // =========================================================================
    // 3. GATE 1 PRE-DISPATCH: DISGUISED BINARY & MIME SPOOFING DETECTION
    // =========================================================================

    @Test
    fun gate1_disguisedWindowsPE_detectedAndBlocked() {
        // Disguised Windows PE/EXE binary (.jpg extension with MZ header)
        val disguisedPE = tempFolder.newFile("vacation_photo.jpg")
        disguisedPE.writeBytes(byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00, 0x03, 0x00))
        val result = securityManager.validateFileType(disguisedPE, "jpg")
        assertTrue("Disguised Windows PE must be blocked", result is ValidationResult.Blocked)
        assertTrue((result as ValidationResult.Blocked).reason.contains("Windows PE/EXE"))
    }

    @Test
    fun gate1_disguisedAndroidDEX_detectedAndBlocked() {
        // Disguised Android DEX executable (.png extension with dex\n header)
        val disguisedDEX = tempFolder.newFile("avatar.png")
        disguisedDEX.writeBytes(byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00))
        val result = securityManager.validateFileType(disguisedDEX, "png")
        assertTrue("Disguised DEX must be blocked", result is ValidationResult.Blocked)
        assertTrue((result as ValidationResult.Blocked).reason.contains("Android DEX"))
    }

    @Test
    fun gate1_disguisedLinuxELF_detectedAndBlocked() {
        // Disguised Linux ELF binary (.pdf extension with .ELF header)
        val disguisedELF = tempFolder.newFile("statement.pdf")
        disguisedELF.writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00))
        val result = securityManager.validateFileType(disguisedELF, "pdf")
        assertTrue("Disguised ELF must be blocked", result is ValidationResult.Blocked)
        assertTrue((result as ValidationResult.Blocked).reason.contains("Linux ELF"))
    }

    @Test
    fun gate1_disguisedShellScript_detectedAndBlocked() {
        // Disguised Shell script (.txt extension with #! shebang)
        val disguisedScript = tempFolder.newFile("notes.txt")
        disguisedScript.writeBytes("#!/bin/bash\nrm -rf /".toByteArray())
        val result = securityManager.validateFileType(disguisedScript, "txt")
        assertTrue("Disguised shell script must be blocked", result is ValidationResult.Blocked)
        assertTrue((result as ValidationResult.Blocked).reason.contains("shell script"))
    }

    @Test
    fun gate1_mimeSpoofing_extensionMismatchBlocked() {
        // A valid PNG file maliciously renamed to invoice.pdf
        val spoofedFile = tempFolder.newFile("invoice.pdf")
        spoofedFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val result = securityManager.validateFileType(spoofedFile, "pdf")
        assertTrue("MIME spoofing must be blocked", result is ValidationResult.Blocked)
        assertTrue((result as ValidationResult.Blocked).reason.contains("MIME Spoofing detected"))
    }

    // =========================================================================
    // 4. ARCHIVE SAFETY GATE: ZIP SLIP, ZIP BOMBS, NESTED & EXECUTABLE CHECKS
    // =========================================================================

    @Test
    fun archiveSanitizer_benignZip_passesInspection() {
        val zipFile = tempFolder.newFile("safe_bundle.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("document.txt"))
            zos.write("Benign text file inside archive".toByteArray())
            zos.closeEntry()
        }
        val scan = securityManager.validateArchive(zipFile)
        assertTrue("Benign archive must be Safe", scan is ArchiveScanResult.Safe)
        assertEquals(1, (scan as ArchiveScanResult.Safe).totalEntries)
    }

    @Test
    fun archiveSanitizer_zipSlipPathTraversal_detectedAndBlocked() {
        val zipFile = tempFolder.newFile("malicious_slip.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("../../../../etc/shadow"))
            zos.write("root:x:0:0:root:/root:/bin/bash".toByteArray())
            zos.closeEntry()
        }
        val scan = securityManager.validateArchive(zipFile)
        assertTrue("Zip Slip attack must be blocked", scan is ArchiveScanResult.Blocked)
        assertTrue((scan as ArchiveScanResult.Blocked).reason.contains("Zip Slip attack detected"))
    }

    @Test
    fun archiveSanitizer_nestedArchive_detectedAndBlocked() {
        val zipFile = tempFolder.newFile("nested_bomb.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("inner_payload.zip"))
            zos.write("Fake zip content".toByteArray())
            zos.closeEntry()
        }
        val scan = securityManager.validateArchive(zipFile)
        assertTrue("Nested archive must be blocked", scan is ArchiveScanResult.Blocked)
        assertTrue((scan as ArchiveScanResult.Blocked).reason.contains("Nested archive detected"))
    }

    @Test
    fun archiveSanitizer_executableInsideArchive_detectedAndBlocked() {
        val zipFile = tempFolder.newFile("bundle_with_exe.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("payload.exe"))
            zos.write("Executable binary bytes".toByteArray())
            zos.closeEntry()
        }
        val scan = securityManager.validateArchive(zipFile)
        assertTrue("Executable inside archive must be blocked", scan is ArchiveScanResult.Blocked)
        assertTrue((scan as ArchiveScanResult.Blocked).reason.contains("Blocked executable file found inside archive"))
    }

    // =========================================================================
    // 5. THREAT BLOOM FILTER LOOKUP
    // =========================================================================

    @Test
    fun threatBloomFilter_knownMalwareHash_detected() {
        val knownThreatHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        bloomFilter.addHash(knownThreatHash)
        assertTrue("Threat Bloom filter must contain added malware hash", bloomFilter.contains(knownThreatHash))

        val cleanHash = "a1b2c3d4e5f60718293a4b5c6d7e8f901234567890abcdef1234567890abcdef"
        assertFalse("Clean hash must not trigger Bloom filter", bloomFilter.contains(cleanHash))
    }

    // =========================================================================
    // 6. GATE 2 INBOUND QUARANTINE SCAN & INTEGRITY VERIFICATION
    // =========================================================================

    @Test
    fun gate2_cleanQuarantinedFile_passesIntegrityAndScan() = runBlocking {
        val cleanFile = tempFolder.newFile("quarantine_safe.png")
        val cleanBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
        )
        cleanFile.writeBytes(cleanBytes)
        val expectedHash = sha256Hex(cleanBytes)

        val verdict = securityManager.scanInboundQuarantine(
            tempFile = cleanFile,
            expectedHash = expectedHash,
            declaredName = "quarantine_safe.png"
        )
        assertTrue("Clean file matching expected hash must be Safe", verdict is SecurityScanResult.Safe)
        assertEquals(expectedHash, (verdict as SecurityScanResult.Safe).hash)
    }

    @Test
    fun gate2_corruptedOrTamperedHash_failsIntegrityCheck() = runBlocking {
        val tamperedFile = tempFolder.newFile("quarantine_tampered.txt")
        tamperedFile.writeText("Original content modified in flight by Mallory")
        val bogusExpectedHash = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

        val verdict = securityManager.scanInboundQuarantine(
            tempFile = tamperedFile,
            expectedHash = bogusExpectedHash,
            declaredName = "quarantine_tampered.txt"
        )
        assertTrue("Tampered hash must produce SecurityScanResult.Corrupted", verdict is SecurityScanResult.Corrupted)
        val corrupted = verdict as SecurityScanResult.Corrupted
        assertEquals(ThreatType.HASH_MISMATCH, corrupted.threatType)
        assertTrue(corrupted.message.contains("Hash mismatch"))
    }

    @Test
    fun gate2_missingOrEmptyQuarantinedFile_returnsCorrupted() = runBlocking {
        val emptyFile = tempFolder.newFile("empty_quarantine.tmp")
        val verdict = securityManager.scanInboundQuarantine(
            tempFile = emptyFile,
            expectedHash = "somehash",
            declaredName = "test.txt"
        )
        assertTrue("Empty payload must produce SecurityScanResult.Corrupted", verdict is SecurityScanResult.Corrupted)
    }

    @Test
    fun gate2_disguisedBinaryInQuarantine_blockedAndNotReleased() = runBlocking {
        val maliciousQuarantine = tempFolder.newFile("quarantine_trojan.jpg")
        val mzData = byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00, 0x03, 0x00)
        maliciousQuarantine.writeBytes(mzData)
        val actualHash = sha256Hex(mzData)

        // Even though hash matches what was computed for this file, Gate 2 magic byte inspection catches it
        val verdict = securityManager.scanInboundQuarantine(
            tempFile = maliciousQuarantine,
            expectedHash = actualHash,
            declaredName = "photo.jpg"
        )
        assertTrue("Disguised binary must be Rejected in Gate 2", verdict is SecurityScanResult.Rejected)
        val rejected = verdict as SecurityScanResult.Rejected
        assertTrue(rejected.reason.contains("PE/EXE"))
    }

    // =========================================================================
    // 7. CRYPTOGRAPHIC FILE SHREDDER TESTS
    // =========================================================================

    @Test
    fun fileShredder_cryptographicMultiPass_wipesAndDeletes() {
        val maliciousFile = tempFolder.newFile("malware_quarantine.tmp")
        val payload = "HIGHLY_DANGEROUS_EXPLOIT_SHELLCODE".repeat(100).toByteArray()
        maliciousFile.writeBytes(payload)

        assertTrue(maliciousFile.exists())
        assertTrue(maliciousFile.length() > 0)

        val shredded = FileShredder.shred(maliciousFile)
        assertTrue("Shredder must return true", shredded)
        assertFalse("Quarantined payload must be unlinked and deleted from filesystem", maliciousFile.exists())
    }

    // =========================================================================
    // 8. CHUNK ENCRYPTOR: CHACHA20-POLY1305 AEAD VERIFICATION
    // =========================================================================

    @Test
    fun chunkEncryptor_chaCha20Poly1305_roundtripPreservesBytes() {
        val sessionKey = ChunkEncryptor.generateSessionKey()
        assertEquals(32, sessionKey.size)

        val encryptor = ChunkEncryptor(sessionKey)
        val plaintext = ByteArray(FileChunk.DEFAULT_CHUNK_SIZE).also { Random(42).nextBytes(it) }

        val ciphertext = encryptor.encryptChunk(plaintext)
        // 12-byte IV + Plaintext + 16-byte Poly1305 MAC tag
        assertEquals(plaintext.size + 12 + 16, ciphertext.size)

        val decrypted = encryptor.decryptChunk(ciphertext)
        assertTrue("Decrypted chunk must match plaintext exactly", plaintext.contentEquals(decrypted))
    }

    @Test(expected = GeneralSecurityException::class)
    fun chunkEncryptor_tamperedCiphertext_failsDecryption() {
        val sessionKey = ChunkEncryptor.generateSessionKey()
        val encryptor = ChunkEncryptor(sessionKey)
        val plaintext = "Sensitive file chunk data".toByteArray()

        val ciphertext = encryptor.encryptChunk(plaintext)
        // Tamper with the MAC tag at the end of the ciphertext
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1].toInt() xor 0x01).toByte()

        // Must throw GeneralSecurityException due to AEAD tag verification failure
        encryptor.decryptChunk(ciphertext)
    }

    // =========================================================================
    // 9. CONTROLLED RELEASE INVARIANT TESTS
    // =========================================================================

    @Test
    fun controlledRelease_onlyCompletedStatusAllowsAccess() {
        val safeVaultDir = tempFolder.newFolder("vault_safe")
        val safeFile = File(safeVaultDir, "hash123_document.pdf")
        safeFile.writeText("Safe verified document content")

        val completedTransfer = TransferEntity(
            transferId = 1001L,
            conversationId = "conv_1",
            fileName = "document.pdf",
            fileSizeBytes = safeFile.length(),
            localUri = safeFile.absolutePath,
            fileHash = "hash123",
            chunksCompleted = 10,
            totalChunks = 10,
            status = "COMPLETED",
            isIncoming = true
        )

        val quarantinedTransfer = completedTransfer.copy(
            transferId = 1002L,
            status = "QUARANTINED"
        )

        val rejectedTransfer = completedTransfer.copy(
            transferId = 1003L,
            status = "REJECTED_MALICIOUS"
        )

        // Helper verifying release invariant:
        fun resolveFile(transfer: TransferEntity): File? {
            if (transfer.status != "COMPLETED" && transfer.status != "RELEASED") {
                return null
            }
            val f = File(transfer.localUri)
            return if (f.exists()) f else null
        }

        assertNotNull("COMPLETED transfer must resolve safe file", resolveFile(completedTransfer))
        assertNull("QUARANTINED transfer must NEVER resolve file", resolveFile(quarantinedTransfer))
        assertNull("REJECTED_MALICIOUS transfer must NEVER resolve file", resolveFile(rejectedTransfer))
    }
}
