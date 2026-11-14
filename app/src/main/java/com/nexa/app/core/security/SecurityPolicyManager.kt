package com.nexa.app.core.security

/**
 * Central security policy rules for NEXA's Zero-Trust Local File Pipeline.
 */
object SecurityPolicyManager {

    val BANNED_EXTENSIONS = setOf(
        "apk", "dex", "so", "exe", "sh", "bat", "cmd", "js", "vbs", "html", "htm", "svg",
        "jar", "bin", "elf", "scr", "msi", "com", "ps1", "vbe", "wsf", "wsh", "dll", "deb", "rpm"
    )

    val ALLOWED_MEDIA_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "mp4", "m4v", "mkv", "webm",
        "mp3", "flac", "ogg", "opus", "wav", "aac"
    )

    val ALLOWED_DOCUMENT_EXTENSIONS = setOf(
        "pdf", "txt", "csv", "log", "zip"
    )

    const val MAX_FILE_SIZE_BYTES = 1024L * 1024 * 1024 // 1 GB
    const val MAX_IMAGE_DIMENSION_PX = 8192
    const val MAX_IMAGE_MEGAPIXELS = 33.5
    const val MAX_ARCHIVE_ENTRIES = 1000
    const val MAX_ARCHIVE_DECOMPRESSED_BYTES = 100L * 1024 * 1024 // 100 MB
    const val MAX_DECOMPRESSION_RATIO = 10 // 1:10 ratio limit

    fun isExtensionBanned(extension: String): Boolean {
        val clean = extension.lowercase().removePrefix(".").trim()
        return clean in BANNED_EXTENSIONS
    }

    fun isFormatSupported(extension: String): Boolean {
        val clean = extension.lowercase().removePrefix(".").trim()
        if (isExtensionBanned(clean)) return false
        return clean in ALLOWED_MEDIA_EXTENSIONS || clean in ALLOWED_DOCUMENT_EXTENSIONS
    }
}
