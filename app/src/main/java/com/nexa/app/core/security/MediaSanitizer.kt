package com.nexa.app.core.security

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * On-Device Image Re-Encoding Sanitizer (Steganography & Payload Neutralizer)
 *
 * Traditional apps rely on heavy server-side GPU/CPU transcode clusters.
 * In NEXA's Zero-Cloud P2P model, incoming image payloads are re-encoded on-device:
 * 1. Safe inJustDecodeBounds probe rejects OOM Decompression Bombs.
 * 2. Decodes raw bytes into a pure ARGB pixel matrix in memory.
 * 3. Re-encodes to a clean WebP/JPEG image, discarding:
 *    - Payloads hidden in Least Significant Bits (LSB).
 *    - Malicious payloads appended after EOF markers (e.g. `\xFF\xD9` in JPEG or `IEND` in PNG).
 *    - EXIF metadata (GPS coordinates, device tracking identifiers, embedded comments).
 */
object MediaSanitizer {

    private const val TAG = "MediaSanitizer"

    // Safe bounds to prevent Decompression Bomb (OOM) attacks
    const val MAX_IMAGE_DIMENSION = 8192
    const val MAX_IMAGE_PIXELS = 33_177_600L // ~33 Megapixels (e.g. 7680x4320 / 8K)

    val SANITIZABLE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp")

    fun isSanitizableImage(extension: String): Boolean {
        return extension.lowercase().removePrefix(".").trim() in SANITIZABLE_EXTENSIONS
    }

    /**
     * Decode the raw bytes into a pure pixel array and re-encode to a clean file.
     * This strips out any hidden steganographic payload appended after EOF or inside LSBs,
     * as well as EXIF GPS and metadata tags.
     */
    fun sanitizeImage(
        rawFile: File,
        sanitizedOutputFile: File,
        deleteOriginal: Boolean = true,
        quality: Int = 90
    ): Boolean {
        if (!rawFile.exists() || rawFile.length() == 0L) return false

        return try {
            // 1. Safe Dimension Probe (inJustDecodeBounds check first to prevent OOM decompression bomb)
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(rawFile.absolutePath, boundsOptions)

            val width = boundsOptions.outWidth
            val height = boundsOptions.outHeight

            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Image decode failed: invalid dimensions ${width}x${height}")
                return false
            }

            val totalPixels = width.toLong() * height.toLong()
            if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION || totalPixels > MAX_IMAGE_PIXELS) {
                Log.w(TAG, "Image rejected: Exceeds safe decompression limits (${width}x${height}, $totalPixels px)")
                return false
            }

            // 2. Decode into pure ARGB_8888 bitmap in memory
            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inJustDecodeBounds = false
            }

            val decodedBitmap = BitmapFactory.decodeFile(rawFile.absolutePath, decodeOptions)
                ?: return false

            // Ensure parent directory exists for sanitized file
            sanitizedOutputFile.parentFile?.let {
                if (!it.exists()) it.mkdirs()
            }

            // 3. Re-encode to clean WebP/JPEG format
            val format = if (sanitizedOutputFile.extension.lowercase() in setOf("jpg", "jpeg")) {
                Bitmap.CompressFormat.JPEG
            } else {
                Bitmap.CompressFormat.WEBP_LOSSY
            }

            FileOutputStream(sanitizedOutputFile).use { fos ->
                decodedBitmap.compress(format, quality.coerceIn(1, 100), fos)
                fos.flush()
            }

            decodedBitmap.recycle()

            // 4. Optionally shred or delete the unverified original raw file
            if (deleteOriginal && rawFile.absolutePath != sanitizedOutputFile.absolutePath) {
                rawFile.delete()
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sanitize image: ${e.message}", e)
            false
        }
    }
}
