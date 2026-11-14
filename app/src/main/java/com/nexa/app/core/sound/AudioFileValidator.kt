package com.nexa.app.core.sound

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.nexa.app.data.local.preferences.SoundCategory
import java.io.File
import java.io.FileOutputStream

/**
 * Result of validating a user-selected audio file for NEXA Sound Settings.
 */
sealed class AudioValidationResult {
    data class Success(
        val cachedUri: String,
        val displayName: String,
        val durationMs: Long,
        val fileSize: Long
    ) : AudioValidationResult()

    data class Error(val reason: String) : AudioValidationResult()
}

/**
 * Validates, persists, and securely caches user-selected audio files for NEXA Sound Settings.
 * Supports: MP3, WAV, M4A, OGG.
 * Enforces maximum size limit (15MB) and decodability.
 * Zero Server Upload: Stored strictly in app-private directory (context.filesDir/sounds).
 */
object AudioFileValidator {

    private const val TAG = "AudioFileValidator"
    const val MAX_FILE_SIZE_BYTES = 15L * 1024L * 1024L // 15 MB
    const val MAX_DURATION_MS = 600_000L // 10 minutes maximum duration
    private val ALLOWED_EXTENSIONS = setOf("mp3", "wav", "m4a", "ogg")
    private val ALLOWED_MIME_TYPES = setOf(
        "audio/mpeg",
        "audio/mp3",
        "audio/wav",
        "audio/x-wav",
        "audio/mp4",
        "audio/m4a",
        "audio/x-m4a",
        "audio/ogg",
        "application/ogg",
        "audio/flac"
    )

    /**
     * Inspects, validates, caches, and registers an audio file from a Storage Access Framework Uri.
     */
    fun validateAndCacheAudio(
        context: Context,
        sourceUri: Uri,
        category: SoundCategory
    ): AudioValidationResult {
        try {
            // 1. Attempt to take persistable URI permission if applicable
            try {
                context.contentResolver.takePersistableUriPermission(
                    sourceUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "Persistable URI permission could not be taken: ${e.message}")
            }

            // 2. Query file metadata (display name & size)
            var displayName = "custom_audio_${category.name.lowercase()}"
            var fileSize = 0L

            context.contentResolver.query(sourceUri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        cursor.getString(nameIndex)?.let { displayName = it }
                    }
                    if (sizeIndex != -1) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }

            // 3. Extract and check extension
            val extension = displayName.substringAfterLast('.', "").lowercase()
            val mimeType = context.contentResolver.getType(sourceUri)?.lowercase()

            val isExtensionValid = extension in ALLOWED_EXTENSIONS
            val isMimeValid = mimeType != null && ALLOWED_MIME_TYPES.any { mimeType.contains(it) }

            if (!isExtensionValid && !isMimeValid) {
                return AudioValidationResult.Error(
                    "Unsupported audio format. NEXA supports MP3, WAV, M4A, and OGG files."
                )
            }

            // 4. Validate file size
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return AudioValidationResult.Error(
                    "Audio file exceeds maximum size limit of 15MB (${fileSize / (1024 * 1024)}MB)."
                )
            }

            // 5. Securely copy to app-private storage: filesDir/sounds/custom_{category}.{ext}
            val soundDir = File(context.filesDir, "sounds")
            if (!soundDir.exists()) {
                soundDir.mkdirs()
            }

            val safeExt = if (extension in ALLOWED_EXTENSIONS) extension else "mp3"
            val targetFile = File(soundDir, "custom_${category.name.lowercase()}.$safeExt")

            var copiedBytes = 0L
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        copiedBytes += bytesRead
                        if (copiedBytes > MAX_FILE_SIZE_BYTES) {
                            targetFile.delete()
                            return AudioValidationResult.Error("Audio file exceeds maximum size limit of 15MB.")
                        }
                        output.write(buffer, 0, bytesRead)
                    }
                }
            } ?: return AudioValidationResult.Error("Could not open audio stream from storage provider.")

            if (fileSize == 0L) {
                fileSize = copiedBytes
            }

            // 6. Verify audio decodability and duration via MediaMetadataRetriever
            val retriever = MediaMetadataRetriever()
            var durationMs = 0L
            try {
                retriever.setDataSource(targetFile.absolutePath)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationMs = durationStr?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                Log.e(TAG, "Audio decodability check failed", e)
                targetFile.delete()
                return AudioValidationResult.Error("Corrupted or undecodable audio file: ${e.message}")
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {}
            }

            if (durationMs > MAX_DURATION_MS) {
                targetFile.delete()
                return AudioValidationResult.Error("Audio duration exceeds 10 minutes limit.")
            }

            val targetUri = Uri.fromFile(targetFile)
            return AudioValidationResult.Success(
                cachedUri = targetUri.toString(),
                displayName = displayName,
                durationMs = durationMs,
                fileSize = fileSize
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate audio file", e)
            return AudioValidationResult.Error("Failed to process audio file: ${e.localizedMessage ?: "Unknown error"}")
        }
    }
}
