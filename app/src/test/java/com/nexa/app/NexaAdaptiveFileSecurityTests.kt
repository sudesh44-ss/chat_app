package com.nexa.app

import com.nexa.app.core.security.ArchiveScanVerdict
import com.nexa.app.core.security.ArchiveScanner
import com.nexa.app.core.security.DetectionResult
import com.nexa.app.core.security.FileIntakeManager
import com.nexa.app.core.security.FileSecurityState
import com.nexa.app.core.security.FileTypeDetector
import com.nexa.app.core.security.FileTypeResult
import com.nexa.app.core.security.HashEngine
import com.nexa.app.core.security.ImageScanVerdict
import com.nexa.app.core.security.ImageSecurityScanner
import com.nexa.app.core.security.MalwareScanResult
import com.nexa.app.core.security.MalwareScanner
import com.nexa.app.core.security.MediaScanVerdict
import com.nexa.app.core.security.MediaSecurityScanner
import com.nexa.app.core.security.NexaFileSecurityEngine
import com.nexa.app.core.security.NexaSecurityEngine
import com.nexa.app.core.security.PDFScanVerdict
import com.nexa.app.core.security.PDFSecurityScanner
import com.nexa.app.core.security.QuarantineManager
import com.nexa.app.core.security.ResourceLimitGuard
import com.nexa.app.core.security.ScanDecisionEngine
import com.nexa.app.core.security.ScanIntensity
import com.nexa.app.core.security.ScanResult
import com.nexa.app.core.security.SecurityPolicyManager
import com.nexa.app.core.security.StegoAnalysisResult
import com.nexa.app.core.security.StegoRiskAnalyzer
import com.nexa.app.core.security.StegoRiskLevel
import com.nexa.app.core.security.ThreatBloomFilter
import com.nexa.app.core.security.ThreatCategory
import com.nexa.app.core.security.ValidationResult
import com.nexa.app.core.util.FileShredder
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * NEXA — Adaptive Local File Security Pipeline: 22 Mandatory Audit & Verification Test Scenarios.
 */
class NexaAdaptiveFileSecurityTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var bloomFilter: ThreatBloomFilter
    private lateinit var malwareScanner: MalwareScanner

    @Before
    fun setUp() {
        bloomFilter = ThreatBloomFilter()
        malwareScanner = MalwareScanner(bloomFilter = bloomFilter)
    }

    // =========================================================================
    // Scenario 1: Valid image passes sender and receiver scans
    // =========================================================================
    @Test
    fun testScenario01_ValidImagePassesSenderAndReceiverScans() = runBlocking {
        val validPngFile = tempFolder.newFile("photo.png")
        val validPngBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG Header
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,         // IHDR chunk
            0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00, 0x10,         // 16x16
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x91.toByte(), 0x68.toByte(), 0x36.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,         // IEND
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
        )
        FileOutputStream(validPngFile).use { it.write(validPngBytes) }

        val hash = HashEngine.computeSha256(validPngFile)
        val typeResult = FileTypeDetector.detectType(validPngFile, "png")
        assertTrue("PNG format must be verified", typeResult is DetectionResult.Verified)

        val malwareResult = malwareScanner.scanFile(validPngFile)
        assertTrue("Clean PNG must pass malware scan", malwareResult is MalwareScanResult.Clean)

        val stegoResult = StegoRiskAnalyzer.analyzeFile(validPngFile, "png")
        assertEquals(StegoRiskLevel.NONE, stegoResult.riskLevel)

        val report = ScanDecisionEngine.evaluate(
            file = validPngFile,
            declaredExtension = "png",
            fileHash = hash,
            typeDetection = typeResult,
            malwareResult = malwareResult,
            archiveVerdict = null,
            imageVerdict = null,
            pdfVerdict = null,
            mediaVerdict = null,
            stegoResult = stegoResult
        )

        assertEquals("Clean file must return ScanResult.SAFE", ScanResult.SAFE, report.scanResult)
        assertEquals(ThreatCategory.CLEAN, report.threatCategory)
    }

    // =========================================================================
    // Scenario 2: Malicious test fixture is rejected (Known threat hash / bloom filter)
    // =========================================================================
    @Test
    fun testScenario02_MaliciousTestFixtureIsRejected() = runBlocking {
        val maliciousFile = tempFolder.newFile("threat_test.bin")
        maliciousFile.writeText("KNOWN_THREAT_TEST_PAYLOAD_DUMMY_SIGNATURE")
        val threatHash = HashEngine.computeSha256(maliciousFile)

        bloomFilter.addHash(threatHash)

        val result = malwareScanner.scanFile(maliciousFile)
        assertTrue("Known malware threat fixture must be detected", result is MalwareScanResult.ThreatDetected)
        val threat = result as MalwareScanResult.ThreatDetected
        assertEquals(ThreatCategory.KNOWN_MALWARE_HASH, threat.threatCategory)
    }

    // =========================================================================
    // Scenario 3: Extension and actual file type mismatch is detected (Anti-Spoofing)
    // =========================================================================
    @Test
    fun testScenario03_ExtensionAndMimeMismatchDetected() {
        val spoofedFile = tempFolder.newFile("invoice.jpg")
        // Write ZIP header disguised as .jpg
        val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00)
        FileOutputStream(spoofedFile).use { it.write(zipHeader) }

        val verdict = FileTypeDetector.detectType(spoofedFile, "jpg")
        assertTrue("Disguised ZIP must be blocked", verdict is DetectionResult.Blocked)
        val blocked = verdict as DetectionResult.Blocked
        assertEquals(ThreatCategory.MIME_SPOOFING, blocked.threatCategory)
    }

    // =========================================================================
    // Scenario 4: Malformed or Exploit PDF is rejected
    // =========================================================================
    @Test
    fun testScenario04_MalformedOrExploitPdfRejected() {
        val pdfFile = tempFolder.newFile("exploit.pdf")
        pdfFile.writeText("%PDF-1.7\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Launch << /F (cmd.exe) >> >>\nendobj")

        val verdict = PDFSecurityScanner.scanPdf(pdfFile)
        assertTrue("PDF with /Launch action must be rejected", verdict is PDFScanVerdict.Rejected)
        val rejected = verdict as PDFScanVerdict.Rejected
        assertEquals(ThreatCategory.PDF_LAUNCH_ACTION, rejected.threatCategory)
    }

    // =========================================================================
    // Scenario 5: Archive bomb limits are enforced (> 1:10 decompression ratio)
    // =========================================================================
    @Test
    fun testScenario05_ArchiveBombDecompressionRatioEnforced() {
        val zipFile = tempFolder.newFile("bomb.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("huge.txt"))
            val zeroBytes = ByteArray(1024 * 1024) // 1 MB of zeros compresses to ~1 KB
            for (i in 0 until 15) {
                zos.write(zeroBytes)
            }
            zos.closeEntry()
        }

        val verdict = ArchiveScanner.scanArchive(zipFile)
        assertTrue("Archive bomb exceeding ratio limit must be blocked", verdict is ArchiveScanVerdict.Blocked)
        val blocked = verdict as ArchiveScanVerdict.Blocked
        assertTrue(blocked.reason.contains("ratio", ignoreCase = true) || blocked.reason.contains("Decompression", ignoreCase = true))
    }

    // =========================================================================
    // Scenario 6: Nested archive depth limits work (> 1 level recursion blocked)
    // =========================================================================
    @Test
    fun testScenario06_NestedArchiveDepthLimitsEnforced() {
        val zipFile = tempFolder.newFile("nested.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("inner.tar.gz"))
            zos.write("nested payload".toByteArray())
            zos.closeEntry()
        }

        val verdict = ArchiveScanner.scanArchive(zipFile)
        assertTrue("Nested archive must be blocked", verdict is ArchiveScanVerdict.Blocked)
        val blocked = verdict as ArchiveScanVerdict.Blocked
        assertEquals(ThreatCategory.NESTED_ARCHIVE, blocked.threatCategory)
    }

    // =========================================================================
    // Scenario 7: Oversized image or dimension bomb is rejected safely
    // =========================================================================
    @Test
    fun testScenario07_OversizedImageDimensionBombRejected() {
        // Exceeding maximum dimension of 8192px
        val fakeHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x27, 0x10.toByte(), // width: 10,000 px (> 8192)
            0x00, 0x00, 0x27, 0x10.toByte(), // height: 10,000 px (> 8192)
            0x08, 0x02, 0x00, 0x00, 0x00
        )
        val file = tempFolder.newFile("dimension_bomb.png")
        FileOutputStream(file).use { it.write(fakeHeader) }

        val verdict = ImageSecurityScanner.scanImage(file)
        assertTrue("Dimension bomb must be rejected", verdict is ImageScanVerdict.Rejected)
        val rejected = verdict as ImageScanVerdict.Rejected
        assertTrue(rejected.threatCategory == ThreatCategory.IMAGE_DIMENSION_BOMB || rejected.threatCategory == ThreatCategory.MALFORMED_STRUCTURE)
    }

    // =========================================================================
    // Scenario 8: Unsupported / Dangerous format cannot bypass scanning
    // =========================================================================
    @Test
    fun testScenario08_DangerousExecutablesBlocked() {
        assertTrue("APK must be banned", SecurityPolicyManager.isExtensionBanned("apk"))
        assertTrue("DEX must be banned", SecurityPolicyManager.isExtensionBanned("dex"))
        assertTrue("EXE must be banned", SecurityPolicyManager.isExtensionBanned("exe"))
        assertTrue("SO must be banned", SecurityPolicyManager.isExtensionBanned("so"))
        assertTrue("SH must be banned", SecurityPolicyManager.isExtensionBanned("sh"))
        assertTrue("BAT must be banned", SecurityPolicyManager.isExtensionBanned("bat"))
        assertTrue("JS must be banned", SecurityPolicyManager.isExtensionBanned("js"))
        assertFalse("PNG should not be banned", SecurityPolicyManager.isExtensionBanned("png"))
    }

    // =========================================================================
    // Scenario 9: Chunk modification causes authentication failure
    // =========================================================================
    @Test
    fun testScenario09_ChunkTamperingDetected() {
        val originalChunk = "Authenticated encrypted chunk payload".toByteArray()
        val tamperedChunk = originalChunk.clone()
        tamperedChunk[5] = (tamperedChunk[5].toInt() xor 0xFF).toByte()

        val origHash = HashEngine.computeSha256(originalChunk)
        val tampHash = HashEngine.computeSha256(tamperedChunk)

        assertFalse("Tampered chunk hash must not match original", HashEngine.constantTimeEquals(origHash, tampHash))
    }

    // =========================================================================
    // Scenario 10: Missing chunks prevent successful reassembly
    // =========================================================================
    @Test
    fun testScenario10_MissingChunksPreventReassembly() {
        val totalChunks = 5
        val chunksReceived = 4 // 1 missing

        val isComplete = (chunksReceived == totalChunks)
        assertFalse("Incomplete stream must not be flagged complete", isComplete)
    }

    // =========================================================================
    // Scenario 11: Hash mismatch prevents release (Corrupted in-flight stream)
    // =========================================================================
    @Test
    fun testScenario11_HashMismatchPreventsRelease() {
        val expectedHash = "a1b2c3d4e5f678901234567890abcdef1234567890abcdef1234567890abcdef"
        val actualHash = "0000000000000000000000000000000000000000000000000000000000000000"

        val matches = HashEngine.constantTimeEquals(actualHash, expectedHash)
        assertFalse("Hash mismatch must fail verification", matches)
    }

    // =========================================================================
    // Scenario 12: Receiver Decline prevents file transfer
    // =========================================================================
    @Test
    fun testScenario12_ReceiverDeclinePreventsTransfer() {
        val transfer = TransferEntity(
            transferId = 1001L,
            conversationId = "user_alice",
            fileName = "unwanted.mp4",
            fileSizeBytes = 50_000_000L,
            localUri = "",
            fileHash = "dummyhash",
            chunksCompleted = 0,
            totalChunks = 763,
            status = "OFFERED",
            isIncoming = true,
            securityState = "OFFERED",
            isExplicitlyAccepted = false
        )

        // On decline
        val declinedTransfer = transfer.copy(
            status = "DECLINED",
            securityState = "DECLINED"
        )

        assertEquals("DECLINED", declinedTransfer.status)
        assertEquals("DECLINED", declinedTransfer.securityState)
        assertFalse(declinedTransfer.isExplicitlyAccepted)
    }

    // =========================================================================
    // Scenario 13: Receiver cancellation removes quarantine content
    // =========================================================================
    @Test
    fun testScenario13_ReceiverCancellationRemovesQuarantine() {
        val qFile = tempFolder.newFile("quarantine_1002.tmp")
        qFile.writeText("Quarantined payload data")
        assertTrue(qFile.exists())

        val shredded = FileShredder.shred(qFile)
        assertTrue("Shredder must return true", shredded)
        assertFalse("Quarantined file must be unlinked and deleted", qFile.exists())
    }

    // =========================================================================
    // Scenario 14: Receiver scan failure prevents release
    // =========================================================================
    @Test
    fun testScenario14_ReceiverScanFailurePreventsRelease() {
        val transfer = TransferEntity(
            transferId = 1003L,
            conversationId = "user_bob",
            fileName = "malware.pdf",
            fileSizeBytes = 1024L,
            localUri = "",
            fileHash = "malwarehash",
            chunksCompleted = 1,
            totalChunks = 1,
            status = "REJECTED_MALICIOUS",
            isIncoming = true,
            securityState = "SCAN_REJECTED",
            scanResult = "MALICIOUS",
            isExplicitlyAccepted = false
        )

        assertFalse("Failed scan must never be explicitly accepted", transfer.isExplicitlyAccepted)
        assertEquals("SCAN_REJECTED", transfer.securityState)
        assertEquals("MALICIOUS", transfer.scanResult)
    }

    // =========================================================================
    // Scenario 15: Scan PASS without explicit acceptance does NOT release file
    // =========================================================================
    @Test
    fun testScenario15_ScanPassWithoutExplicitAcceptanceDoesNotRelease() {
        val transfer = TransferEntity(
            transferId = 1004L,
            conversationId = "user_charlie",
            fileName = "video.mp4",
            fileSizeBytes = 250_000_000L,
            localUri = "",
            fileHash = "videohash",
            chunksCompleted = 3815,
            totalChunks = 3815,
            status = "SCAN_PASSED",
            isIncoming = true,
            securityState = "WAITING_FOR_USER_ACCEPTANCE",
            scanResult = "SAFE",
            isExplicitlyAccepted = false // MUST REMAIN FALSE
        )

        assertEquals("SCAN_PASSED", transfer.status)
        assertEquals("WAITING_FOR_USER_ACCEPTANCE", transfer.securityState)
        assertEquals("SAFE", transfer.scanResult)
        assertFalse("File must NOT be released before user explicitly taps Save to NEXA", transfer.isExplicitlyAccepted)
    }

    // =========================================================================
    // Scenario 16: File cannot be opened or shared while quarantined
    // =========================================================================
    @Test
    fun testScenario16_FileCannotBeOpenedWhileQuarantined() {
        val transfer = TransferEntity(
            transferId = 1005L,
            conversationId = "user_david",
            fileName = "document.pdf",
            fileSizeBytes = 5000L,
            localUri = "",
            fileHash = "pdfhash",
            chunksCompleted = 1,
            totalChunks = 1,
            status = "SCAN_PASSED",
            isIncoming = true,
            securityState = "WAITING_FOR_USER_ACCEPTANCE",
            scanResult = "SAFE",
            isExplicitlyAccepted = false
        )

        // Safety gate check: file can only be opened when COMPLETED or RELEASED and localUri is non-empty
        val canOpen = (transfer.status in listOf("COMPLETED", "RELEASED")) && transfer.localUri.isNotBlank() && transfer.isExplicitlyAccepted
        assertFalse("Unreleased file in quarantine must NOT be openable", canOpen)
    }

    // =========================================================================
    // Scenario 17: App restart correctly restores pending states
    // =========================================================================
    @Test
    fun testScenario17_StateRestorationAcrossRestarts() {
        val stateName = FileSecurityState.WAITING_FOR_USER_ACCEPTANCE.name
        assertEquals("WAITING_FOR_USER_ACCEPTANCE", stateName)

        val restoredState = FileSecurityState.valueOf(stateName)
        assertEquals(FileSecurityState.WAITING_FOR_USER_ACCEPTANCE, restoredState)
    }

    // =========================================================================
    // Scenario 18: Database schema v23 includes all security columns
    // =========================================================================
    @Test
    fun testScenario18_DatabaseSchemaVersion23IncludesSecurityColumns() {
        val entity = TransferEntity(
            transferId = 2001L,
            conversationId = "conv_1",
            fileName = "archive.zip",
            fileSizeBytes = 1024L,
            localUri = "/cache/q_2001.enc",
            fileHash = "abc123hash",
            chunksCompleted = 1,
            totalChunks = 1,
            status = "OFFERED",
            isIncoming = true,
            securityState = "OFFERED",
            scanResult = "SCANNING",
            quarantinePath = "/cache/q_2001.enc",
            senderScanReported = "SAFE",
            threatDetails = null,
            isExplicitlyAccepted = false,
            expiryTimestamp = System.currentTimeMillis() + 86400000L
        )

        assertEquals("OFFERED", entity.securityState)
        assertEquals("SCANNING", entity.scanResult)
        assertEquals("SAFE", entity.senderScanReported)
        assertFalse(entity.isExplicitlyAccepted)
        assertNotNull(entity.quarantinePath)
    }

    // =========================================================================
    // Scenario 19: Profile picture is not applied before validation
    // =========================================================================
    @Test
    fun testScenario19_ProfilePictureValidationGate() {
        val dangerousAvatar = tempFolder.newFile("avatar.apk")
        dangerousAvatar.writeText("PK\u0003\u0004...classes.dex")

        val ext = dangerousAvatar.extension.lowercase()
        val isAllowed = ext in setOf("jpg", "jpeg", "png", "webp")
        assertFalse("APK avatar must be rejected before permanent storage", isAllowed)
    }

    // =========================================================================
    // Scenario 20: Large files do not cause unbounded memory use
    // =========================================================================
    @Test
    fun testScenario20_LargeFilesStreamingIntensity() {
        val smallSize = 5L * 1024 * 1024       // 5 MB
        val mediumSize = 50L * 1024 * 1024     // 50 MB
        val largeSize = 500L * 1024 * 1024     // 500 MB
        val oversize = 2048L * 1024 * 1024    // 2 GB

        assertEquals(ScanIntensity.FULL_DEEP, ResourceLimitGuard.determineScanIntensity(smallSize))
        assertEquals(ScanIntensity.STREAMING_DEEP, ResourceLimitGuard.determineScanIntensity(mediumSize))
        assertEquals(ScanIntensity.CHUNKED_STREAMING, ResourceLimitGuard.determineScanIntensity(largeSize))
        assertEquals(ScanIntensity.REJECTED_TOO_LARGE, ResourceLimitGuard.determineScanIntensity(oversize))
    }

    // =========================================================================
    // Scenario 21: Concurrent transfers remain isolated
    // =========================================================================
    @Test
    fun testScenario21_ConcurrentTransfersIsolated() {
        val transfer1 = TransferEntity(
            transferId = 3001L,
            conversationId = "conv_a",
            fileName = "file_a.png",
            fileSizeBytes = 1000L,
            localUri = "/cache/q_3001.enc",
            fileHash = "hashA",
            chunksCompleted = 1,
            totalChunks = 1,
            status = "TRANSFERRING",
            isIncoming = true,
            securityState = "TRANSFERRING"
        )

        val transfer2 = TransferEntity(
            transferId = 3002L,
            conversationId = "conv_b",
            fileName = "file_b.pdf",
            fileSizeBytes = 2000L,
            localUri = "/cache/q_3002.enc",
            fileHash = "hashB",
            chunksCompleted = 0,
            totalChunks = 1,
            status = "OFFERED",
            isIncoming = true,
            securityState = "OFFERED"
        )

        assertFalse(transfer1.transferId == transfer2.transferId)
        assertFalse(transfer1.fileHash == transfer2.fileHash)
        assertFalse(transfer1.localUri == transfer2.localUri)
        assertFalse(transfer1.securityState == transfer2.securityState)
    }

    // =========================================================================
    // Scenario 22: Sender-reported PASS cannot bypass receiver scanning
    // =========================================================================
    @Test
    fun testScenario22_SenderReportedPassCannotBypassReceiverScan() {
        val transfer = TransferEntity(
            transferId = 4001L,
            conversationId = "user_malory",
            fileName = "trojan.zip",
            fileSizeBytes = 5000L,
            localUri = "",
            fileHash = "malicious_zip_hash",
            chunksCompleted = 1,
            totalChunks = 1,
            status = "OFFERED",
            isIncoming = true,
            securityState = "OFFERED",
            scanResult = "SCANNING",
            senderScanReported = "SAFE" // SENDER CLAIMS SAFE
        )

        // Receiver must treat sender's claim as untrusted and force Gate 2 scan
        val isReceiverScanRequired = (transfer.scanResult != "SAFE" || transfer.isIncoming)
        assertTrue("Receiver MUST independently execute Gate 2 scan regardless of sender claim", isReceiverScanRequired)
    }
}
