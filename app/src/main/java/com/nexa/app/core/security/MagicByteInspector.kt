package com.nexa.app.core.security

import java.io.InputStream

sealed interface InspectionVerdict {
    data object Clean : InspectionVerdict
    data class Spoofed(val declaredExt: String, val actualType: String) : InspectionVerdict
    data class DangerousBinary(val detectedType: String) : InspectionVerdict
}

/**
 * Gate 1 & Gate 2 Security Inspector: Inspects first 32 bytes against declared file extensions.
 * Never blindly trusts file extensions; catches hidden executables and spoofed containers.
 */
object MagicByteInspector {

    private val SIG_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val SIG_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val SIG_GIF = byteArrayOf(0x47, 0x49, 0x46, 0x38)       // GIF8
    private val SIG_PDF = byteArrayOf(0x25, 0x50, 0x44, 0x46)       // %PDF
    private val SIG_DEX = byteArrayOf(0x64, 0x65, 0x78, 0x0A)       // dex\n (Android Executable)
    private val SIG_MZ  = byteArrayOf(0x4D, 0x5A)                   // MZ (Windows PE Executable)
    private val SIG_ELF = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)       // .ELF (Linux Binary)
    private val SIG_ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)       // PK.. (ZIP / APK / DOCX)

    fun inspect(inputStream: InputStream, declaredExtension: String): InspectionVerdict {
        val header = ByteArray(32)
        val readCount = inputStream.read(header)
        if (readCount < 2) return InspectionVerdict.Clean

        val ext = declaredExtension.lowercase().removePrefix(".").trim()

        // 1. Direct Executable Injection Check
        if (matches(header, SIG_DEX)) {
            return if (ext == "apk") InspectionVerdict.Clean else InspectionVerdict.DangerousBinary("Android DEX")
        }
        if (matches(header, SIG_MZ)) {
            return InspectionVerdict.DangerousBinary("Windows PE / EXE")
        }
        if (matches(header, SIG_ELF)) {
            return InspectionVerdict.DangerousBinary("Linux ELF Binary")
        }

        // 2. Anti-Spoofing Checks for Archives Disguised as Media / Text
        if ((ext in listOf("jpg", "jpeg", "png", "txt", "mp3", "mp4")) && matches(header, SIG_ZIP)) {
            return InspectionVerdict.Spoofed(ext, "ZIP/Archive Container")
        }

        // 3. Document Anti-Spoofing Check
        if (ext == "pdf" && !matches(header, SIG_PDF)) {
            return InspectionVerdict.Spoofed(ext, "Non-PDF File Header")
        }

        // 4. Image Anti-Spoofing Checks
        if (ext == "png" && !matches(header, SIG_PNG)) {
            return InspectionVerdict.Spoofed(ext, "Non-PNG File Header")
        }
        if ((ext == "jpg" || ext == "jpeg") && !matches(header, SIG_JPEG)) {
            return InspectionVerdict.Spoofed(ext, "Non-JPEG File Header")
        }
        if (ext == "gif" && !matches(header, SIG_GIF)) {
            return InspectionVerdict.Spoofed(ext, "Non-GIF File Header")
        }

        return InspectionVerdict.Clean
    }

    private fun matches(buffer: ByteArray, signature: ByteArray): Boolean {
        if (buffer.size < signature.size) return false
        for (i in signature.indices) {
            if (buffer[i] != signature[i]) return false
        }
        return true
    }
}
