package com.nexa.app.core.security

import android.media.ExifInterface
import java.io.File

data class MetadataReport(
    val hasGpsLocation: Boolean,
    val hasDeviceMetadata: Boolean,
    val hasAuthorMetadata: Boolean,
    val sanitized: Boolean
)

/**
 * Inspects and sanitizes metadata from images and documents to prevent
 * accidental information leakage (e.g. GPS coordinates, device serials, author names).
 */
object MetadataAnalyzer {

    // Sensitive EXIF tags
    private val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD
    )

    private val DEVICE_TAGS = listOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION
    )

    private val AUTHOR_TAGS = listOf(
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_USER_COMMENT
    )

    fun inspectImageMetadata(file: File): MetadataReport {
        if (!file.exists() || file.length() < 16) {
            return MetadataReport(hasGpsLocation = false, hasDeviceMetadata = false, hasAuthorMetadata = false, sanitized = true)
        }

        return try {
            val exif = ExifInterface(file.absolutePath)
            val hasGps = GPS_TAGS.any { exif.getAttribute(it) != null }
            val hasDevice = DEVICE_TAGS.any { exif.getAttribute(it) != null }
            val hasAuthor = AUTHOR_TAGS.any { exif.getAttribute(it) != null }

            MetadataReport(
                hasGpsLocation = hasGps,
                hasDeviceMetadata = hasDevice,
                hasAuthorMetadata = hasAuthor,
                sanitized = !hasGps && !hasDevice && !hasAuthor
            )
        } catch (_: Exception) {
            MetadataReport(hasGpsLocation = false, hasDeviceMetadata = false, hasAuthorMetadata = false, sanitized = true)
        }
    }

    /**
     * Strips GPS and device identifier tags from JPEG EXIF headers in-place.
     */
    fun stripImageExif(file: File): Boolean {
        if (!file.exists() || file.length() < 16) return false

        return try {
            val exif = ExifInterface(file.absolutePath)
            var modified = false

            for (tag in GPS_TAGS) {
                if (exif.getAttribute(tag) != null) {
                    exif.setAttribute(tag, null)
                    modified = true
                }
            }

            for (tag in DEVICE_TAGS) {
                if (exif.getAttribute(tag) != null) {
                    exif.setAttribute(tag, null)
                    modified = true
                }
            }

            for (tag in AUTHOR_TAGS) {
                if (exif.getAttribute(tag) != null) {
                    exif.setAttribute(tag, null)
                    modified = true
                }
            }

            if (modified) {
                exif.saveAttributes()
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
