package com.nexa.app

import com.nexa.app.core.storage.NexaStorageCategory
import com.nexa.app.core.storage.NexaStorageManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Unit and integration tests for NEXA User-Accessible Storage Manager,
 * directory self-healing, collision-safe file handling, path traversal prevention,
 * cache clearing isolation, and storage usage calculations.
 */
class StorageAndDataUnitTests {

    private lateinit var tempRoot: File
    private lateinit var tempNexaDir: File
    private lateinit var tempCacheDir: File
    private lateinit var tempInternalDataDir: File
    private lateinit var storageManager: NexaStorageManager

    @Before
    fun setUp() {
        val baseTemp = File(System.getProperty("java.io.tmpdir"), "nexa_storage_test_${System.currentTimeMillis()}")
        baseTemp.mkdirs()
        tempRoot = baseTemp

        tempNexaDir = File(tempRoot, "media/NEXA")
        tempCacheDir = File(tempRoot, "cache")
        tempInternalDataDir = File(tempRoot, "internal_data")

        tempNexaDir.mkdirs()
        tempCacheDir.mkdirs()
        tempInternalDataDir.mkdirs()

        storageManager = NexaStorageManager(
            context = null,
            customRootDir = tempNexaDir,
            customCacheDir = tempCacheDir,
            customDataDir = tempInternalDataDir
        )
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    // ==========================================
    // 1. Storage Categories Tests
    // ==========================================

    @Test
    fun testStorageCategories_allEightCategoriesExist() {
        val categories = NexaStorageCategory.entries
        assertEquals(8, categories.size)
        assertTrue(categories.contains(NexaStorageCategory.IMAGES))
        assertTrue(categories.contains(NexaStorageCategory.VIDEOS))
        assertTrue(categories.contains(NexaStorageCategory.AUDIO))
        assertTrue(categories.contains(NexaStorageCategory.DOCUMENTS))
        assertTrue(categories.contains(NexaStorageCategory.DOWNLOADS))
        assertTrue(categories.contains(NexaStorageCategory.EXPORTS))
        assertTrue(categories.contains(NexaStorageCategory.ENCRYPTED_BACKUPS))
        assertTrue(categories.contains(NexaStorageCategory.AVATARS))
    }

    @Test
    fun testStorageCategories_subPathsAndNames() {
        assertEquals("Media/Images", NexaStorageCategory.IMAGES.subPath)
        assertEquals("Media/Videos", NexaStorageCategory.VIDEOS.subPath)
        assertEquals("Media/Audio", NexaStorageCategory.AUDIO.subPath)
        assertEquals("Media/Documents", NexaStorageCategory.DOCUMENTS.subPath)
        assertEquals("Downloads", NexaStorageCategory.DOWNLOADS.subPath)
        assertEquals("Exports", NexaStorageCategory.EXPORTS.subPath)
        assertEquals("Backups/Encrypted", NexaStorageCategory.ENCRYPTED_BACKUPS.subPath)
        assertEquals("Profile/Avatars", NexaStorageCategory.AVATARS.subPath)
    }

    // ==========================================
    // 2. Directory Tree Initialization & Self-Healing
    // ==========================================

    @Test
    fun testInitStorageTree_createsAllDirectories() {
        val success = storageManager.initStorageTree()
        assertTrue(success)

        NexaStorageCategory.entries.forEach { category ->
            val dir = File(tempNexaDir, category.subPath)
            assertTrue("Directory for ${category.name} must exist", dir.exists())
            assertTrue("Directory for ${category.name} must be a directory", dir.isDirectory)
        }
    }

    @Test
    fun testInitStorageTree_generatesNoMediaFilesForPrivateFolders() {
        storageManager.initStorageTree()

        val backupNoMedia = File(tempNexaDir, "Backups/Encrypted/.nomedia")
        val avatarNoMedia = File(tempNexaDir, "Profile/Avatars/.nomedia")
        val imageNoMedia = File(tempNexaDir, "Media/Images/.nomedia")

        assertTrue("Encrypted backups must have .nomedia", backupNoMedia.exists())
        assertTrue("Avatars must have .nomedia", avatarNoMedia.exists())
        assertFalse("User images must NOT have .nomedia (indexed in gallery)", imageNoMedia.exists())
    }

    @Test
    fun testSelfHealing_recreatesMissingDirectoryOnDemand() {
        storageManager.initStorageTree()

        val videoDir = File(tempNexaDir, NexaStorageCategory.VIDEOS.subPath)
        assertTrue(videoDir.exists())

        // Simulate external deletion by user or cleaner app
        videoDir.deleteRecursively()
        assertFalse(videoDir.exists())

        // Access via getDirectory triggers self-healing
        val recreatedDir = storageManager.getDirectory(NexaStorageCategory.VIDEOS)
        assertTrue("Directory must be recreated automatically", recreatedDir.exists())
    }

    // ==========================================
    // 3. Path Traversal & Collision-Safe Naming
    // ==========================================

    @Test
    fun testPathTraversalProtection_staysWithinTargetCategory() {
        val targetCategory = NexaStorageCategory.DOCUMENTS
        val targetDir = storageManager.getDirectory(targetCategory)

        // Attempt directory traversal attacks
        val candidate1 = storageManager.resolveSafeFile(targetCategory, "../../../evil.sh")
        val candidate2 = storageManager.resolveSafeFile(targetCategory, "..\\..\\windows_attack.exe")
        val candidate3 = storageManager.resolveSafeFile(targetCategory, "/etc/passwd")

        assertEquals(targetDir.canonicalPath, candidate1.parentFile?.canonicalPath)
        assertEquals(targetDir.canonicalPath, candidate2.parentFile?.canonicalPath)
        assertEquals(targetDir.canonicalPath, candidate3.parentFile?.canonicalPath)

        assertFalse(candidate1.name.contains(".."))
        assertFalse(candidate2.name.contains(".."))
    }

    @Test
    fun testCollisionSafeNaming_incrementsIndexes() {
        val targetCategory = NexaStorageCategory.DOWNLOADS

        val file1 = storageManager.resolveSafeFile(targetCategory, "invoice.pdf")
        assertEquals("invoice.pdf", file1.name)
        file1.writeText("First invoice content")

        val file2 = storageManager.resolveSafeFile(targetCategory, "invoice.pdf")
        assertEquals("invoice (1).pdf", file2.name)
        file2.writeText("Second invoice content")

        val file3 = storageManager.resolveSafeFile(targetCategory, "invoice.pdf")
        assertEquals("invoice (2).pdf", file3.name)
    }

    @Test
    fun testSaveMediaFile_savesContentAndPreventsCollision() = runTest {
        val category = NexaStorageCategory.IMAGES
        val data1 = "Image binary 1".toByteArray()
        val data2 = "Image binary 2".toByteArray()

        val saved1 = storageManager.saveMediaFile(category, "photo.jpg", ByteArrayInputStream(data1))
        assertTrue(saved1.exists())
        assertEquals("photo.jpg", saved1.name)
        assertEquals(data1.size.toLong(), saved1.length())

        val saved2 = storageManager.saveMediaFile(category, "photo.jpg", ByteArrayInputStream(data2))
        assertTrue(saved2.exists())
        assertEquals("photo (1).jpg", saved2.name)
        assertEquals(data2.size.toLong(), saved2.length())
    }

    // ==========================================
    // 4. Security Isolation Verification
    // ==========================================

    @Test
    fun testVerifySecurityIsolation_internalAndExternalAreDecoupled() {
        val isIsolated = storageManager.verifySecurityIsolation()
        assertTrue("External NEXA directory must never overlap with internal databases or private files", isIsolated)
    }

    // ==========================================
    // 5. Cache Clear & Storage Usage Calculation
    // ==========================================

    @Test
    fun testFormatBytes_formatsStandardValues() {
        assertEquals("0 B", NexaStorageManager.formatBytes(0L))
        assertEquals("0 B", NexaStorageManager.formatBytes(-5L))
        assertEquals("512 B", NexaStorageManager.formatBytes(512L))
        assertEquals("1.0 KB", NexaStorageManager.formatBytes(1024L))
        assertEquals("1.5 KB", NexaStorageManager.formatBytes(1536L))
        assertEquals("1.0 MB", NexaStorageManager.formatBytes(1048576L))
        assertEquals("10.0 MB", NexaStorageManager.formatBytes(10485760L))
        assertEquals("1.00 GB", NexaStorageManager.formatBytes(1073741824L))
    }

    @Test
    fun testClearCache_deletesOnlyCacheFiles() = runTest {
        // Create user media files that MUST be preserved
        val imageFile = File(storageManager.getDirectory(NexaStorageCategory.IMAGES), "preserved.jpg")
        imageFile.writeText("Preserved User Photo")

        val docFile = File(storageManager.getDirectory(NexaStorageCategory.DOCUMENTS), "contract.pdf")
        docFile.writeText("Preserved User Document")

        // Create temporary cache files that MUST be deleted
        val cacheFile1 = File(tempCacheDir, "thumbnail_1.tmp")
        cacheFile1.writeText("Temporary thumbnail bytes 12345")
        val cacheFile2 = File(tempCacheDir, "http_cache.bin")
        cacheFile2.writeText("Temporary http response")

        val expectedClearedSize = cacheFile1.length() + cacheFile2.length()

        // Act: clear cache
        val result = storageManager.clearCache()

        assertTrue(result.success)
        assertEquals(2, result.filesDeletedCount)
        assertEquals(expectedClearedSize, result.bytesCleared)

        // Assert cache files deleted
        assertFalse(cacheFile1.exists())
        assertFalse(cacheFile2.exists())

        // Assert user media untouched
        assertTrue("User photo must NOT be deleted", imageFile.exists())
        assertTrue("User document must NOT be deleted", docFile.exists())
    }

    @Test
    fun testCalculateStorageUsage_accurateBreakdown() = runTest {
        storageManager.initStorageTree()

        // Add 2 images
        val img1 = File(storageManager.getDirectory(NexaStorageCategory.IMAGES), "img1.png")
        img1.writeBytes(ByteArray(1000))
        val img2 = File(storageManager.getDirectory(NexaStorageCategory.IMAGES), "img2.png")
        img2.writeBytes(ByteArray(2000))

        // Add 1 video
        val vid1 = File(storageManager.getDirectory(NexaStorageCategory.VIDEOS), "vid1.mp4")
        vid1.writeBytes(ByteArray(5000))

        // Add 1 encrypted backup
        val bak1 = File(storageManager.getDirectory(NexaStorageCategory.ENCRYPTED_BACKUPS), "backup_vault.enc")
        bak1.writeBytes(ByteArray(4000))

        // Add cache
        val c1 = File(tempCacheDir, "cache_temp.bin")
        c1.writeBytes(ByteArray(1500))

        val breakdown = storageManager.calculateStorageUsage()

        assertEquals(3000L, breakdown.imagesBytes)
        assertEquals(2, breakdown.imagesCount)

        assertEquals(5000L, breakdown.videosBytes)
        assertEquals(1, breakdown.videosCount)

        assertEquals(4000L, breakdown.encryptedBackupsBytes)
        assertEquals(1, breakdown.encryptedBackupsCount)

        assertEquals(1500L, breakdown.cacheBytes)

        val expectedTotal = 3000L + 5000L + 4000L + 1500L
        assertEquals(expectedTotal, breakdown.totalAppBytes)
        assertTrue(breakdown.isFolderAccessible)
    }
}
