package com.nexa.app.core.security

import java.io.InputStream

enum class SecurityVerdict {
    CLEAN,
    SPOOFED_EXTENSION,
    DANGEROUS_EXECUTABLE
}

/**
 * Gate 1 & Gate 2 Security Inspector: Inspects first 32 bytes against declared MIME extensions.
 * Never trusts file extensions blindly. Prevents executable spoofing and malware delivery.
 */
object MagicByteDetector {

    // Magic byte signatures
    private val SIGNATURE_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val SIGNATURE_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val SIGNATURE_GIF = byteArrayOf(0x47, 0x49, 0x46, 0x38) // GIF8
    private val SIGNATURE_PDF = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF
    private val SIGNATURE_DEX = byteArrayOf(0x64, 0x65, 0x78, 0x0A) // dex\n (Android Binary)
    private val SIGNATURE_MZ  = byteArrayOf(0x4D, 0x5A)             // MZ (Windows Executable PE)
    private val SIGNATURE_ELF = byteArrayOf(0x7F, 0x45, 0x4C, 0x46) // .ELF (Linux Binary)

    fun inspect(inputStream: InputStream, declaredExtension: String): SecurityVerdict {
        val header = ByteArray(32)
        val bytesRead = inputStream.read(header)
        if (bytesRead < 2) return SecurityVerdict.CLEAN

        val ext = declaredExtension.lowercase().removePrefix(".").trim()

        val isExecutable = matches(header, SIGNATURE_DEX) ||
                matches(header, SIGNATURE_MZ) ||
                matches(header, SIGNATURE_ELF)

        // 1. Direct Executable Threat Check
        if (isExecutable) {
            if (ext != "apk") {
                return if (ext in listOf("png", "jpg", "jpeg", "gif", "pdf", "txt", "mp4", "mp3")) {
                    SecurityVerdict.SPOOFED_EXTENSION
                } else {
                    SecurityVerdict.DANGEROUS_EXECUTABLE
                }
            }
        }

        // 2. Anti-Spoofing Checks for Media & Documents
        when (ext) {
            "png" -> if (!matches(header, SIGNATURE_PNG)) return SecurityVerdict.SPOOFED_EXTENSION
            "jpg", "jpeg" -> if (!matches(header, SIGNATURE_JPEG)) return SecurityVerdict.SPOOFED_EXTENSION
            "gif" -> if (!matches(header, SIGNATURE_GIF)) return SecurityVerdict.SPOOFED_EXTENSION
            "pdf" -> if (!matches(header, SIGNATURE_PDF)) return SecurityVerdict.SPOOFED_EXTENSION
        }

        return SecurityVerdict.CLEAN
    }

    private fun matches(source: ByteArray, signature: ByteArray): Boolean {
        if (source.size < signature.size) return false
        for (i in signature.indices) {
            if (source[i] != signature[i]) return false
        }
        return true
    }
}
