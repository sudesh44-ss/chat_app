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
 * High-performance streaming cryptographic hash engine.
 * Calculates SHA-256 digests in streaming chunks without loading full files into memory.
 * Employs constant-time byte comparisons to eliminate side-channel timing leaks.
 */
object HashEngine {

    private const val BUFFER_SIZE = 16384 // 16 KB streaming buffer

    fun computeSha256(file: File): String {
        require(file.exists()) { "File does not exist: ${file.absolutePath}" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return toHex(digest.digest())
    }

    fun computeSha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        while (stream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return toHex(digest.digest())
    }

    suspend fun computeSha256(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open content stream for URI: $uri")
        stream.use { computeSha256(it) }
    }

    fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return toHex(digest.digest(bytes))
    }

    /**
     * Constant-time comparison between two hex-encoded hashes.
     * Prevents timing attacks on hash verification.
     */
    fun constantTimeEquals(expectedHex: String, actualHex: String): Boolean {
        val expectedBytes = expectedHex.lowercase().toByteArray(Charsets.US_ASCII)
        val actualBytes = actualHex.lowercase().toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(expectedBytes, actualBytes)
    }

    private fun toHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val lookup = "0123456789abcdef".toCharArray()
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = lookup[v ushr 4]
            hexChars[i * 2 + 1] = lookup[v and 0x0F]
        }
        return String(hexChars)
    }
}
