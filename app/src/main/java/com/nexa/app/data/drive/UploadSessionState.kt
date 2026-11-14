package com.nexa.app.data.drive

import java.io.IOException
import java.net.URI

/**
 * Thrown when Google Drive rejects a resumable upload session URI with HTTP 404, 410,
 * indicating the server-side upload session has expired or been purged.
 */
class UploadSessionExpiredException(message: String) : IOException(message)

/**
 * Immutable model of an active Google Drive resumable upload session.
 * Encrypted and persisted in SecureStorage across application process death and device restarts.
 */
data class UploadSessionState(
    val sessionUri: String,
    val versionEpoch: Long,
    val vaultFilePath: String,
    val expectedFileSize: Long,
    val lastConfirmedOffset: Long,
    val updatedAt: Long,
    val existingFileId: String? = null
) {
    /**
     * Verifies that the session metadata and URI meet strict security requirements.
     * Rejects non-HTTPS schemes and non-Google Drive hosts to protect against SSRF and injection.
     */
    fun isValid(): Boolean {
        return isValidSessionUri(sessionUri) && expectedFileSize > 0 && vaultFilePath.isNotBlank()
    }

    companion object {
        fun isValidSessionUri(uriStr: String?): Boolean {
            if (uriStr.isNullOrBlank()) return false
            return try {
                val uri = URI(uriStr)
                uri.scheme.equals("https", ignoreCase = true) &&
                        uri.host != null &&
                        uri.host.equals("www.googleapis.com", ignoreCase = true) &&
                        uri.path != null &&
                        uri.path.startsWith("/upload/drive/v3/files")
            } catch (e: Exception) {
                false
            }
        }
    }
}
