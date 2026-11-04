package com.nexa.app

import com.nexa.app.core.media.FastStartMuxerHelper
import com.nexa.app.core.media.VideoMetadata
import com.nexa.app.core.media.VideoMetadataProbe
import com.nexa.app.core.media.VideoTransformConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VideoCompressionUnitTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testVideoTransformConfigDefaults() {
        assertEquals(1080, VideoTransformConfig.MAX_HEIGHT_1080P)
        assertEquals(720, VideoTransformConfig.MAX_HEIGHT_720P)
        assertEquals(30.0f, VideoTransformConfig.MAX_FPS_CAP, 0.001f)

        // Target Bitrates (Constrained VBR)
        assertEquals(2_800_000, VideoTransformConfig.TARGET_VIDEO_BITRATE_1080P)
        assertEquals(2_000_000, VideoTransformConfig.TARGET_VIDEO_BITRATE_720P)
        assertEquals(1_200_000, VideoTransformConfig.TARGET_VIDEO_BITRATE_SD)

        // Audio Stream Optimization: AAC-LC 128 kbps stereo, 44.1 kHz
        assertEquals(128_000, VideoTransformConfig.AUDIO_BITRATE_AAC)
        assertEquals(44_100, VideoTransformConfig.AUDIO_SAMPLE_RATE_HZ)
        assertEquals(2, VideoTransformConfig.AUDIO_CHANNEL_COUNT)

        // Compression Thresholds
        assertEquals(15L * 1024L * 1024L, VideoTransformConfig.COMPRESSION_SIZE_THRESHOLD_BYTES)
        assertEquals(720, VideoTransformConfig.COMPRESSION_MAX_HEIGHT_THRESHOLD)
        assertEquals(30.0f, VideoTransformConfig.COMPRESSION_MAX_FPS_THRESHOLD, 0.001f)
    }

    @Test
    fun testVideoMetadataRotationAndDisplayDimensions() {
        // Landscape video (1920x1080) at 0 degrees
        val landscape0 = VideoMetadata(
            width = 1920,
            height = 1080,
            durationMs = 15000L,
            originalBitrate = 12_000_000L,
            sizeBytes = 22_500_000L,
            fps = 30.0f,
            rotationDegrees = 0
        )
        assertEquals(1920, landscape0.displayWidth)
        assertEquals(1080, landscape0.displayHeight)

        // Portrait video recorded on smartphone (stored as 1920x1080 with 90 degree rotation)
        val portrait90 = landscape0.copy(rotationDegrees = 90)
        assertEquals(1080, portrait90.displayWidth)
        assertEquals(1920, portrait90.displayHeight)

        // Portrait video at 270 degrees
        val portrait270 = landscape0.copy(rotationDegrees = 270)
        assertEquals(1080, portrait270.displayWidth)
        assertEquals(1920, portrait270.displayHeight)

        // Upside down landscape at 180 degrees
        val upsideDown180 = landscape0.copy(rotationDegrees = 180)
        assertEquals(1920, upsideDown180.displayWidth)
        assertEquals(1080, upsideDown180.displayHeight)
    }

    @Test
    fun testVideoMetadataFormattingHelpers() {
        val meta = VideoMetadata(
            width = 1280,
            height = 720,
            durationMs = 65000L, // 1 min 5 sec
            originalBitrate = 2_000_000L,
            sizeBytes = 16_000_000L,
            fps = 30.0f
        )
        assertEquals("01:05", meta.durationFormatted)
        assertEquals("15.3 MB", meta.formattedSize)

        val smallMeta = meta.copy(sizeBytes = 512 * 1024, durationMs = 5000L)
        assertEquals("00:05", smallMeta.durationFormatted)
        assertEquals("512 KB", smallMeta.formattedSize)
    }

    @Test
    fun testCalculateEstimatedSizeBytesFormula() {
        // 10 seconds with default 720p (2.0 Mbps) + AAC (128 kbps)
        // totalBitrate = 2_128_000 bps
        // expectedBytes = (2_128_000 / 8) * 10 = 266_000 * 10 = 2_660_000 bytes
        val estimated = VideoMetadataProbe.calculateEstimatedSizeBytes(
            durationMs = 10_000L,
            targetVideoBitrate = 2_000_000L,
            targetAudioBitrate = 128_000L
        )
        assertEquals(2_660_000L, estimated)

        // Specification exact numbers: 2,200,000 video + 128,000 audio for 10s:
        // ((2,200,000 + 128,000) / 8) * 10 = 2,910,000 bytes
        val specEstimated = VideoMetadataProbe.calculateEstimatedSizeBytes(
            durationMs = 10_000L,
            targetVideoBitrate = 2_200_000L,
            targetAudioBitrate = 128_000L
        )
        assertEquals(2_910_000L, specEstimated)

        // 0 duration returns 0
        assertEquals(0L, VideoMetadataProbe.calculateEstimatedSizeBytes(0L))
    }

    @Test
    fun testCompressionDecisionLogic() {
        // Case 1: Already light video (8 MB, 720p, 30 FPS) -> No compression needed
        val lightVideo = VideoMetadata(
            width = 1280,
            height = 720,
            durationMs = 10000L,
            originalBitrate = 1_500_000L,
            sizeBytes = 8L * 1024L * 1024L,
            fps = 30.0f
        )
        val needsLight = isCandidateForCompression(lightVideo)
        assertFalse("Light video should bypass compression", needsLight)

        // Case 2: Size exceeds 15 MB threshold (25 MB, 720p, 30 FPS) -> Needs compression
        val heavySize = lightVideo.copy(sizeBytes = 25L * 1024L * 1024L)
        assertTrue("File > 15MB must be compressed", isCandidateForCompression(heavySize))

        // Case 3: Resolution exceeds 720p (1080p, 10 MB, 30 FPS) -> Needs downscaling
        val fullHd = lightVideo.copy(width = 1920, height = 1080)
        assertTrue("1080p resolution must be downscaled", isCandidateForCompression(fullHd))

        // Case 4: High framerate (720p, 10 MB, 60 FPS) -> Needs framerate capping to 30 FPS
        val highFps = lightVideo.copy(fps = 60.0f)
        assertTrue("60 FPS must be capped to 30 FPS", isCandidateForCompression(highFps))

        // Case 5: 4K 60 FPS camera recording -> Definitely needs compression
        val ultraHd = VideoMetadata(
            width = 3840,
            height = 2160,
            durationMs = 30000L,
            originalBitrate = 50_000_000L,
            sizeBytes = 180L * 1024L * 1024L,
            fps = 60.0f
        )
        assertTrue("4K 60FPS must be compressed", isCandidateForCompression(ultraHd))
    }

    @Test
    fun testFastStartMuxerHelperWithSyntheticMp4Containers() {
        // 1. Synthetic Fast-Start MP4: [ftyp: 32B] -> [moov: 128B] -> [mdat: 1024B]
        val fastStartFile = tempFolder.newFile("faststart.mp4")
        writeSyntheticMp4(
            file = fastStartFile,
            boxes = listOf(
                Pair("ftyp", 32),
                Pair("moov", 128),
                Pair("mdat", 1024)
            )
        )

        val reportFast = FastStartMuxerHelper.getFastStartReport(fastStartFile)
        assertTrue(reportFast.isFastStart)
        assertEquals(32L, reportFast.moovOffset)
        assertEquals(160L, reportFast.mdatOffset)
        assertTrue(FastStartMuxerHelper.isFastStartOptimized(fastStartFile))

        // 2. Synthetic Non-Optimized MP4: [ftyp: 32B] -> [mdat: 1024B] -> [moov: 128B]
        val nonFastStartFile = tempFolder.newFile("non_faststart.mp4")
        writeSyntheticMp4(
            file = nonFastStartFile,
            boxes = listOf(
                Pair("ftyp", 32),
                Pair("mdat", 1024),
                Pair("moov", 128)
            )
        )

        val reportSlow = FastStartMuxerHelper.getFastStartReport(nonFastStartFile)
        assertFalse(reportSlow.isFastStart)
        assertEquals(1056L, reportSlow.moovOffset)
        assertEquals(32L, reportSlow.mdatOffset)
        assertFalse(FastStartMuxerHelper.isFastStartOptimized(nonFastStartFile))

        // 3. Corrupted or incomplete file
        val emptyFile = tempFolder.newFile("empty.mp4")
        assertFalse(FastStartMuxerHelper.isFastStartOptimized(emptyFile))
    }

    private fun isCandidateForCompression(metadata: VideoMetadata): Boolean {
        val minDimension = minOf(metadata.displayWidth, metadata.displayHeight)
        val maxDimension = maxOf(metadata.displayWidth, metadata.displayHeight)
        val exceedsSize = metadata.sizeBytes > VideoTransformConfig.COMPRESSION_SIZE_THRESHOLD_BYTES
        val exceedsResolution = minDimension > VideoTransformConfig.COMPRESSION_MAX_HEIGHT_THRESHOLD ||
                maxDimension > 1280
        val exceedsFps = metadata.fps > VideoTransformConfig.COMPRESSION_MAX_FPS_THRESHOLD
        return exceedsSize || exceedsResolution || exceedsFps
    }

    private fun writeSyntheticMp4(file: File, boxes: List<Pair<String, Int>>) {
        FileOutputStream(file).use { fos ->
            for ((type, size) in boxes) {
                val header = ByteArray(size)
                val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                buffer.putInt(size)
                buffer.put(type.toByteArray(Charsets.US_ASCII).copyOf(4))
                fos.write(header)
            }
        }
    }
}

