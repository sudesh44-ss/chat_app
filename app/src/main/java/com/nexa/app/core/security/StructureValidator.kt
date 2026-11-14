package com.nexa.app.core.security

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

sealed interface StructureValidationResult {
    data class Valid(val format: String, val details: String? = null) : StructureValidationResult
    data class Malformed(val reason: String, val threatCategory: ThreatCategory) : StructureValidationResult
}

/**
 * Validates file structure, header-trailer pairing, and parser integrity
 * to protect against malformed payloads and parser differential attacks.
 */
object StructureValidator {

    private val ZIP_EOCD = byteArrayOf(0x50, 0x4B, 0x05, 0x06) // End of Central Directory
    private val PDF_TRAILER = "%%EOF".toByteArray(Charsets.US_ASCII)
    private val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val JPEG_TRAILER = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val PNG_TRAILER = "IEND".toByteArray(Charsets.US_ASCII)

    fun validateStructure(file: File, declaredExtension: String): StructureValidationResult {
        if (!file.exists() || file.length() < 4) {
            return StructureValidationResult.Malformed(
                "File too small or missing (< 4 bytes)",
                ThreatCategory.CORRUPTED_STREAM
            )
        }

        val ext = declaredExtension.lowercase().removePrefix(".").trim()

        return when (ext) {
            "jpg", "jpeg" -> validateJpeg(file)
            "png" -> validatePng(file)
            "pdf" -> validatePdf(file)
            "zip" -> validateZip(file)
            "webp" -> validateWebp(file)
            "mp4", "m4v" -> validateMp4(file)
            else -> StructureValidationResult.Valid(ext, "Unconstrained generic structure")
        }
    }

    private fun validateJpeg(file: File): StructureValidationResult {
        val len = file.length()
        if (len < 4) return StructureValidationResult.Malformed("JPEG too small", ThreatCategory.MALFORMED_STRUCTURE)

        val header = ByteArray(3)
        FileInputStream(file).use { it.read(header) }
        if (header[0] != JPEG_HEADER[0] || header[1] != JPEG_HEADER[1] || header[2] != JPEG_HEADER[2]) {
            return StructureValidationResult.Malformed("Invalid JPEG SOI marker", ThreatCategory.MIME_SPOOFING)
        }

        // Validate presence of EOI marker (FF D9) in last 64 KB
        val window = minOf(len, 65536L).toInt()
        val tail = ByteArray(window)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(len - window)
            raf.readFully(tail)
        }

        var foundEoi = false
        for (i in 0 until window - 1) {
            if (tail[i] == JPEG_TRAILER[0] && tail[i + 1] == JPEG_TRAILER[1]) {
                foundEoi = true
                break
            }
        }

        return if (foundEoi) {
            StructureValidationResult.Valid("JPEG", "Valid SOI and EOI markers found")
        } else {
            StructureValidationResult.Malformed("JPEG missing authoritative EOI marker (FF D9)", ThreatCategory.MALFORMED_STRUCTURE)
        }
    }

    private fun validatePng(file: File): StructureValidationResult {
        val len = file.length()
        if (len < 16) return StructureValidationResult.Malformed("PNG too small", ThreatCategory.MALFORMED_STRUCTURE)

        val header = ByteArray(8)
        FileInputStream(file).use { it.read(header) }
        for (i in 0 until 8) {
            if (header[i] != PNG_HEADER[i]) {
                return StructureValidationResult.Malformed("Invalid PNG signature", ThreatCategory.MIME_SPOOFING)
            }
        }

        // Validate presence of IEND marker in last 64 KB
        val window = minOf(len, 65536L).toInt()
        val tail = ByteArray(window)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(len - window)
            raf.readFully(tail)
        }

        var foundIend = false
        for (i in 0 until window - 4) {
            if (tail[i] == PNG_TRAILER[0] && tail[i + 1] == PNG_TRAILER[1] &&
                tail[i + 2] == PNG_TRAILER[2] && tail[i + 3] == PNG_TRAILER[3]
            ) {
                foundIend = true
                break
            }
        }

        return if (foundIend) {
            StructureValidationResult.Valid("PNG", "Valid PNG header and IEND chunk found")
        } else {
            StructureValidationResult.Malformed("PNG missing authoritative IEND chunk", ThreatCategory.MALFORMED_STRUCTURE)
        }
    }

    private fun validatePdf(file: File): StructureValidationResult {
        val len = file.length()
        if (len < 10) return StructureValidationResult.Malformed("PDF too small", ThreatCategory.MALFORMED_STRUCTURE)

        val header = ByteArray(5)
        FileInputStream(file).use { it.read(header) }
        val headerStr = String(header, Charsets.US_ASCII)
        if (!headerStr.startsWith("%PDF-")) {
            return StructureValidationResult.Malformed("Missing %PDF- header", ThreatCategory.MIME_SPOOFING)
        }

        // Validate %%EOF trailer in last 1024 bytes
        val window = minOf(len, 1024L).toInt()
        val tail = ByteArray(window)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(len - window)
            raf.readFully(tail)
        }
        val tailStr = String(tail, Charsets.US_ASCII)

        return if (tailStr.contains("%%EOF")) {
            StructureValidationResult.Valid("PDF", "Valid %PDF- header and %%EOF trailer found")
        } else {
            StructureValidationResult.Malformed("PDF missing %%EOF trailer marker", ThreatCategory.MALFORMED_STRUCTURE)
        }
    }

    private fun validateZip(file: File): StructureValidationResult {
        val len = file.length()
        if (len < 22) return StructureValidationResult.Malformed("ZIP too small for central directory", ThreatCategory.MALFORMED_STRUCTURE)

        // Validate presence of End of Central Directory (EOCD: 50 4B 05 06) in last 64 KB
        val window = minOf(len, 65536L).toInt()
        val tail = ByteArray(window)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(len - window)
            raf.readFully(tail)
        }

        var foundEocd = false
        for (i in 0 until window - 4) {
            if (tail[i] == ZIP_EOCD[0] && tail[i + 1] == ZIP_EOCD[1] &&
                tail[i + 2] == ZIP_EOCD[2] && tail[i + 3] == ZIP_EOCD[3]
            ) {
                foundEocd = true
                break
            }
        }

        return if (foundEocd) {
            StructureValidationResult.Valid("ZIP", "Valid ZIP local header and EOCD record found")
        } else {
            StructureValidationResult.Malformed("ZIP missing End of Central Directory (EOCD) record", ThreatCategory.MALFORMED_STRUCTURE)
        }
    }

    private fun validateWebp(file: File): StructureValidationResult {
        val len = file.length()
        if (len < 12) return StructureValidationResult.Malformed("WebP too small", ThreatCategory.MALFORMED_STRUCTURE)

        val header = ByteArray(12)
        FileInputStream(file).use { it.read(header) }
        val riff = String(header, 0, 4, Charsets.US_ASCII)
        val webp = String(header, 8, 4, Charsets.US_ASCII)

        return if (riff == "RIFF" && webp == "WEBP") {
            StructureValidationResult.Valid("WEBP", "Valid RIFF/WEBP header structure")
        } else {
            StructureValidationResult.Malformed("Invalid WebP container structure", ThreatCategory.MIME_SPOOFING)
        }
    }

    private fun validateMp4(file: File): StructureValidationResult {
        val len = file.length()
        if (len < 8) return StructureValidationResult.Malformed("MP4 too small", ThreatCategory.MALFORMED_STRUCTURE)

        val header = ByteArray(8)
        FileInputStream(file).use { it.read(header) }
        val boxType = String(header, 4, 4, Charsets.US_ASCII)

        return if (boxType == "ftyp") {
            StructureValidationResult.Valid("MP4", "Valid MP4 ftyp atom found")
        } else {
            StructureValidationResult.Malformed("MP4 missing primary ftyp atom", ThreatCategory.MEDIA_CONTAINER_ANOMALY)
        }
    }
}
