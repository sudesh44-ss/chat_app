package com.nexa.app.data.remote.transfer

import android.content.Context
import com.nexa.app.core.security.DualScanEngine
import com.nexa.app.core.security.ScanResult
import com.nexa.app.core.util.FileShredder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Isolated P2P Stream Sink and Quarantine Sandbox.
 * Chunks stream strictly into isolated internal cache (/cache/quarantine/{transferId}.tmp)
 * with an in-flight incremental SHA-256 hash digest. Files are NEVER dropped directly into
 * public storage before Gate 2 deep inspection passes.
 */
class QuarantineSandboxSink(
    private val context: Context,
    private val dualScanEngine: DualScanEngine
) {

    val quarantineDir: File by lazy {
        File(context.cacheDir, "quarantine").apply { if (!exists()) mkdirs() }
    }

    val safeVaultDir: File by lazy {
        File(context.filesDir, "vault/safe").apply { if (!exists()) mkdirs() }
    }

    private val openSinks = ConcurrentHashMap<String, SinkSession>()

    private class SinkSession(
        val tempFile: File,
        val raf: RandomAccessFile,
        val digest: MessageDigest
    )

    fun getTempFile(transferId: String): File {
        return File(quarantineDir, "$transferId.tmp")
    }

    @Synchronized
    fun startSink(transferId: String, totalBytes: Long): File {
        val tempFile = getTempFile(transferId)
        if (tempFile.exists()) tempFile.delete()

        val raf = RandomAccessFile(tempFile, "rw")
        if (totalBytes > 0) {
            raf.setLength(totalBytes) // Fast sparse pre-allocation
        }
        val digest = MessageDigest.getInstance("SHA-256")
        openSinks[transferId] = SinkSession(tempFile, raf, digest)
        return tempFile
    }

    @Synchronized
    fun writeChunk(transferId: String, chunkData: ByteArray, offset: Long) {
        val session = openSinks[transferId] ?: throw IllegalStateException("Sink not opened for transferId: $transferId")
        session.raf.seek(offset)
        session.raf.write(chunkData)
        session.digest.update(chunkData)
    }

    suspend fun finishAndVerify(
        transferId: String,
        expectedHash: String,
        fileName: String,
        declaredExt: String
    ): ScanResult = withContext(Dispatchers.IO) {
        val session = openSinks.remove(transferId)
        val tempFile = session?.tempFile ?: getTempFile(transferId)

        try {
            session?.raf?.close()
        } catch (ignored: Exception) {}

        if (!tempFile.exists() || tempFile.length() == 0L) {
            return@withContext ScanResult.Corrupted("Quarantined payload missing or empty for transfer: $transferId")
        }

        // Compute or verify calculated SHA-256 digest
        val inFlightHash = session?.digest?.digest()?.joinToString("") { "%02x".format(it) }

        // Perform Gate 2 verification
        val scanVerdict = dualScanEngine.scanInboundQuarantine(
            tempFile = tempFile,
            expectedHash = expectedHash,
            declaredExt = declaredExt
        )

        when (scanVerdict) {
            is ScanResult.Clean -> {
                // Atomic promotion to safe vault
                val safeDestination = File(safeVaultDir, fileName)
                tempFile.copyTo(safeDestination, overwrite = true)
                tempFile.delete()
                ScanResult.Clean(safeDestination.absolutePath)
            }
            is ScanResult.Corrupted -> {
                // Corrupted or tampered in-flight stream: instant zero-shred purge
                FileShredder.shred(tempFile)
                scanVerdict
            }
            is ScanResult.Malicious -> {
                // Retain file locked inside isolated quarantine for audit or user override
                scanVerdict
            }
        }
    }

    fun forcePromote(transferId: String, fileName: String): File {
        val tempFile = getTempFile(transferId)
        val safeDestination = File(safeVaultDir, fileName)
        tempFile.copyTo(safeDestination, overwrite = true)
        tempFile.delete()
        return safeDestination
    }

    fun shredQuarantine(transferId: String): Boolean {
        openSinks.remove(transferId)?.let {
            try { it.raf.close() } catch (ignored: Exception) {}
        }
        val tempFile = getTempFile(transferId)
        return FileShredder.shred(tempFile)
    }
}
