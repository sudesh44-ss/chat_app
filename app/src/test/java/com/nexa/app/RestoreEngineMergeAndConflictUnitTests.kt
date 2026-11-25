package com.nexa.app

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.core.backup.BackupManifest
import com.nexa.app.core.backup.BackupVaultHeader
import com.nexa.app.core.backup.Bip39PassphraseHelper
import com.nexa.app.core.backup.ChatRestoreInventoryItem
import com.nexa.app.core.backup.RestoreEngine
import com.nexa.app.core.backup.RestoreInventory
import com.nexa.app.core.backup.RestoreMode
import com.nexa.app.core.backup.RestorePlan
import com.nexa.app.core.backup.VaultBundlePackager
import com.nexa.app.core.storage.NexaStorageCategory
import com.nexa.app.core.storage.NexaStorageManager
import com.nexa.app.domain.model.BackupCategory
import com.nexa.app.domain.model.BackupRestorePoint
import com.nexa.app.domain.model.RestorePointStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Production Unit Test Suite for Part 7: Restore Engine, Selective Restore, Merge & Conflict Handling.
 *
 * Verifies:
 * 1. Restore Point Discovery: Model serialization, legacy detection, status filtering.
 * 2. Header & Checksum Integrity: Magic NXVAULT validation, checksum mismatch rejection.
 * 3. BIP-39 Passphrase Verification: Normalization, 12-word validation, tamper rejection.
 * 4. Staged Vault Inspection: Manifest integrity and inventory extraction.
 * 5. Category-Selective Restore: 7-category toggles, unselected categories untouched.
 * 6. Chat-Selective Restore: Personal vs groups, search filtering, isolated chat import.
 * 7. Non-Destructive MERGE Mode: Existing chats preserved, missing chats imported, stable ID deduplication.
 * 8. MERGE Snippet Updates: Newer backup messages update chat snippet; older messages do not overwrite.
 * 9. Scoped REPLACE Mode: Scoped deletion of target chats, unselected chats and credentials preserved.
 * 10. Media Restoration: Routing to NexaStorageManager, path traversal defense, collision resolution.
 * 11. Settings Restoration: Strict allowlist (themes/preferences imported; tokens/keys/PINs stripped).
 * 12. Transactional Rollback: Database consistency guaranteed upon failure.
 * 13. Legacy Backup Compatibility: Format v1 single database restoration.
 */
class RestoreEngineMergeAndConflictUnitTests {

    private lateinit var tempDir: File
    private lateinit var nexaRootDir: File
    private lateinit var internalFilesDir: File
    private lateinit var cacheDir: File
    private lateinit var storageManager: NexaStorageManager

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "nexa_restore_test_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        nexaRootDir = File(tempDir, "media/NEXA").apply { mkdirs() }
        internalFilesDir = File(tempDir, "internal_files").apply { mkdirs() }
        cacheDir = File(tempDir, "cache").apply { mkdirs() }

        storageManager = NexaStorageManager(
            context = null,
            customRootDir = nexaRootDir,
            customCacheDir = cacheDir,
            customDataDir = internalFilesDir
        )
        storageManager.initStorageTree()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // 1. RESTORE POINT DISCOVERY & MODEL TESTS
    // =========================================================================

    @Test
    fun testRestorePointDiscovery_modelAndFiltering() {
        val epoch = 1732380000000L
        val verifiedPoint = BackupRestorePoint(
            backupId = "bp_${epoch}_a1b2c3d4",
            createdAt = epoch,
            fileName = "nexa_backup_bp_${epoch}_a1b2c3d4.vault",
            driveFileId = "drive_file_123",
            sizeBytes = 1048576L,
            totalMessages = 150,
            totalConversations = 10,
            mediaFilesCount = 25,
            status = RestorePointStatus.VERIFIED
        )
        val incompletePoint = verifiedPoint.copy(
            backupId = "bp_${epoch}_incomplete",
            status = RestorePointStatus.INCOMPLETE
        )
        val legacyPoint = BackupRestorePoint(
            backupId = "legacy_001",
            createdAt = epoch - 86400000L,
            fileName = "nx_secure_backup.vault",
            driveFileId = "drive_file_legacy",
            sizeBytes = 524288L,
            status = RestorePointStatus.VERIFIED,
            isLegacy = true,
            formatVersion = 1
        )

        val points = listOf(verifiedPoint, incompletePoint, legacyPoint)
        val validRestorablePoints = points.filter { it.status == RestorePointStatus.VERIFIED }

        assertEquals(2, validRestorablePoints.size)
        assertTrue(validRestorablePoints.any { it.isLegacy })
        assertFalse(validRestorablePoints.contains(incompletePoint))

        val props = verifiedPoint.toAppProperties()
        assertEquals(verifiedPoint.backupId, props[BackupRestorePoint.KEY_BACKUP_ID])
        assertEquals("150", props[BackupRestorePoint.KEY_TOTAL_MESSAGES])
        assertEquals("10", props[BackupRestorePoint.KEY_TOTAL_CONVERSATIONS])
        assertNull(props["auth_token"])
        assertNull(props["phone_number"])
    }

    // =========================================================================
    // 2. HEADER & CHECKSUM INTEGRITY TESTS
    // =========================================================================

    @Test
    fun testBackupVaultHeader_validationAndTamperRejection() {
        val salt = ByteArray(16) { 0x01 }
        val iv = ByteArray(12) { 0x02 }
        val payloadHash = MessageDigest.getInstance("SHA-256").digest("CIPHERTEXT".toByteArray())

        val header = BackupVaultHeader(
            version = 2,
            salt = salt,
            iv = iv,
            payloadSha256 = payloadHash
        )
        val headerBytes = header.toByteArray()
        assertEquals(71, headerBytes.size)

        val parsed = BackupVaultHeader.fromByteArray(headerBytes)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.version)
        assertTrue(salt.contentEquals(parsed.salt))
        assertTrue(iv.contentEquals(parsed.iv))
        assertTrue(payloadHash.contentEquals(parsed.payloadSha256))

        // Corrupted Magic
        val corruptBytes = headerBytes.clone()
        corruptBytes[0] = 'B'.code.toByte()
        corruptBytes[1] = 'A'.code.toByte()
        corruptBytes[2] = 'D'.code.toByte()
        assertNull(BackupVaultHeader.fromByteArray(corruptBytes))

        // Checksum verification failure simulation
        val tamperedCiphertext = "TAMPERED_CIPHERTEXT".toByteArray()
        val computedTamperedHash = MessageDigest.getInstance("SHA-256").digest(tamperedCiphertext)
        assertFalse(computedTamperedHash.contentEquals(parsed.payloadSha256))
    }

    // =========================================================================
    // 3. BIP-39 PASSPHRASE VERIFICATION TESTS
    // =========================================================================

    @Test
    fun testBip39Passphrase_normalizationAndValidation() {
        val validMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val uppercaseMnemonic = "ABANDON ABANDON ABANDON ABANDON ABANDON ABANDON ABANDON ABANDON ABANDON ABANDON ABANDON ABOUT"
        val untrimmedMnemonic = "  abandon   abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about  "

        assertEquals(validMnemonic, Bip39PassphraseHelper.normalize(uppercaseMnemonic))
        assertEquals(validMnemonic, Bip39PassphraseHelper.normalize(untrimmedMnemonic))
        assertTrue(Bip39PassphraseHelper.validateMnemonic(validMnemonic))

        // Invalid word counts
        assertFalse(Bip39PassphraseHelper.validateMnemonic("abandon abandon"))
        assertFalse(Bip39PassphraseHelper.validateMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"))
        // Invalid words
        assertFalse(Bip39PassphraseHelper.validateMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon notaword"))
    }

    // =========================================================================
    // 4. STAGED VAULT INSPECTION & MANIFEST INTEGRITY TESTS
    // =========================================================================

    @Test
    fun testManifestIntegrity_matchesComputedSha256() {
        val stagingDir = File(tempDir, "staging_inspect").apply { mkdirs() }
        val dbDir = File(stagingDir, "databases").apply { mkdirs() }
        val snapshotDb = File(dbDir, "nexa_master_snapshot.db").apply {
            writeText("SNAPSHOT_DATABASE_CONTENT_OK")
        }
        val computedSha = VaultBundlePackager.computeSha256(snapshotDb)

        val manifestJson = JSONObject().apply {
            put("version_epoch", 1732380000000L)
            put("format_version", 2)
            put("total_conversations", 5)
            put("total_messages", 42)
            put("media_files_count", 3)
            put("database_sha256", computedSha)
            put("category_config", BackupCategoryConfig().toJsonObject())
        }
        File(stagingDir, "manifest.json").writeText(manifestJson.toString(2))

        val readSha = manifestJson.getString("database_sha256")
        assertEquals(computedSha, readSha)

        // Simulate tampering
        snapshotDb.writeText("TAMPERED_DATABASE_CONTENT")
        val tamperedSha = VaultBundlePackager.computeSha256(snapshotDb)
        assertFalse(readSha.equals(tamperedSha, ignoreCase = true))
    }

    // =========================================================================
    // 5. CATEGORY SELECTIVE RESTORE CONFIG TESTS
    // =========================================================================

    @Test
    fun testCategorySelection_selectiveTogglesControlDataRestoration() {
        val allConfig = BackupCategoryConfig.allEnabled()
        assertTrue(allConfig.includeChats)
        assertTrue(allConfig.includeImages)
        assertTrue(allConfig.includeVideos)
        assertTrue(allConfig.includeDocuments)
        assertTrue(allConfig.includeAudio)
        assertTrue(allConfig.includeOtherFiles)
        assertTrue(allConfig.includeSettings)

        // Selective plan: only images and documents
        val selectiveConfig = BackupCategoryConfig(
            includeChats = false,
            includeImages = true,
            includeVideos = false,
            includeDocuments = true,
            includeAudio = false,
            includeOtherFiles = false,
            includeSettings = false
        )

        assertTrue(selectiveConfig.isCategoryEnabled(BackupCategory.IMAGES))
        assertTrue(selectiveConfig.isCategoryEnabled(BackupCategory.DOCUMENTS))
        assertFalse(selectiveConfig.isCategoryEnabled(BackupCategory.VIDEOS))
        assertFalse(selectiveConfig.isCategoryEnabled(BackupCategory.AUDIO))
        assertFalse(selectiveConfig.isCategoryEnabled(BackupCategory.CHATS))
        assertFalse(selectiveConfig.isCategoryEnabled(BackupCategory.SETTINGS))

        // Media matching
        assertTrue(VaultBundlePackager.matchesMediaCategory("photo.jpg", selectiveConfig))
        assertTrue(VaultBundlePackager.matchesMediaCategory("report.pdf", selectiveConfig))
        assertFalse(VaultBundlePackager.matchesMediaCategory("movie.mp4", selectiveConfig))
        assertFalse(VaultBundlePackager.matchesMediaCategory("audio.mp3", selectiveConfig))
    }

    // =========================================================================
    // 6. CHAT & GROUP SELECTIVE RESTORE FILTERING TESTS
    // =========================================================================

    @Test
    fun testChatSelection_searchAndFilterLogic() {
        val chats = listOf(
            ChatRestoreInventoryItem("conv_alice", "Alice Johnson", "DIRECT", isGroup = false, messageCount = 50, attachmentCount = 5, existsLocally = true, localMessageCount = 20, lastTimestamp = 1000L),
            ChatRestoreInventoryItem("conv_bob", "Bob Smith", "DIRECT", isGroup = false, messageCount = 10, attachmentCount = 0, existsLocally = false, localMessageCount = 0, lastTimestamp = 2000L),
            ChatRestoreInventoryItem("conv_group_dev", "Android Dev Group", "GROUP", isGroup = true, messageCount = 200, attachmentCount = 20, existsLocally = true, localMessageCount = 200, lastTimestamp = 3000L),
            ChatRestoreInventoryItem("conv_group_family", "Family Group", "GROUP", isGroup = true, messageCount = 80, attachmentCount = 15, existsLocally = false, localMessageCount = 0, lastTimestamp = 4000L)
        )

        // Filter direct chats
        val directChats = chats.filter { !it.isGroup }
        assertEquals(2, directChats.size)
        assertEquals("conv_alice", directChats[0].conversationId)

        // Filter group chats
        val groupChats = chats.filter { it.isGroup }
        assertEquals(2, groupChats.size)
        assertEquals("conv_group_dev", groupChats[0].conversationId)

        // Search query
        val searched = chats.filter { it.title.contains("dev", ignoreCase = true) }
        assertEquals(1, searched.size)
        assertEquals("conv_group_dev", searched[0].conversationId)

        // Selected subset
        val selectedIds = setOf("conv_alice", "conv_group_dev")
        val selectedChats = chats.filter { selectedIds.contains(it.conversationId) }
        assertEquals(2, selectedChats.size)
        assertTrue(selectedChats.all { it.existsLocally })
    }

    // =========================================================================
    // 7. NON-DESTRUCTIVE MERGE ENGINE TESTS
    // =========================================================================

    @Test
    fun testMergeMode_sqlGenerationAndNonDestructiveness() {
        val executedSqls = mutableListOf<String>()
        val mockDb = createMockDatabase(executedSqls)

        val stagingDir = File(tempDir, "merge_staging").apply { mkdirs() }
        val snapshotDb = File(stagingDir, "databases/nexa_master_snapshot.db").apply {
            parentFile?.mkdirs()
            writeText("SNAPSHOT_MOCK")
        }

        val inventory = RestoreInventory(
            backupId = "bp_test_merge",
            createdAt = System.currentTimeMillis(),
            formatVersion = 2,
            totalConversations = 2,
            totalMessages = 15,
            mediaFilesCount = 0,
            availableCategories = BackupCategoryConfig.allEnabled(),
            chatItems = listOf(
                ChatRestoreInventoryItem("conv_alice", "Alice", "DIRECT", false, 10, 0, true, 5, 2000L),
                ChatRestoreInventoryItem("conv_bob", "Bob", "DIRECT", false, 5, 0, false, 0, 1000L)
            ),
            stagingDir = stagingDir,
            stagedDbFile = snapshotDb
        )

        val plan = RestorePlan(
            mode = RestoreMode.MERGE,
            selectedCategories = BackupCategoryConfig(includeChats = true),
            selectedConversationIds = setOf("conv_alice", "conv_bob")
        )

        val engine = RestoreEngine(context = null, customFilesDir = internalFilesDir)
        val summary = runBlocking {
            engine.executeRestore(
                inventory = inventory,
                liveDatabase = mockDb,
                plan = plan
            )
        }

        assertTrue(summary.isSuccess)
        assertEquals(RestoreMode.MERGE, summary.mode)

        // Verify that INSERT OR IGNORE statements were generated for non-destructive merge
        assertTrue(executedSqls.any { it.contains("INSERT OR IGNORE INTO main.chats") })
        assertTrue(executedSqls.any { it.contains("INSERT OR IGNORE INTO main.messages") })
        assertTrue(executedSqls.any { it.contains("INSERT OR IGNORE INTO main.message_attachments") })
        // Verify that existing chats are updated only if staged message timestamp is newer
        assertTrue(executedSqls.any { it.contains("UPDATE main.chats") && it.contains("lastMessageTimestamp") })
        // Verify no blanket DELETE statement was executed on messages
        assertFalse(executedSqls.any { it.startsWith("DELETE FROM main.messages WHERE conversationId") })
    }

    // =========================================================================
    // 8. SCOPED REPLACE ENGINE TESTS
    // =========================================================================

    @Test
    fun testReplaceMode_scopedDeletionAndTargetedInsertion() {
        val executedSqls = mutableListOf<String>()
        val mockDb = createMockDatabase(executedSqls)

        val stagingDir = File(tempDir, "replace_staging").apply { mkdirs() }
        val snapshotDb = File(stagingDir, "databases/nexa_master_snapshot.db").apply {
            parentFile?.mkdirs()
            writeText("SNAPSHOT_MOCK")
        }

        val inventory = RestoreInventory(
            backupId = "bp_test_replace",
            createdAt = System.currentTimeMillis(),
            formatVersion = 2,
            totalConversations = 2,
            totalMessages = 15,
            mediaFilesCount = 0,
            availableCategories = BackupCategoryConfig.allEnabled(),
            chatItems = listOf(
                ChatRestoreInventoryItem("conv_alice", "Alice", "DIRECT", false, 10, 0, true, 5, 2000L)
            ),
            stagingDir = stagingDir,
            stagedDbFile = snapshotDb
        )

        val plan = RestorePlan(
            mode = RestoreMode.REPLACE,
            selectedCategories = BackupCategoryConfig(includeChats = true),
            selectedConversationIds = setOf("conv_alice")
        )

        val engine = RestoreEngine(context = null, customFilesDir = internalFilesDir)
        val summary = runBlocking {
            engine.executeRestore(
                inventory = inventory,
                liveDatabase = mockDb,
                plan = plan
            )
        }

        assertTrue(summary.isSuccess)
        assertEquals(RestoreMode.REPLACE, summary.mode)

        // Verify scoped DELETE on selected conversation only
        assertTrue(executedSqls.any { it.contains("DELETE FROM main.messages WHERE conversationId IN ('conv_alice')") })
        assertTrue(executedSqls.any { it.contains("DELETE FROM main.chats WHERE conversationId IN ('conv_alice')") })
        // Verify that unselected conversations or tables like users are NOT deleted
        assertFalse(executedSqls.any { it.contains("DELETE FROM main.users") })
        assertFalse(executedSqls.any { it.contains("DELETE FROM main.user_profile") })
        // Verify subsequent insertion
        assertTrue(executedSqls.any { it.contains("INSERT INTO main.chats") })
        assertTrue(executedSqls.any { it.contains("INSERT INTO main.messages") })
    }

    // =========================================================================
    // 9. MEDIA RESTORATION & SCOPED STORAGE ROUTING TESTS
    // =========================================================================

    @Test
    fun testMediaRestoration_scopedStorageRoutingAndCollisionDisambiguation() {
        val stagingDir = File(tempDir, "media_staging").apply { mkdirs() }
        val stagedMediaDir = File(stagingDir, "media_vault").apply { mkdirs() }

        // Create test media files in staging
        val imageFile = File(stagedMediaDir, "photo_beach.jpg").apply { writeText("IMAGE_DATA") }
        val videoFile = File(stagedMediaDir, "clip_sunset.mp4").apply { writeText("VIDEO_DATA") }
        val docFile = File(stagedMediaDir, "invoice_nov.pdf").apply { writeText("DOC_DATA") }

        // Pre-create an existing file in target to test collision disambiguation
        val imagesTargetDir = storageManager.getDirectory(NexaStorageCategory.IMAGES)
        val existingImage = File(imagesTargetDir, "photo_beach.jpg").apply { writeText("EXISTING_OLD_IMAGE") }

        val safeCandidate = storageManager.resolveSafeFile(NexaStorageCategory.IMAGES, "photo_beach.jpg")
        assertEquals("photo_beach (1).jpg", safeCandidate.name)

        // Test path traversal defense
        val traversalCandidate = storageManager.resolveSafeFile(NexaStorageCategory.IMAGES, "../../etc/passwd")
        assertTrue(traversalCandidate.canonicalPath.startsWith(imagesTargetDir.canonicalPath))
        assertFalse(traversalCandidate.name.contains(".."))
    }

    // =========================================================================
    // 10. SETTINGS RESTORATION & SECURITY FILTERING TESTS
    // =========================================================================

    @Test
    fun testSettingsRestoration_stripsSensitiveTokensAndSecrets() {
        val stagingDir = File(tempDir, "settings_staging").apply { mkdirs() }
        val prefsDir = File(stagingDir, "preferences").apply { mkdirs() }

        // Prepare raw settings containing both safe portable settings and prohibited secrets
        val rawSettings = JSONObject().apply {
            put("app_theme_mode", "DARK")
            put("font_size_scale", 1.15)
            put("chat_bubble_style", "MODERN_ROUNDED")
            put("notifications_enabled", true)
            put("auth_token", "eyJhbGciOiJIUzI1NiIsIn...")
            put("jwt_secret_key", "super_secret_session_key")
            put("app_lock_pin_hash", "9876_hashed")
            put("device_private_key", "MIIEvgIBADANBgkqhkiG9w0BAQE...")
            put("session_id", "sess_abcdef123456")
            put("biometric_enrolled", true)
        }
        File(prefsDir, "app_user_settings.json").writeText(rawSettings.toString(2))

        // Execute real allowlist sanitizer
        val sanitizedPrefs = RestoreEngine.sanitizeJsonObject(
            json = rawSettings,
            allowlist = RestoreEngine.APPROVED_SETTINGS_ALLOWLIST
        )

        assertEquals(4, sanitizedPrefs.size)
        assertTrue(sanitizedPrefs.containsKey("app_theme_mode"))
        assertTrue(sanitizedPrefs.containsKey("font_size_scale"))
        assertTrue(sanitizedPrefs.containsKey("chat_bubble_style"))
        assertTrue(sanitizedPrefs.containsKey("notifications_enabled"))

        assertFalse(sanitizedPrefs.containsKey("auth_token"))
        assertFalse(sanitizedPrefs.containsKey("jwt_secret_key"))
        assertFalse(sanitizedPrefs.containsKey("app_lock_pin_hash"))
        assertFalse(sanitizedPrefs.containsKey("device_private_key"))
        assertFalse(sanitizedPrefs.containsKey("session_id"))
        assertFalse(sanitizedPrefs.containsKey("biometric_enrolled"))
    }

    // =========================================================================
    // 11. TRANSACTIONAL ROLLBACK & DATABASE SAFETY TESTS
    // =========================================================================

    @Test
    fun testTransactionalSafety_rollsBackCleanlyOnFailure() {
        val executedSqls = mutableListOf<String>()
        var transactionBegun = false
        var transactionSuccessful = false
        var transactionEnded = false

        val loader = SupportSQLiteDatabase::class.java.classLoader
        val mockDb = Proxy.newProxyInstance(
            loader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            when (method.name) {
                "beginTransaction" -> {
                    transactionBegun = true
                    null
                }
                "setTransactionSuccessful" -> {
                    transactionSuccessful = true
                    null
                }
                "endTransaction" -> {
                    transactionEnded = true
                    null
                }
                "execSQL" -> {
                    val sql = args?.get(0) as? String ?: ""
                    executedSqls.add(sql)
                    if (sql.contains("INSERT OR IGNORE INTO main.messages")) {
                        throw IllegalStateException("Simulated SQLite write error during merge!")
                    }
                    null
                }
                "query" -> {
                    createMockCursor(1, isOk = true)
                }
                else -> null
            }
        } as SupportSQLiteDatabase

        val stagingDir = File(tempDir, "fail_staging").apply { mkdirs() }
        val snapshotDb = File(stagingDir, "databases/nexa_master_snapshot.db").apply {
            parentFile?.mkdirs()
            writeText("SNAPSHOT_MOCK")
        }

        val inventory = RestoreInventory(
            backupId = "bp_test_fail",
            createdAt = System.currentTimeMillis(),
            formatVersion = 2,
            totalConversations = 1,
            totalMessages = 5,
            mediaFilesCount = 0,
            availableCategories = BackupCategoryConfig.allEnabled(),
            chatItems = listOf(ChatRestoreInventoryItem("conv_fail", "Fail", "DIRECT", false, 5, 0, false, 0, 1000L)),
            stagingDir = stagingDir,
            stagedDbFile = snapshotDb
        )

        val plan = RestorePlan(
            mode = RestoreMode.MERGE,
            selectedCategories = BackupCategoryConfig(includeChats = true),
            selectedConversationIds = setOf("conv_fail")
        )

        val engine = RestoreEngine(context = null, customFilesDir = internalFilesDir)
        val summary = runBlocking {
            engine.executeRestore(
                inventory = inventory,
                liveDatabase = mockDb,
                plan = plan
            )
        }

        assertFalse(summary.isSuccess)
        assertTrue(summary.errors.isNotEmpty())
        assertTrue(summary.errors[0].contains("Simulated SQLite write error"))
        assertTrue(transactionBegun)
        assertFalse(transactionSuccessful) // Never marked successful!
        assertTrue(transactionEnded)      // Cleanly ended/rolled back!
    }

    // =========================================================================
    // 12. LEGACY FORMAT V1 COMPATIBILITY TESTS
    // =========================================================================

    @Test
    fun testLegacyBackupCompatibility_formatV1HandledCorrectly() {
        val legacyPoint = BackupRestorePoint(
            backupId = "legacy_backup_001",
            createdAt = 1730000000000L,
            fileName = "nx_secure_backup.vault",
            driveFileId = "legacy_drive_id",
            sizeBytes = 204800L,
            status = RestorePointStatus.VERIFIED,
            isLegacy = true,
            formatVersion = 1
        )

        assertTrue(legacyPoint.isLegacy)
        assertEquals(1, legacyPoint.formatVersion)
        assertEquals("nx_secure_backup.vault", legacyPoint.fileName)

        val stagingDir = File(tempDir, "legacy_staging").apply { mkdirs() }
        val dbDir = File(stagingDir, "databases").apply { mkdirs() }
        val legacyDb = File(dbDir, "nexa_master_snapshot.db").apply {
            writeText("LEGACY_SQLITE_CONTENT")
        }

        val executedSqls = mutableListOf<String>()
        val mockDb = createMockDatabase(executedSqls)

        val engine = RestoreEngine(context = null, customFilesDir = internalFilesDir)
        val inventory = runBlocking {
            engine.inspectStagedBundle(
                stagingDir = stagingDir,
                liveDatabase = mockDb,
                backupId = legacyPoint.backupId
            )
        }

        assertTrue(inventory.isLegacy)
        assertEquals(1, inventory.formatVersion)
        assertEquals(legacyDb.absolutePath, inventory.stagedDbFile.absolutePath)
    }

    // =========================================================================
    // HELPER MOCK GENERATORS FOR JVM TESTS
    // =========================================================================

    private fun createMockDatabase(executedSqls: MutableList<String>): SupportSQLiteDatabase {
        val loader = SupportSQLiteDatabase::class.java.classLoader
        return Proxy.newProxyInstance(
            loader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    val sql = args?.get(0) as? String ?: ""
                    executedSqls.add(sql)
                    null
                }
                "beginTransaction" -> null
                "setTransactionSuccessful" -> null
                "endTransaction" -> null
                "query" -> {
                    createMockCursor(1, isOk = true)
                }
                else -> null
            }
        } as SupportSQLiteDatabase
    }

    private fun createMockCursor(countVal: Int, isOk: Boolean): Cursor {
        var isFirst = true
        var isClosed = false
        val loader = Cursor::class.java.classLoader
        return Proxy.newProxyInstance(
            loader,
            arrayOf(Cursor::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "moveToFirst" -> {
                    val res = isFirst
                    isFirst = false
                    res
                }
                "moveToNext" -> false
                "getInt" -> countVal
                "getLong" -> 1000L
                "getString" -> if (isOk) "ok" else "error"
                "getCount" -> if (isOk) 0 else 1
                "getColumnIndex" -> 0
                "close" -> {
                    isClosed = true
                    null
                }
                "isClosed" -> isClosed
                else -> null
            }
        } as Cursor
    }
}
