package com.nexa.app.core.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class VideoMetadata(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val originalBitrate: Long,
    val sizeBytes: Long,
    val fps: Float = 30.0f,
    val rotationDegrees: Int = 0,
    val mimeType: String = "video/mp4"
) {
    /**
     * Effective visual width after applying display rotation (portrait vs landscape).
     */
    val displayWidth: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width

    /**
     * Effective visual height after applying display rotation (portrait vs landscape).
     */
    val displayHeight: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

    val durationFormatted: String
        get() {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }

    val formattedSize: String
        get() {
            val mb = sizeBytes.toDouble() / (1024.0 * 1024.0)
            return if (mb >= 1.0) {
                String.format(Locale.US, "%.1f MB", mb)
            } else {
                val kb = sizeBytes.toDouble() / 1024.0
                String.format(Locale.US, "%.0f KB", kb)
            }
        }
}

object VideoMetadataProbe {
    private const val TAG = "VideoMetadataProbe"

    suspend fun probe(context: Context, videoUri: Uri): VideoMetadata? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"

            var size = 0L
            try {
                context.contentResolver.openFileDescriptor(videoUri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            } catch (e: Exception) {
                // Fallback to querying OpenableColumns
                try {
                    context.contentResolver.query(videoUri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst() && sizeIndex != -1) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                } catch (ignored: Exception) {}
            }

            val fps = extractFramerate(context, videoUri, retriever, duration)

            VideoMetadata(
                width = rawWidth,
                height = rawHeight,
                durationMs = duration,
                originalBitrate = bitrate,
                sizeBytes = size,
                fps = fps,
                rotationDegrees = rotation,
                mimeType = mime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to probe video metadata for $videoUri", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {}
        }
    }

    private fun extractFramerate(
        context: Context,
        uri: Uri,
        retriever: MediaMetadataRetriever,
        durationMs: Long
    ): Float {
        // 1. MediaExtractor check for exact video track frame rate (API 16+)
        try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            val rate = format.getInteger(MediaFormat.KEY_FRAME_RATE)
                            if (rate > 0) return rate.toFloat()
                        }
                    }
                }
            } finally {
                extractor.release()
            }
        } catch (ignored: Exception) {}

        // 2. MediaMetadataRetriever capture framerate on Android 11+ (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                if (captureFps != null && captureFps > 0f) {
                    return captureFps
                }
            } catch (ignored: Exception) {}
        }

        // 3. MediaMetadataRetriever frame count / duration on Android 9+ (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && durationMs > 0) {
            try {
                val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
                if (frameCount != null && frameCount > 0) {
                    val computed = (frameCount.toFloat() * 1000f) / durationMs
                    if (computed in 1.0f..240.0f) {
                        return computed
                    }
                }
            } catch (ignored: Exception) {}
        }

        return 30.0f
    }

    /**
     * Calculates estimated compressed size in bytes using target bitrate heuristic:
     * EstSizeBytes = ((targetVideoBitrate + targetAudioBitrate) / 8) * (durationMs / 1000)
     */
    fun calculateEstimatedSizeBytes(
        durationMs: Long,
        targetVideoBitrate: Long = VideoTransformConfig.TARGET_VIDEO_BITRATE_720P.toLong(),
        targetAudioBitrate: Long = VideoTransformConfig.AUDIO_BITRATE_AAC.toLong()
    ): Long {
        if (durationMs <= 0L) return 0L
        val totalBitrateBps = targetVideoBitrate + targetAudioBitrate
        val durationSeconds = durationMs.toDouble() / 1000.0
        val bytes = ((totalBitrateBps.toDouble() / 8.0) * durationSeconds).toLong()
        return bytes.coerceAtLeast(1024L)
    }
}

