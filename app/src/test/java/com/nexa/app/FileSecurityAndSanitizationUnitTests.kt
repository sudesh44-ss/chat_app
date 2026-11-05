package com.nexa.app

import com.nexa.app.core.security.ArchiveExtractionResult
import com.nexa.app.core.security.ArchiveSanitizer
import com.nexa.app.core.security.ArchiveScanResult
import com.nexa.app.core.security.MediaSanitizer
import com.nexa.app.core.security.NexaFileSecurityEngine
import com.nexa.app.core.security.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileSecurityAndSanitizationUnitTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun fileSecurity_validMagicByteHeadersPass() {
        // 1. Valid JPEG
        val jpegFile = tempFolder.newFile("photo.jpg")
        jpegFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10))
        val jpegResult = NexaFileSecurityEngine.validateFileIntegrity(jpegFile, "jpg")
        assertTrue("Valid JPEG must pass", jpegResult is ValidationResult.Safe)

        // 2. Valid PNG
        val pngFile = tempFolder.newFile("graphic.png")
        pngFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00))
        val pngResult = NexaFileSecurityEngine.validateFileIntegrity(pngFile, "png")
        assertTrue("Valid PNG must pass", pngResult is ValidationResult.Safe)

        // 3. Valid PDF
        val pdfFile = tempFolder.newFile("document.pdf")
        pdfFile.writeBytes("%PDF-1.7\n%Testing header\n".toByteArray(Charsets.US_ASCII))
        val pdfResult = NexaFileSecurityEngine.validateFileIntegrity(pdfFile, "pdf")
        assertTrue("Valid PDF must pass", pdfResult is ValidationResult.Safe)

        // 4. Valid WebP
        val webpFile = tempFolder.newFile("image.webp")
        val webpBytes = ByteArray(16)
        webpBytes[0] = 'R'.code.toByte(); webpBytes[1] = 'I'.code.toByte()
        webpBytes[2] = 'F'.code.toByte(); webpBytes[3] = 'F'.code.toByte()
        webpBytes[8] = 'W'.code.toByte(); webpBytes[9] = 'E'.code.toByte()
        webpBytes[10] = 'B'.code.toByte(); webpBytes[11] = 'P'.code.toByte()
        webpFile.writeBytes(webpBytes)
        val webpResult = NexaFileSecurityEngine.validateFileIntegrity(webpFile, "webp")
        assertTrue("Valid WebP must pass", webpResult is ValidationResult.Safe)

        // 5. Valid GIF
        val gifFile = tempFolder.newFile("anim.gif")
        gifFile.writeBytes("GIF89a\u0001\u0000\u0001\u0000".toByteArray(Charsets.US_ASCII))
        val gifResult = NexaFileSecurityEngine.validateFileIntegrity(gifFile, "gif")
        assertTrue("Valid GIF must pass", gifResult is ValidationResult.Safe)

        // 6. Valid MP4
        val mp4File = tempFolder.newFile("clip.mp4")
        val mp4Bytes = ByteArray(16)
        mp4Bytes[4] = 'f'.code.toByte(); mp4Bytes[5] = 't'.code.toByte()
        mp4Bytes[6] = 'y'.code.toByte(); mp4Bytes[7] = 'p'.code.toByte()
        mp4File.writeBytes(mp4Bytes)
        val mp4Result = NexaFileSecurityEngine.validateFileIntegrity(mp4File, "mp4")
        assertTrue("Valid MP4 must pass", mp4Result is ValidationResult.Safe)
    }

    @Test
    fun fileSecurity_bannedExtensionsStrictlyBlocked() {
        val bannedList = listOf("apk", "exe", "bat", "sh", "so", "dex", "cmd", "js", "vbs", "html", "htm", "svg")

        for (ext in bannedList) {
            val file = tempFolder.newFile("test_payload.$ext")
            file.writeBytes("echo 'Malicious Payload'".toByteArray())
            val result = NexaFileSecurityEngine.validateFileIntegrity(file, ext)
            assertTrue("Extension .$ext must be blocked", result is ValidationResult.Blocked)
            val blocked = result as ValidationResult.Blocked
            assertTrue(blocked.reason.contains("Dangerous executable file type"))
        }
    }

    @Test
    fun fileSecurity_mimeSpoofingBlocked() {
        // Attack 1: Windows PE Executable (starts with MZ) renamed to photo.jpg
        val peDisguisedAsJpg = tempFolder.newFile("vacation.jpg")
        peDisguisedAsJpg.writeBytes(byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00, 0x03, 0x00))
        val peResult = NexaFileSecurityEngine.validateFileIntegrity(peDisguisedAsJpg, "jpg")
        assertTrue("Disguised PE executable must be blocked", peResult is ValidationResult.Blocked)
        assertTrue((peResult as ValidationResult.Blocked).reason.contains("Windows PE/EXE"))

        // Attack 2: Android DEX binary (starts with dex\n) renamed to logo.png
        val dexDisguisedAsPng = tempFolder.newFile("logo.png")
        dexDisguisedAsPng.writeBytes(byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00))
        val dexResult = NexaFileSecurityEngine.validateFileIntegrity(dexDisguisedAsPng, "png")
        assertTrue("Disguised DEX binary must be blocked", dexResult is ValidationResult.Blocked)
        assertTrue((dexResult as ValidationResult.Blocked).reason.contains("Android DEX"))

        // Attack 3: Linux ELF binary (starts with .ELF) renamed to avatar.webp
        val elfDisguisedAsWebp = tempFolder.newFile("avatar.webp")
        elfDisguisedAsWebp.writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00))
        val elfResult = NexaFileSecurityEngine.validateFileIntegrity(elfDisguisedAsWebp, "webp")
        assertTrue("Disguised ELF binary must be blocked", elfResult is ValidationResult.Blocked)
        assertTrue((elfResult as ValidationResult.Blocked).reason.contains("Linux ELF"))

        // Attack 4: Shell script (starts with #!) renamed to receipt.jpg
        val shDisguisedAsJpg = tempFolder.newFile("receipt.jpg")
        shDisguisedAsJpg.writeBytes("#!/bin/bash\nrm -rf /\n".toByteArray())
        val shResult = NexaFileSecurityEngine.validateFileIntegrity(shDisguisedAsJpg, "jpg")
        assertTrue("Disguised shell script must be blocked", shResult is ValidationResult.Blocked)
        assertTrue((shResult as ValidationResult.Blocked).reason.contains("shell script"))

        // Attack 5: Plain text content renamed to image.jpg
        val textDisguisedAsJpg = tempFolder.newFile("notes.jpg")
        textDisguisedAsJpg.writeBytes("This is just plain text content".toByteArray())
        val textResult = NexaFileSecurityEngine.validateFileIntegrity(textDisguisedAsJpg, "jpg")
        assertTrue("MIME spoofing must be blocked", textResult is ValidationResult.Blocked)
        assertTrue((textResult as ValidationResult.Blocked).reason.contains("MIME Spoofing detected"))
    }

    @Test
    fun archiveSanitizer_zipSlipPathTraversalBlocked() {
        val zipFile = tempFolder.newFile("malicious_slip.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // Entry with directory traversal payload
            val entry = ZipEntry("../../system/etc/evil.sh")
            zos.putNextEntry(entry)
            zos.write("malicious binary".toByteArray())
            zos.closeEntry()
        }

        val result = ArchiveSanitizer.inspectArchive(zipFile)
        assertTrue("Zip Slip path traversal must be blocked", result is ArchiveScanResult.Blocked)
        val blocked = result as ArchiveScanResult.Blocked
        assertTrue(blocked.reason.contains("Zip Slip attack detected"))
    }

    @Test
    fun archiveSanitizer_nestedArchiveBlocked() {
        val zipFile = tempFolder.newFile("nested_container.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val nestedEntry = ZipEntry("documents/secret.zip")
            zos.putNextEntry(nestedEntry)
            zos.write("nested zip dummy bytes".toByteArray())
            zos.closeEntry()
        }

        val result = ArchiveSanitizer.inspectArchive(zipFile)
        assertTrue("Nested archive must be blocked (recursion depth limit)", result is ArchiveScanResult.Blocked)
        val blocked = result as ArchiveScanResult.Blocked
        assertTrue(blocked.reason.contains("Nested archive detected"))
    }

    @Test
    fun archiveSanitizer_bannedExecutableInsideArchiveBlocked() {
        val zipFile = tempFolder.newFile("archive_with_exe.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val exeEntry = ZipEntry("payload.exe")
            zos.putNextEntry(exeEntry)
            zos.write("MZ executable payload".toByteArray())
            zos.closeEntry()
        }

        val result = ArchiveSanitizer.inspectArchive(zipFile)
        assertTrue("Executable inside archive must be blocked", result is ArchiveScanResult.Blocked)
        val blocked = result as ArchiveScanResult.Blocked
        assertTrue(blocked.reason.contains("Blocked executable file found inside archive"))
    }

    @Test
    fun archiveSanitizer_decompressionRatioZipBombBlocked() {
        // Create a synthetic zip bomb: highly repetitive data compressed with maximum deflation
        // Ratio > 10:1
        val zipFile = tempFolder.newFile("bomb.zip")
        val highlyRepetitive = ByteArray(150_000) { 'A'.code.toByte() } // 150 KB uncompressed

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)
            val entry = ZipEntry("huge_zeroes.txt")
            zos.putNextEntry(entry)
            zos.write(highlyRepetitive)
            zos.closeEntry()
        }

        // Compressed zip should be < 500 bytes, while uncompressed is 150 KB (> 300x ratio)
        assertTrue(zipFile.length() < 1000)

        val result = ArchiveSanitizer.inspectArchive(zipFile)
        assertTrue("Zip Bomb with > 1:10 ratio must be blocked", result is ArchiveScanResult.Blocked)
        val blocked = result as ArchiveScanResult.Blocked
        assertTrue(blocked.reason.contains("Zip-Bomb detected"))
    }

    @Test
    fun archiveSanitizer_safeArchiveExtractionPasses() {
        val zipFile = tempFolder.newFile("clean_archive.zip")
        val text1 = "Hello World from Nexa Secure P2P"
        val text2 = "Secondary configuration notes"

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val entry1 = ZipEntry("notes/hello.txt")
            zos.putNextEntry(entry1)
            zos.write(text1.toByteArray())
            zos.closeEntry()

            val entry2 = ZipEntry("notes/config.txt")
            zos.putNextEntry(entry2)
            zos.write(text2.toByteArray())
            zos.closeEntry()
        }

        val inspectResult = ArchiveSanitizer.inspectArchive(zipFile)
        assertTrue("Clean archive must pass inspection", inspectResult is ArchiveScanResult.Safe)
        val safe = inspectResult as ArchiveScanResult.Safe
        assertEquals(2, safe.totalEntries)

        // Safe extraction
        val extractDir = tempFolder.newFolder("extracted_safe")
        val extractResult = ArchiveSanitizer.safeExtract(zipFile, extractDir)
        assertTrue("Clean archive extraction must succeed", extractResult is ArchiveExtractionResult.Success)
        val success = extractResult as ArchiveExtractionResult.Success
        assertEquals(2, success.extractedFiles.size)

        val file1 = File(extractDir, "notes/hello.txt")
        assertTrue(file1.exists())
        assertEquals(text1, file1.readText())

        val file2 = File(extractDir, "notes/config.txt")
        assertTrue(file2.exists())
        assertEquals(text2, file2.readText())
    }

    @Test
    fun mediaSanitizer_extensionAndBoundsCheck() {
        assertTrue(MediaSanitizer.isSanitizableImage("jpg"))
        assertTrue(MediaSanitizer.isSanitizableImage("jpeg"))
        assertTrue(MediaSanitizer.isSanitizableImage("png"))
        assertTrue(MediaSanitizer.isSanitizableImage("webp"))
        assertTrue(MediaSanitizer.isSanitizableImage("bmp"))

        assertFalse(MediaSanitizer.isSanitizableImage("exe"))
        assertFalse(MediaSanitizer.isSanitizableImage("apk"))
        assertFalse(MediaSanitizer.isSanitizableImage("zip"))
        assertFalse(MediaSanitizer.isSanitizableImage("pdf"))

        assertEquals(8192, MediaSanitizer.MAX_IMAGE_DIMENSION)
        assertTrue(MediaSanitizer.MAX_IMAGE_PIXELS > 30_000_000L)
    }
}
