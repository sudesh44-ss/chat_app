package com.nexa.app.core.security

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

sealed interface FileTypeResult {
    data class Valid(val mimeType: String, val canonicalExtension: String) : FileTypeResult
    data class Spoofed(val declaredExt: String, val detectedMime: String, val reason: String) : FileTypeResult
    data class DangerousExecutable(val detectedType: String, val reason: String) : FileTypeResult
    data class PolyglotDetected(val primaryType: String, val secondaryType: String, val reason: String) : FileTypeResult
    data class Corrupted(val reason: String) : FileTypeResult
}

sealed interface DetectionResult {
    data class Verified(val mimeType: String) : DetectionResult
    data class Blocked(val reason: String, val threatCategory: ThreatCategory) : DetectionResult
}

/**
 * Pure-Kotlin, high-performance file signature and container inspector.
 * Operates without native JNI dependencies (avoiding libmagic overhead/bloat).
 * Rejects extension spoofing, disguised binaries, and polyglot payloads.
 */
object FileTypeDetector {

    // Magic Byte Signatures
    val SIGNATURE_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    val SIGNATURE_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val SIGNATURE_GIF = byteArrayOf(0x47, 0x49, 0x46, 0x38) // GIF8
    val SIGNATURE_PDF = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF
    val SIGNATURE_WEBP_RIFF = byteArrayOf(0x52, 0x49, 0x46, 0x46) // RIFF
    val SIGNATURE_WEBP_TAG = byteArrayOf(0x57, 0x45, 0x42, 0x50)  // WEBP (at offset 8)
    val SIGNATURE_ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)       // PK\x03\x04
    val SIGNATURE_GZIP = byteArrayOf(0x1F.toByte(), 0x8B.toByte())
    val SIGNATURE_7Z = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C) // 7z\xBC\xAF\x27\x1C
    val SIGNATURE_FLAC = byteArrayOf(0x66, 0x4C, 0x61, 0x43)      // fLaC
    val SIGNATURE_OGG = byteArrayOf(0x4F, 0x67, 0x67, 0x53)       // OggS
    val SIGNATURE_MP4_FTYP = byteArrayOf(0x66, 0x74, 0x79, 0x70)  // ftyp (at offset 4)
    val SIGNATURE_MKV = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) // EBML (MKV/WebM)

    // Dangerous Executable Signatures
    val SIGNATURE_DEX = byteArrayOf(0x64, 0x65, 0x78, 0x0A)       // dex\n
    val SIGNATURE_PE_MZ = byteArrayOf(0x4D, 0x5A)                 // MZ (Windows PE Executable)
    val SIGNATURE_ELF = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)       // .ELF (Linux Binary)
    val SIGNATURE_SHEBANG = byteArrayOf(0x23, 0x21)               // #! (Shell Script)

    // Strictly banned executable file extensions
    val BANNED_EXTENSIONS = setOf(
        "apk", "dex", "so", "exe", "sh", "bat", "cmd", "js", "vbs", "html", "htm", "svg",
        "jar", "bin", "elf", "scr", "msi", "com", "ps1", "vbe", "wsf", "wsh", "dll"
    )

    fun detectType(file: File, declaredExtension: String): DetectionResult {
        return when (val res = inspectFile(file, declaredExtension)) {
            is FileTypeResult.Valid -> DetectionResult.Verified(res.mimeType)
            is FileTypeResult.DangerousExecutable -> DetectionResult.Blocked(res.reason, ThreatCategory.BANNED_EXECUTABLE)
            is FileTypeResult.Spoofed -> DetectionResult.Blocked(res.reason, ThreatCategory.MIME_SPOOFING)
            is FileTypeResult.PolyglotDetected -> DetectionResult.Blocked(res.reason, ThreatCategory.BANNED_EXECUTABLE)
            is FileTypeResult.Corrupted -> DetectionResult.Blocked(res.reason, ThreatCategory.CORRUPTED_STREAM)
        }
    }

    fun isExecutableHeader(header: ByteArray): Boolean {
        return matches(header, SIGNATURE_DEX) ||
                matches(header, SIGNATURE_PE_MZ) ||
                matches(header, SIGNATURE_ELF) ||
                matches(header, SIGNATURE_SHEBANG)
    }

    fun inspectFile(file: File, declaredExtension: String): FileTypeResult {
        if (!file.exists() || file.length() == 0L) {
            return FileTypeResult.Corrupted("File does not exist or is empty")
        }
        return FileInputStream(file).use { inspectStream(it, declaredExtension, file.length()) }
    }

    fun inspectStream(stream: InputStream, declaredExtension: String, streamLength: Long = -1L): FileTypeResult {
        val ext = declaredExtension.lowercase().removePrefix(".").trim()

        // 1. Extension Blacklist Gate
        if (BANNED_EXTENSIONS.contains(ext)) {
            return FileTypeResult.DangerousExecutable(
                detectedType = "Banned Extension: .$ext",
                reason = "Dangerous executable file extension .$ext is strictly blocked"
            )
        }

        // 2. Read first 512 bytes for header and polyglot inspection
        val header = ByteArray(512)
        val bytesRead = stream.read(header)
        if (bytesRead < 4) {
            return FileTypeResult.Corrupted("File header is too short (< 4 bytes)")
        }

        // 3. Absolute Executable Header Rejection (Regardless of claimed extension)
        if (matches(header, SIGNATURE_DEX)) {
            return FileTypeResult.DangerousExecutable("Android DEX", "Disguised Android DEX executable binary detected")
        }
        if (matches(header, SIGNATURE_PE_MZ)) {
            return FileTypeResult.DangerousExecutable("Windows PE/EXE", "Disguised Windows PE/EXE executable detected")
        }
        if (matches(header, SIGNATURE_ELF)) {
            return FileTypeResult.DangerousExecutable("Linux ELF", "Disguised Linux ELF binary detected")
        }
        if (matches(header, SIGNATURE_SHEBANG)) {
            return FileTypeResult.DangerousExecutable("Shell Script", "Disguised shell script (#!) detected")
        }

        // 4. Polyglot Attack Detection (e.g. valid JPEG/PNG containing embedded ZIP or DEX in the header block)
        if (matches(header, SIGNATURE_JPEG) || matches(header, SIGNATURE_PNG)) {
            val polyglotZip = findSequence(header, SIGNATURE_ZIP, fromIndex = 16, toIndex = bytesRead)
            if (polyglotZip != -1) {
                return FileTypeResult.PolyglotDetected(
                    primaryType = "Image",
                    secondaryType = "ZIP",
                    reason = "Polyglot payload detected: Archive header embedded inside image container"
                )
            }
            val polyglotDex = findSequence(header, SIGNATURE_DEX, fromIndex = 16, toIndex = bytesRead)
            if (polyglotDex != -1) {
                return FileTypeResult.PolyglotDetected(
                    primaryType = "Image",
                    secondaryType = "Android DEX",
                    reason = "Polyglot payload detected: Android DEX binary embedded inside image container"
                )
            }
        }

        // 5. Anti-Spoofing Gate: Verify true header matches declared extension
        val (detectedMime, isValidMatch) = when (ext) {
            "jpg", "jpeg" -> Pair("image/jpeg", matches(header, SIGNATURE_JPEG))
            "png" -> Pair("image/png", matches(header, SIGNATURE_PNG))
            "pdf" -> Pair("application/pdf", matches(header, SIGNATURE_PDF))
            "webp" -> Pair("image/webp", matches(header, SIGNATURE_WEBP_RIFF) && matchesAtOffset(header, SIGNATURE_WEBP_TAG, 8))
            "gif" -> Pair("image/gif", matches(header, SIGNATURE_GIF))
            "zip" -> Pair("application/zip", matches(header, SIGNATURE_ZIP))
            "gz", "gzip" -> Pair("application/gzip", matches(header, SIGNATURE_GZIP))
            "7z" -> Pair("application/x-7z-compressed", matches(header, SIGNATURE_7Z))
            "mp4", "m4v" -> Pair("video/mp4", matchesAtOffset(header, SIGNATURE_MP4_FTYP, 4))
            "mkv" -> Pair("video/x-matroska", matches(header, SIGNATURE_MKV))
            "webm" -> Pair("video/webm", matches(header, SIGNATURE_MKV))
            "flac" -> Pair("audio/flac", matches(header, SIGNATURE_FLAC))
            "ogg", "opus" -> Pair("audio/ogg", matches(header, SIGNATURE_OGG))
            "txt", "csv", "log", "md" -> {
                // Benign text files must NOT have binary zip, exe, or pdf headers
                val isBinaryHeader = matches(header, SIGNATURE_ZIP) ||
                        matches(header, SIGNATURE_PDF) ||
                        matches(header, SIGNATURE_PNG) ||
                        matches(header, SIGNATURE_JPEG)
                Pair("text/plain", !isBinaryHeader)
            }
            else -> {
                // If extension is unlisted, check if it matches a known format
                Pair("application/octet-stream", true)
            }
        }

        if (!isValidMatch) {
            return FileTypeResult.Spoofed(
                declaredExt = ext,
                detectedMime = detectedMime,
                reason = "MIME Spoofing detected! Declared extension .$ext does not match file header bytes"
            )
        }

        return FileTypeResult.Valid(
            mimeType = detectedMime,
            canonicalExtension = ext
        )
    }

    private fun matches(source: ByteArray, signature: ByteArray): Boolean {
        if (source.size < signature.size) return false
        for (i in signature.indices) {
            if (source[i] != signature[i]) return false
        }
        return true
    }

    private fun matchesAtOffset(source: ByteArray, signature: ByteArray, offset: Int): Boolean {
        if (source.size < offset + signature.size) return false
        for (i in signature.indices) {
            if (source[offset + i] != signature[i]) return false
        }
        return true
    }

    private fun findSequence(source: ByteArray, target: ByteArray, fromIndex: Int, toIndex: Int): Int {
        val max = (toIndex - target.size).coerceAtMost(source.size - target.size)
        for (i in fromIndex..max) {
            var found = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }
}
