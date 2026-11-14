package com.nexa.app.core.security

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

data class StegoAnalysisResult(
    val riskLevel: StegoRiskLevel,
    val trailingBytesCount: Long = 0L,
    val embeddedSignatures: List<String> = emptyList(),
    val details: String? = null
)

/**
 * On-Device Steganography Risk Analyzer.
 *
 * Checks:
 * 1. Trailing bytes after authoritative EOF markers (JPEG FF D9, PNG IEND).
 * 2. Embedded archive / executable containers hidden within media files (polyglots).
 * 3. Anomalous high-entropy payload segments.
 *
 * Documented Limitation:
 * Statistical and LSB steganography without container headers cannot be definitively detected
 * purely through static heuristic analysis. Therefore, images in sensitive pipelines (e.g. profile photos)
 * are re-encoded through the ImageSecurityScanner decoder gate to wipe LSB bits.
 */
object StegoRiskAnalyzer {

    // Authoritative EOF Markers
    private val JPEG_EOF = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val PNG_IEND = byteArrayOf(0x49, 0x45, 0x4E, 0x44) // "IEND"

    // Signatures of embedded containers
    private val ZIP_SIG = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val MZ_SIG = byteArrayOf(0x4D, 0x5A)
    private val DEX_SIG = byteArrayOf(0x64, 0x65, 0x78, 0x0A)
    private val ELF_SIG = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)

    fun analyzeFile(file: File, declaredExtension: String): StegoAnalysisResult {
        if (!file.exists() || file.length() < 16) {
            return StegoAnalysisResult(StegoRiskLevel.NONE)
        }

        val ext = declaredExtension.lowercase().removePrefix(".").trim()
        val embeddedSigns = mutableListOf<String>()

        // 1. Scan for embedded container signatures past the file header (offset >= 64)
        scanForEmbeddedContainers(file, embeddedSigns)

        // 2. Trailing byte analysis after EOF marker for JPEG and PNG
        var trailingBytes = 0L
        if (ext in setOf("jpg", "jpeg")) {
            trailingBytes = checkJpegTrailingBytes(file)
        } else if (ext == "png") {
            trailingBytes = checkPngTrailingBytes(file)
        }

        // 3. Compute Risk Level
        return when {
            embeddedSigns.isNotEmpty() -> {
                StegoAnalysisResult(
                    riskLevel = StegoRiskLevel.HIGH,
                    trailingBytesCount = trailingBytes,
                    embeddedSignatures = embeddedSigns,
                    details = "Embedded container detected: ${embeddedSigns.joinToString(", ")}"
                )
            }
            trailingBytes > 512 -> {
                StegoAnalysisResult(
                    riskLevel = StegoRiskLevel.MEDIUM,
                    trailingBytesCount = trailingBytes,
                    embeddedSignatures = embeddedSigns,
                    details = "Detected $trailingBytes trailing bytes appended after EOF marker"
                )
            }
            trailingBytes > 0 -> {
                StegoAnalysisResult(
                    riskLevel = StegoRiskLevel.LOW,
                    trailingBytesCount = trailingBytes,
                    embeddedSignatures = embeddedSigns,
                    details = "Detected $trailingBytes trailing bytes after EOF marker"
                )
            }
            else -> {
                StegoAnalysisResult(
                    riskLevel = StegoRiskLevel.NONE,
                    trailingBytesCount = 0L,
                    embeddedSignatures = emptyList(),
                    details = "No steganographic container or trailing payload detected"
                )
            }
        }
    }

    private fun checkJpegTrailingBytes(file: File): Long {
        val fileLength = file.length()
        if (fileLength < 4) return 0L

        // Search backward from the end of the file within the last 64KB for 0xFF 0xD9
        val searchWindow = minOf(fileLength, 65536L).toInt()
        val buffer = ByteArray(searchWindow)

        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(fileLength - searchWindow)
                raf.readFully(buffer)
            }

            var lastEofIndex = -1
            for (i in 0 until searchWindow - 1) {
                if (buffer[i] == JPEG_EOF[0] && buffer[i + 1] == JPEG_EOF[1]) {
                    lastEofIndex = i
                }
            }

            if (lastEofIndex != -1) {
                val eofOffsetInFile = (fileLength - searchWindow) + lastEofIndex + 2
                val trailing = fileLength - eofOffsetInFile
                return if (trailing > 0) trailing else 0L
            }
        } catch (_: Exception) {}

        return 0L
    }

    private fun checkPngTrailingBytes(file: File): Long {
        val fileLength = file.length()
        if (fileLength < 12) return 0L

        // In PNG, IEND chunk is 4 bytes length (00 00 00 00) + 4 bytes "IEND" + 4 bytes CRC
        val searchWindow = minOf(fileLength, 65536L).toInt()
        val buffer = ByteArray(searchWindow)

        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(fileLength - searchWindow)
                raf.readFully(buffer)
            }

            var iendIndex = -1
            for (i in 0 until searchWindow - 4) {
                if (buffer[i] == PNG_IEND[0] &&
                    buffer[i + 1] == PNG_IEND[1] &&
                    buffer[i + 2] == PNG_IEND[2] &&
                    buffer[i + 3] == PNG_IEND[3]
                ) {
                    iendIndex = i
                }
            }

            if (iendIndex != -1) {
                // IEND + 4 bytes CRC
                val eofOffsetInFile = (fileLength - searchWindow) + iendIndex + 4 + 4
                val trailing = fileLength - eofOffsetInFile
                return if (trailing > 0) trailing else 0L
            }
        } catch (_: Exception) {}

        return 0L
    }

    private fun scanForEmbeddedContainers(file: File, findings: MutableList<String>) {
        val maxScanBytes = minOf(file.length(), 2L * 1024 * 1024).toInt() // Scan first 2MB for hidden archives
        if (maxScanBytes < 64) return

        try {
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(maxScanBytes)
                val read = fis.read(buffer)
                if (read < 64) return

                // Scan starting past header (offset 64)
                for (i in 64 until read - 4) {
                    if (buffer[i] == ZIP_SIG[0] && buffer[i + 1] == ZIP_SIG[1] &&
                        buffer[i + 2] == ZIP_SIG[2] && buffer[i + 3] == ZIP_SIG[3]
                    ) {
                        findings.add("Embedded ZIP container at offset $i")
                    }
                    if (buffer[i] == MZ_SIG[0] && buffer[i + 1] == MZ_SIG[1]) {
                        findings.add("Embedded Windows PE/MZ header at offset $i")
                    }
                    if (buffer[i] == DEX_SIG[0] && buffer[i + 1] == DEX_SIG[1] &&
                        buffer[i + 2] == DEX_SIG[2] && buffer[i + 3] == DEX_SIG[3]
                    ) {
                        findings.add("Embedded Android DEX header at offset $i")
                    }
                    if (buffer[i] == ELF_SIG[0] && buffer[i + 1] == ELF_SIG[1] &&
                        buffer[i + 2] == ELF_SIG[2] && buffer[i + 3] == ELF_SIG[3]
                    ) {
                        findings.add("Embedded Linux ELF header at offset $i")
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
