package com.nexa.app.data.remote.swarm

import android.content.Context
import com.nexa.app.core.security.HashEngine
import com.nexa.app.core.security.FileSecurityManager
import com.nexa.app.core.util.FileShredder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Isolated storage sandbox manager for Gate 2 quarantine & verified safe storage.
 * Inbound chunks land strictly inside sandbox cache until post-download Gate 2 passes.
 */
class QuarantineSandbox(private val context: Context) {

    val quarantineDir: File by lazy {
        File(context.filesDir, "security/swarm_quarantine").apply {
            check(exists() || mkdirs()) { "Unable to create private quarantine storage" }
        }
    }

    val safeVaultDir: File by lazy {
        File(context.filesDir, "vault/safe").apply { if (!exists()) mkdirs() }
    }

    fun getTempFile(fileHash: String): File {
        require(fileHash.matches(Regex("(?i)[0-9a-f]{64}"))) { "Invalid transfer content hash" }
        return File(quarantineDir, "q_${HashEngine.computeSha256(fileHash.toByteArray()).take(32)}.tmp")
    }

    fun allocateTempStorage(fileHash: String, totalBytes: Long): RandomAccessFile {
        require(totalBytes in 1..FileSecurityManager.MAX_FILE_SIZE_BYTES) { "Invalid transfer size" }
        val file = getTempFile(fileHash)
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(totalBytes) // Fast sparse pre-allocation
        return raf
    }

    fun promoteToSafeStorage(fileHash: String, actualFileName: String): File {
        val temp = getTempFile(fileHash)
        check(temp.isFile && temp.length() > 0) { "Quarantined file is missing or empty" }
        check(HashEngine.computeSha256(temp).equals(fileHash, ignoreCase = true)) {
            "Quarantined file changed after scanning"
        }
        val safeName = actualFileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('.')
            .take(100)
            .ifBlank { "received-file" }
        val destination = File(safeVaultDir, "${fileHash.take(12)}_$safeName")
        check(destination.canonicalFile.parentFile == safeVaultDir.canonicalFile) {
            "Release destination escaped the safe vault"
        }
        check(!destination.exists()) { "A released file already exists" }
        val staging = File.createTempFile("release_", ".tmp", safeVaultDir)
        try {
            FileInputStream(temp).use { input ->
                FileOutputStream(staging).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(HashEngine.computeSha256(staging).equals(fileHash, ignoreCase = true)) {
                "File content changed while being released"
            }
            check(staging.renameTo(destination)) { "Unable to atomically move file into safe storage" }
        } finally {
            if (staging.exists()) staging.delete()
        }
        return destination
    }

    fun shredQuarantine(fileHash: String) {
        val file = getTempFile(fileHash)
        check(FileShredder.shred(file)) { "Unable to remove quarantined file" }
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
        return if (file.exists() && file.canonicalFile.parentFile == safeVaultDir.canonicalFile) {
            file.delete()
        } else {
            false
        }
    }
}
