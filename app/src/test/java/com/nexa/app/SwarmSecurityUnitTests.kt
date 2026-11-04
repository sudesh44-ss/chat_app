package com.nexa.app

import com.nexa.app.core.security.MagicByteDetector
import com.nexa.app.core.security.MalwareBloomFilter
import com.nexa.app.core.security.SecurityVerdict
import com.nexa.app.core.security.YaraScannerBridge
import com.nexa.app.core.swarm.SwarmBitfield
import com.nexa.app.core.swarm.SwarmConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class SwarmSecurityUnitTests {

    @Test
    fun magicByteDetector_validDocuments_returnsClean() {
        // Valid PNG
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00)
        assertEquals(SecurityVerdict.CLEAN, MagicByteDetector.inspect(ByteArrayInputStream(pngBytes), "png"))

        // Valid PDF
        val pdfBytes = "%PDF-1.7\nSample content".toByteArray()
        assertEquals(SecurityVerdict.CLEAN, MagicByteDetector.inspect(ByteArrayInputStream(pdfBytes), "pdf"))

        // Valid JPEG
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        assertEquals(SecurityVerdict.CLEAN, MagicByteDetector.inspect(ByteArrayInputStream(jpegBytes), "jpg"))
    }

    @Test
    fun magicByteDetector_executableSpoofedAsMediaOrDocument_flagsSpoofing() {
        // DEX header disguised as PDF
        val spoofedDexAsPdf = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00)
        assertEquals(
            SecurityVerdict.SPOOFED_EXTENSION,
            MagicByteDetector.inspect(ByteArrayInputStream(spoofedDexAsPdf), "pdf")
        )

        // Windows PE (MZ) disguised as PNG
        val spoofedMzAsPng = byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00, 0x03, 0x00)
        assertEquals(
            SecurityVerdict.SPOOFED_EXTENSION,
            MagicByteDetector.inspect(ByteArrayInputStream(spoofedMzAsPng), "png")
        )

        // Linux ELF disguised as JPEG
        val spoofedElfAsJpg = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01)
        assertEquals(
            SecurityVerdict.SPOOFED_EXTENSION,
            MagicByteDetector.inspect(ByteArrayInputStream(spoofedElfAsJpg), "jpeg")
        )
    }

    @Test
    fun magicByteDetector_rawExecutables_flagsDangerous() {
        val rawMz = byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00)
        assertEquals(
            SecurityVerdict.DANGEROUS_EXECUTABLE,
            MagicByteDetector.inspect(ByteArrayInputStream(rawMz), "bin")
        )
    }

    @Test
    fun malwareBloomFilter_identifiesKnownBadHashes() {
        val filter = MalwareBloomFilter()
        val badHash1 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val badHash2 = "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"
        val cleanHash = "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e"

        filter.addHash(badHash1)
        filter.addHash(badHash2)

        assertTrue(filter.containsHash(badHash1))
        assertTrue(filter.containsHash(badHash2))
        assertFalse(filter.containsHash(cleanHash))
    }

    @Test
    fun yaraScannerBridge_detectsExploitSignatures() {
        val bridge = YaraScannerBridge()

        // Clean temp file
        val cleanFile = File.createTempFile("test_clean", ".tmp")
        cleanFile.writeBytes("Normal file text content with no exploits.".toByteArray())
        val cleanResult = bridge.scanFile(cleanFile)
        assertTrue(cleanResult.isClean)
        cleanFile.delete()

        // Exploit file with NOP sled
        val exploitFile = File.createTempFile("test_exploit", ".tmp")
        val maliciousPayload = byteArrayOf(
            0x90.toByte(), 0x90.toByte(), 0x90.toByte(), 0x90.toByte(),
            0x90.toByte(), 0x90.toByte(), 0x90.toByte(), 0x90.toByte(),
            0xCC.toByte()
        )
        exploitFile.writeBytes(maliciousPayload)
        val exploitResult = bridge.scanFile(exploitFile)
        assertFalse(exploitResult.isClean)
        assertNotNull(exploitResult.ruleName)
        assertEquals("Suspicious.NopSled.Exploit", exploitResult.ruleName)
        exploitFile.delete()
    }

    @Test
    fun swarmBitfield_bitmapOperationsAndSerialization() {
        val bitfield = SwarmBitfield(totalChunks = 16)
        assertFalse(bitfield.isComplete())
        assertEquals(0, bitfield.completedCount())
        assertEquals(16, bitfield.getMissingChunks().size)

        // Mark chunks 0, 5, 15
        bitfield.markAvailable(0)
        bitfield.markAvailable(5)
        bitfield.markAvailable(15)

        assertTrue(bitfield.isAvailable(0))
        assertTrue(bitfield.isAvailable(5))
        assertTrue(bitfield.isAvailable(15))
        assertFalse(bitfield.isAvailable(1))
        assertEquals(3, bitfield.completedCount())

        // Serialization roundtrip
        val serialized = bitfield.serialize()
        val restored = SwarmBitfield.deserialize(16, serialized)

        assertTrue(restored.isAvailable(0))
        assertTrue(restored.isAvailable(5))
        assertTrue(restored.isAvailable(15))
        assertFalse(restored.isAvailable(1))
        assertEquals(3, restored.completedCount())

        // Complete all chunks
        for (i in 0 until 16) {
            bitfield.markAvailable(i)
        }
        assertTrue(bitfield.isComplete())
        assertEquals(0, bitfield.getMissingChunks().size)
    }

    @Test
    fun swarmConstants_conformToSpecification() {
        assertEquals(65536, SwarmConstants.CHUNK_SIZE)
        assertEquals(2L * 1024L * 1024L * 1024L, SwarmConstants.MAX_CACHE_BYTES)
        assertEquals(1536L * 1024L * 1024L, SwarmConstants.PRUNE_TARGET_BYTES)
    }
}
