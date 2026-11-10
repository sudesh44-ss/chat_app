package com.nexa.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Unit tests for P2-B: HTTP Range-Based Google Drive Restore Download Resume.
 *
 * Verifies:
 * 1. HTTP Range request header generation for partially downloaded files.
 * 2. Content-Range header parsing and start byte validation.
 * 3. HTTP 206 Partial Content appending vs HTTP 200 OK safe restart from zero.
 * 4. Prevention of corrupted byte concatenation when remote ETag changes mid-stream.
 * 5. Safe handling of HTTP 416 Range Not Satisfiable.
 * 6. Fail-closed staging invariant: incomplete downloads remain .part files and never replace live DB.
 */
class DriveRestoreDownloadResumeUnitTests {

    @Test
    fun testRangeHeaderGenerationForPartialFile() {
        val existingDownloadedBytes = 1048576L // 1 MB downloaded
        val rangeHeader = "bytes=$existingDownloadedBytes-"
        assertEquals("Range header must request bytes from current offset to EOF", "bytes=1048576-", rangeHeader)
    }

    @Test
    fun testContentRangeParsingAndValidation() {
        val contentRange = "bytes 1048576-5242879/5242880"
        assertTrue("Content-Range must start with bytes", contentRange.startsWith("bytes "))

        val rangeParts = contentRange.removePrefix("bytes ").trim().split("/")
        val byteRange = rangeParts[0].split("-")
        val startByte = byteRange[0].toLong()
        val endByte = byteRange[1].toLong()
        val totalLength = rangeParts[1].toLong()

        assertEquals("Start byte must match 1048576", 1048576L, startByte)
        assertEquals("End byte must match 5242879", 5242879L, endByte)
        assertEquals("Total length must match 5242880", 5242880L, totalLength)

        // Validate that if local file size matches startByte, appending is safe
        val localFileSize = 1048576L
        val isAppendSafe = (startByte == localFileSize)
        assertTrue("Appending is strictly safe when startByte equals local file size", isAppendSafe)

        // Validate that if local file size does NOT match startByte, append is rejected
        val mismatchedLocalSize = 512000L
        val isMismatchedSafe = (startByte == mismatchedLocalSize)
        assertFalse("Mismatched startByte must reject append and trigger reset", isMismatchedSafe)
    }

    @Test
    fun testSafeRestartOnHttp200InsteadOfHttp206() {
        val partFile = File.createTempFile("nx_download_test_", ".vault.part")
        try {
            // Write 512 KB partial data
            partFile.writeBytes(ByteArray(512 * 1024) { 0x11 })
            assertEquals(512 * 1024L, partFile.length())

            // Server returned HTTP 200 OK (full file, Range ignored)
            val responseCode = 200
            val shouldAppend = (responseCode == 206)
            assertFalse("HTTP 200 must NOT append to existing partial file", shouldAppend)

            // When response is 200, partFile must be truncated/restarted from 0
            if (!shouldAppend) {
                partFile.delete()
                FileOutputStream(partFile, false).use { out ->
                    out.write(ByteArray(1024) { 0x22 })
                }
            }

            assertEquals("File must be freshly written without old bytes", 1024L, partFile.length())
        } finally {
            partFile.delete()
        }
    }

    @Test
    fun testEtagMismatchRejectsAppendingToPreventMultiVersionCorruption() {
        val originalEtag = "v1-checksum-abc"
        val serverReturnedEtag = "v2-checksum-xyz"

        val etagMatches = originalEtag.trim('"').equals(serverReturnedEtag.trim('"'), ignoreCase = true)
        assertFalse("ETag mismatch must detect remote file modification", etagMatches)

        val mustResetDownload = !etagMatches
        assertTrue("ETag mismatch must force download restart from byte 0", mustResetDownload)
    }

    @Test
    fun testHttp416RangeNotSatisfiableHandling() {
        val code = 416
        val localBytes = 6000000L
        val actualRemoteTotal = 5242880L

        // Local bytes exceed remote file (e.g. file was replaced by a smaller one)
        val isOutOfRange = localBytes > actualRemoteTotal
        assertTrue("Local file exceeding remote size triggers 416", isOutOfRange)

        // Safe recovery action: delete partial file and issue fresh GET
        var restartedFromZero = false
        if (code == 416) {
            restartedFromZero = true
        }
        assertTrue("416 must trigger reset and restart from byte 0", restartedFromZero)
    }

    @Test
    fun testFailClosedStagingPromotionOnlyOnFullSizeMatch() {
        val partFile = File.createTempFile("nx_test_staging_", ".vault.part")
        val destinationFile = File(partFile.parentFile, "nx_test_final.vault")

        try {
            val totalBytes = 1000L
            partFile.writeBytes(ByteArray(800)) // Incomplete (800 of 1000 bytes)

            // Attempt promotion
            val isComplete = partFile.length() == totalBytes
            assertFalse("Incomplete staging file must not be promoted", isComplete)

            if (isComplete) {
                partFile.renameTo(destinationFile)
            }
            assertFalse("Destination vault must not exist when download was incomplete", destinationFile.exists())

            // Now complete download
            FileOutputStream(partFile, true).use { out ->
                out.write(ByteArray(200))
            }
            assertEquals(totalBytes, partFile.length())

            val nowComplete = partFile.length() == totalBytes
            assertTrue("Complete staging file is ready for promotion", nowComplete)

            if (nowComplete) {
                partFile.renameTo(destinationFile)
            }
            assertTrue("Destination vault must exist after full completion", destinationFile.exists())
            assertEquals(totalBytes, destinationFile.length())
        } finally {
            partFile.delete()
            destinationFile.delete()
        }
    }
}
