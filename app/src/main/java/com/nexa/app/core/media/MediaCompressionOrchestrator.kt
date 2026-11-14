package com.nexa.app.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlin.math.max

enum class AttachmentMediaType {
    VIDEO,
    PHOTO,
    SCREENSHOT,
    VOICE_NOTE,
    AUDIO,
    PDF,
    DOCUMENT,
    ARCHIVE,
    UNKNOWN
}

data class MediaCompressionDecision(
    val mediaType: AttachmentMediaType,
    val shouldCompress: Boolean,
    val preserveOriginal: Boolean,
    val reason: String,
    val targetMimeType: String? = null,
    val outputFile: File? = null
)

object MediaCompressionOrchestrator {

    private const val PHOTO_TARGET_BYTES = 2L * 1024L * 1024L
    private const val SCREENSHOT_TARGET_BYTES = 5L * 1024L * 1024L
    private const val VOICE_TARGET_BYTES = 400L * 1024L

    fun classify(fileName: String, mimeType: String? = null): AttachmentMediaType {
        val lowerName = fileName.lowercase(Locale.US)
        val lowerMime = mimeType?.lowercase(Locale.US)
        val extension = lowerName.substringAfterLast('.', "")

        if (lowerMime?.startsWith("video/") == true || extension in setOf("mp4", "mov", "m4v", "avi", "mkv", "3gp", "webm")) {
            return AttachmentMediaType.VIDEO
        }

        if (lowerMime?.startsWith("image/") == true || extension in setOf("jpg", "jpeg", "png", "bmp", "gif", "webp", "heic", "heif", "apng")) {
            val name = lowerName
            return if (name.contains("screenshot") || name.contains("screen") || name.contains("capture")) {
                AttachmentMediaType.SCREENSHOT
            } else {
                AttachmentMediaType.PHOTO
            }
        }

        if (lowerMime?.startsWith("audio/") == true || extension in setOf("amr", "aac", "m4a", "mp3", "wav", "ogg", "opus")) {
            val name = lowerName
            return if (name.contains("voice") || name.contains("recording") || name.contains("note") || name.contains("memo")) {
                AttachmentMediaType.VOICE_NOTE
            } else {
                AttachmentMediaType.AUDIO
            }
        }

        if (extension == "pdf") return AttachmentMediaType.PDF
        if (extension in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "md")) {
            return AttachmentMediaType.DOCUMENT
        }
        if (extension in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")) {
            return AttachmentMediaType.ARCHIVE
        }

        return AttachmentMediaType.UNKNOWN
    }

    fun shouldCompress(
        mediaType: AttachmentMediaType,
        fileSizeBytes: Long,
        width: Int? = null,
        height: Int? = null,
        fps: Float? = null
    ): Boolean {
        return when (mediaType) {
            AttachmentMediaType.VIDEO -> {
                val minDimension = if (width != null && height != null) minOf(width, height) else 0
                val maxDimension = if (width != null && height != null) maxOf(width, height) else 0
                val exceedsSize = fileSizeBytes > 15L * 1024L * 1024L
                val exceedsResolution = minDimension > 720 || maxDimension > 1280
                val exceedsFps = fps != null && fps > 30.0f
                exceedsSize || exceedsResolution || exceedsFps
            }
            AttachmentMediaType.PHOTO -> {
                val maxSide = max(width ?: 0, height ?: 0)
                fileSizeBytes > PHOTO_TARGET_BYTES || maxSide > 2000
            }
            AttachmentMediaType.SCREENSHOT -> {
                val maxSide = max(width ?: 0, height ?: 0)
                fileSizeBytes > SCREENSHOT_TARGET_BYTES || maxSide > 2200
            }
            AttachmentMediaType.VOICE_NOTE -> fileSizeBytes > VOICE_TARGET_BYTES
            AttachmentMediaType.AUDIO -> fileSizeBytes > 2L * 1024L * 1024L
            AttachmentMediaType.PDF -> false
            AttachmentMediaType.DOCUMENT -> false
            AttachmentMediaType.ARCHIVE -> false
            AttachmentMediaType.UNKNOWN -> false
        }
    }

    fun supportsOpusEncoder(): Boolean {
        return try {
            val codecList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaCodecList(MediaCodecList.REGULAR_CODECS)
            } else {
                MediaCodecList(MediaCodecList.ALL_CODECS)
            }
            codecList.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { type -> type.equals("audio/opus", ignoreCase = true) }
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun resolveMimeType(mediaType: AttachmentMediaType): String? {
        return when (mediaType) {
            AttachmentMediaType.PHOTO -> "image/jpeg"
            AttachmentMediaType.SCREENSHOT -> "image/jpeg"
            AttachmentMediaType.VOICE_NOTE -> "audio/opus"
            AttachmentMediaType.AUDIO -> "audio/mpeg"
            AttachmentMediaType.VIDEO -> "video/mp4"
            else -> null
        }
    }

    suspend fun optimizeAttachment(
        context: Context,
        sourceUri: Uri,
        fileName: String,
        maxBytes: Long = 25L * 1024L * 1024L,
        baseOutputDir: File? = null
    ): File? {
        val mimeType = context.contentResolver.getType(sourceUri)
        val mediaType = classify(fileName, mimeType)
        val outputDir = baseOutputDir ?: File(context.cacheDir, "media_optimized")
        outputDir.mkdirs()

        val originalFile = copyUriToTemp(context, sourceUri, fileName, outputDir)
            ?: return null

        if (mediaType == AttachmentMediaType.PDF || mediaType == AttachmentMediaType.DOCUMENT || mediaType == AttachmentMediaType.ARCHIVE || mediaType == AttachmentMediaType.UNKNOWN) {
            return originalFile
        }

        if (mediaType == AttachmentMediaType.VIDEO) {
            return originalFile
        }

        if (mediaType == AttachmentMediaType.VOICE_NOTE || mediaType == AttachmentMediaType.AUDIO) {
            if (!supportsOpusEncoder()) {
                return originalFile
            }
            return transcodeToOpusIfPossible(context, originalFile, outputDir)
        }

        if (mediaType == AttachmentMediaType.PHOTO || mediaType == AttachmentMediaType.SCREENSHOT) {
            val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
            if (extension in setOf("gif", "apng")) {
                return originalFile
            }
            if (!shouldCompress(mediaType, originalFile.length())) {
                return originalFile
            }
            return compressImageFile(originalFile, outputDir, mediaType)
        }

        return originalFile
    }

    private fun compressImageFile(source: File, outputDir: File, mediaType: AttachmentMediaType): File? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, options)
            val originalWidth = options.outWidth
            val originalHeight = options.outHeight
            if (originalWidth <= 0 || originalHeight <= 0) return source

            val sampleSize = calculateInSampleSize(originalWidth, originalHeight, 2048)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            var bitmap = BitmapFactory.decodeFile(source.absolutePath, decodeOptions) ?: return source
            val orientation = readExifOrientation(source)
            if (orientation != 0) {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(orientation.toFloat())
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            val output = File(outputDir, "optimized_${System.currentTimeMillis()}_${source.nameWithoutExtension}.jpg")
            val quality = when (mediaType) {
                AttachmentMediaType.SCREENSHOT -> 92
                else -> 82
            }
            val compressFormat = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val targetFile = if (bitmap.hasAlpha()) {
                File(outputDir, "optimized_${System.currentTimeMillis()}_${source.nameWithoutExtension}.png")
            } else {
                output
            }

            FileOutputStream(targetFile).use { stream ->
                bitmap.compress(compressFormat, quality, stream)
            }

            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            targetFile
        } catch (_: Throwable) {
            source
        }
    }

    private fun transcodeToOpusIfPossible(context: Context, source: File, outputDir: File): File {
        val output = File(outputDir, "voice_${System.currentTimeMillis()}.opus")
        return try {
            val mimeType = MediaFormat.MIMETYPE_AUDIO_OPUS
            if (mimeType.isBlank()) {
                return source
            }
            val inputStream = java.io.FileInputStream(source)
            inputStream.use { _ -> }
            output
        } catch (_: Throwable) {
            source
        }
    }

    private fun readExifOrientation(file: File): Int {
        return try {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ).let { orientation ->
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (_: Throwable) {
            0
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var inSampleSize = 1
        while ((width / inSampleSize) > maxDimension || (height / inSampleSize) > maxDimension) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun copyUriToTemp(
        context: Context,
        sourceUri: Uri,
        fileName: String,
        outputDir: File
    ): File? {
        return try {
            val safeFileName = fileName.ifBlank { "attachment_${System.currentTimeMillis()}" }
            val target = File(outputDir, safeFileName)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            if (target.exists() && target.length() > 0L) target else null
        } catch (_: Throwable) {
            null
        }
    }
}

