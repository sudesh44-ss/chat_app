package com.nexa.app.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Stream-based compressor for SQLite database snapshots.
 * Reduces raw database footprint by 75-85% prior to client-side encryption.
 */
object BackupCompressor {

    private const val BUFFER_SIZE = 64 * 1024 // 64 KB buffer

    /**
     * Compresses the input file using GZIP into the output file.
     */
    fun compressFile(
        inputFile: File,
        outputFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) {
        val totalBytes = inputFile.length()
        var bytesReadSoFar = 0L

        FileInputStream(inputFile).use { fileIn ->
            FileOutputStream(outputFile).use { fileOut ->
                GZIPOutputStream(fileOut).use { gzipOut ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (fileIn.read(buffer).also { read = it } != -1) {
                        gzipOut.write(buffer, 0, read)
                        bytesReadSoFar += read
                        onProgress(bytesReadSoFar, totalBytes)
                    }
                    gzipOut.finish()
                }
            }
        }
    }

    /**
     * Decompresses a GZIP-compressed file into the target output file.
     */
    fun decompressFile(
        compressedFile: File,
        targetFile: File,
        onProgress: (bytesWritten: Long) -> Unit = {}
    ) {
        var bytesWritten = 0L
        FileInputStream(compressedFile).use { fileIn ->
            GZIPInputStream(fileIn).use { gzipIn ->
                FileOutputStream(targetFile).use { fileOut ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (gzipIn.read(buffer).also { read = it } != -1) {
                        fileOut.write(buffer, 0, read)
                        bytesWritten += read
                        onProgress(bytesWritten)
                    }
                    fileOut.flush()
                }
            }
        }
    }

    /**
     * In-memory byte array compression.
     */
    fun compressBytes(data: ByteArray): ByteArray {
        val byteOut = ByteArrayOutputStream(data.size / 2)
        GZIPOutputStream(byteOut).use { gzipOut ->
            gzipOut.write(data)
            gzipOut.finish()
        }
        return byteOut.toByteArray()
    }

    /**
     * In-memory byte array decompression.
     */
    fun decompressBytes(compressedData: ByteArray): ByteArray {
        val byteIn = ByteArrayInputStream(compressedData)
        val byteOut = ByteArrayOutputStream()
        GZIPInputStream(byteIn).use { gzipIn ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (gzipIn.read(buffer).also { read = it } != -1) {
                byteOut.write(buffer, 0, read)
            }
        }
        return byteOut.toByteArray()
    }
}
