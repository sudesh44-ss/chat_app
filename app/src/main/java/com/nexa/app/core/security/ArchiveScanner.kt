package com.nexa.app.core.security

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

sealed interface ArchiveScanVerdict {
    data class Safe(val totalEntries: Int, val totalUncompressedBytes: Long) : ArchiveScanVerdict
    data class Blocked(val reason: String, val threatCategory: ThreatCategory) : ArchiveScanVerdict
}

/**
 * Format-specific archive security scanner.
 * Enforces:
 * 1. 1:10 Decompression Ratio Limit (Zip-Bomb defense)
 * 2. 100 MB Absolute Decompression Ceiling
 * 3. 50 MB Maximum Single File Ceiling
 * 4. 1,000 Entries Maximum Limit (Inode exhaustion defense)
 * 5. Zip-Slip (Path Traversal `..`, `/`, `\`) Protection
 * 6. Nested Archive Block (Recursion depth max 1)
 * 7. Executable Binary Block inside archives
 * 8. Duplicate Entry Detection (Parser differential attack defense)
 */
object ArchiveScanner {

    const val MAX_COMPRESSION_RATIO = 10L               // 1:10 ratio
    const val MAX_UNCOMPRESSED_TOTAL_BYTES = 100L * 1024 * 1024 // 100 MB
    const val MAX_SINGLE_FILE_BYTES = 50L * 1024 * 1024         // 50 MB
    const val MAX_ENTRY_COUNT = 1000                    // Inode limit

    private val NESTED_ARCHIVE_EXTENSIONS = setOf(
        "zip", "tar", "gz", "gzip", "bz2", "xz", "7z", "rar", "apk", "jar"
    )

    fun scanArchive(file: File): ArchiveScanVerdict {
        if (!file.exists() || file.length() == 0L) {
            return ArchiveScanVerdict.Blocked("Archive file does not exist or is empty", ThreatCategory.CORRUPTED_STREAM)
        }
        return FileInputStream(file).use { scanArchiveStream(it, file.length(), allowPackageCode = false) }
    }

    fun scanAndroidPackage(file: File): ArchiveScanVerdict {
        if (!FileReleaseClassifier.isApkContainer(file)) {
            return ArchiveScanVerdict.Blocked("APK structure is missing AndroidManifest.xml", ThreatCategory.MALFORMED_STRUCTURE)
        }
        return FileInputStream(file).use { scanArchiveStream(it, file.length(), allowPackageCode = true) }
    }

    fun scanArchiveStream(stream: InputStream, archiveSizeBytes: Long): ArchiveScanVerdict =
        scanArchiveStream(stream, archiveSizeBytes, allowPackageCode = false)

    private fun scanArchiveStream(
        stream: InputStream,
        archiveSizeBytes: Long,
        allowPackageCode: Boolean
    ): ArchiveScanVerdict {
        val maxAllowedUncompressed = if (archiveSizeBytes > 0) {
            (archiveSizeBytes * MAX_COMPRESSION_RATIO).coerceAtMost(MAX_UNCOMPRESSED_TOTAL_BYTES)
        } else {
            MAX_UNCOMPRESSED_TOTAL_BYTES
        }

        var totalEntries = 0
        var totalUncompressedBytes = 0L
        val seenEntryNames = HashSet<String>()

        try {
            ZipInputStream(stream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    totalEntries++
                    if (totalEntries > MAX_ENTRY_COUNT) {
                        return ArchiveScanVerdict.Blocked(
                            "Zip-Bomb detected: Archive exceeds entry limit of $MAX_ENTRY_COUNT files",
                            ThreatCategory.ZIP_BOMB
                        )
                    }

                    val rawName = entry.name
                    val normalizedName = rawName.replace('\\', '/')

                    // 1. Path Traversal ("Zip Slip") Protection
                    if (normalizedName.contains("..") || normalizedName.startsWith("/") || rawName.contains(":\\")) {
                        return ArchiveScanVerdict.Blocked(
                            "Zip Slip path traversal attack detected in entry: $rawName",
                            ThreatCategory.ZIP_SLIP
                        )
                    }

                    // 2. Duplicate Entry Detection (Differential parser exploitation)
                    if (!seenEntryNames.add(normalizedName.lowercase())) {
                        return ArchiveScanVerdict.Blocked(
                            "Duplicate archive entry detected: $rawName (Potential parser collision attack)",
                            ThreatCategory.MALFORMED_STRUCTURE
                        )
                    }

                    val ext = normalizedName.substringAfterLast('.', "").lowercase()

                    // 3. Nested Archive Rejection (Recursion depth > 1)
                    if (ext in NESTED_ARCHIVE_EXTENSIONS) {
                        return ArchiveScanVerdict.Blocked(
                            "Nested archive rejected: $rawName. Max archive recursion depth is 1",
                            ThreatCategory.NESTED_ARCHIVE
                        )
                    }

                    // 4. Executable Content Block inside Archive
                    val expectedApkCode = allowPackageCode && ext in setOf("dex", "so")
                    if (!expectedApkCode && FileTypeDetector.BANNED_EXTENSIONS.contains(ext)) {
                        return ArchiveScanVerdict.Blocked(
                            "Dangerous executable found inside archive: $rawName",
                            ThreatCategory.ARCHIVE_EXECUTABLE
                        )
                    }

                    // 5. Streaming Byte Counting with Ratio Verification
                    val buffer = ByteArray(8192)
                    var bytesInEntry = 0L
                    var read: Int
                    while (zis.read(buffer).also { read = it } != -1) {
                        bytesInEntry += read
                        totalUncompressedBytes += read

                        if (bytesInEntry > MAX_SINGLE_FILE_BYTES) {
                            return ArchiveScanVerdict.Blocked(
                                "Single file inside archive exceeds ${MAX_SINGLE_FILE_BYTES / (1024 * 1024)} MB: $rawName",
                                ThreatCategory.RESOURCE_LIMIT_EXCEEDED
                            )
                        }

                        if (totalUncompressedBytes > maxAllowedUncompressed) {
                            return ArchiveScanVerdict.Blocked(
                                "Zip-Bomb detected: Decompression ratio exceeds 1:$MAX_COMPRESSION_RATIO ($totalUncompressedBytes bytes)",
                                ThreatCategory.ZIP_BOMB
                            )
                        }
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            return ArchiveScanVerdict.Blocked(
                "Malformed archive or decompression error: ${e.message}",
                ThreatCategory.MALFORMED_STRUCTURE
            )
        }

        return ArchiveScanVerdict.Safe(totalEntries, totalUncompressedBytes)
    }
}
