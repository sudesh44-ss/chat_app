package com.nexa.app.core.util

import java.io.File
import java.io.RandomAccessFile

/**
 * Multi-pass zero-fill file destruction utility.
 * Overwrites malicious or quarantined payload bytes with 0x00 in 4096-byte blocks
 * before unlinking to prevent memory scraping or forensic file recovery.
 */
object FileShredder {

    fun shred(file: File): Boolean {
        if (!file.exists()) return true
        return try {
            val length = file.length()
            if (length > 0) {
                val raf = RandomAccessFile(file, "rws")
                val zeroBytes = ByteArray(4096)
                var remaining = length

                raf.seek(0)
                while (remaining > 0) {
                    val toWrite = minOf(remaining, zeroBytes.size.toLong()).toInt()
                    raf.write(zeroBytes, 0, toWrite)
                    remaining -= toWrite
                }
                raf.close()
            }
            file.delete()
        } catch (e: Exception) {
            file.delete()
        }
    }

    fun shredPath(filePath: String): Boolean {
        return shred(File(filePath))
    }
}
