package com.nexa.app.data.remote.swarm

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/**
 * Isolated storage sandbox manager for Gate 2 quarantine & verified safe storage.
 * Inbound chunks land strictly inside sandbox cache until post-download Gate 2 passes.
 */
class QuarantineSandbox(private val context: Context) {

    val quarantineDir: File by lazy {
        File(context.filesDir, "vault/quarantine").apply { if (!exists()) mkdirs() }
    }

    val safeVaultDir: File by lazy {
        File(context.filesDir, "vault/safe").apply { if (!exists()) mkdirs() }
    }

    fun getTempFile(fileHash: String): File {
        return File(quarantineDir, "$fileHash.tmp")
    }

    fun allocateTempStorage(fileHash: String, totalBytes: Long): RandomAccessFile {
        val file = getTempFile(fileHash)
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(totalBytes) // Fast sparse pre-allocation
        return raf
    }

    fun promoteToSafeStorage(fileHash: String, actualFileName: String): File {
        val temp = getTempFile(fileHash)
        val destination = File(safeVaultDir, actualFileName)
        temp.copyTo(destination, overwrite = true)
        temp.delete()
        return destination
    }

    fun shredQuarantine(fileHash: String) {
        val file = getTempFile(fileHash)
        if (file.exists()) {
            try {
                val raf = RandomAccessFile(file, "rw")
                val zeroBytes = ByteArray(file.length().coerceAtMost(4096).toInt())
                raf.seek(0)
                raf.write(zeroBytes) // Overwrite header & first 4 KB
                raf.close()
            } catch (e: Exception) {
                // Best-effort overwrite
            } finally {
                file.delete()
            }
        }
    }

    fun calculateSafeVaultSizeBytes(): Long {
        var total = 0L
        safeVaultDir.listFiles()?.forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }

    fun deleteSafeFile(localPath: String): Boolean {
        val file = File(localPath)
        return if (file.exists()) file.delete() else false
    }
}
