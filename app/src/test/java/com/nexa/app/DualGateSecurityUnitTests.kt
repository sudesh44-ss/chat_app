package com.nexa.app

import com.nexa.app.core.security.DualScanEngine
import com.nexa.app.core.security.InspectionVerdict
import com.nexa.app.core.security.MagicByteInspector
import com.nexa.app.core.security.ScanResult
import com.nexa.app.core.security.ThreatBloomFilter
import com.nexa.app.core.security.YaraNativeBridge
import com.nexa.app.core.util.FileShredder
import com.nexa.app.data.local.entity.QuarantineEntity
import com.nexa.app.data.local.entity.QuarantineStatus
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
import java.security.MessageDigest

/**
 * Comprehensive Unit Test Suite verifying the Zero-Trust Dual-Gate Security Pipeline:
 * - Magic byte inspection and extension anti-spoofing
 * - O(1) Offline Threat Bloom Filter hash lookups
 * - Static bytecode heuristic pattern inspection (LibYARA bridge fallback)
 * - Multi-pass cryptographic zero-fill file shredder
 * - Gate 2 post-delivery quarantine verification & tamper detection
 */
class DualGateSecurityUnitTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var bloomFilter: ThreatBloomFilter
    private lateinit var yaraScanner: YaraNativeBridge

    @Before
    fun setUp() {
        bloomFilter = ThreatBloomFilter()
        yaraScanner = YaraNativeBridge()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    // ==========================================
    // 1. MAGIC BYTE & ANTI-SPOOFING TESTS
    // ==========================================

    @Test
    fun testMagicByteCleanPNG() {
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00)
        val verdict = MagicByteInspector.inspect(ByteArrayInputStream(pngHeader), "png")
        assertEquals(InspectionVerdict.Clean, verdict)
    }

    @Test
    fun testMagicByteCleanPDF() {
        val pdfHeader = "%PDF-1.7\nSample document content".toByteArray(Charsets.US_ASCII)
        val verdict = MagicByteInspector.inspect(ByteArrayInputStream(pdfHeader), "pdf")
        assertEquals(InspectionVerdict.Clean, verdict)
    }

    @Test
    fun testMagicByteCleanDEXAllowedOnlyInAPK() {
        val dexHeader = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00)
        val apkVerdict = MagicByteInspector.inspect(ByteArrayInputStream(dexHeader), "apk")
        assertEquals(InspectionVerdict.Clean, apkVerdict)
    }

    @Test
    fun testMagicByteDEXInNonAPKFlaggedAsDangerousExecutable() {
        val dexHeader = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00)
        val verdict = MagicByteInspector.inspect(ByteArrayInputStream(dexHeader), "jpg")
        assertTrue(verdict is InspectionVerdict.DangerousBinary)
        assertEquals("Android DEX", (verdict as InspectionVerdict.DangerousBinary).detectedType)
    }

    @Test
    fun testMagicByteWindowsPEFlaggedAsDangerousExecutable() {
        val mzHeader = byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00, 0x03, 0x00) // MZ
        val verdict = MagicByteInspector.inspect(ByteArrayInputStream(mzHeader), "pdf")
        assertTrue(verdict is InspectionVerdict.DangerousBinary)
        assertEquals("Windows PE / EXE", (verdict as InspectionVerdict.DangerousBinary).detectedType)
    }

    @Test
    fun testMagicByteLinuxELFFlaggedAsDangerousExecutable() {
        val elfHeader = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00) // .ELF
        val verdict = MagicByteInspector.inspect(ByteArrayInputStream(elfHeader), "txt")
        assertTrue(verdict is InspectionVerdict.DangerousBinary)
        assertEquals("Linux ELF Binary", (verdict as InspectionVerdict.DangerousBinary).detectedType)
    }

    @Test
    fun testMagicByteZIPDisguisedAsJPEGFlaggedAsSpoofed() {
        val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00) // PK..
        val verdict = MagicByteInspector.inspect(ByteArrayInputStream(zipHeader), "jpg")
        assertTrue(verdict is InspectionVerdict.Spoofed)
        assertEquals("jpg", (verdict as InspectionVerdict.Spoofed).declaredExt)
        assertEquals("ZIP/Archive Container", verdict.actualType)
    }

    @Test
    fun testMagicBytePDFSpoofedHeaderFlagged() {
        val corruptHeader = "This is not a real PDF file".toByteArray(Charsets.US_ASCII)
        val verdict = MagicByteInspector.inspect(ByteArrayInputStream(corruptHeader), "pdf")
        assertTrue(verdict is InspectionVerdict.Spoofed)
        assertEquals("pdf", (verdict as InspectionVerdict.Spoofed).declaredExt)
    }

    // ==========================================
    // 2. THREAT BLOOM FILTER TESTS
    // ==========================================

    @Test
    fun testBloomFilterMatchesKnownMalwareHashes() {
        // EICAR Standard Test Hash
        val eicarHash = "275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f"
        assertTrue("Bloom Filter must match seeded EICAR hash", bloomFilter.contains(eicarHash))

        // WannaCry Primary Payload Hash
        val wannacryHash = "ed01ebfbc9eb5bbea545af4d01bf5f1071661840480439c6e5babe8e080e41aa"
        assertTrue("Bloom Filter must match seeded WannaCry hash", bloomFilter.contains(wannacryHash))
    }

    @Test
    fun testBloomFilterRejectsArbitraryCleanHash() {
        val cleanHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertFalse("Clean hash must not be flagged by Bloom Filter", bloomFilter.contains(cleanHash))
    }

    @Test
    fun testBloomFilterDynamicAddAndContains() {
        val dynamicThreatHash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        assertFalse(bloomFilter.contains(dynamicThreatHash))

        bloomFilter.addHash(dynamicThreatHash)
        assertTrue(bloomFilter.contains(dynamicThreatHash))
    }

    @Test
    fun testBloomFilterSerializationRoundtrip() {
        val testHash = "99887766554433221100aabbccddeeff99887766554433221100aabbccddeeff"
        bloomFilter.addHash(testHash)

        val exportedBytes = bloomFilter.exportBytes()
        val restoredFilter = ThreatBloomFilter()
        restoredFilter.loadFromBytes(exportedBytes)

        assertTrue("Restored Bloom Filter must retain added threat hash", restoredFilter.contains(testHash))
    }

    // ==========================================
    // 3. YARA / HEURISTIC PATTERN INSPECTION
    // ==========================================

    @Test
    fun testYaraCleanFile() {
        val cleanFile = tempFolder.newFile("clean_doc.txt")
        cleanFile.writeText("This is an entirely legitimate, non-malicious text document.")

        val threat = yaraScanner.scanFile(cleanFile.absolutePath)
        assertNull("Clean file must return null threat", threat)
    }

    @Test
    fun testYaraFlagsEicarPattern() {
        val eicarBytes = "X5O!P%@AP[4\\PZX54(P^)7CC)7}\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!\$H+H*".toByteArray(Charsets.US_ASCII)
        val threat = yaraScanner.scanInputStream(ByteArrayInputStream(eicarBytes))
        assertEquals("Test.EICAR.StandardVector", threat)
    }

    @Test
    fun testYaraFlagsNopSledBufferOverflow() {
        val nopFile = tempFolder.newFile("payload.bin")
        val data = ByteArray(1024) { 0x41 }
        // Inject 8 consecutive 0x90 NOP bytes
        for (i in 100..107) {
            data[i] = 0x90.toByte()
        }
        FileOutputStream(nopFile).use { it.write(data) }

        val threat = yaraScanner.scanFile(nopFile.absolutePath)
        assertEquals("Exploit.NopSled.BufferOverflow", threat)
    }

    @Test
    fun testYaraFlagsShellcodeCallPopSequence() {
        val shellcodeFile = tempFolder.newFile("exploit.bin")
        val data = ByteArray(512)
        // E8 00 00 00 00 58
        data[20] = 0xE8.toByte()
        data[21] = 0x00
        data[22] = 0x00
        data[23] = 0x00
        data[24] = 0x00
        data[25] = 0x58
        FileOutputStream(shellcodeFile).use { it.write(data) }

        val threat = yaraScanner.scanFile(shellcodeFile.absolutePath)
        assertEquals("Exploit.Shellcode.CallPop", threat)
    }

    @Test
    fun testYaraFlagsMaliciousPdfJavaScript() {
        val pdfFile = tempFolder.newFile("invoice.pdf")
        pdfFile.writeText("%PDF-1.7\n/Type /Catalog /Pages 2 0 R /Names << /JavaScript 4 0 R >> /EmbeddedFiles 5 0 R")

        val threat = yaraScanner.scanFile(pdfFile.absolutePath)
        assertEquals("Exploit.PDF.MaliciousJavaScript", threat)
    }

    @Test
    fun testYaraFlagsWeaponizedOfficeMacro() {
        val docFile = tempFolder.newFile("resume.doc")
        docFile.writeText("PK\u0003\u0004 ... vbaProject.bin ... Sub Document_Open() Shell ... End Sub")

        val threat = yaraScanner.scanFile(docFile.absolutePath)
        assertEquals("Exploit.Office.WeaponizedMacro", threat)
    }

    // ==========================================
    // 4. CRYPTOGRAPHIC FILE SHREDDER TESTS
    // ==========================================

    @Test
    fun testFileShredderWipesWithZerosAndDeletes() {
        val maliciousFile = tempFolder.newFile("trojan.tmp")
        val secretBytes = "SENSITIVE_MALICIOUS_PAYLOAD_BYTES".repeat(100).toByteArray()
        FileOutputStream(maliciousFile).use { it.write(secretBytes) }

        assertTrue(maliciousFile.exists())
        assertTrue(maliciousFile.length() > 0)

        val shredded = FileShredder.shred(maliciousFile)
        assertTrue("Shredder must return true on success", shredded)
        assertFalse("File must be unlinked after shredding", maliciousFile.exists())
    }

    // ==========================================
    // 5. GATE 2 INBOUND QUARANTINE SCAN TESTS
    // ==========================================

    @Test
    fun testGate2InboundCleanPayloadVerification() = runBlocking {
        val dualScan = DualScanEngine(
            bloomFilter = bloomFilter,
            yaraScanner = yaraScanner
        )

        val cleanFile = tempFolder.newFile("safe_image.png")
        val cleanBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
        )
        FileOutputStream(cleanFile).use { it.write(cleanBytes) }
        val expectedHash = sha256Hex(cleanBytes)

        val verdict = dualScan.scanInboundQuarantine(cleanFile, expectedHash, "png")
        assertTrue("Clean PNG must pass Gate 2 verification", verdict is ScanResult.Clean)
        assertEquals(expectedHash, (verdict as ScanResult.Clean).hash)
    }

    @Test
    fun testGate2InboundTamperedHashMismatch() = runBlocking {
        val dualScan = DualScanEngine(
            bloomFilter = bloomFilter,
            yaraScanner = yaraScanner
        )

        val file = tempFolder.newFile("tampered.txt")
        file.writeText("Corrupted in flight bytes")
        val wrongHash = "0000000000000000000000000000000000000000000000000000000000000000"

        val verdict = dualScan.scanInboundQuarantine(file, wrongHash, "txt")
        assertTrue("Tampered hash must return ScanResult.Corrupted", verdict is ScanResult.Corrupted)
        assertTrue((verdict as ScanResult.Corrupted).message.contains("Hash mismatch"))
    }

    @Test
    fun testGate2InboundFlaggedMaliciousThreat() = runBlocking {
        val dualScan = DualScanEngine(
            bloomFilter = bloomFilter,
            yaraScanner = yaraScanner
        )

        val maliciousFile = tempFolder.newFile("fake_photo.jpg")
        // Disguise Windows MZ binary as .jpg
        val mzData = byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00, 0x03, 0x00)
        FileOutputStream(maliciousFile).use { it.write(mzData) }
        val hash = sha256Hex(mzData)

        val verdict = dualScan.scanInboundQuarantine(maliciousFile, hash, "jpg")
        assertTrue("Disguised MZ binary must be flagged as ScanResult.Malicious", verdict is ScanResult.Malicious)
        assertTrue((verdict as ScanResult.Malicious).reason.contains("dangerous binary"))
    }

    // ==========================================
    // 6. ROOM AUDIT ENTITY & STATUS TESTS
    // ==========================================

    @Test
    fun testQuarantineEntityStatusTransitions() {
        val entity = QuarantineEntity(
            fileHash = "abc123hash",
            fileName = "suspicious_attachment.pdf",
            threatReason = "Exploit.PDF.MaliciousJavaScript",
            status = QuarantineStatus.ISOLATED,
            quarantinedFilePath = "/cache/quarantine/transfer_1.tmp"
        )

        assertEquals("abc123hash", entity.fileHash)
        assertEquals(QuarantineStatus.ISOLATED, entity.status)
        assertEquals("Exploit.PDF.MaliciousJavaScript", entity.threatReason)

        val shreddedEntity = entity.copy(status = QuarantineStatus.SHREDDED)
        assertEquals(QuarantineStatus.SHREDDED, shreddedEntity.status)

        val overriddenEntity = entity.copy(status = QuarantineStatus.OVERRIDDEN_SAFE)
        assertEquals(QuarantineStatus.OVERRIDDEN_SAFE, overriddenEntity.status)
    }
}
