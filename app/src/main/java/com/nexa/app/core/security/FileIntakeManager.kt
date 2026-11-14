package com.nexa.app.core.security

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class IntakeResult(
    val stagedFile: File,
    val declaredName: String,
    val declaredExtension: String,
    val fileSizeBytes: Long,
    val fileHash: String
)

sealed interface IntakeError {
    data class Blocked(val reason: String, val threatCategory: ThreatCategory) : IntakeError
    data class Inaccessible(val message: String) : IntakeError
}

/**
 * Handles safe file intake from Android Storage Access Framework (SAF) Uris.
 * Enforces bounded streaming to protect memory and prepares files for security scanning.
 */
class FileIntakeManager(
    private val context: Context,
    private val quarantineManager: QuarantineManager
) {

    suspend fun stageOutboundFile(uri: Uri): Result<IntakeResult> = withContext(Dispatchers.IO) {
        val (declaredName, sizeBytes) = queryUriMetadata(uri)
        val ext = declaredName.substringAfterLast('.', "").lowercase().removePrefix(".").trim()

        if (SecurityPolicyManager.isExtensionBanned(ext)) {
            return@withContext Result.failure(
                SecurityException("Dangerous file extension is blocked: .$ext")
            )
        }

        if (sizeBytes > SecurityPolicyManager.MAX_FILE_SIZE_BYTES) {
            return@withContext Result.failure(
                SecurityException("File size exceeds maximum transfer limit (${SecurityPolicyManager.MAX_FILE_SIZE_BYTES / (1024 * 1024)} MB)")
            )
        }

        val stagingFile = File(context.cacheDir, "intake_${System.currentTimeMillis()}.tmp")

        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(SecurityException("Unable to open source file stream"))

            stream.use { input ->
                FileOutputStream(stagingFile).use { output ->
                    val buffer = ByteArray(16384)
                    var read: Int
                    var totalWritten = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        totalWritten += read
                        if (totalWritten > SecurityPolicyManager.MAX_FILE_SIZE_BYTES) {
                            stagingFile.delete()
                            return@withContext Result.failure(
                                SecurityException("File size exceeded limit during streaming")
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }

            val hash = HashEngine.computeSha256(stagingFile)
            Result.success(
                IntakeResult(
                    stagedFile = stagingFile,
                    declaredName = declaredName,
                    declaredExtension = ext,
                    fileSizeBytes = stagingFile.length(),
                    fileHash = hash
                )
            )
        } catch (e: Exception) {
            stagingFile.delete()
            Result.failure(e)
        }
    }

    private fun queryUriMetadata(uri: Uri): Pair<String, Long> {
        var name = "attachment_${System.currentTimeMillis()}"
        var size = 0L

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        name = cursor.getString(nameIdx) ?: name
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx != -1) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (_: Exception) {}

        return Pair(name, size)
    }
}
