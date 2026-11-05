package com.nexa.app.core.security

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

sealed interface ArchiveScanResult {
    data class Safe(val totalEntries: Int, val totalUncompressedBytes: Long) : ArchiveScanResult
    data class Blocked(val reason: String) : ArchiveScanResult
}

sealed interface ArchiveExtractionResult {
    data class Success(val extractedFiles: List<File>, val totalBytes: Long) : ArchiveExtractionResult
    data class Failed(val reason: String) : ArchiveExtractionResult
}

/**
 * Archive & Zip-Bomb Decompression Gate:
 * 1. 1:10 Max Decompression Expansion Ratio Gate
 * 2. Absolute 100 MB Decompression Ceiling
 * 3. Max Recursion Depth: 1 level (Blocks nested archives)
 * 4. Inode / Entry Limit: 1000 entries
 * 5. Zip-Slip (Path Traversal) Protection
 * 6. Internal Executable Binary Block (.apk, .dex, .exe, .sh, etc.)
 */
object ArchiveSanitizer {

    // Security Gate Limits
    const val MAX_COMPRESSION_RATIO = 10L // 1:10 decompression ratio limit
    const val MAX_UNCOMPRESSED_TOTAL_BYTES = 100L * 1024 * 1024 // 100 MB absolute ceiling
    const val MAX_ENTRY_COUNT = 1000 // Inode limit
    const val MAX_SINGLE_FILE_BYTES = 50L * 1024 * 1024 // 50 MB max per single file

    private val NESTED_ARCHIVE_EXTENSIONS = setOf(
        "zip", "tar", "gz", "bz2", "xz", "7z", "rar", "apk", "jar"
    )

    /**
     * Pre-scans the zip archive without extracting it to disk.
     * Checks entry counts, compression ratios, nested archives, executable extensions, and zip-slip attacks.
     */
    fun inspectArchive(archiveFile: File): ArchiveScanResult {
        if (!archiveFile.exists() || archiveFile.length() == 0L) {
            return ArchiveScanResult.Blocked("Archive file does not exist or is empty.")
        }

        val archiveSize = archiveFile.length()
        val maxAllowedUncompressed = (archiveSize * MAX_COMPRESSION_RATIO).coerceAtMost(MAX_UNCOMPRESSED_TOTAL_BYTES)

        var totalEntries = 0
        var totalUncompressedBytes = 0L

        try {
            ZipInputStream(FileInputStream(archiveFile)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    totalEntries++
                    if (totalEntries > MAX_ENTRY_COUNT) {
                        return ArchiveScanResult.Blocked("Zip-Bomb detected: Total entry count exceeds $MAX_ENTRY_COUNT entries.")
                    }

                    val entryName = entry.name
                    // 1. Path traversal check ("Zip Slip")
                    if (entryName.contains("..") || entryName.startsWith("/") || entryName.startsWith("\\")) {
                        return ArchiveScanResult.Blocked("Zip Slip attack detected in entry: $entryName")
                    }

                    val ext = entryName.substringAfterLast('.', "").lowercase()

                    // 2. Block nested archives (Max recursion depth = 1)
                    if (ext in NESTED_ARCHIVE_EXTENSIONS) {
                        return ArchiveScanResult.Blocked("Nested archive detected ($entryName). Archive recursion depth must not exceed 1.")
                    }

                    // 3. Block dangerous executables inside archive
                    if (ext in NexaFileSecurityEngine.BANNED_EXTENSIONS) {
                        return ArchiveScanResult.Blocked("Blocked executable file found inside archive: $entryName")
                    }

                    // 4. Stream entry bytes with byte-counter limit to verify declared size vs real size
                    val buffer = ByteArray(8192)
                    var bytesInEntry = 0L
                    var read: Int
                    while (zis.read(buffer).also { read = it } != -1) {
                        bytesInEntry += read
                        totalUncompressedBytes += read

                        if (bytesInEntry > MAX_SINGLE_FILE_BYTES) {
                            return ArchiveScanResult.Blocked("Decompression limit exceeded: Single file in archive exceeds ${MAX_SINGLE_FILE_BYTES / (1024 * 1024)} MB.")
                        }

                        if (totalUncompressedBytes > maxAllowedUncompressed) {
                            return ArchiveScanResult.Blocked(
                                "Zip-Bomb detected: Decompression ratio exceeds 1:$MAX_COMPRESSION_RATIO (Size: $totalUncompressedBytes bytes)."
                            )
                        }
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            return ArchiveScanResult.Blocked("Corrupted or malformed archive: ${e.message}")
        }

        return ArchiveScanResult.Safe(totalEntries, totalUncompressedBytes)
    }

    /**
     * Safely extracts archive contents to target destination directory with Zip-Slip path validation.
     */
    fun safeExtract(archiveFile: File, destinationDir: File): ArchiveExtractionResult {
        val inspection = inspectArchive(archiveFile)
        if (inspection is ArchiveScanResult.Blocked) {
            return ArchiveExtractionResult.Failed(inspection.reason)
        }

        if (!destinationDir.exists()) {
            destinationDir.mkdirs()
        }

        val canonicalDestDirPath = destinationDir.canonicalPath
        val extractedFiles = mutableListOf<File>()
        var extractedBytes = 0L

        try {
            ZipInputStream(FileInputStream(archiveFile)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val outputFile = File(destinationDir, entry.name)

                    // Zip Slip Validation: ensure outputFile is within destinationDir
                    val canonicalDestFile = outputFile.canonicalPath
                    if (!canonicalDestFile.startsWith(canonicalDestDirPath + File.separator) && canonicalDestFile != canonicalDestDirPath) {
                        return ArchiveExtractionResult.Failed("Zip-Slip path traversal blocked for ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                        FileOutputStream(outputFile).use { fos ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (zis.read(buffer).also { read = it } != -1) {
                                fos.write(buffer, 0, read)
                                extractedBytes += read
                            }
                            fos.flush()
                        }
                        extractedFiles.add(outputFile)
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            return ArchiveExtractionResult.Failed("Extraction error: ${e.message}")
        }

        return ArchiveExtractionResult.Success(extractedFiles, extractedBytes)
    }
}
