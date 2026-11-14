package com.nexa.app.domain.repository

import android.net.Uri
import com.nexa.app.core.media.AttachmentMediaType
import com.nexa.app.core.media.CompressionProgress
import com.nexa.app.core.media.VideoMetadata
import kotlinx.coroutines.flow.Flow
import java.io.File

enum class VideoSendMode {
    COMPRESSED_HD, // Transcoded & downscaled to 720p/1080p HEVC (65-80% smaller)
    ORIGINAL_RAW   // Pass-through bit-exact document mode without modification
}

interface MediaProcessingRepository {
    suspend fun probeMetadata(videoUri: Uri): VideoMetadata?
    fun shouldCompress(metadata: VideoMetadata): Boolean
    fun processVideo(videoUri: Uri, mode: VideoSendMode): Flow<CompressionProgress>
    suspend fun classifyAttachment(uri: Uri, fileName: String, mimeType: String? = null): AttachmentMediaType
    suspend fun optimizeAttachment(uri: Uri, fileName: String, maxBytes: Long = 25L * 1024L * 1024L): File?
    fun cleanupTranscodeCache(file: File): Boolean
    fun clearAllTranscodeCache(): Int
}

