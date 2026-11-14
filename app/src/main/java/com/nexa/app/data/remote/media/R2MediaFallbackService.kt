package com.nexa.app.data.remote.media

import android.util.Log
import com.nexa.app.core.media.BlindMediaVaultEngine
import com.nexa.app.core.media.EncryptedMediaResult
import com.nexa.app.core.network.ServerEndpointConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Request payload for cloud fallback upload ticket.
 */
data class UploadTicketRequest(
    val nxId: String,
    val sizeBytes: Long,
    val cipherHash: String
)

/**
 * Server ticket authorizing encrypted blob upload to Cloudflare R2 ephemeral mailbox.
 */
data class UploadTicketResponse(
    val status: String,
    val objectKey: String,
    val uploadUrl: String,
    val expiresInSecs: Int,
    val maxAllowedBytes: Long,
    val ttlPolicy: String,
    val timestamp: Long
)

/**
 * Immediate purge ACK notification sent upon successful recipient download and decryption.
 */
data class PurgeAckRequest(
    val objectKey: String,
    val recipientNxId: String
)

/**
 * Server confirmation that cloud object was dropped immediately (Zero Retention).
 */
data class PurgeAckResponse(
    val status: String,
    val objectKey: String,
    val retainedBytes: Long,
    val storageCost: String,
    val timestamp: Long
)

/**
 * Result of client-side encryption and successful upload to ephemeral cloud mailbox.
 */
data class R2FallbackUploadResult(
    val objectKey: String,
    val uploadUrl: String,
    val cipherHash: String,
    val mediaKey: ByteArray,
    val iv: ByteArray,
    val sizeBytes: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as R2FallbackUploadResult
        if (objectKey != other.objectKey) return false
        if (uploadUrl != other.uploadUrl) return false
        if (cipherHash != other.cipherHash) return false
        if (!mediaKey.contentEquals(other.mediaKey)) return false
        if (!iv.contentEquals(other.iv)) return false
        if (sizeBytes != other.sizeBytes) return false
        return true
    }

    override fun hashCode(): Int {
        var result = objectKey.hashCode()
        result = 31 * result + uploadUrl.hashCode()
        result = 31 * result + cipherHash.hashCode()
        result = 31 * result + mediaKey.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + sizeBytes.hashCode()
        return result
    }
}

/**
 * Service managing client-side encrypted media fallback over Cloudflare R2 when direct P2P is unavailable.
 */
interface R2MediaFallbackService {
    suspend fun requestUploadTicket(
        sessionToken: String,
        nxId: String,
        sizeBytes: Long,
        cipherHash: String
    ): Result<UploadTicketResponse>

    suspend fun uploadEncryptedBlob(
        uploadUrl: String,
        encryptedFile: File,
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit>

    suspend fun downloadEncryptedBlob(
        downloadUrl: String,
        destinationFile: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File>

    suspend fun sendPurgeAck(
        sessionToken: String,
        objectKey: String,
        recipientNxId: String
    ): Result<PurgeAckResponse>

    suspend fun executeFallbackUpload(
        sessionToken: String,
        nxId: String,
        sourceFile: File,
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null
    ): Result<R2FallbackUploadResult>

    suspend fun executeFallbackDownload(
        downloadUrl: String,
        expectedCipherHash: String,
        mediaKey: ByteArray,
        iv: ByteArray,
        destinationDecryptedFile: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File>
}

/**
 * Production implementation of R2MediaFallbackService calling NEXA Go backend and Cloudflare R2.
 */
class R2MediaFallbackServiceImpl(
    private val baseUrl: String = ServerEndpointConfig.httpBaseUrl,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) : R2MediaFallbackService {

    companion object {
        private const val TAG = "R2MediaFallback"
        const val MAX_ALLOWED_BYTES = 100 * 1024 * 1024L // 100 MB ceiling
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()

        /**
         * Redacts sensitive tickets, tokens, and hashes from presigned URLs before logging.
         */
        fun sanitizeUrlForLog(url: String): String {
            return url.replace(Regex("([?&](ticket|token|signature|hash|X-Amz-[^=]+)=)[^&]+"), "$1[REDACTED]")
        }
    }

    override suspend fun requestUploadTicket(
        sessionToken: String,
        nxId: String,
        sizeBytes: Long,
        cipherHash: String
    ): Result<UploadTicketResponse> = withContext(Dispatchers.IO) {
        if (sizeBytes > MAX_ALLOWED_BYTES) {
            return@withContext Result.failure(
                IllegalArgumentException("File size ($sizeBytes bytes) exceeds max cloud fallback ceiling ($MAX_ALLOWED_BYTES bytes)")
            )
        }

        try {
            val jsonBody = JSONObject().apply {
                put("nx_id", nxId)
                put("size_bytes", sizeBytes)
                put("cipher_hash", cipherHash)
            }

            val request = Request.Builder()
                .url("$baseUrl/api/v1/media/upload-ticket")
                .header("Authorization", "Bearer $sessionToken")
                .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to get upload ticket: HTTP ${response.code} - $bodyStr")
                    return@withContext Result.failure(IOException("Upload ticket failed: HTTP ${response.code}"))
                }

                val json = JSONObject(bodyStr)
                val ticket = UploadTicketResponse(
                    status = json.optString("status", "TICKET_ISSUED"),
                    objectKey = json.getString("object_key"),
                    uploadUrl = json.getString("upload_url"),
                    expiresInSecs = json.optInt("expires_in_secs", 900),
                    maxAllowedBytes = json.optLong("max_allowed_bytes", MAX_ALLOWED_BYTES),
                    ttlPolicy = json.optString("ttl_policy", "Ephemeral Mailbox"),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )

                Log.d(TAG, "Acquired R2 upload ticket for key ${ticket.objectKey} (URL: ${sanitizeUrlForLog(ticket.uploadUrl)})")
                Result.success(ticket)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception requesting R2 upload ticket", e)
            Result.failure(e)
        }
    }

    override suspend fun uploadEncryptedBlob(
        uploadUrl: String,
        encryptedFile: File,
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!encryptedFile.exists()) {
            return@withContext Result.failure(IllegalArgumentException("Encrypted file does not exist: ${encryptedFile.path}"))
        }

        try {
            val totalBytes = encryptedFile.length()
            val sanitizedUrl = sanitizeUrlForLog(uploadUrl)
            Log.i(TAG, "Uploading encrypted blob ($totalBytes bytes) to $sanitizedUrl")

            val fileRequestBody = encryptedFile.asRequestBody(BINARY_MEDIA_TYPE)
            val countingBody = object : RequestBody() {
                override fun contentType() = fileRequestBody.contentType()
                override fun contentLength() = totalBytes

                override fun writeTo(sink: BufferedSink) {
                    encryptedFile.inputStream().use { inputStream ->
                        val buffer = ByteArray(64 * 1024)
                        var uploaded = 0L
                        var read: Int
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            sink.write(buffer, 0, read)
                            uploaded += read
                            onProgress?.invoke(uploaded, totalBytes)
                        }
                    }
                }
            }

            val request = Request.Builder()
                .url(uploadUrl)
                .put(countingBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 200 && response.code != 201 && response.code != 204) {
                    val err = "R2 upload failed: HTTP ${response.code}"
                    Log.w(TAG, err)
                    return@withContext Result.failure(IOException(err))
                }

                Log.i(TAG, "Encrypted blob upload completed successfully ($totalBytes bytes)")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during encrypted blob upload to ${sanitizeUrlForLog(uploadUrl)}", e)
            Result.failure(e)
        }
    }

    override suspend fun downloadEncryptedBlob(
        downloadUrl: String,
        destinationFile: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)?
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val sanitizedUrl = sanitizeUrlForLog(downloadUrl)
            Log.i(TAG, "Downloading encrypted blob from $sanitizedUrl to ${destinationFile.name}")

            val request = Request.Builder()
                .url(downloadUrl)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = "R2 download failed: HTTP ${response.code}"
                    Log.w(TAG, err)
                    return@withContext Result.failure(IOException(err))
                }

                val responseBody = response.body ?: return@withContext Result.failure(IOException("Empty response body"))
                val totalBytes = responseBody.contentLength()

                responseBody.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var readTotal = 0L
                        var n: Int
                        while (input.read(buffer).also { n = it } != -1) {
                            output.write(buffer, 0, n)
                            readTotal += n
                            onProgress?.invoke(readTotal, totalBytes)
                        }
                    }
                }

                Log.i(TAG, "Encrypted blob download complete (${destinationFile.length()} bytes)")
                Result.success(destinationFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception downloading blob from ${sanitizeUrlForLog(downloadUrl)}", e)
            Result.failure(e)
        }
    }

    override suspend fun sendPurgeAck(
        sessionToken: String,
        objectKey: String,
        recipientNxId: String
    ): Result<PurgeAckResponse> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("object_key", objectKey)
                put("recipient_nx_id", recipientNxId)
            }

            val request = Request.Builder()
                .url("$baseUrl/api/v1/media/purge-ack")
                .header("Authorization", "Bearer $sessionToken")
                .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to send purge ACK: HTTP ${response.code} - $bodyStr")
                    return@withContext Result.failure(IOException("Purge ACK failed: HTTP ${response.code}"))
                }

                val json = JSONObject(bodyStr)
                val purgeResp = PurgeAckResponse(
                    status = json.optString("status", "PURGED"),
                    objectKey = json.getString("object_key"),
                    retainedBytes = json.optLong("retained_bytes", 0L),
                    storageCost = json.optString("storage_cost", "₹0"),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )

                Log.i(TAG, "Purge ACK confirmed for key ${purgeResp.objectKey}: retained bytes=${purgeResp.retainedBytes}, cost=${purgeResp.storageCost}")
                Result.success(purgeResp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending purge ACK for key $objectKey", e)
            Result.failure(e)
        }
    }

    override suspend fun executeFallbackUpload(
        sessionToken: String,
        nxId: String,
        sourceFile: File,
        onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)?
    ): Result<R2FallbackUploadResult> = withContext(Dispatchers.IO) {
        if (!sourceFile.exists()) {
            return@withContext Result.failure(IllegalArgumentException("Source file does not exist: ${sourceFile.path}"))
        }
        if (sourceFile.length() > MAX_ALLOWED_BYTES) {
            return@withContext Result.failure(
                IllegalArgumentException("Source file exceeds 100MB cloud fallback limit (${sourceFile.length()} bytes)")
            )
        }

        val tempEncryptedFile = File.createTempFile("nexa_r2_upload_", ".enc")
        try {
            // 1. Client-side AES-256-GCM encryption with SHA-256 cipher hash
            val encResult: EncryptedMediaResult = BlindMediaVaultEngine.encryptMediaStream(
                sourceFile = sourceFile,
                destinationEncryptedFile = tempEncryptedFile
            )

            // 2. Request ephemeral upload ticket from Go backend
            val ticketResult = requestUploadTicket(
                sessionToken = sessionToken,
                nxId = nxId,
                sizeBytes = encResult.sizeBytes,
                cipherHash = encResult.cipherHash
            )

            val ticket = ticketResult.getOrElse { return@withContext Result.failure(it) }

            // 3. Upload encrypted payload to Cloudflare R2 presigned URL
            val uploadResult = uploadEncryptedBlob(
                uploadUrl = ticket.uploadUrl,
                encryptedFile = tempEncryptedFile,
                onProgress = onProgress
            )

            uploadResult.getOrElse { return@withContext Result.failure(it) }

            Result.success(
                R2FallbackUploadResult(
                    objectKey = ticket.objectKey,
                    uploadUrl = ticket.uploadUrl,
                    cipherHash = encResult.cipherHash,
                    mediaKey = encResult.mediaKey,
                    iv = encResult.iv,
                    sizeBytes = encResult.sizeBytes
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "executeFallbackUpload failed", e)
            Result.failure(e)
        } finally {
            if (tempEncryptedFile.exists()) {
                tempEncryptedFile.delete()
            }
        }
    }

    override suspend fun executeFallbackDownload(
        downloadUrl: String,
        expectedCipherHash: String,
        mediaKey: ByteArray,
        iv: ByteArray,
        destinationDecryptedFile: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)?
    ): Result<File> = withContext(Dispatchers.IO) {
        val tempEncryptedFile = File.createTempFile("nexa_r2_dl_", ".enc")
        try {
            // 1. Download encrypted blob from presigned URL
            val downloadResult = downloadEncryptedBlob(
                downloadUrl = downloadUrl,
                destinationFile = tempEncryptedFile,
                onProgress = onProgress
            )
            downloadResult.getOrElse { return@withContext Result.failure(it) }

            // 2. Verify ciphertext hash and decrypt client-side using BlindMediaVaultEngine
            val success = BlindMediaVaultEngine.decryptMediaStream(
                encryptedSourceFile = tempEncryptedFile,
                destinationPlainFile = destinationDecryptedFile,
                mediaKey = mediaKey,
                iv = iv,
                expectedCipherHash = expectedCipherHash
            )

            if (!success) {
                return@withContext Result.failure(
                    SecurityException("Cryptographic verification or tag check failed during media decryption")
                )
            }

            Result.success(destinationDecryptedFile)
        } catch (e: Exception) {
            Log.e(TAG, "executeFallbackDownload failed", e)
            Result.failure(e)
        } finally {
            if (tempEncryptedFile.exists()) {
                tempEncryptedFile.delete()
            }
        }
    }
}
