package com.nexa.app.core.security

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

sealed interface MediaScanVerdict {
    data object Safe : MediaScanVerdict
    data class Rejected(val reason: String, val threatCategory: ThreatCategory) : MediaScanVerdict
}

/**
 * Streaming media container validator for video (MP4, M4V, MKV, WebM)
 * and audio (MP3, FLAC, OGG, OPUS, WAV, AAC).
 *
 * Validates container atom hierarchy and header syntax without loading
 * full media streams into memory. Enforces bounded atom traversal.
 */
object MediaSecurityScanner {

    private const val MAX_ATOM_SEARCH_DEPTH = 100
    private const val BUFFER_SIZE = 8192

    // MP4 standard top-level atom types
    private val VALID_MP4_BOXES = setOf(
        "ftyp", "moov", "mdat", "free", "skip", "wide", "meta", "udta", "uuid", "pdin"
    )

    fun scanMediaFile(file: File, declaredExtension: String): MediaScanVerdict {
        if (!file.exists() || file.length() < 8) {
            return MediaScanVerdict.Rejected("Media file missing or too small", ThreatCategory.CORRUPTED_STREAM)
        }

        val ext = declaredExtension.lowercase().removePrefix(".").trim()
        return try {
            FileInputStream(file).use { fis ->
                scanMediaStream(fis, ext, file.length())
            }
        } catch (e: Exception) {
            MediaScanVerdict.Rejected("Failed to inspect media stream: ${e.message}", ThreatCategory.MALFORMED_STRUCTURE)
        }
    }

    fun scanMediaStream(stream: InputStream, declaredExt: String, totalLength: Long): MediaScanVerdict {
        return when (declaredExt) {
            "mp4", "m4v" -> scanMp4Container(stream, totalLength)
            "mkv", "webm" -> scanMatroskaContainer(stream, totalLength)
            "mp3" -> scanMp3(stream, totalLength)
            "flac" -> scanFlac(stream, totalLength)
            "ogg", "opus" -> scanOgg(stream, totalLength)
            "wav" -> scanWav(stream, totalLength)
            else -> MediaScanVerdict.Safe // Non-container media formats
        }
    }

    private fun scanMp4Container(stream: InputStream, totalLength: Long): MediaScanVerdict {
        var bytesReadTotal = 0L
        var boxCount = 0
        var foundFtyp = false
        var foundMoov = false
        var foundMdat = false

        val headerBuffer = ByteArray(8)

        while (bytesReadTotal < totalLength && boxCount < MAX_ATOM_SEARCH_DEPTH) {
            val read = stream.read(headerBuffer)
            if (read < 8) break
            bytesReadTotal += read
            boxCount++

            val boxSize = ((headerBuffer[0].toLong() and 0xFF) shl 24) or
                    ((headerBuffer[1].toLong() and 0xFF) shl 16) or
                    ((headerBuffer[2].toLong() and 0xFF) shl 8) or
                    (headerBuffer[3].toLong() and 0xFF)

            val boxType = String(headerBuffer, 4, 4, Charsets.US_ASCII)

            if (boxCount == 1 && boxType != "ftyp") {
                return MediaScanVerdict.Rejected(
                    "MP4 container anomaly: First atom must be 'ftyp', found '$boxType'",
                    ThreatCategory.MEDIA_CONTAINER_ANOMALY
                )
            }

            if (boxType == "ftyp") foundFtyp = true
            if (boxType == "moov") foundMoov = true
            if (boxType == "mdat") foundMdat = true

            // Box size validation
            val payloadToSkip: Long = when {
                boxSize == 1L -> {
                    // 64-bit extended size
                    val extSizeBuf = ByteArray(8)
                    val extRead = stream.read(extSizeBuf)
                    if (extRead < 8) return MediaScanVerdict.Rejected("Truncated extended MP4 atom", ThreatCategory.CORRUPTED_STREAM)
                    bytesReadTotal += extRead
                    var largeSize = 0L
                    for (i in 0 until 8) {
                        largeSize = (largeSize shl 8) or (extSizeBuf[i].toLong() and 0xFF)
                    }
                    if (largeSize < 16) {
                        return MediaScanVerdict.Rejected("Invalid 64-bit atom size: $largeSize", ThreatCategory.MEDIA_CONTAINER_ANOMALY)
                    }
                    largeSize - 16
                }
                boxSize == 0L -> {
                    // Box extends to EOF
                    totalLength - bytesReadTotal
                }
                boxSize < 8 -> {
                    return MediaScanVerdict.Rejected("Invalid MP4 atom size: $boxSize", ThreatCategory.MEDIA_CONTAINER_ANOMALY)
                }
                else -> boxSize - 8
            }

            if (payloadToSkip < 0 || bytesReadTotal + payloadToSkip > totalLength + 1024) {
                return MediaScanVerdict.Rejected("MP4 atom size exceeds container boundaries", ThreatCategory.MEDIA_CONTAINER_ANOMALY)
            }

            // Skip atom payload safely using bounded skip
            var remainingToSkip = payloadToSkip
            val skipBuf = ByteArray(BUFFER_SIZE)
            while (remainingToSkip > 0) {
                val toRead = minOf(remainingToSkip, BUFFER_SIZE.toLong()).toInt()
                val skipped = stream.read(skipBuf, 0, toRead)
                if (skipped == -1) break
                remainingToSkip -= skipped
                bytesReadTotal += skipped
            }
        }

        if (!foundFtyp) {
            return MediaScanVerdict.Rejected("MP4 missing essential ftyp atom", ThreatCategory.MEDIA_CONTAINER_ANOMALY)
        }

        return MediaScanVerdict.Safe
    }

    private fun scanMatroskaContainer(stream: InputStream, totalLength: Long): MediaScanVerdict {
        val ebmlHeader = ByteArray(4)
        val read = stream.read(ebmlHeader)
        if (read < 4) return MediaScanVerdict.Rejected("Matroska stream too short", ThreatCategory.CORRUPTED_STREAM)

        // Matroska EBML ID: 0x1A 0x45 0xDF 0xA3
        val isEbml = (ebmlHeader[0] == 0x1A.toByte() &&
                ebmlHeader[1] == 0x45.toByte() &&
                ebmlHeader[2] == 0xDF.toByte() &&
                ebmlHeader[3] == 0xA3.toByte())

        return if (isEbml) {
            MediaScanVerdict.Safe
        } else {
            MediaScanVerdict.Rejected("Invalid EBML header for MKV/WebM container", ThreatCategory.MEDIA_CONTAINER_ANOMALY)
        }
    }

    private fun scanMp3(stream: InputStream, totalLength: Long): MediaScanVerdict {
        val header = ByteArray(10)
        val read = stream.read(header)
        if (read < 4) return MediaScanVerdict.Rejected("MP3 stream too short", ThreatCategory.CORRUPTED_STREAM)

        // Check for ID3 tag header (ID3v2)
        val isId3 = header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()
        // Check for MPEG sync frame (0xFF 0xFB, 0xFF 0xF3, 0xFF 0xF2, 0xFF 0xFA)
        val isSyncFrame = (header[0] == 0xFF.toByte()) && ((header[1].toInt() and 0xE0) == 0xE0)

        return if (isId3 || isSyncFrame) {
            MediaScanVerdict.Safe
        } else {
            MediaScanVerdict.Rejected("Invalid MP3 header (missing ID3 and MPEG sync)", ThreatCategory.MIME_SPOOFING)
        }
    }

    private fun scanFlac(stream: InputStream, totalLength: Long): MediaScanVerdict {
        val header = ByteArray(4)
        val read = stream.read(header)
        if (read < 4) return MediaScanVerdict.Rejected("FLAC stream too short", ThreatCategory.CORRUPTED_STREAM)

        // fLaC marker: 0x66 0x4C 0x61 0x43
        val isFlac = header[0] == 0x66.toByte() && header[1] == 0x4C.toByte() &&
                header[2] == 0x61.toByte() && header[3] == 0x43.toByte()

        return if (isFlac) {
            MediaScanVerdict.Safe
        } else {
            MediaScanVerdict.Rejected("Invalid FLAC signature", ThreatCategory.MIME_SPOOFING)
        }
    }

    private fun scanOgg(stream: InputStream, totalLength: Long): MediaScanVerdict {
        val header = ByteArray(4)
        val read = stream.read(header)
        if (read < 4) return MediaScanVerdict.Rejected("OGG stream too short", ThreatCategory.CORRUPTED_STREAM)

        // OggS marker: 0x4F 0x67 0x67 0x53
        val isOgg = header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() &&
                header[2] == 0x67.toByte() && header[3] == 0x53.toByte()

        return if (isOgg) {
            MediaScanVerdict.Safe
        } else {
            MediaScanVerdict.Rejected("Invalid OggS capture pattern", ThreatCategory.MIME_SPOOFING)
        }
    }

    private fun scanWav(stream: InputStream, totalLength: Long): MediaScanVerdict {
        val header = ByteArray(12)
        val read = stream.read(header)
        if (read < 12) return MediaScanVerdict.Rejected("WAV stream too short", ThreatCategory.CORRUPTED_STREAM)

        // RIFF .... WAVE
        val isRiff = header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                header[2] == 0x46.toByte() && header[3] == 0x46.toByte()
        val isWave = header[8] == 0x57.toByte() && header[9] == 0x41.toByte() &&
                header[10] == 0x56.toByte() && header[11] == 0x45.toByte()

        return if (isRiff && isWave) {
            MediaScanVerdict.Safe
        } else {
            MediaScanVerdict.Rejected("Invalid RIFF/WAVE header", ThreatCategory.MIME_SPOOFING)
        }
    }
}
