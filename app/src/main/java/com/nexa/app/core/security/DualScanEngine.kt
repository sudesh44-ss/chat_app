package com.nexa.app.core.security

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

sealed interface DualScanResult {
    data class Clean(val hash: String) : DualScanResult
    data class Malicious(val reason: String) : DualScanResult
    data class Corrupted(val message: String) : DualScanResult
    data class Inconclusive(val reason: String) : DualScanResult
}

/**
 * Dual-Gate Zero-Trust Security Pipeline Coordinator:
 * - Gate 1 (Sender Pre-Dispatch): Halts malicious files locally before DataChannel broadcast.
 * - Gate 2 (Receiver Post-Delivery Quarantine): Verifies in-sandbox reassembly and checksum integrity before vault promotion.
 */
class DualScanEngine(
    private val context: Context? = null,
    private val bloomFilter: ThreatBloomFilter,
    private val yaraScanner: YaraNativeBridge
) {

    // GATE 1: SENDER PRE-DISPATCH SCAN
    suspend fun scanOutbound(uri: Uri, declaredExtension: String): DualScanResult = withContext(Dispatchers.IO) {
        val ext = declaredExtension.lowercase().removePrefix(".").trim()
        if (NexaFileSecurityEngine.BANNED_EXTENSIONS.contains(ext)) {
            return@withContext DualScanResult.Malicious("Blocked dangerous executable file extension: .$ext")
        }

        val ctx = context ?: return@withContext DualScanResult.Corrupted("Inaccessible source context")
        val stream = try {
            ctx.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: return@withContext DualScanResult.Corrupted("Inaccessible source file")

        // 1. Magic Bytes & Extension Spoofing Check
        val headerVerdict = stream.use { MagicByteInspector.inspect(it, declaredExtension) }
        when (headerVerdict) {
            is InspectionVerdict.DangerousBinary -> {
                return@withContext DualScanResult.Malicious("Blocked executable file type: ${headerVerdict.detectedType}")
            }
            is InspectionVerdict.Spoofed -> {
                return@withContext DualScanResult.Malicious("File spoofing detected: Disguised as .${headerVerdict.declaredExt} (${headerVerdict.actualType})")
            }
            is InspectionVerdict.Clean -> Unit
        }

        // 2. Compute Streaming SHA-256 Hash
        val hash = try {
            computeSha256(uri)
        } catch (e: Exception) {
            return@withContext DualScanResult.Corrupted("Failed to compute file digest: ${e.localizedMessage}")
        }

        // 3. Offline Known Malware Bloom Filter Lookup (O(1))
        if (bloomFilter.contains(hash)) {
            return@withContext DualScanResult.Malicious("Identified in known malware database.")
        }

        // 4. Native YARA Signature Inspection (Shellcode / Exploits / Macros)
        val yaraThreat = yaraScanner.scanUri(ctx, uri)
        if (yaraThreat != null) {
            return@withContext DualScanResult.Malicious("Matched threat signature: $yaraThreat")
        }

        DualScanResult.Clean(hash)
    }

    // GATE 2: RECEIVER POST-DELIVERY ISOLATION SCAN
    suspend fun scanInboundQuarantine(tempFile: File, expectedHash: String, declaredExt: String): DualScanResult = withContext(Dispatchers.IO) {
        if (!tempFile.exists() || tempFile.length() == 0L) {
            return@withContext DualScanResult.Corrupted("Quarantined payload is missing or empty")
        }
        if (tempFile.length() > FileSecurityManager.MAX_FILE_SIZE_BYTES) {
            return@withContext DualScanResult.Inconclusive("Quarantined file exceeds the maximum supported scan size")
        }

        // 1. Cryptographic Hash Integrity Verification
        val actualHash = try {
            computeFileSha256(tempFile)
        } catch (e: Exception) {
            return@withContext DualScanResult.Corrupted("Failed to verify quarantined file digest: ${e.localizedMessage}")
        }

        if (!actualHash.equals(expectedHash, ignoreCase = true)) {
            return@withContext DualScanResult.Corrupted(
                "Hash mismatch: Expected $expectedHash but got $actualHash (Tampered or incomplete stream)"
            )
        }

        val classification = FileReleaseClassifier.classify(tempFile, "received.$declaredExt")
        when (classification) {
            FileClassification.APK -> {
                if (declaredExt.lowercase().removePrefix(".") != "apk") {
                    return@withContext DualScanResult.Malicious("Post-download scan flagged an APK extension mismatch")
                }
                if (ArchiveScanner.scanAndroidPackage(tempFile) is ArchiveScanVerdict.Blocked) {
                    return@withContext DualScanResult.Malicious("Post-download scan flagged a malformed APK package")
                }
            }
            FileClassification.EXECUTABLE -> {
                val executableThreat = yaraScanner.scanFile(tempFile.absolutePath)
                return@withContext DualScanResult.Malicious(
                    executableThreat?.let { "Executable signature matched: $it" }
                        ?: "Post-download scan flagged dangerous binary: executable content"
                )
            }
            FileClassification.UNSUPPORTED -> {
                return@withContext DualScanResult.Inconclusive("Unsupported file format; complete inspection is unavailable")
            }
            FileClassification.NORMAL -> {
                val headerVerdict = FileInputStream(tempFile).use {
                    MagicByteInspector.inspect(it, declaredExt)
                }
                when (headerVerdict) {
                    is InspectionVerdict.DangerousBinary -> {
                        return@withContext DualScanResult.Malicious(
                            "Post-download scan flagged dangerous binary: ${headerVerdict.detectedType}"
                        )
                    }
                    is InspectionVerdict.Spoofed -> {
                        return@withContext DualScanResult.Malicious(
                            "Post-download scan flagged extension spoofing: .${headerVerdict.declaredExt}"
                        )
                    }
                    is InspectionVerdict.Clean -> Unit
                }
                if (declaredExt.lowercase().removePrefix(".") == "zip" &&
                    ArchiveScanner.scanArchive(tempFile) is ArchiveScanVerdict.Blocked
                ) {
                    return@withContext DualScanResult.Malicious("Post-download scan flagged an unsafe archive")
                }
            }
        }

        // 3. Deep Static LibYARA Scan on Reassembled Assembly
        val yaraThreat = yaraScanner.scanFile(tempFile.absolutePath)
        if (yaraThreat != null) {
            return@withContext DualScanResult.Malicious("Post-download scan flagged threat: $yaraThreat")
        }

        DualScanResult.Clean(actualHash)
    }

    private fun computeSha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context?.contentResolver?.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
