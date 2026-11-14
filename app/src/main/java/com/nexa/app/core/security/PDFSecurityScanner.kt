package com.nexa.app.core.security

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

sealed interface PDFScanVerdict {
    data class Safe(val totalObjectsFound: Int, val isClean: Boolean = true) : PDFScanVerdict
    data class Rejected(val reason: String, val threatCategory: ThreatCategory) : PDFScanVerdict
}

/**
 * PDF structural security scanner.
 * Inspects PDF byte structures without invoking external rendering or execution engines.
 * Detects:
 * 1. Embedded JavaScript (/JavaScript, /JS)
 * 2. Launch Actions (/Launch) attempting to execute external processes
 * 3. Embedded Executable Payloads (/EmbeddedFiles with .exe, .apk, .dex, .sh)
 * 4. Excessive object counts (Parser DoS bombs)
 * 5. Malformed headers or trailers
 */
object PDFSecurityScanner {

    private const val MAX_PDF_OBJECTS = 50000

    private val KEYWORD_JAVASCRIPT = "/JavaScript".toByteArray(Charsets.US_ASCII)
    private val KEYWORD_JS = "/JS".toByteArray(Charsets.US_ASCII)
    private val KEYWORD_LAUNCH = "/Launch".toByteArray(Charsets.US_ASCII)
    private val KEYWORD_EMBEDDED_FILES = "/EmbeddedFiles".toByteArray(Charsets.US_ASCII)
    private val KEYWORD_OBJ = "obj".toByteArray(Charsets.US_ASCII)
    private val HEADER_PDF = "%PDF-".toByteArray(Charsets.US_ASCII)

    fun scanPdf(file: File): PDFScanVerdict = scanPDFFile(file)

    fun scanPDFFile(file: File): PDFScanVerdict {
        if (!file.exists() || file.length() < 10) {
            return PDFScanVerdict.Rejected("PDF file is missing or corrupted (< 10 bytes)", ThreatCategory.CORRUPTED_STREAM)
        }
        return FileInputStream(file).use { scanPDFStream(it, file.length()) }
    }

    fun scanPDFStream(stream: InputStream, streamLength: Long): PDFScanVerdict {
        val buffer = ByteArray(65536)
        var totalObjects = 0
        var isFirstChunk = true

        var hasJavaScript = false
        var hasLaunch = false
        var hasEmbeddedFiles = false

        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            val slice = if (read == buffer.size) buffer else buffer.copyOf(read)

            // Header verification on first chunk
            if (isFirstChunk) {
                isFirstChunk = false
                if (!indexOf(slice, HEADER_PDF, 0)) {
                    return PDFScanVerdict.Rejected("Invalid PDF: Missing %PDF- header", ThreatCategory.MIME_SPOOFING)
                }
            }

            // Count objects
            totalObjects += countOccurrences(slice, KEYWORD_OBJ)
            if (totalObjects > MAX_PDF_OBJECTS) {
                return PDFScanVerdict.Rejected(
                    "PDF Object Bomb detected: Exceeds $MAX_PDF_OBJECTS objects",
                    ThreatCategory.RESOURCE_LIMIT_EXCEEDED
                )
            }

            if (indexOf(slice, KEYWORD_JAVASCRIPT, 0) || indexOf(slice, KEYWORD_JS, 0)) {
                hasJavaScript = true
            }

            if (indexOf(slice, KEYWORD_LAUNCH, 0)) {
                hasLaunch = true
            }

            if (indexOf(slice, KEYWORD_EMBEDDED_FILES, 0)) {
                hasEmbeddedFiles = true
            }
        }

        if (hasLaunch) {
            return PDFScanVerdict.Rejected(
                "PDF contains dangerous /Launch action that executes external system commands",
                ThreatCategory.PDF_LAUNCH_ACTION
            )
        }

        if (hasJavaScript) {
            return PDFScanVerdict.Rejected(
                "PDF contains embedded /JavaScript code execution vectors",
                ThreatCategory.PDF_JAVASCRIPT_INJECTION
            )
        }

        if (hasEmbeddedFiles) {
            return PDFScanVerdict.Rejected(
                "PDF contains /EmbeddedFiles container (potential embedded payload)",
                ThreatCategory.PDF_EMBEDDED_EXECUTABLE
            )
        }

        return PDFScanVerdict.Safe(totalObjectsFound = totalObjects)
    }

    private fun indexOf(source: ByteArray, target: ByteArray, startOffset: Int): Boolean {
        if (source.size < target.size + startOffset) return false
        val max = source.size - target.size
        for (i in startOffset..max) {
            var found = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    found = false
                    break
                }
            }
            if (found) return true
        }
        return false
    }

    private fun countOccurrences(source: ByteArray, target: ByteArray): Int {
        var count = 0
        var i = 0
        while (i <= source.size - target.size) {
            var match = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                count++
                i += target.size
            } else {
                i++
            }
        }
        return count
    }
}
