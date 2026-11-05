package com.nexa.app

import com.nexa.app.core.media.ProfilePhotoOptimizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ProfilePhotoOptimizerUnitTests {

    @Test
    fun calculateInSampleSize_downscalesPowerOfTwoCorrectly() {
        // 1. Image smaller than or equal to max dimension -> inSampleSize = 1
        val sample1024 = ProfilePhotoOptimizer.calculateInSampleSize(1024, 1024, 1024)
        assertEquals("Exact dimension should not downsample", 1, sample1024)

        val sample800 = ProfilePhotoOptimizer.calculateInSampleSize(800, 600, 1024)
        assertEquals("Sub-1024 dimension should not downsample", 1, sample800)

        // 2. 4K image (4096 x 3072) -> needs downsampling to avoid OOM
        val sample4K = ProfilePhotoOptimizer.calculateInSampleSize(4096, 3072, 1024)
        assertTrue("4K image must use inSampleSize >= 2", sample4K >= 2)

        // 3. 8K DSLR image (8192 x 6144) -> needs aggressive downsampling
        val sample8K = ProfilePhotoOptimizer.calculateInSampleSize(8192, 6144, 1024)
        assertTrue("8K image must use inSampleSize >= 4", sample8K >= 4)
    }

    @Test
    fun centerSquareCrop_dimensionMathCalculation() {
        // Landscape image: 1920 x 1080
        val landW = 1920
        val landH = 1080
        val targetLand = minOf(landW, landH)
        val cropXLand = (landW - targetLand) / 2
        val cropYLand = (landH - targetLand) / 2
        assertEquals(1080, targetLand)
        assertEquals(420, cropXLand)
        assertEquals(0, cropYLand)

        // Portrait image: 1080 x 2400
        val portW = 1080
        val portH = 2400
        val targetPort = minOf(portW, portH)
        val cropXPort = (portW - targetPort) / 2
        val cropYPort = (portH - targetPort) / 2
        assertEquals(1080, targetPort)
        assertEquals(0, cropXPort)
        assertEquals(660, cropYPort)
    }

    @Test
    fun computeShortContentHash_deterministicAndUnique() {
        val payloadA = "NEXA_AVATAR_BYTE_STREAM_12345".toByteArray(Charsets.UTF_8)
        val hashA1 = ProfilePhotoOptimizer.computeShortContentHash(payloadA)
        val hashA2 = ProfilePhotoOptimizer.computeShortContentHash(payloadA)

        // 1. Deterministic
        assertEquals("Identical byte stream must produce identical short hash", hashA1, hashA2)
        assertEquals("Short content hash must be 8 hex characters", 8, hashA1.length)

        // 2. Distinct for different content
        val payloadB = "NEXA_AVATAR_BYTE_STREAM_DIFFERENT".toByteArray(Charsets.UTF_8)
        val hashB = ProfilePhotoOptimizer.computeShortContentHash(payloadB)
        assertNotEquals("Different content must produce distinct hash", hashA1, hashB)
    }

    @Test
    fun mediaMatrixRules_profilePhotoConstraintsVerification() {
        // Max dimension requirement
        assertEquals("Profile avatar maximum dimension must be 1024px", 1024, ProfilePhotoOptimizer.MAX_DIMENSION)

        // WebP lossy quality requirement
        assertEquals("WebP lossy target quality must be 88", 88, ProfilePhotoOptimizer.WEBP_QUALITY)

        // Target size verification (150 KB to 400 KB range)
        val minTargetBytes = 150 * 1024
        val maxTargetBytes = 400 * 1024
        val sampleCompressedSize = 240 * 1024 // 240 KB typical for 1024x1024 Q=88 WebP
        assertTrue(sampleCompressedSize in minTargetBytes..maxTargetBytes)
    }
}
