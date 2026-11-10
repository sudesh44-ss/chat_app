package com.nexa.app.data.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Direct REST API client for Google Drive v3 operating exclusively inside `appDataFolder`.
 */
class DriveAppDataService(
    private val authManager: GoogleDriveAuthManager,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    private val jsonMediaType = "application/json; charset=UTF-8".toMediaType()

    /**
     * Queries the hidden `appDataFolder` for an existing backup vault file.
     */
    suspend fun getExistingBackupMetadata(): DriveFile? = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken()
        val query = "name = '$BACKUP_FILE_NAME' and trashed = false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val fields = URLEncoder.encode("files(id,name,modifiedTime,size,appProperties)", "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$encodedQuery&fields=$fields"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401) {
                authManager.invalidateToken(token)
                throw IOException("Drive authorization expired (HTTP 401)")
            }

            if (!response.isSuccessful) {
                throw IOException("Failed to query appDataFolder: HTTP ${response.code} ${response.message}")
            }

            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val filesArray = json.optJSONArray("files") ?: return@withContext null

            if (filesArray.length() == 0) return@withContext null

            val item = filesArray.getJSONObject(0)
            val id = item.optString("id")
            val name = item.optString("name")
            val modifiedTime = item.optString("modifiedTime")
            val size = item.optLong("size", 0L)

            val appProps = mutableMapOf<String, String>()
            val propsObj = item.optJSONObject("appProperties")
            propsObj?.keys()?.forEach { key ->
                propsObj.optString(key)?.let { appProps[key] = it }
            }

            DriveFile(
                id = id,
                name = name,
                modifiedTime = modifiedTime,
                size = size,
                appProperties = appProps
            )
        }
    }

    /**
     * Initiates a resumable upload session on Google Drive.
     * Returns the session URI to which binary slices can be streamed.
     */
    suspend fun startResumableUploadSession(
        vaultFile: File,
        versionEpoch: Long,
        existingFileId: String? = null
    ): String = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken()
        val totalBytes = vaultFile.length()

        val isUpdate = !existingFileId.isNullOrBlank()
        val url = if (isUpdate) {
            "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=resumable"
        } else {
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
        }

        val metadataJson = JSONObject().apply {
            if (!isUpdate) {
                put("name", BACKUP_FILE_NAME)
                put("parents", org.json.JSONArray().put("appDataFolder"))
            }
            val appProps = JSONObject().apply {
                put("version_epoch", versionEpoch.toString())
            }
            put("appProperties", appProps)
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", "application/octet-stream")
            .header("X-Upload-Content-Length", totalBytes.toString())

        if (isUpdate) {
            requestBuilder.patch(metadataJson.toString().toRequestBody(jsonMediaType))
        } else {
            requestBuilder.post(metadataJson.toString().toRequestBody(jsonMediaType))
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 401) {
                authManager.invalidateToken(token)
                throw IOException("Drive authorization expired (HTTP 401)")
            }

            if (!response.isSuccessful && response.code !in 200..201) {
                throw IOException("Failed to initiate resumable upload session: HTTP ${response.code}")
            }

            val sessionUri = response.header("Location")
                ?: throw IOException("Google Drive did not return a resumable session URI header (Location)")

            sessionUri
        }
    }

    /**
     * Downloads an encrypted backup vault from Google Drive into a local cache destination.
     * Supports HTTP Range-based download resumption (HTTP 206 Partial Content), ETag verification,
     * and automatic fallback to fresh download (HTTP 200 OK) if the server does not support Range
     * or if the remote file has changed.
     */
    suspend fun downloadVault(
        fileId: String,
        destinationFile: File,
        expectedTotalBytes: Long? = null,
        expectedEtag: String? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken()
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"

        val parentDir = destinationFile.parentFile ?: destinationFile.absoluteFile.parentFile
        if (parentDir?.exists() == false) {
            parentDir.mkdirs()
        }
        val partFile = File(parentDir, "${destinationFile.name}.part")

        var currentBytes = if (partFile.exists()) partFile.length() else 0L

        // If local part file already equals expected size, promote immediately
        if (expectedTotalBytes != null && expectedTotalBytes > 0 && currentBytes >= expectedTotalBytes) {
            if (destinationFile.exists()) destinationFile.delete()
            if (partFile.renameTo(destinationFile)) {
                onProgress(expectedTotalBytes, expectedTotalBytes)
                return@withContext true
            }
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")

        if (currentBytes > 0) {
            requestBuilder.header("Range", "bytes=$currentBytes-")
            if (!expectedEtag.isNullOrBlank()) {
                requestBuilder.header("If-Match", expectedEtag)
            }
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 401) {
                authManager.invalidateToken(token)
                throw IOException("Drive authorization expired (HTTP 401)")
            }

            // Handle 416 Range Not Satisfiable: local file invalid or range past EOF
            if (response.code == 416) {
                partFile.delete()
                currentBytes = 0L
                val freshRequest = Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", "Bearer $token")
                    .build()

                return@withContext client.newCall(freshRequest).execute().use { freshResp ->
                    processDownloadStream(freshResp, partFile, destinationFile, expectedEtag, onProgress)
                }
            }

            processDownloadStream(response, partFile, destinationFile, expectedEtag, onProgress)
        }
    }

    private fun processDownloadStream(
        response: okhttp3.Response,
        partFile: File,
        destinationFile: File,
        expectedEtag: String?,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Boolean {
        if (!response.isSuccessful && response.code !in listOf(200, 206)) {
            throw IOException("Download failed with HTTP ${response.code}")
        }

        val responseBody = response.body ?: throw IOException("Empty response body from Drive media download")
        val code = response.code

        val currentEtag = response.header("ETag")?.trim('"')
        if (!expectedEtag.isNullOrBlank() && !currentEtag.isNullOrBlank()) {
            if (!currentEtag.equals(expectedEtag.trim('"'), ignoreCase = true)) {
                partFile.delete()
                throw IOException("Remote backup file ETag changed from $expectedEtag to $currentEtag. Restarting download.")
            }
        }

        val isRangeResponse = code == 206
        var append = false
        var totalBytes = responseBody.contentLength()
        var startingOffset = 0L

        if (isRangeResponse) {
            val contentRange = response.header("Content-Range")
            if (!contentRange.isNullOrBlank() && contentRange.startsWith("bytes ")) {
                val rangeParts = contentRange.removePrefix("bytes ").trim().split("/")
                val byteRange = rangeParts.getOrNull(0)?.split("-")
                val startByte = byteRange?.getOrNull(0)?.toLongOrNull() ?: 0L
                val totalLength = rangeParts.getOrNull(1)?.toLongOrNull() ?: -1L

                if (totalLength > 0) {
                    totalBytes = totalLength
                }

                if (startByte == partFile.length() && partFile.exists()) {
                    append = true
                    startingOffset = startByte
                } else {
                    partFile.delete()
                    append = false
                    startingOffset = 0L
                }
            } else {
                partFile.delete()
                append = false
                startingOffset = 0L
            }
        } else {
            // Full file (200 OK): restart from 0, never append 200 response to existing partial file
            partFile.delete()
            append = false
            startingOffset = 0L
        }

        responseBody.byteStream().use { inStream ->
            FileOutputStream(partFile, append).use { outStream ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                var totalRead = startingOffset

                while (inStream.read(buffer).also { read = it } != -1) {
                    outStream.write(buffer, 0, read)
                    totalRead += read
                    onProgress(totalRead, if (totalBytes > 0) totalBytes else totalRead)
                }
                outStream.flush()
            }
        }

        if (totalBytes > 0 && partFile.length() != totalBytes) {
            throw IOException("Downloaded file size (${partFile.length()}) does not match expected size ($totalBytes)")
        }

        if (destinationFile.exists()) destinationFile.delete()
        val renamed = partFile.renameTo(destinationFile)
        if (!renamed) {
            partFile.copyTo(destinationFile, overwrite = true)
            partFile.delete()
        }

        return destinationFile.exists() && destinationFile.length() > 0
    }

    /**
     * Deletes a remote backup vault file by ID.
     */
    suspend fun deleteFile(fileId: String): Boolean = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken()
        val url = "https://www.googleapis.com/drive/v3/files/$fileId"

        val request = Request.Builder()
            .url(url)
            .delete()
            .header("Authorization", "Bearer $token")
            .build()

        client.newCall(request).execute().use { response ->
            response.isSuccessful || response.code == 404
        }
    }

    companion object {
        const val BACKUP_FILE_NAME = "nx_secure_backup.vault"
    }
}
