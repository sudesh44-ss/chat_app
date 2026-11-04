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
     */
    suspend fun downloadVault(
        fileId: String,
        destinationFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken()
        val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"

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
                throw IOException("Download failed with HTTP ${response.code}")
            }

            val responseBody = response.body ?: throw IOException("Empty response body from Drive media download")
            val totalBytes = responseBody.contentLength()

            if (destinationFile.parentFile?.exists() == false) {
                destinationFile.parentFile?.mkdirs()
            }

            responseBody.byteStream().use { inStream ->
                FileOutputStream(destinationFile).use { outStream ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var totalRead = 0L

                    while (inStream.read(buffer).also { read = it } != -1) {
                        outStream.write(buffer, 0, read)
                        totalRead += read
                        onProgress(totalRead, totalBytes)
                    }
                    outStream.flush()
                }
            }

            destinationFile.exists() && destinationFile.length() > 0
        }
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
