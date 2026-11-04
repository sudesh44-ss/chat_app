package com.nexa.app.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nexa.app.core.media.CompressionProgress
import com.nexa.app.core.media.FastStartMuxerHelper
import com.nexa.app.core.media.VideoCompressionEngine
import com.nexa.app.core.media.VideoMetadata
import com.nexa.app.core.media.VideoMetadataProbe
import com.nexa.app.core.media.VideoTransformConfig
import com.nexa.app.core.security.MagicByteDetector
import com.nexa.app.core.security.SecurityVerdict
import com.nexa.app.domain.repository.MediaProcessingRepository
import com.nexa.app.domain.repository.VideoSendMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class MediaProcessingRepositoryImpl(
    private val context: Context,
    private val compressionEngine: VideoCompressionEngine
) : MediaProcessingRepository {

    companion object {
        private const val TAG = "MediaProcessingRepo"
    }

    override suspend fun probeMetadata(videoUri: Uri): VideoMetadata? {
        return VideoMetadataProbe.probe(context, videoUri)
    }

    override fun shouldCompress(metadata: VideoMetadata): Boolean {
        val minDimension = min(metadata.displayWidth, metadata.displayHeight)
        val maxDimension = max(metadata.displayWidth, metadata.displayHeight)
        val exceedsSize = metadata.sizeBytes > VideoTransformConfig.COMPRESSION_SIZE_THRESHOLD_BYTES
        val exceedsResolution = minDimension > VideoTransformConfig.COMPRESSION_MAX_HEIGHT_THRESHOLD ||
                maxDimension > 1280
        val exceedsFps = metadata.fps > VideoTransformConfig.COMPRESSION_MAX_FPS_THRESHOLD

        val needs = exceedsSize || exceedsResolution || exceedsFps
        Log.d(
            TAG,
            "Compression check: size=${metadata.formattedSize} (>15MB: $exceedsSize), " +
                    "res=${metadata.displayWidth}x${metadata.displayHeight} (minDim=$minDimension > 720: $exceedsResolution), " +
                    "fps=${metadata.fps} (>30: $exceedsFps) -> needsCompression=$needs"
        )
        return needs
    }

    override fun processVideo(videoUri: Uri, mode: VideoSendMode): Flow<CompressionProgress> {
        return flow {
            // Mode 1: Send Original RAW
            if (mode == VideoSendMode.ORIGINAL_RAW) {
                Log.i(TAG, "Pass-through mode requested (ORIGINAL_RAW) for $videoUri")
                val stagingDir = File(context.cacheDir, "raw_media").apply { if (!exists()) mkdirs() }
                val tempFile = File(stagingDir, "RAW_${System.currentTimeMillis()}.mp4")

                withContext(Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(videoUri)
                        ?: throw IOException("Unable to open input stream for $videoUri")

                    inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    // Security check on RAW file
                    val verdict = FileInputStream(tempFile).use {
                        MagicByteDetector.inspect(it, "mp4")
                    }
                    if (verdict != SecurityVerdict.CLEAN) {
                        tempFile.delete()
                        throw SecurityException("Security check failed for original media: $verdict")
                    }
                }

                emit(CompressionProgress.InProgress(100))
                emit(CompressionProgress.Completed(tempFile))
                return@flow
            }

            // Mode 2: COMPRESSED_HD
            val metadata = probeMetadata(videoUri)
            val (targetHeight, targetBitrate, inputFps) = if (metadata != null) {
                val maxDim = max(metadata.displayWidth, metadata.displayHeight)
                val targetH = when {
                    maxDim >= 2160 -> VideoTransformConfig.MAX_HEIGHT_1080P
                    maxDim >= 1080 -> VideoTransformConfig.MAX_HEIGHT_720P
                    else -> min(metadata.displayHeight, VideoTransformConfig.MAX_HEIGHT_720P)
                }
                val targetBps = when {
                    targetH >= VideoTransformConfig.MAX_HEIGHT_1080P -> VideoTransformConfig.TARGET_VIDEO_BITRATE_1080P
                    targetH >= VideoTransformConfig.MAX_HEIGHT_720P -> VideoTransformConfig.TARGET_VIDEO_BITRATE_720P
                    else -> VideoTransformConfig.TARGET_VIDEO_BITRATE_SD
                }
                Triple(targetH, targetBps, metadata.fps)
            } else {
                Triple(
                    VideoTransformConfig.MAX_HEIGHT_720P,
                    VideoTransformConfig.TARGET_VIDEO_BITRATE_720P,
                    30.0f
                )
            }

            Log.i(
                TAG,
                "Routing to compression engine: targetHeight=$targetHeight, targetBitrate=$targetBitrate bps, fps=$inputFps"
            )

            compressionEngine.compress(
                inputUri = videoUri,
                targetHeight = targetHeight,
                inputFps = inputFps,
                targetBitrate = targetBitrate
            ).collect { progress ->
                when (progress) {
                    is CompressionProgress.InProgress -> {
                        emit(progress)
                    }
                    is CompressionProgress.Completed -> {
                        val file = progress.outputFile
                        // Gate 1: Integrity and Magic Byte validation
                        val verdict = withContext(Dispatchers.IO) {
                            FileInputStream(file).use {
                                MagicByteDetector.inspect(it, "mp4")
                            }
                        }
                        if (verdict != SecurityVerdict.CLEAN) {
                            file.delete()
                            throw SecurityException("Compressed video failed security verification: $verdict")
                        }

                        // Gate 2: Fast-Start MOOV atom placement verification
                        val isFastStart = FastStartMuxerHelper.isFastStartOptimized(file)
                        val originalSize = metadata?.sizeBytes ?: 0L
                        val compressedSize = file.length()
                        val ratio = if (originalSize > 0) {
                            ((originalSize - compressedSize).toDouble() / originalSize.toDouble()) * 100.0
                        } else 0.0

                        Log.i(
                            TAG,
                            "Compressed video ready: ${file.name} ($compressedSize bytes, savings=${ratio.toInt()}%, fastStart=$isFastStart)"
                        )

                        emit(CompressionProgress.Completed(file))
                    }
                    is CompressionProgress.Failed -> {
                        emit(progress)
                    }
                }
            }
        }.flowOn(Dispatchers.Default)
    }

    override fun cleanupTranscodeCache(file: File): Boolean {
        return try {
            if (file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Cleaned up cache file: ${file.name} (deleted=$deleted)")
                deleted
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning cache file: ${file.name}", e)
            false
        }
    }

    override fun clearAllTranscodeCache(): Int {
        var count = 0
        try {
            val transcodeDir = File(context.cacheDir, VideoTransformConfig.TRANSCODE_CACHE_DIR)
            if (transcodeDir.exists() && transcodeDir.isDirectory) {
                transcodeDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.delete()) {
                        count++
                    }
                }
            }
            val rawDir = File(context.cacheDir, "raw_media")
            if (rawDir.exists() && rawDir.isDirectory) {
                rawDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.delete()) {
                        count++
                    }
                }
            }
            Log.d(TAG, "Cleared $count temporary transcode cache files")
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing transcode cache", e)
        }
        return count
    }
}

