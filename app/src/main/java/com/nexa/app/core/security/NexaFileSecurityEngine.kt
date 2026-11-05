package com.nexa.app.core.security

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

sealed class ValidationResult {
    data object Safe : ValidationResult()
    data class Blocked(val reason: String) : ValidationResult()
}

/**
 * On-Device File Security Engine:
 * 1. Magic Bytes & File Signature Verification (Never blindly trusts extensions)
 * 2. MIME Spoofing & Disguised Container Detection
 * 3. Banned Dangerous Executables & Shellcode Detection
 */
object NexaFileSecurityEngine {

    // Magic Bytes Signatures (File headers)
    val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
    val PDF_HEADER = byteArrayOf(0x25.toByte(), 0x50.toByte(), 0x44.toByte(), 0x46.toByte()) // %PDF
    val WEBP_RIFF = byteArrayOf(0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte()) // RIFF
    val WEBP_MAGIC = byteArrayOf(0x57.toByte(), 0x45.toByte(), 0x42.toByte(), 0x50.toByte()) // WEBP
    val GIF_HEADER = byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte()) // GIF8
    val ZIP_HEADER = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte()) // PK\x03\x04
    val MP4_FTYP = byteArrayOf(0x66.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte()) // ftyp

    // Executable / Binary Signatures for active detection regardless of claimed extension
    val DEX_HEADER = byteArrayOf(0x64.toByte(), 0x65.toByte(), 0x78.toByte(), 0x0A.toByte()) // dex\n
    val PE_MZ_HEADER = byteArrayOf(0x4D.toByte(), 0x5A.toByte()) // MZ (Windows PE Executable)
    val ELF_HEADER = byteArrayOf(0x7F.toByte(), 0x45.toByte(), 0x4C.toByte(), 0x46.toByte()) // .ELF (Linux Binary)
    val SHEBANG_HEADER = byteArrayOf(0x23.toByte(), 0x21.toByte()) // #! (Shell Script)

    // Block dangerous executable extensions completely
    val BANNED_EXTENSIONS = setOf(
        "apk", "dex", "so", "exe", "sh", "bat", "cmd", "js", "vbs", "html", "htm", "svg",
        "jar", "bin", "elf", "scr", "msi", "com", "ps1", "vbe", "wsf", "wsh", "dll"
    )

    fun validateFileIntegrity(file: File, claimedExtension: String): ValidationResult {
        if (!file.exists()) {
            return ValidationResult.Blocked("File does not exist.")
        }
        if (file.length() < 4) {
            return ValidationResult.Blocked("File corrupted or too small (< 4 bytes).")
        }

        // 1. Extension Sanity Check
        val ext = claimedExtension.lowercase().removePrefix(".").trim()
        if (BANNED_EXTENSIONS.contains(ext)) {
            return ValidationResult.Blocked("Dangerous executable file type is not allowed: .$ext")
        }

        // 2. Read first 32 bytes for magic signature inspection
        val header = ByteArray(32)
        val bytesRead = try {
            FileInputStream(file).use { fis -> fis.read(header) }
        } catch (e: Exception) {
            return ValidationResult.Blocked("Failed to read file header: ${e.message}")
        }

        if (bytesRead < 4) {
            return ValidationResult.Blocked("File corrupted or unreadable.")
        }

        // 3. Absolute Executable Injection Block (Even if disguised as .jpg, .png, etc.)
        if (matchesSignature(header, DEX_HEADER)) {
            return ValidationResult.Blocked("Disguised Android DEX executable detected.")
        }
        if (matchesSignature(header, PE_MZ_HEADER)) {
            return ValidationResult.Blocked("Disguised Windows PE/EXE executable detected.")
        }
        if (matchesSignature(header, ELF_HEADER)) {
            return ValidationResult.Blocked("Disguised Linux ELF executable detected.")
        }
        if (matchesSignature(header, SHEBANG_HEADER)) {
            return ValidationResult.Blocked("Disguised shell script (#!) detected.")
        }

        // 4. Verify real signature against claimed extension (Anti-Spoofing Gate)
        val isHeaderValid = when (ext) {
            "jpg", "jpeg" -> matchesSignature(header, JPEG_HEADER)
            "png" -> matchesSignature(header, PNG_HEADER)
            "pdf" -> matchesSignature(header, PDF_HEADER)
            "webp" -> matchesSignature(header, WEBP_RIFF) && matchesAtOffset(header, WEBP_MAGIC, 8)
            "gif" -> matchesSignature(header, GIF_HEADER)
            "mp4", "m4v" -> matchesAtOffset(header, MP4_FTYP, 4)
            "zip" -> matchesSignature(header, ZIP_HEADER)
            else -> {
                // If it claims to be a benign text/doc but contains a ZIP header
                if (matchesSignature(header, ZIP_HEADER) && ext in setOf("txt", "log", "csv")) {
                    false
                } else {
                    true
                }
            }
        }

        if (!isHeaderValid) {
            return ValidationResult.Blocked("MIME Spoofing detected! Extension .$ext does not match file bytes.")
        }

        return ValidationResult.Safe
    }

    fun validateStreamIntegrity(stream: InputStream, claimedExtension: String): ValidationResult {
        val header = ByteArray(32)
        val bytesRead = stream.read(header)
        if (bytesRead < 4) return ValidationResult.Blocked("Stream too short or empty.")

        val ext = claimedExtension.lowercase().removePrefix(".").trim()
        if (BANNED_EXTENSIONS.contains(ext)) {
            return ValidationResult.Blocked("Dangerous executable file type is not allowed: .$ext")
        }

        if (matchesSignature(header, DEX_HEADER)) return ValidationResult.Blocked("Disguised Android DEX executable detected.")
        if (matchesSignature(header, PE_MZ_HEADER)) return ValidationResult.Blocked("Disguised Windows PE/EXE executable detected.")
        if (matchesSignature(header, ELF_HEADER)) return ValidationResult.Blocked("Disguised Linux ELF executable detected.")
        if (matchesSignature(header, SHEBANG_HEADER)) return ValidationResult.Blocked("Disguised shell script (#!) detected.")

        val isHeaderValid = when (ext) {
            "jpg", "jpeg" -> matchesSignature(header, JPEG_HEADER)
            "png" -> matchesSignature(header, PNG_HEADER)
            "pdf" -> matchesSignature(header, PDF_HEADER)
            "webp" -> matchesSignature(header, WEBP_RIFF) && matchesAtOffset(header, WEBP_MAGIC, 8)
            "gif" -> matchesSignature(header, GIF_HEADER)
            "mp4", "m4v" -> matchesAtOffset(header, MP4_FTYP, 4)
            "zip" -> matchesSignature(header, ZIP_HEADER)
            else -> true
        }

        return if (isHeaderValid) ValidationResult.Safe else ValidationResult.Blocked("MIME Spoofing detected! Extension .$ext does not match file bytes.")
    }

    private fun matchesSignature(source: ByteArray, signature: ByteArray): Boolean {
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
}
