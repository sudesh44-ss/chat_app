package com.nexa.app.core.security

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Result of a file security scan across Gate 1 (Sender Pre-Dispatch)
 * and Gate 2 (Receiver Post-Delivery Quarantine).
 */
sealed interface SecurityScanResult {
    data class Safe(val hash: String, val detectedType: String? = null) : SecurityScanResult
    data class Rejected(val reason: String, val threatType: ThreatType = ThreatType.UNKNOWN) : SecurityScanResult
    data class Corrupted(val message: String, val threatType: ThreatType = ThreatType.HASH_MISMATCH) : SecurityScanResult
    data class Inconclusive(val reason: String) : SecurityScanResult
}

/**
 * Categorized threat types for deterministic security audits and UI feedback.
 */
enum class ThreatType {
    BANNED_EXECUTABLE,
    MIME_SPOOFING,
    KNOWN_MALWARE_HASH,
    YARA_SIGNATURE_MATCH,
    ZIP_BOMB_DECOMPRESSION_RATIO,
    ZIP_SLIP_PATH_TRAVERSAL,
    ARCHIVE_NESTED,
    ARCHIVE_EXECUTABLE_CONTENT,
    ARCHIVE_THREAT,
    FILE_TOO_LARGE,
    HASH_MISMATCH,
    CORRUPTED_PAYLOAD,
    UNKNOWN
}

/**
 * High-level Zero-Trust File Security Manager.
 * Orchestrates:
 * 1. File type and magic byte anti-spoofing validation (NexaFileSecurityEngine & MagicByteInspector)
 * 2. File size ceiling checks
 * 3. Static bytecode signature and pattern inspection (LibYARA / YaraNativeBridge)
 * 4. Archive inspection (ArchiveSanitizer: Zip-Bomb ratio, Zip-Slip, nested archives, executables)
 * 5. Cryptographic hash calculation (SHA-256)
 * 6. Dual-Gate coordination (Gate 1 outbound pre-dispatch & Gate 2 inbound quarantine verification)
 */
class FileSecurityManager(
    private val context: Context? = null,
    val threatBloomFilter: ThreatBloomFilter = ThreatBloomFilter(),
    val yaraNativeBridge: YaraNativeBridge = YaraNativeBridge(),
    val dualScanEngine: DualScanEngine = DualScanEngine(context, threatBloomFilter, yaraNativeBridge)
) {

    companion object {
        const val MAX_FILE_SIZE_BYTES = 500L * 1024 * 1024 // 500 MB max transfer ceiling
    }

    /**
     * Validates file size against upper limits.
     */
    fun validateFileSize(fileSizeBytes: Long, maxAllowedBytes: Long = MAX_FILE_SIZE_BYTES): Boolean {
        return fileSizeBytes in 1..maxAllowedBytes
    }

    /**
     * Validates file integrity and anti-spoofing for an on-disk File.
     */
    fun validateFileType(file: File, declaredExtension: String): ValidationResult {
        return NexaFileSecurityEngine.validateFileIntegrity(file, declaredExtension)
    }

    /**
     * Validates file integrity and anti-spoofing for an InputStream.
     */
    fun validateFileType(stream: InputStream, declaredExtension: String): ValidationResult {
        return NexaFileSecurityEngine.validateStreamIntegrity(stream, declaredExtension)
    }

    /**
     * Inspects an archive for Zip-Bombs, Zip-Slip, nested archives, and embedded executables.
     */
    fun validateArchive(archiveFile: File): ArchiveScanResult {
        return ArchiveSanitizer.inspectArchive(archiveFile)
    }

    /**
     * Computes the SHA-256 hex digest for a file on disk.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(16384)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes the SHA-256 hex digest for an open InputStream.
     */
    fun computeSha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16384)
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes the SHA-256 hex digest for a content Uri.
     */
    fun computeSha256(uri: Uri): String {
        val ctx = context ?: throw IllegalStateException("Context is required to resolve Uri")
        val stream = ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Unable to open input stream for Uri: $uri")
        return stream.use { computeSha256(it) }
    }

    /**
     * GATE 1: SENDER PRE-DISPATCH SCAN
     * Executed locally before any chunks or signaling offers are transmitted.
     * Fail-closed: Any security violation halts the transfer immediately.
     */
    suspend fun scanOutbound(
        uri: Uri,
        declaredName: String,
        fileSizeBytes: Long
    ): SecurityScanResult = withContext(Dispatchers.IO) {
        val ext = declaredName.substringAfterLast('.', "").lowercase().removePrefix(".").trim()

        // 1. File Size Ceiling Check
        if (fileSizeBytes <= 0) {
            return@withContext SecurityScanResult.Corrupted(
                message = "File is empty (0 bytes)",
                threatType = ThreatType.CORRUPTED_PAYLOAD
            )
        }
        if (fileSizeBytes > MAX_FILE_SIZE_BYTES) {
            return@withContext SecurityScanResult.Rejected(
                reason = "File size (${fileSizeBytes / (1024 * 1024)} MB) exceeds maximum allowed limit (${MAX_FILE_SIZE_BYTES / (1024 * 1024)} MB)",
                threatType = ThreatType.FILE_TOO_LARGE
            )
        }

        // 2. Extension Blacklist Gate
        if (NexaFileSecurityEngine.BANNED_EXTENSIONS.contains(ext)) {
            return@withContext SecurityScanResult.Rejected(
                reason = "Blocked dangerous executable file extension: .$ext",
                threatType = ThreatType.BANNED_EXECUTABLE
            )
        }

        val ctx = context ?: return@withContext SecurityScanResult.Inconclusive("Inaccessible source context")
        val stream = try {
            ctx.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: return@withContext SecurityScanResult.Corrupted(
            message = "Inaccessible source file: unable to open stream",
            threatType = ThreatType.CORRUPTED_PAYLOAD
        )

        // 3. Magic Bytes & Extension Spoofing Inspection
        val headerVerdict = stream.use { validateFileType(it, ext) }
        if (headerVerdict is ValidationResult.Blocked) {
            val threatType = if (headerVerdict.reason.contains("executable", ignoreCase = true) ||
                headerVerdict.reason.contains("PE/EXE", ignoreCase = true) ||
                headerVerdict.reason.contains("DEX", ignoreCase = true) ||
                headerVerdict.reason.contains("ELF", ignoreCase = true) ||
                headerVerdict.reason.contains("shell script", ignoreCase = true)) {
                ThreatType.BANNED_EXECUTABLE
            } else {
                ThreatType.MIME_SPOOFING
            }
            return@withContext SecurityScanResult.Rejected(
                reason = headerVerdict.reason,
                threatType = threatType
            )
        }

        // 4. Compute Cryptographic SHA-256 Digest
        val hash = try {
            computeSha256(uri)
        } catch (e: Exception) {
            return@withContext SecurityScanResult.Corrupted(
                message = "Failed to compute file digest: ${e.localizedMessage}",
                threatType = ThreatType.CORRUPTED_PAYLOAD
            )
        }

        // 5. Offline Threat Bloom Filter Hash Lookup (O(1))
        if (threatBloomFilter.contains(hash)) {
            return@withContext SecurityScanResult.Rejected(
                reason = "File identified in known malware signature database (SHA-256: $hash)",
                threatType = ThreatType.KNOWN_MALWARE_HASH
            )
        }

        // 6. Native LibYARA Static Inspection (Shellcode / Exploits / Macros)
        val yaraThreat = yaraNativeBridge.scanUri(ctx, uri)
        if (yaraThreat != null) {
            return@withContext SecurityScanResult.Rejected(
                reason = "Matched threat pattern: $yaraThreat",
                threatType = ThreatType.YARA_SIGNATURE_MATCH
            )
        }

        SecurityScanResult.Safe(hash = hash)
    }

    /**
     * GATE 2: RECEIVER POST-DELIVERY QUARANTINE SCAN
     * Executed strictly inside isolated quarantine sandbox on the fully reassembled file
     * before promotion to the safe vault or external exposure.
     */
    suspend fun scanInboundQuarantine(
        tempFile: File,
        expectedHash: String,
        declaredName: String
    ): SecurityScanResult = withContext(Dispatchers.IO) {
        if (!tempFile.exists() || tempFile.length() == 0L) {
            return@withContext SecurityScanResult.Corrupted(
                message = "Quarantined payload is missing or empty",
                threatType = ThreatType.CORRUPTED_PAYLOAD
            )
        }
        if (!validateFileSize(tempFile.length())) {
            return@withContext SecurityScanResult.Inconclusive(
                "Quarantined file exceeds the maximum supported scan size"
            )
        }

        val ext = declaredName.substringAfterLast('.', "").lowercase().removePrefix(".").trim()
        val classification = try {
            FileReleaseClassifier.classify(tempFile, declaredName)
        } catch (e: Exception) {
            return@withContext SecurityScanResult.Inconclusive(
                "Unable to classify received file: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }

        // 1. Cryptographic SHA-256 Integrity Verification
        val actualHash = try {
            computeSha256(tempFile)
        } catch (e: Exception) {
            return@withContext SecurityScanResult.Corrupted(
                message = "Failed to verify quarantined file digest: ${e.localizedMessage}",
                threatType = ThreatType.HASH_MISMATCH
            )
        }

        if (!actualHash.equals(expectedHash, ignoreCase = true)) {
            return@withContext SecurityScanResult.Corrupted(
                message = "Hash mismatch: Expected $expectedHash but got $actualHash (Tampered or incomplete stream)",
                threatType = ThreatType.HASH_MISMATCH
            )
        }

        // 2. Re-Verify Magic Bytes on Reassembled Assembly
        when (classification) {
            FileClassification.APK -> {
                if (ext != "apk") {
                    return@withContext SecurityScanResult.Rejected(
                        reason = "Android application package does not match the declared .$ext extension",
                        threatType = ThreatType.MIME_SPOOFING
                    )
                }
                when (val packageVerdict = ArchiveScanner.scanAndroidPackage(tempFile)) {
                    is ArchiveScanVerdict.Blocked -> return@withContext SecurityScanResult.Rejected(
                        reason = "APK structure check failed: ${packageVerdict.reason}",
                        threatType = ThreatType.ARCHIVE_THREAT
                    )
                    is ArchiveScanVerdict.Safe -> Unit
                }
            }
            FileClassification.EXECUTABLE -> {
                val executableThreat = yaraNativeBridge.scanFile(tempFile.absolutePath)
                return@withContext SecurityScanResult.Rejected(
                    reason = executableThreat?.let { "PE/EXE executable payload detected: $it" }
                        ?: "PE/EXE executable payload detected: file header matches a Windows executable signature",
                    threatType = ThreatType.BANNED_EXECUTABLE
                )
            }
            FileClassification.UNSUPPORTED -> {
                return@withContext SecurityScanResult.Inconclusive(
                    "File format .$ext is not supported for complete inspection"
                )
            }
            FileClassification.NORMAL -> {
                val headerVerdict = validateFileType(tempFile, ext)
                if (headerVerdict is ValidationResult.Blocked) {
                    val threatType = if (headerVerdict.reason.contains("executable", ignoreCase = true)) {
                        ThreatType.BANNED_EXECUTABLE
                    } else {
                        ThreatType.MIME_SPOOFING
                    }
                    return@withContext SecurityScanResult.Rejected(
                        reason = "Post-delivery quarantine inspection blocked: ${headerVerdict.reason}",
                        threatType = threatType
                    )
                }
            }
        }

        // 3. Archive Safety Gate (Zip-Bomb Decompression Ratio, Inode Ceiling, Zip-Slip)
        if (ext == "zip") {
            val archiveVerdict = validateArchive(tempFile)
            if (archiveVerdict is ArchiveScanResult.Blocked) {
                val threatType = when {
                    archiveVerdict.reason.contains("Zip Slip", ignoreCase = true) -> ThreatType.ZIP_SLIP_PATH_TRAVERSAL
                    archiveVerdict.reason.contains("Zip-Bomb", ignoreCase = true) -> ThreatType.ZIP_BOMB_DECOMPRESSION_RATIO
                    archiveVerdict.reason.contains("Nested archive", ignoreCase = true) -> ThreatType.ARCHIVE_NESTED
                    archiveVerdict.reason.contains("executable", ignoreCase = true) -> ThreatType.ARCHIVE_EXECUTABLE_CONTENT
                    else -> ThreatType.ARCHIVE_THREAT
                }
                return@withContext SecurityScanResult.Rejected(
                    reason = "Archive Gate Blocked: ${archiveVerdict.reason}",
                    threatType = threatType
                )
            }
        }

        // 4. LibYARA Deep Inspection on Reassembled Binary
        val yaraThreat = yaraNativeBridge.scanFile(tempFile.absolutePath)
        if (yaraThreat != null) {
            return@withContext SecurityScanResult.Rejected(
                reason = "Quarantine scan matched threat signature: $yaraThreat",
                threatType = ThreatType.YARA_SIGNATURE_MATCH
            )
        }

        SecurityScanResult.Safe(hash = actualHash)
    }
}
