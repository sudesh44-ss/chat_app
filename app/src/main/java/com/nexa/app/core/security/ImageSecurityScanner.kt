package com.nexa.app.core.security

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

sealed interface ImageScanVerdict {
    data class Safe(val width: Int, val height: Int, val mimeType: String) : ImageScanVerdict
    data class Rejected(val reason: String, val threatCategory: ThreatCategory) : ImageScanVerdict
}

/**
 * Image security and dimension bomb defense scanner.
 * Verifies:
 * 1. Safe dimension boundaries (< 8192px width & height)
 * 2. Total pixel count limits (< 33.5 Megapixels / ~134 MB in memory)
 * 3. Decoder sanity without triggering native OutOfMemoryError
 * 4. Strips trailing payloads & EXIF metadata via clean re-encoding
 */
object ImageSecurityScanner {

    const val MAX_IMAGE_DIMENSION_PX = 8192
    const val MAX_IMAGE_PIXELS = 33_554_432L // ~33.5 Megapixels

    fun scanImage(file: File): ImageScanVerdict = scanImageFile(file)

    fun reEncodeToWebP(source: File, destination: File, quality: Int = 88): Boolean {
        return sanitizeImage(source, destination, Bitmap.CompressFormat.WEBP, quality)
    }

    fun scanImageFile(file: File): ImageScanVerdict {
        if (!file.exists() || file.length() == 0L) {
            return ImageScanVerdict.Rejected("Image file is missing or empty", ThreatCategory.CORRUPTED_STREAM)
        }

        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        try {
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
        } catch (t: Throwable) {
            return ImageScanVerdict.Rejected("Malformed image header or decode failure: ${t.message}", ThreatCategory.MALFORMED_STRUCTURE)
        }

        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        val mimeType = boundsOptions.outMimeType ?: "image/unknown"

        if (width <= 0 || height <= 0) {
            return ImageScanVerdict.Rejected("Invalid or unparseable image dimensions ($width x $height)", ThreatCategory.MALFORMED_STRUCTURE)
        }

        if (width > MAX_IMAGE_DIMENSION_PX || height > MAX_IMAGE_DIMENSION_PX) {
            return ImageScanVerdict.Rejected(
                "Image dimension bomb blocked: Dimensions ($width x $height) exceed ceiling of ${MAX_IMAGE_DIMENSION_PX}px",
                ThreatCategory.IMAGE_DIMENSION_BOMB
            )
        }

        val totalPixels = width.toLong() * height.toLong()
        if (totalPixels > MAX_IMAGE_PIXELS) {
            return ImageScanVerdict.Rejected(
                "Pixel decompression bomb blocked: Total pixels ($totalPixels) exceed maximum limit ($MAX_IMAGE_PIXELS)",
                ThreatCategory.IMAGE_DIMENSION_BOMB
            )
        }

        return ImageScanVerdict.Safe(width, height, mimeType)
    }

    /**
     * Re-encodes an image file to neutralize trailing steganographic payloads and EXIF metadata.
     */
    fun sanitizeImage(inputFile: File, outputFile: File, format: Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP, quality: Int = 90): Boolean {
        val verdict = scanImageFile(inputFile)
        if (verdict !is ImageScanVerdict.Safe) return false

        return try {
            val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath) ?: return false
            FileOutputStream(outputFile).use { fos ->
                val success = bitmap.compress(format, quality, fos)
                fos.flush()
                bitmap.recycle()
                success
            }
        } catch (e: Exception) {
            false
        }
    }
}
