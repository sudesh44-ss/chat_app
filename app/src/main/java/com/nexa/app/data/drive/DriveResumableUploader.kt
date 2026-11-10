package com.nexa.app.data.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Robust 256 KB chunked resumable uploader for Google Drive REST API v3.
 * Supports byte offset tracking, network dropout recovery, and exponential backoff retry.
 */
class DriveResumableUploader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    /**
     * Uploads the file to the active Google Drive resumable session URI.
     * Returns the remote file ID upon successful completion.
     */
    suspend fun upload(
        sessionUri: String,
        file: File,
        onProgress: (uploadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onChunkUploaded: (confirmedOffset: Long) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        if (!UploadSessionState.isValidSessionUri(sessionUri)) {
            throw IllegalArgumentException("Invalid or untrusted Google Drive resumable upload session URI")
        }

        val totalBytes = file.length()
        var currentOffset = queryUploadOffset(sessionUri, totalBytes)
        onProgress(currentOffset, totalBytes)
        onChunkUploaded(currentOffset)

        if (currentOffset >= totalBytes && totalBytes > 0) {
            val completedFileId = fetchUploadedFileMetadata(sessionUri, totalBytes)
            if (completedFileId != null) {
                onProgress(totalBytes, totalBytes)
                onChunkUploaded(totalBytes)
                return@withContext completedFileId
            }
        }

        val buffer = ByteArray(CHUNK_SIZE)

        RandomAccessFile(file, "r").use { raf ->
            while (currentOffset < totalBytes) {
                raf.seek(currentOffset)
                val bytesToRead = minOf(CHUNK_SIZE.toLong(), totalBytes - currentOffset).toInt()
                raf.readFully(buffer, 0, bytesToRead)

                val chunkData = if (bytesToRead == CHUNK_SIZE) buffer else buffer.copyOf(bytesToRead)
                val startByte = currentOffset
                val endByte = currentOffset + bytesToRead - 1

                val uploadedFileId = uploadChunkWithRetry(
                    sessionUri = sessionUri,
                    chunkData = chunkData,
                    startByte = startByte,
                    endByte = endByte,
                    totalBytes = totalBytes
                )

                if (uploadedFileId != null) {
                    onProgress(totalBytes, totalBytes)
                    onChunkUploaded(totalBytes)
                    return@withContext uploadedFileId
                }

                currentOffset = queryUploadOffset(sessionUri, totalBytes)
                onProgress(currentOffset, totalBytes)
                onChunkUploaded(currentOffset)
            }
        }

        // Final query to retrieve file ID if last chunk completed
        val finalFileId = fetchUploadedFileMetadata(sessionUri, totalBytes)
        if (finalFileId != null) {
            onProgress(totalBytes, totalBytes)
            onChunkUploaded(totalBytes)
            return@withContext finalFileId
        }

        throw IOException("Resumable upload loop terminated without obtaining remote file ID")
    }

    /**
     * Attempts chunk upload with exponential backoff for transient 5xx or connection issues.
     */
    private suspend fun uploadChunkWithRetry(
        sessionUri: String,
        chunkData: ByteArray,
        startByte: Long,
        endByte: Long,
        totalBytes: Long,
        maxRetries: Int = 5
    ): String? {
        var backoffMs = 1000L

        for (attempt in 1..maxRetries) {
            try {
                val mediaType = "application/octet-stream".toMediaType()
                val requestBody = chunkData.toRequestBody(mediaType)
                val contentRange = "bytes $startByte-$endByte/$totalBytes"

                val request = Request.Builder()
                    .url(sessionUri)
                    .put(requestBody)
                    .header("Content-Range", contentRange)
                    .header("Content-Length", chunkData.size.toString())
                    .build()

                client.newCall(request).execute().use { response ->
                    val code = response.code
                    val bodyString = response.body?.string().orEmpty()

                    when (code) {
                        200, 201 -> {
                            val json = JSONObject(bodyString)
                            return json.optString("id", "")
                        }
                        308 -> {
                            // Resume Incomplete - chunk recorded by Drive
                            return null
                        }
                        404, 410 -> {
                            // Session expired or purged on Google Drive server
                            throw UploadSessionExpiredException("Resumable upload session expired or not found on server (HTTP $code)")
                        }
                        in 500..599 -> {
                            // Transient server error, wait & retry
                            if (attempt == maxRetries) {
                                throw IOException("Google Drive server error $code after $maxRetries retries")
                            }
                        }
                        else -> {
                            throw IOException("Google Drive rejected chunk with HTTP $code: $bodyString")
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is UploadSessionExpiredException) throw e
                if (attempt == maxRetries) throw e
            }

            delay(backoffMs)
            backoffMs *= 2
        }
        return null
    }

    /**
     * Queries Google Drive for the last confirmed byte offset stored in the session.
     */
    suspend fun queryUploadOffset(sessionUri: String, totalBytes: Long): Long = withContext(Dispatchers.IO) {
        if (!UploadSessionState.isValidSessionUri(sessionUri)) {
            throw IllegalArgumentException("Invalid or untrusted Google Drive resumable upload session URI")
        }

        val emptyBody = ByteArray(0).toRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder()
            .url(sessionUri)
            .put(emptyBody)
            .header("Content-Range", "bytes */$totalBytes")
            .header("Content-Length", "0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    308 -> {
                        val rangeHeader = response.header("Range") // e.g. "bytes=0-262143"
                        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=0-")) {
                            val endByteStr = rangeHeader.substringAfter("bytes=0-").trim()
                            val lastByte = endByteStr.toLongOrNull()
                            if (lastByte != null) {
                                return@withContext lastByte + 1
                            }
                        }
                        return@withContext 0L
                    }
                    200, 201 -> {
                        return@withContext totalBytes
                    }
                    404, 410 -> {
                        throw UploadSessionExpiredException("Google Drive resumable upload session has expired (HTTP ${response.code})")
                    }
                }
            }
        } catch (e: UploadSessionExpiredException) {
            throw e
        } catch (ignored: Exception) {}

        0L
    }

    private fun fetchUploadedFileMetadata(sessionUri: String, totalBytes: Long): String? {
        val emptyBody = ByteArray(0).toRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder()
            .url(sessionUri)
            .put(emptyBody)
            .header("Content-Range", "bytes */$totalBytes")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code in 200..201) {
                val json = JSONObject(response.body?.string().orEmpty())
                return if (json.has("id")) json.getString("id") else null
            }
        }
        return null
    }

    companion object {
        const val CHUNK_SIZE = 256 * 1024 // 256 KB slice
    }
}
