package com.nexa.app.core.media

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Mp4Box(
    val type: String,
    val offset: Long,
    val size: Long
)

data class FastStartReport(
    val isFastStart: Boolean,
    val moovOffset: Long,
    val mdatOffset: Long,
    val boxes: List<Mp4Box>,
    val description: String
)

/**
 * Verifies MOOV atom header placement in MP4 container files.
 * Fast-Start (progressive download/P2P streaming) requires the metadata index ('moov' atom)
 * to be placed ahead of the media payload ('mdat' atom) so recipients can start playback immediately
 * from the first incoming chunks.
 */
object FastStartMuxerHelper {
    private const val TAG = "FastStartMuxerHelper"

    /**
     * Inspects top-level MP4 container boxes.
     */
    fun inspectBoxes(file: File): List<Mp4Box> {
        if (!file.exists() || file.length() < 8) return emptyList()

        val boxes = mutableListOf<Mp4Box>()
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(file, "r")
            val fileLength = file.length()
            var currentOffset = 0L

            val headerBuf = ByteArray(8)
            val byteBuffer = ByteBuffer.wrap(headerBuf).order(ByteOrder.BIG_ENDIAN)

            while (currentOffset + 8 <= fileLength) {
                raf.seek(currentOffset)
                raf.readFully(headerBuf)
                byteBuffer.rewind()

                val rawSize = byteBuffer.int.toLong() and 0xFFFFFFFFL
                val typeBytes = ByteArray(4)
                byteBuffer.get(typeBytes)
                val type = String(typeBytes, Charsets.US_ASCII)

                var boxSize = rawSize
                var headerLength = 8L

                if (rawSize == 1L) {
                    // 64-bit extended size follows
                    if (currentOffset + 16 > fileLength) break
                    val extendedSizeBuf = ByteArray(8)
                    raf.readFully(extendedSizeBuf)
                    boxSize = ByteBuffer.wrap(extendedSizeBuf).order(ByteOrder.BIG_ENDIAN).long
                    headerLength = 16L
                } else if (rawSize == 0L) {
                    // Box extends to EOF
                    boxSize = fileLength - currentOffset
                }

                if (boxSize < headerLength) {
                    // Corrupted or unrecognized atom size
                    break
                }

                boxes.add(Mp4Box(type = type, offset = currentOffset, size = boxSize))

                currentOffset += boxSize
                if (rawSize == 0L || currentOffset >= fileLength) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error inspecting MP4 boxes for ${file.name}", e)
        } finally {
            try {
                raf?.close()
            } catch (ignored: Exception) {}
        }
        return boxes
    }

    /**
     * Returns true if 'moov' atom appears before 'mdat' atom in the MP4 file.
     */
    fun isFastStartOptimized(file: File): Boolean {
        val boxes = inspectBoxes(file)
        var moovOffset = -1L
        var mdatOffset = -1L

        for (box in boxes) {
            if (box.type == "moov" && moovOffset == -1L) {
                moovOffset = box.offset
            }
            if (box.type == "mdat" && mdatOffset == -1L) {
                mdatOffset = box.offset
            }
        }

        val fastStart = moovOffset != -1L && mdatOffset != -1L && moovOffset < mdatOffset
        Log.d(TAG, "File ${file.name} fast-start check: moovOffset=$moovOffset, mdatOffset=$mdatOffset -> $fastStart")
        return fastStart
    }

    /**
     * Generates a detailed diagnostic report for container atom layout.
     */
    fun getFastStartReport(file: File): FastStartReport {
        val boxes = inspectBoxes(file)
        var moovOffset = -1L
        var mdatOffset = -1L

        for (box in boxes) {
            if (box.type == "moov" && moovOffset == -1L) {
                moovOffset = box.offset
            }
            if (box.type == "mdat" && mdatOffset == -1L) {
                mdatOffset = box.offset
            }
        }

        val isFastStart = moovOffset != -1L && mdatOffset != -1L && moovOffset < mdatOffset
        val description = when {
            moovOffset == -1L -> "Missing 'moov' atom (incomplete or corrupted MP4 container)"
            mdatOffset == -1L -> "Missing 'mdat' atom (no media payload)"
            isFastStart -> "Fast-Start Optimized: 'moov' (offset $moovOffset) is placed before 'mdat' (offset $mdatOffset)"
            else -> "Non-Optimized: 'mdat' (offset $mdatOffset) precedes 'moov' (offset $moovOffset) - requires full download before playback"
        }

        return FastStartReport(
            isFastStart = isFastStart,
            moovOffset = moovOffset,
            mdatOffset = mdatOffset,
            boxes = boxes,
            description = description
        )
    }
}

