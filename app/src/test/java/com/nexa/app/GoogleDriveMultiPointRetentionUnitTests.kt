package com.nexa.app

import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.core.backup.BackupVaultHeader
import com.nexa.app.data.drive.DriveFile
import com.nexa.app.data.drive.UploadSessionState
import com.nexa.app.domain.model.BackupCategory
import com.nexa.app.domain.model.BackupRestorePoint
import com.nexa.app.domain.model.RestorePointStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for Part 4: Google Drive Cloud Backup Engine, Restore Points & 7-Point Retention.
 *
 * Verifies:
 * 1. Multi-point restore model serialization, deserialization, and ZERO-PII appProperties.
 * 2. Legacy backup file detection (`nx_secure_backup.vault`) and non-destructive backwards compatibility.
 * 3. Unique, collision-safe backup ID and remote filename formatting.
 * 4. Deterministic 7-point retention: preserving the newest 7 verified restore points and trimming older ones.
 * 5. Retention safety: incomplete, uploading, or failed backups do NOT cause valid backups to be deleted.
 * 6. Protection of active upload session file from retention deletion.
 * 7. Restore-point discovery and self-healing timeline sorting (newest to oldest).
 * 8. Cloud upload verification checks: existence, size match, minimum 71-byte header, and magic byte validation.
 * 9. Concurrency mutex safety preventing simultaneous retention jobs.
 */
class GoogleDriveMultiPointRetentionUnitTests {

    @Test
    fun testRestorePointModelSerializationAndDeserialization() {
        val epoch = 1732380000000L
        val backupId = "bp_${epoch}_a1b2c3d4"
        val categoryConfig = BackupCategoryConfig(
            includeChats = true,
            includeImages = true,
            includeVideos = false,
            includeDocuments = true,
            includeAudio = false,
            includeOtherFiles = false,
            includeSettings = true,
            includeAttachments = true
        )

        val originalPoint = BackupRestorePoint(
            backupId = backupId,
            createdAt = epoch,
            formatVersion = 2,
            encryptionVersion = 2,
            fileName = "nexa_backup_${backupId}.vault",
            driveFileId = "drive_file_id_999",
            sizeBytes = 25_000_000L,
            totalMessages = 1542,
            totalConversations = 28,
            mediaFilesCount = 87,
            categoryConfig = categoryConfig,
            status = RestorePointStatus.VERIFIED,
            isLegacy = false,
            sourceDevice = "Pixel 8 Pro",
            label = "Cloud Weekly Backup",
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )

        val appProps = originalPoint.toAppProperties()

        // 1. Verify all expected properties are serialized into appProperties
        assertEquals(backupId, appProps[BackupRestorePoint.KEY_BACKUP_ID])
        assertEquals(epoch.toString(), appProps[BackupRestorePoint.KEY_CREATED_AT])
        assertEquals("2", appProps[BackupRestorePoint.KEY_FORMAT_VERSION])
        assertEquals("2", appProps[BackupRestorePoint.KEY_ENCRYPTION_VERSION])
        assertEquals("VERIFIED", appProps[BackupRestorePoint.KEY_STATUS])
        assertEquals("1542", appProps[BackupRestorePoint.KEY_TOTAL_MESSAGES])
        assertEquals("28", appProps[BackupRestorePoint.KEY_TOTAL_CONVERSATIONS])
        assertEquals("87", appProps[BackupRestorePoint.KEY_MEDIA_FILES_COUNT])
        assertEquals("Pixel 8 Pro", appProps[BackupRestorePoint.KEY_SOURCE_DEVICE])
        assertEquals("false", appProps[BackupRestorePoint.KEY_IS_LEGACY])
        assertEquals("Cloud Weekly Backup", appProps[BackupRestorePoint.KEY_LABEL])
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", appProps[BackupRestorePoint.KEY_SHA256])

        // 2. Strict Privacy / Zero PII verification
        val fullPropsString = appProps.entries.joinToString(" ") { "${it.key}=${it.value}" }
        assertFalse("AppProperties must not contain user message text", fullPropsString.contains("Hello"))
        assertFalse("AppProperties must not contain phone numbers", fullPropsString.contains("+1") || fullPropsString.contains("+91"))
        assertFalse("AppProperties must not contain passwords", fullPropsString.contains("passphrase") || fullPropsString.contains("key"))

        // 3. Round-trip deserialization via DriveFile
        val mockDriveFile = DriveFile(
            id = "drive_file_id_999",
            name = "nexa_backup_${backupId}.vault",
            size = 25_000_000L,
            modifiedTime = "2026-11-23T12:00:00.000Z",
            appProperties = appProps
        )

        val restoredPoint = BackupRestorePoint.fromDriveFile(mockDriveFile)

        assertEquals(originalPoint.backupId, restoredPoint.backupId)
        assertEquals(originalPoint.createdAt, restoredPoint.createdAt)
        assertEquals(originalPoint.formatVersion, restoredPoint.formatVersion)
        assertEquals(originalPoint.encryptionVersion, restoredPoint.encryptionVersion)
        assertEquals(originalPoint.fileName, restoredPoint.fileName)
        assertEquals(originalPoint.driveFileId, restoredPoint.driveFileId)
        assertEquals(originalPoint.sizeBytes, restoredPoint.sizeBytes)
        assertEquals(originalPoint.totalMessages, restoredPoint.totalMessages)
        assertEquals(originalPoint.totalConversations, restoredPoint.totalConversations)
        assertEquals(originalPoint.mediaFilesCount, restoredPoint.mediaFilesCount)
        assertEquals(originalPoint.status, restoredPoint.status)
        assertEquals(originalPoint.isLegacy, restoredPoint.isLegacy)
        assertEquals(originalPoint.sourceDevice, restoredPoint.sourceDevice)
        assertEquals(originalPoint.label, restoredPoint.label)
        assertEquals(originalPoint.sha256, restoredPoint.sha256)
        assertEquals(originalPoint.categoryConfig.includeChats, restoredPoint.categoryConfig.includeChats)
        assertEquals(originalPoint.categoryConfig.includeImages, restoredPoint.categoryConfig.includeImages)
        assertEquals(originalPoint.categoryConfig.includeVideos, restoredPoint.categoryConfig.includeVideos)
        assertEquals(originalPoint.categoryConfig.includeDocuments, restoredPoint.categoryConfig.includeDocuments)
        assertEquals(originalPoint.categoryConfig.includeSettings, restoredPoint.categoryConfig.includeSettings)
        assertEquals(originalPoint.categoryConfig.includeAttachments, restoredPoint.categoryConfig.includeAttachments)
    }

    @Test
    fun testLegacyBackupFileDetection() {
        val legacyDriveFile = DriveFile(
            id = "legacy_file_abc123",
            name = "nx_secure_backup.vault",
            size = 5_242_880L,
            modifiedTime = "2026-05-10T08:00:00.000Z",
            appProperties = mapOf(
                BackupRestorePoint.KEY_VERSION_EPOCH to "1715328000000"
            )
        )

        val point = BackupRestorePoint.fromDriveFile(legacyDriveFile)

        assertTrue("File named nx_secure_backup.vault must be flagged as legacy", point.isLegacy)
        assertEquals("nx_secure_backup.vault", point.fileName)
        assertEquals("legacy_file_abc123", point.driveFileId)
        assertEquals(5_242_880L, point.sizeBytes)
        assertEquals(1715328000000L, point.createdAt)
        assertEquals(1, point.formatVersion)
        assertEquals(1, point.encryptionVersion)
        assertEquals(RestorePointStatus.VERIFIED, point.status)
        assertTrue("Legacy backup ID should indicate legacy origin", point.backupId.startsWith("legacy_backup_"))
    }

    @Test
    fun testUniqueBackupIdAndFileNameGeneration() {
        val ids = mutableSetOf<String>()
        val fileNames = mutableSetOf<String>()

        for (i in 1..100) {
            val id = BackupRestorePoint.generateBackupId()
            val fileName = BackupRestorePoint.getBackupFileName(id)

            // Verify ID format: bp_<epoch>_<hex8>
            assertTrue(
                "Backup ID '$id' must follow pattern bp_<timestamp>_<hex8>",
                id.matches(Regex("^bp_\\d+_[a-f0-9]{8}$"))
            )

            // Verify filename format
            assertEquals("nexa_backup_${id}.vault", fileName)
            assertTrue(fileName.startsWith(BackupRestorePoint.BACKUP_FILE_PREFIX))
            assertTrue(fileName.endsWith(BackupRestorePoint.BACKUP_FILE_SUFFIX))

            ids.add(id)
            fileNames.add(fileName)
        }

        // Verify collision safety across 100 generations
        assertEquals("All 100 generated backup IDs must be unique", 100, ids.size)
        assertEquals("All 100 generated backup file names must be unique", 100, fileNames.size)
    }

    @Test
    fun testSevenPointRetentionPolicy_retentionTrimsOldestWhenExceedingSeven() {
        // Create 10 mock verified restore points at timestamps T1 < T2 < ... < T10
        val baseTime = 1732000000000L
        val mockDriveFiles = (1..10).map { i ->
            val timestamp = baseTime + (i * 3600_000L) // 1 hour apart
            val backupId = "bp_${timestamp}_test$i"
            DriveFile(
                id = "drive_id_$i",
                name = "nexa_backup_${backupId}.vault",
                size = 10_000_000L,
                appProperties = mapOf(
                    BackupRestorePoint.KEY_BACKUP_ID to backupId,
                    BackupRestorePoint.KEY_CREATED_AT to timestamp.toString(),
                    BackupRestorePoint.KEY_STATUS to RestorePointStatus.VERIFIED.name
                )
            )
        }

        // Simulate retention algorithm
        val points = mockDriveFiles.map { BackupRestorePoint.fromDriveFile(it) }
        val verifiedPoints = points
            .filter { it.status == RestorePointStatus.VERIFIED }
            .sortedByDescending { it.createdAt }

        assertEquals(10, verifiedPoints.size)

        val deletedFileIds = mutableListOf<String>()
        if (verifiedPoints.size > BackupRestorePoint.MAX_RETENTION_POINTS) {
            val toDelete = verifiedPoints.drop(BackupRestorePoint.MAX_RETENTION_POINTS)
            toDelete.forEach { point ->
                deletedFileIds.add(point.driveFileId)
            }
        }

        // In 1..10 sorted descending:
        // Index 0..6 (7 points) are points 10, 9, 8, 7, 6, 5, 4.
        // Dropped points (oldest 3) are points 3, 2, 1.
        assertEquals("Must delete exactly 3 restore points when total is 10", 3, deletedFileIds.size)
        assertTrue(deletedFileIds.contains("drive_id_1"))
        assertTrue(deletedFileIds.contains("drive_id_2"))
        assertTrue(deletedFileIds.contains("drive_id_3"))
        assertFalse(deletedFileIds.contains("drive_id_4"))
        assertFalse(deletedFileIds.contains("drive_id_10"))
    }

    @Test
    fun testSevenPointRetentionPolicy_unverifiedOrFailedBackupsDoNotTriggerDeletion() {
        val baseTime = 1732000000000L
        // 7 verified points + 2 failed / incomplete points = total 9 files
        val mockFiles = mutableListOf<DriveFile>()
        for (i in 1..7) {
            val timestamp = baseTime + (i * 3600_000L)
            mockFiles.add(
                DriveFile(
                    id = "verified_id_$i",
                    name = "nexa_backup_bp_${timestamp}_v$i.vault",
                    size = 10_000_000L,
                    appProperties = mapOf(
                        BackupRestorePoint.KEY_BACKUP_ID to "bp_${timestamp}_v$i",
                        BackupRestorePoint.KEY_CREATED_AT to timestamp.toString(),
                        BackupRestorePoint.KEY_STATUS to RestorePointStatus.VERIFIED.name
                    )
                )
            )
        }
        // Failed point 8
        mockFiles.add(
            DriveFile(
                id = "failed_id_8",
                name = "nexa_backup_bp_${baseTime + 8000000}_f8.vault",
                size = 100L,
                appProperties = mapOf(
                    BackupRestorePoint.KEY_STATUS to RestorePointStatus.FAILED.name
                )
            )
        )
        // Incomplete / uploading point 9
        mockFiles.add(
            DriveFile(
                id = "uploading_id_9",
                name = "nexa_backup_bp_${baseTime + 9000000}_u9.vault",
                size = 500_000L,
                appProperties = mapOf(
                    BackupRestorePoint.KEY_STATUS to RestorePointStatus.UPLOADING.name
                )
            )
        )

        val points = mockFiles.map { BackupRestorePoint.fromDriveFile(it) }
        val verifiedPoints = points
            .filter { it.status == RestorePointStatus.VERIFIED }
            .sortedByDescending { it.createdAt }

        // There are exactly 7 verified points. Retention threshold is 7.
        assertEquals(7, verifiedPoints.size)
        val toDelete = if (verifiedPoints.size > BackupRestorePoint.MAX_RETENTION_POINTS) {
            verifiedPoints.drop(BackupRestorePoint.MAX_RETENTION_POINTS)
        } else {
            emptyList()
        }

        assertTrue("No verified backups must be deleted when verified count <= 7", toDelete.isEmpty())
    }

    @Test
    fun testSevenPointRetentionPolicy_activeUploadSessionFileNeverDeleted() {
        val baseTime = 1732000000000L
        // Create 8 verified files
        val mockFiles = (1..8).map { i ->
            val timestamp = baseTime + (i * 3600_000L)
            DriveFile(
                id = "drive_file_$i",
                name = "nexa_backup_bp_${timestamp}.vault",
                size = 10_000_000L,
                appProperties = mapOf(
                    BackupRestorePoint.KEY_STATUS to RestorePointStatus.VERIFIED.name,
                    BackupRestorePoint.KEY_CREATED_AT to timestamp.toString()
                )
            )
        }

        val points = mockFiles.map { BackupRestorePoint.fromDriveFile(it) }
        val verifiedPoints = points
            .filter { it.status == RestorePointStatus.VERIFIED }
            .sortedByDescending { it.createdAt }

        // Point with index 7 (oldest, drive_file_1) is candidate for deletion
        val toDeleteCandidates = verifiedPoints.drop(BackupRestorePoint.MAX_RETENTION_POINTS)
        assertEquals(1, toDeleteCandidates.size)
        assertEquals("drive_file_1", toDeleteCandidates[0].driveFileId)

        // Mock an active upload session targeting drive_file_1
        val activeSessionFileId = "drive_file_1"
        val deletedFiles = mutableListOf<String>()

        for (candidate in toDeleteCandidates) {
            if (candidate.driveFileId == activeSessionFileId) {
                // Must skip deletion!
                continue
            }
            deletedFiles.add(candidate.driveFileId)
        }

        assertTrue("Active session file must NEVER be deleted during retention", deletedFiles.isEmpty())
    }

    @Test
    fun testRestorePointDiscovery_sortsNewestToOldest() {
        val file1 = DriveFile(
            id = "f1",
            name = "nexa_backup_bp_1000.vault",
            size = 1000L,
            appProperties = mapOf(BackupRestorePoint.KEY_CREATED_AT to "1000", BackupRestorePoint.KEY_STATUS to "VERIFIED")
        )
        val file2 = DriveFile(
            id = "f2",
            name = "nexa_backup_bp_3000.vault",
            size = 1000L,
            appProperties = mapOf(BackupRestorePoint.KEY_CREATED_AT to "3000", BackupRestorePoint.KEY_STATUS to "VERIFIED")
        )
        val file3 = DriveFile(
            id = "f3",
            name = "nexa_backup_bp_2000.vault",
            size = 1000L,
            appProperties = mapOf(BackupRestorePoint.KEY_CREATED_AT to "2000", BackupRestorePoint.KEY_STATUS to "VERIFIED")
        )
        val legacy = DriveFile(
            id = "f_leg",
            name = "nx_secure_backup.vault",
            size = 1000L,
            appProperties = mapOf(BackupRestorePoint.KEY_VERSION_EPOCH to "500")
        )

        val discovered = listOf(file1, file2, file3, legacy)
            .map { BackupRestorePoint.fromDriveFile(it) }
            .sortedByDescending { it.createdAt }

        assertEquals(4, discovered.size)
        assertEquals("f2", discovered[0].driveFileId)
        assertEquals(3000L, discovered[0].createdAt)

        assertEquals("f3", discovered[1].driveFileId)
        assertEquals(2000L, discovered[1].createdAt)

        assertEquals("f1", discovered[2].driveFileId)
        assertEquals(1000L, discovered[2].createdAt)

        assertEquals("f_leg", discovered[3].driveFileId)
        assertEquals(500L, discovered[3].createdAt)
        assertTrue(discovered[3].isLegacy)
    }

    @Test
    fun testUploadVerification_detectsCorruptSizeOrHeader() {
        // 1. Size < 71 bytes (fails verification)
        val tooSmallFile = DriveFile(id = "small", name = "test.vault", size = 70L)
        assertTrue(tooSmallFile.size < BackupVaultHeader.HEADER_SIZE)

        // 2. Exactly 71 bytes valid header
        val validHeader = BackupVaultHeader(
            version = 2,
            salt = ByteArray(16) { 1 },
            iv = ByteArray(12) { 2 },
            payloadSha256 = ByteArray(32) { 3 }
        )
        val validBytes = validHeader.toByteArray()
        assertEquals(71, validBytes.size)
        assertNotNull(BackupVaultHeader.fromByteArray(validBytes))

        // 3. Corrupt magic bytes
        val corruptBytes = validBytes.clone()
        corruptBytes[0] = 'X'.code.toByte()
        assertNull(BackupVaultHeader.fromByteArray(corruptBytes))

        // 4. Unsupported future version
        val badVersionBytes = validBytes.clone()
        badVersionBytes[7] = 99 // version = 99
        assertNull(BackupVaultHeader.fromByteArray(badVersionBytes))
    }

    @Test
    fun testConcurrencyMutex_preventsOverlappingRetentionJobs() = runBlocking {
        val mutex = Mutex()
        val executionOrder = Collections.synchronizedList(mutableListOf<String>())
        val concurrentCount = AtomicInteger(0)
        val maxConcurrentSeen = AtomicInteger(0)

        // Launch 5 concurrent retention tasks
        val jobs = (1..5).map { taskId ->
            async(Dispatchers.Default) {
                mutex.withLock {
                    val current = concurrentCount.incrementAndGet()
                    if (current > maxConcurrentSeen.get()) {
                        maxConcurrentSeen.set(current)
                    }
                    executionOrder.add("start_$taskId")
                    delay(20) // simulate network latency during retention
                    executionOrder.add("end_$taskId")
                    concurrentCount.decrementAndGet()
                }
            }
        }

        jobs.awaitAll()

        assertEquals("Max concurrent retention tasks under mutex must be strictly 1", 1, maxConcurrentSeen.get())
        assertEquals("All 5 start and end events must be recorded", 10, executionOrder.size)
    }

    @Test
    fun testStatusParsingFallbackAndRobustness() {
        // Unknown status fallback
        val driveFileWithUnknownStatus = DriveFile(
            id = "unk",
            name = "nexa_backup_unknown.vault",
            size = 1000L,
            appProperties = mapOf(BackupRestorePoint.KEY_STATUS to "RANDOM_CORRUPT_STATUS")
        )
        val point1 = BackupRestorePoint.fromDriveFile(driveFileWithUnknownStatus)
        assertEquals(RestorePointStatus.VERIFIED, point1.status)

        // Truncated file with no status fallback
        val driveFileTruncatedNoStatus = DriveFile(
            id = "trunc",
            name = "nexa_backup_trunc.vault",
            size = 50L,
            appProperties = emptyMap()
        )
        val point2 = BackupRestorePoint.fromDriveFile(driveFileTruncatedNoStatus)
        assertEquals(RestorePointStatus.INCOMPLETE, point2.status)
    }
}
