package com.nexa.app.data.remote.transfer

import android.content.Context
import com.nexa.app.core.security.ArchiveSanitizer
import com.nexa.app.core.security.ArchiveScanResult
import com.nexa.app.core.security.DualScanEngine
import com.nexa.app.core.security.DualScanResult
import com.nexa.app.core.security.ArchiveScanVerdict
import com.nexa.app.core.security.ArchiveScanner
import com.nexa.app.core.security.FileClassification
import com.nexa.app.core.security.FileReleaseClassifier
import com.nexa.app.core.security.HashEngine
import com.nexa.app.core.security.FileSecurityManager
import com.nexa.app.core.security.NexaFileSecurityEngine
import com.nexa.app.core.security.ValidationResult
import com.nexa.app.core.util.FileShredder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Isolated P2P Stream Sink and Quarantine Sandbox.
 * Chunks stream strictly into isolated app-private quarantine storage
 * with an in-flight incremental SHA-256 hash digest. Files are NEVER dropped directly into
 * public storage before Gate 2 deep inspection passes.
 */
class QuarantineSandboxSink(
    private val context: Context,
    private val dualScanEngine: DualScanEngine
) {

    val quarantineDir: File by lazy {
        File(context.filesDir, "security/dual_gate_quarantine").apply {
            check(exists() || mkdirs()) { "Unable to create private quarantine storage" }
        }
    }

    val safeVaultDir: File by lazy {
        File(context.filesDir, "vault/safe").apply { if (!exists()) mkdirs() }
    }

    private val openSinks = ConcurrentHashMap<String, SinkSession>()

    private class SinkSession(
        val tempFile: File,
        val raf: RandomAccessFile
    )

    fun getTempFile(transferId: String): File {
        val internalId = HashEngine.computeSha256(transferId.toByteArray()).take(32)
        return File(quarantineDir, "q_$internalId.tmp")
    }

    @Synchronized
    fun startSink(transferId: String, totalBytes: Long): File {
        require(transferId.isNotBlank())
        require(totalBytes in 1..FileSecurityManager.MAX_FILE_SIZE_BYTES) { "Invalid transfer size" }
        val tempFile = getTempFile(transferId)
        check(!tempFile.exists()) { "Quarantine storage already exists for this transfer" }

        val raf = RandomAccessFile(tempFile, "rw")
        raf.setLength(totalBytes)
        openSinks[transferId] = SinkSession(tempFile, raf)
        return tempFile
    }

    @Synchronized
    fun writeChunk(transferId: String, chunkData: ByteArray, offset: Long) {
        val session = openSinks[transferId] ?: throw IllegalStateException("Sink not opened for transferId: $transferId")
        require(offset >= 0L && offset <= session.tempFile.length() - chunkData.size) {
            "Chunk exceeds allocated quarantine file size"
        }
        session.raf.seek(offset)
        session.raf.write(chunkData)
    }

    suspend fun finishAndVerify(
        transferId: String,
        expectedHash: String,
        fileName: String,
        declaredExt: String
    ): DualScanResult = withContext(Dispatchers.IO) {
        val session = openSinks.remove(transferId)
        val tempFile = session?.tempFile ?: getTempFile(transferId)

        try {
            session?.raf?.close()
        } catch (ignored: Exception) {}

        if (!tempFile.exists() || tempFile.length() == 0L) {
            return@withContext DualScanResult.Corrupted("Quarantined payload missing or empty for transfer: $transferId")
        }

        // 1. Verify actual type and signatures before format-specific inspection.
        when (FileReleaseClassifier.classify(tempFile, fileName)) {
            FileClassification.APK -> {
                if (declaredExt.lowercase().removePrefix(".") != "apk" ||
                    ArchiveScanner.scanAndroidPackage(tempFile) is ArchiveScanVerdict.Blocked
                ) {
                    return@withContext DualScanResult.Malicious("APK structure or declared type is invalid")
                }
            }
            FileClassification.EXECUTABLE -> Unit
            FileClassification.UNSUPPORTED -> {
                return@withContext DualScanResult.Inconclusive("Unsupported file format; complete inspection is unavailable")
            }
            FileClassification.NORMAL -> {
                val integrity = NexaFileSecurityEngine.validateFileIntegrity(tempFile, declaredExt)
                if (integrity is ValidationResult.Blocked) {
                    return@withContext DualScanResult.Malicious("File Security Gate Blocked: ${integrity.reason}")
                }
            }
        }

        // 2. Archive Safety Gate: Verify Decompression Ratios, Zip-Slip, and Nested Archives
        if (declaredExt.lowercase().removePrefix(".").trim() == "zip") {
            val archiveVerdict = ArchiveSanitizer.inspectArchive(tempFile)
            if (archiveVerdict is ArchiveScanResult.Blocked) {
                return@withContext DualScanResult.Malicious("Archive Security Gate Blocked: ${archiveVerdict.reason}")
            }
        }

        // 3. Perform Gate 2 verification (Cryptographic Hash Integrity + LibYARA)
        val scanVerdict = dualScanEngine.scanInboundQuarantine(
            tempFile = tempFile,
            expectedHash = expectedHash,
            declaredExt = declaredExt
        )

        when (scanVerdict) {
            is DualScanResult.Clean -> scanVerdict
            is DualScanResult.Corrupted -> {
                // Corrupted or tampered in-flight stream: instant zero-shred purge
                FileShredder.shred(tempFile)
                scanVerdict
            }
            is DualScanResult.Malicious -> {
                // Retain file locked inside isolated quarantine for audit or user override
                scanVerdict
            }
            is DualScanResult.Inconclusive -> scanVerdict
        }
    }

    internal fun releaseVerifiedQuarantine(sourceFile: File, expectedHash: String, fileName: String): File {
        check(sourceFile.canonicalFile.parentFile == quarantineDir.canonicalFile) {
            "Release source is outside the private quarantine"
        }
        check(sourceFile.isFile && HashEngine.computeSha256(sourceFile).equals(expectedHash, ignoreCase = true)) {
            "Quarantine content does not match the scanned hash"
        }
        check(safeVaultDir.exists() || safeVaultDir.mkdirs()) { "Unable to create the safe vault" }
        val safeName = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('.')
            .take(100)
            .ifBlank { "received-file" }
        val destination = File(safeVaultDir, "${expectedHash.take(12)}_$safeName")
        check(destination.canonicalFile.parentFile == safeVaultDir.canonicalFile)
        check(!destination.exists()) { "A released file already exists" }
        val staging = File.createTempFile("release_", ".tmp", safeVaultDir)
        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(staging).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(HashEngine.computeSha256(staging).equals(expectedHash, ignoreCase = true)) {
                "File content changed while being released"
            }
            check(staging.renameTo(destination)) { "Unable to atomically move file into the safe vault" }
        } finally {
            if (staging.exists()) staging.delete()
        }
        return destination
    }

    fun shredQuarantine(transferId: String): Boolean {
        openSinks.remove(transferId)?.let {
            try { it.raf.close() } catch (ignored: Exception) {}
        }
        val tempFile = getTempFile(transferId)
        return FileShredder.shred(tempFile)
    }
}
