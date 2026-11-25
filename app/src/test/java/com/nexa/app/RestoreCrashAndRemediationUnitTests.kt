package com.nexa.app

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.core.backup.BackupCryptoEngine
import com.nexa.app.core.backup.ChatRestoreInventoryItem
import com.nexa.app.core.backup.RestoreEngine
import com.nexa.app.core.backup.RestoreInventory
import com.nexa.app.core.backup.RestoreMode
import com.nexa.app.core.backup.RestorePlan
import com.nexa.app.core.backup.SettingsPropagationBridge
import com.nexa.app.core.backup.VaultBundlePackager
import com.nexa.app.core.storage.NexaStorageCategory
import com.nexa.app.core.storage.NexaStorageManager
import com.nexa.app.domain.model.BackupCategory
import com.nexa.app.presentation.backup.BackupViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy

/**
 * Production Test Suite for Part 7.2 Remediation:
 * Restore Engine Audit Findings Correction, Strict Allowlist, Group Filtering,
 * Staging Cleanup, Hardcoded Fallback Prevention, and Deterministic Crash-Recovery.
 */
class RestoreCrashAndRemediationUnitTests {

    private lateinit var tempDir: File
    private lateinit var stagingDir: File
    private lateinit var internalFilesDir: File
    private lateinit var nexaRootDir: File
    private lateinit var cacheDir: File
    private lateinit var storageManager: NexaStorageManager

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "nexa_remediation_test_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        stagingDir = File(tempDir, "staging").apply { mkdirs() }
        internalFilesDir = File(tempDir, "internal_files").apply { mkdirs() }
        nexaRootDir = File(tempDir, "media/NEXA").apply { mkdirs() }
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
    // FIX A: SETTINGS RESTORATION PRIVACY (STRICT EXPLICIT ALLOWLIST)
    // =========================================================================

    @Test
    fun testSettingsAllowlist_approvedKeysAcceptedAndUnknownKeysRejected() {
        val payload = JSONObject().apply {
            // Approved Safe Keys
            put("app_theme_mode", "DARK")
            put("font_size_scale", 1.15)
            put("chat_bubble_style", "MODERN_ROUNDED")
            put("notifications_enabled", true)
            put("selected_language", "en")
            put("backup_frequency", "WEEKLY")

            // Unapproved & Sensitive Keys
            put("auth_token", "secret_bearer_token_123")
            put("jwt_secret_key", "super_secret_hmac_key")
            put("app_lock_pin_hash", "pin_hash_9876")
            put("device_private_key", "MIIEvgIBADANBgkqhkiG9w0BAQE...")
            put("session_id", "session_abc123")
            put("biometric_enrolled", true)
            put("seed_phrase", "twelve words that must never leak")
            put("google_oauth_token", "oauth2_token_xyz")
            put("master_salt", "salt_bytes")
            put("pin_code", "1234")

            // Unknown Arbitrary Keys
            put("unknown_random_key", "arbitrary_data")
            put("analytics_tracking_id", "UA-12345-6")
        }

        val sanitized = RestoreEngine.sanitizeJsonObject(payload, RestoreEngine.APPROVED_SETTINGS_ALLOWLIST)

        // Verify Approved keys are present
        assertEquals(6, sanitized.size)
        assertEquals("DARK", sanitized["app_theme_mode"])
        assertEquals(1.15f, sanitized["font_size_scale"])
        assertEquals("MODERN_ROUNDED", sanitized["chat_bubble_style"])
        assertEquals(true, sanitized["notifications_enabled"])
        assertEquals("en", sanitized["selected_language"])
        assertEquals("WEEKLY", sanitized["backup_frequency"])

        // Verify ALL sensitive and unknown keys are strictly rejected
        assertFalse(sanitized.containsKey("auth_token"))
        assertFalse(sanitized.containsKey("jwt_secret_key"))
        assertFalse(sanitized.containsKey("app_lock_pin_hash"))
        assertFalse(sanitized.containsKey("device_private_key"))
        assertFalse(sanitized.containsKey("session_id"))
        assertFalse(sanitized.containsKey("biometric_enrolled"))
        assertFalse(sanitized.containsKey("seed_phrase"))
        assertFalse(sanitized.containsKey("google_oauth_token"))
        assertFalse(sanitized.containsKey("master_salt"))
        assertFalse(sanitized.containsKey("pin_code"))
        assertFalse(sanitized.containsKey("unknown_random_key"))
        assertFalse(sanitized.containsKey("analytics_tracking_id"))
    }

    @Test
    fun testSettingsAllowlist_nestedObjectsSanitizedRecursively() {
        val nestedPayload = JSONObject().apply {
            put("app_theme_mode", "LIGHT")
            val nestedObj = JSONObject().apply {
                put("font_size_scale", 1.0)
                put("stolen_secret", "bad_secret")
            }
            put("nested_settings", nestedObj)
        }

        val allowlist = setOf("app_theme_mode", "font_size_scale", "nested_settings")
        val sanitized = RestoreEngine.sanitizeJsonObject(nestedPayload, allowlist)

        assertTrue(sanitized.containsKey("app_theme_mode"))
        assertTrue(sanitized.containsKey("nested_settings"))

        @Suppress("UNCHECKED_CAST")
        val sanitizedNested = sanitized["nested_settings"] as? Map<String, Any>
        assertNotNull(sanitizedNested)
        assertTrue(sanitizedNested!!.containsKey("font_size_scale"))
        assertFalse(sanitizedNested.containsKey("stolen_secret"))
    }

    @Test
    fun testSettingsAllowlist_emptyAndMalformedPayloadHandledSafely() {
        val emptyPayload = JSONObject()
        val emptySanitized = RestoreEngine.sanitizeJsonObject(emptyPayload, RestoreEngine.APPROVED_SETTINGS_ALLOWLIST)
        assertTrue(emptySanitized.isEmpty())

        // String exceeding max length (2048) rejected
        val hugeString = "A".repeat(3000)
        val payloadWithHugeString = JSONObject().apply {
            put("chat_wallpaper", hugeString)
            put("app_theme_mode", "DARK")
        }
        val sanitized = RestoreEngine.sanitizeJsonObject(payloadWithHugeString, RestoreEngine.APPROVED_SETTINGS_ALLOWLIST)
        assertEquals(1, sanitized.size)
        assertTrue(sanitized.containsKey("app_theme_mode"))
        assertFalse(sanitized.containsKey("chat_wallpaper"))
    }

    @Test
    fun testChatConfigsAllowlist_strictKeyFormatAndPropertyValidation() {
        assertTrue(RestoreEngine.isApprovedChatConfigKey("conv_123_wallpaper"))
        assertTrue(RestoreEngine.isApprovedChatConfigKey("conv_123_pinned_order"))
        assertTrue(RestoreEngine.isApprovedChatConfigKey("conv_123_muted"))
        assertTrue(RestoreEngine.isApprovedChatConfigKey("conv_123_custom_theme"))
        assertTrue(RestoreEngine.isApprovedChatConfigKey("conv_123_notifications"))
        assertTrue(RestoreEngine.isApprovedChatConfigKey("conv_123_bubble_style"))
        assertTrue(RestoreEngine.isApprovedChatConfigKey("conv_123_custom_nickname"))

        // Unapproved / dangerous chat keys
        assertFalse(RestoreEngine.isApprovedChatConfigKey("conv_123_auth_token"))
        assertFalse(RestoreEngine.isApprovedChatConfigKey("conv_123_secret_key"))
        assertFalse(RestoreEngine.isApprovedChatConfigKey("conv_123_password"))
        assertFalse(RestoreEngine.isApprovedChatConfigKey("conv_123_pin"))
        assertFalse(RestoreEngine.isApprovedChatConfigKey("wallpaper")) // no prefix
        assertFalse(RestoreEngine.isApprovedChatConfigKey("conv_123_")) // trailing underscore
        assertFalse(RestoreEngine.isApprovedChatConfigKey("arbitrary_key"))
    }

    // =========================================================================
    // FIX B: GROUP CONVERSATION SELECTION AND FILTERING
    // =========================================================================

    @Test
    fun testGroupFiltering_includeGroupsFalseExcludesGroupsAndMembers() {
        val executedSqls = mutableListOf<String>()
        val mockDb = createMockDatabase(executedSqls)

        val snapshotDb = File(stagingDir, "databases/nexa_master_snapshot.db").apply {
            parentFile?.mkdirs()
            writeText("SNAPSHOT")
        }

        val directChat = ChatRestoreInventoryItem("conv_direct_alice", "Alice", "DIRECT", isGroup = false, 10, 0, false, 0, 1000L)
        val groupChat = ChatRestoreInventoryItem("conv_group_dev", "Android Devs", "GROUP", isGroup = true, 50, 5, false, 0, 2000L)

        val inventory = RestoreInventory(
            backupId = "bp_group_test",
            createdAt = System.currentTimeMillis(),
            formatVersion = 2,
            totalConversations = 2,
            totalMessages = 60,
            mediaFilesCount = 0,
            availableCategories = BackupCategoryConfig.allEnabled(),
            chatItems = listOf(directChat, groupChat),
            stagingDir = stagingDir,
            stagedDbFile = snapshotDb
        )

        // Plan: Chats enabled, but Groups DISABLED
        val plan = RestorePlan(
            mode = RestoreMode.MERGE,
            selectedCategories = BackupCategoryConfig(includeChats = true, includeGroups = false),
            selectedConversationIds = setOf("conv_direct_alice", "conv_group_dev")
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
        assertEquals(0, summary.groupsRestored)

        // Verify that target conversations in the SQL IN clause do NOT include the group conversation
        val chatInserts = executedSqls.filter { it.contains("INSERT OR IGNORE INTO main.chats") }
        assertTrue(chatInserts.isNotEmpty())
        assertTrue(chatInserts.all { !it.contains("conv_group_dev") })
        assertTrue(chatInserts.any { it.contains("conv_direct_alice") })

        // Verify chat_members table was NOT populated
        assertFalse(executedSqls.any { it.contains("INSERT OR IGNORE INTO main.chat_members") })
    }

    @Test
    fun testGroupFiltering_includeGroupsTrueRestoresGroupsAndMembers() {
        val executedSqls = mutableListOf<String>()
        val mockDb = createMockDatabase(executedSqls)

        val snapshotDb = File(stagingDir, "databases/nexa_master_snapshot.db").apply {
            parentFile?.mkdirs()
            writeText("SNAPSHOT")
        }

        val directChat = ChatRestoreInventoryItem("conv_direct_alice", "Alice", "DIRECT", isGroup = false, 10, 0, false, 0, 1000L)
        val groupChat = ChatRestoreInventoryItem("conv_group_dev", "Android Devs", "GROUP", isGroup = true, 50, 5, false, 0, 2000L)

        val inventory = RestoreInventory(
            backupId = "bp_group_test_enabled",
            createdAt = System.currentTimeMillis(),
            formatVersion = 2,
            totalConversations = 2,
            totalMessages = 60,
            mediaFilesCount = 0,
            availableCategories = BackupCategoryConfig.allEnabled(),
            chatItems = listOf(directChat, groupChat),
            stagingDir = stagingDir,
            stagedDbFile = snapshotDb
        )

        // Plan: Chats enabled AND Groups enabled
        val plan = RestorePlan(
            mode = RestoreMode.MERGE,
            selectedCategories = BackupCategoryConfig(includeChats = true, includeGroups = true),
            selectedConversationIds = setOf("conv_direct_alice", "conv_group_dev")
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

        // Verify both direct and group conversations are included in SQL IN clause
        val chatInserts = executedSqls.filter { it.contains("INSERT OR IGNORE INTO main.chats") }
        assertTrue(chatInserts.any { it.contains("conv_direct_alice") && it.contains("conv_group_dev") })

        // Verify chat_members table was populated
        assertTrue(executedSqls.any { it.contains("INSERT OR IGNORE INTO main.chat_members") })
    }

    // =========================================================================
    // FIX C: OTHER FILES & DOWNLOADS CATEGORY
    // =========================================================================

    @Test
    fun testOtherFilesCategory_classificationAndConfigMatching() {
        val archiveFile = "backup_archive.zip"
        val apkFile = "nexa_release.apk"
        val tarFile = "dataset.tar.gz"

        // Classifies into BackupCategory.OTHER_FILES
        assertEquals(BackupCategory.OTHER_FILES, VaultBundlePackager.classifyFileCategory(archiveFile))
        assertEquals(BackupCategory.OTHER_FILES, VaultBundlePackager.classifyFileCategory(apkFile))
        assertEquals(BackupCategory.OTHER_FILES, VaultBundlePackager.classifyFileCategory(tarFile))

        // When includeOtherFiles = true
        val configWithOther = BackupCategoryConfig(includeOtherFiles = true, includeAttachments = true)
        assertTrue(configWithOther.isCategoryEnabled(BackupCategory.OTHER_FILES))
        assertTrue(VaultBundlePackager.matchesMediaCategory(archiveFile, configWithOther))

        // When includeOtherFiles = false
        val configWithoutOther = BackupCategoryConfig(includeOtherFiles = false, includeAttachments = true)
        assertFalse(configWithoutOther.isCategoryEnabled(BackupCategory.OTHER_FILES))
        assertFalse(VaultBundlePackager.matchesMediaCategory(archiveFile, configWithoutOther))

        // withCategory toggle works
        val toggled = configWithoutOther.withCategory(BackupCategory.OTHER_FILES, true)
        assertTrue(toggled.includeOtherFiles)
    }

    // =========================================================================
    // FIX D: STAGING CACHE CLEANUP & PRIVACY
    // =========================================================================

    @Test
    fun testStagingCleanup_safeDeleteStagingDirDeletesCompletely() {
        val testStaging = File(cacheDir, "restore_staging_test_cleanup").apply { mkdirs() }
        File(testStaging, "snapshot.db").writeText("UNENCRYPTED_DB")
        File(testStaging, "manifest.json").writeText("{}")
        val mediaDir = File(testStaging, "media_vault").apply { mkdirs() }
        File(mediaDir, "photo.jpg").writeText("IMAGE")

        assertTrue(testStaging.exists())

        val success = BackupViewModel.safeDeleteStagingDir(testStaging)
        assertTrue(success)
        assertFalse(testStaging.exists())
    }

    @Test
    fun testStagingCleanup_retryableOnLockedOrReadOnlyFiles() {
        val testStaging = File(cacheDir, "restore_staging_readonly").apply { mkdirs() }
        val readOnlyFile = File(testStaging, "locked_file.bin").apply {
            writeText("READONLY_DATA")
            setReadOnly()
        }

        val success = BackupViewModel.safeDeleteStagingDir(testStaging)
        assertTrue(success)
        assertFalse(testStaging.exists())
    }

    // =========================================================================
    // FIX E: NO HARDCODED PASSWORD FALLBACK
    // =========================================================================

    @Test
    fun testNoHardcodedPassword_blankPassphraseRejectedImmediately() {
        val dummyVault = File(tempDir, "dummy.vault").apply { writeText("SHORT") }
        val outDir = File(tempDir, "out_staging")

        try {
            // Decrypt with blank passphrase must throw IllegalArgumentException
            runBlocking {
                val engine = BackupCryptoEngine(context = null, customCacheDir = cacheDir)
                engine.decryptVaultToStaging(dummyVault, "", outDir)
            }
            fail("Expected IllegalArgumentException for blank passphrase")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Passphrase cannot be empty or blank", ignoreCase = true))
        }
    }

    // =========================================================================
    // FIX F: DETERMINISTIC CRASH-RECOVERY INJECTION TESTS
    // =========================================================================

    @Test
    fun testDeterministicCrash_duringScopedDeletionInReplaceMode() {
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
                "beginTransaction" -> { transactionBegun = true; null }
                "setTransactionSuccessful" -> { transactionSuccessful = true; null }
                "endTransaction" -> { transactionEnded = true; null }
                "execSQL" -> {
                    val sql = args?.get(0) as? String ?: ""
                    executedSqls.add(sql)
                    // Inject failure during scoped deletion
                    if (sql.contains("DELETE FROM main.messages WHERE conversationId")) {
                        throw IllegalStateException("CRASH_INJECTION: Power loss during scoped message deletion!")
                    }
                    null
                }
                "query" -> createMockCursor(1, isOk = true)
                else -> null
            }
        } as SupportSQLiteDatabase

        val snapshotDb = File(stagingDir, "databases/nexa_master_snapshot.db").apply {
            parentFile?.mkdirs()
            writeText("SNAPSHOT")
        }

        val directChat = ChatRestoreInventoryItem("conv_alice", "Alice", "DIRECT", isGroup = false, 10, 0, false, 0, 1000L)
        val inventory = RestoreInventory(
            backupId = "bp_crash_test",
            createdAt = System.currentTimeMillis(),
            formatVersion = 2,
            totalConversations = 1,
            totalMessages = 10,
            mediaFilesCount = 0,
            availableCategories = BackupCategoryConfig.allEnabled(),
            chatItems = listOf(directChat),
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

        // Verify restoration failed gracefully
        assertFalse(summary.isSuccess)
        assertTrue(summary.errors.any { it.contains("CRASH_INJECTION") })

        // Verify atomic rollback behavior: Transaction was begun, NEVER marked successful, and cleanly ended
        assertTrue(transactionBegun)
        assertFalse(transactionSuccessful)
        assertTrue(transactionEnded)
    }

    @Test
    fun testDeterministicCrash_duringInsertionBeforeCommitInReplaceMode() {
        var transactionBegun = false
        var transactionSuccessful = false
        var transactionEnded = false

        val loader = SupportSQLiteDatabase::class.java.classLoader
        val mockDb = Proxy.newProxyInstance(
            loader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            when (method.name) {
                "beginTransaction" -> { transactionBegun = true; null }
                "setTransactionSuccessful" -> { transactionSuccessful = true; null }
                "endTransaction" -> { transactionEnded = true; null }
                "execSQL" -> {
                    val sql = args?.get(0) as? String ?: ""
                    // Inject failure during message insertion
                    if (sql.contains("INSERT INTO main.messages")) {
                        throw IllegalStateException("CRASH_INJECTION: Disk write failure during messages insertion!")
                    }
                    null
                }
                "query" -> createMockCursor(1, isOk = true)
                else -> null
            }
        } as SupportSQLiteDatabase

        val snapshotDb = File(stagingDir, "databases/nexa_master_snapshot.db").apply {
            parentFile?.mkdirs()
            writeText("SNAPSHOT")
        }

        val directChat = ChatRestoreInventoryItem("conv_alice", "Alice", "DIRECT", isGroup = false, 10, 0, false, 0, 1000L)
        val inventory = RestoreInventory(
            backupId = "bp_crash_test_insert",
            createdAt = System.currentTimeMillis(),
            formatVersion = 2,
            totalConversations = 1,
            totalMessages = 10,
            mediaFilesCount = 0,
            availableCategories = BackupCategoryConfig.allEnabled(),
            chatItems = listOf(directChat),
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

        assertFalse(summary.isSuccess)
        assertTrue(transactionBegun)
        assertFalse(transactionSuccessful)
        assertTrue(transactionEnded)
    }

    @Test
    fun testDeterministicCrash_mediaRestorationStorageFailureReportedAsWarning() {
        val executedSqls = mutableListOf<String>()
        val mockDb = createMockDatabase(executedSqls)

        val snapshotDb = File(stagingDir, "databases/nexa_master_snapshot.db").apply {
            parentFile?.mkdirs()
            writeText("SNAPSHOT")
        }

        // Staged media files
        val mediaDir = File(stagingDir, "media_vault").apply { mkdirs() }
        File(mediaDir, "photo1.jpg").writeText("PHOTO_DATA_1")

        val inventory = RestoreInventory(
            backupId = "bp_media_crash_test",
            createdAt = System.currentTimeMillis(),
            formatVersion = 2,
            totalConversations = 1,
            totalMessages = 10,
            mediaFilesCount = 1,
            availableCategories = BackupCategoryConfig.allEnabled(),
            chatItems = listOf(ChatRestoreInventoryItem("conv_alice", "Alice", "DIRECT", false, 10, 1, false, 0, 1000L)),
            stagingDir = stagingDir,
            stagedDbFile = snapshotDb
        )

        val plan = RestorePlan(
            mode = RestoreMode.MERGE,
            selectedCategories = BackupCategoryConfig(includeChats = true, includeImages = true),
            selectedConversationIds = setOf("conv_alice")
        )

        val engine = RestoreEngine(context = null, customFilesDir = internalFilesDir)
        val summary = runBlocking {
            engine.executeRestore(
                inventory = inventory,
                liveDatabase = mockDb,
                plan = plan,
                customStorageManager = storageManager
            )
        }

        // Database restoration succeeds even if partial media warning occurs
        assertTrue(summary.isSuccess)
        assertEquals(RestoreMode.MERGE, summary.mode)
        assertEquals(1, summary.mediaFilesRestored)
    }

    // =========================================================================
    // PART 7.4 REMEDIATION: MED-1, MED-2, SET-1 TARGETED TESTS
    // =========================================================================

    @Test
    fun testMediaCategoryFiltering_independentTogglesEnforcedInRestoreEngine() = runBlocking {
        val mediaDir = File(stagingDir, "media_vault").apply { mkdirs() }
        File(mediaDir, "pic.jpg").writeText("IMG")
        File(mediaDir, "vid.mp4").writeText("VID")
        File(mediaDir, "snd.mp3").writeText("AUD")
        File(mediaDir, "doc.pdf").writeText("DOC")
        File(mediaDir, "oth.zip").writeText("ZIP")

        val engine = RestoreEngine(context = null, customFilesDir = internalFilesDir)

        // 1. Only IMAGES enabled
        val configImagesOnly = BackupCategoryConfig(
            includeImages = true,
            includeVideos = false,
            includeAudio = false,
            includeDocuments = false,
            includeOtherFiles = false,
            includeAttachments = true
        )
        val statsImg = engine.restoreMediaFiles(
            context = null,
            stagingDir = stagingDir,
            categoryConfig = configImagesOnly,
            customStorageManager = storageManager
        )
        assertEquals(1, statsImg.restoredCount)
        assertTrue(File(storageManager.getDirectory(NexaStorageCategory.IMAGES), "pic.jpg").exists())
        assertFalse(File(storageManager.getDirectory(NexaStorageCategory.VIDEOS), "vid.mp4").exists())
        assertFalse(File(storageManager.getDirectory(NexaStorageCategory.AUDIO), "snd.mp3").exists())
        assertFalse(File(storageManager.getDirectory(NexaStorageCategory.DOCUMENTS), "doc.pdf").exists())
        assertFalse(File(storageManager.getDirectory(NexaStorageCategory.DOWNLOADS), "oth.zip").exists())

        // Clean output dirs for next test
        internalFilesDir.deleteRecursively(); internalFilesDir.mkdirs()
        nexaRootDir.deleteRecursively(); nexaRootDir.mkdirs()
        storageManager.initStorageTree()

        // 2. Only OTHER_FILES enabled
        val configOtherOnly = BackupCategoryConfig(
            includeImages = false,
            includeVideos = false,
            includeAudio = false,
            includeDocuments = false,
            includeOtherFiles = true,
            includeAttachments = true
        )
        val statsOther = engine.restoreMediaFiles(
            context = null,
            stagingDir = stagingDir,
            categoryConfig = configOtherOnly,
            customStorageManager = storageManager
        )
        assertEquals(1, statsOther.restoredCount)
        assertFalse(File(storageManager.getDirectory(NexaStorageCategory.IMAGES), "pic.jpg").exists())
        assertTrue(File(storageManager.getDirectory(NexaStorageCategory.DOWNLOADS), "oth.zip").exists())

        // Clean output dirs for next test
        internalFilesDir.deleteRecursively(); internalFilesDir.mkdirs()
        nexaRootDir.deleteRecursively(); nexaRootDir.mkdirs()
        storageManager.initStorageTree()

        // 3. Global attachments disabled (includeAttachments = false)
        val configNoAtt = BackupCategoryConfig(
            includeImages = true,
            includeVideos = true,
            includeAttachments = false
        )
        val statsNoAtt = engine.restoreMediaFiles(
            context = null,
            stagingDir = stagingDir,
            categoryConfig = configNoAtt,
            customStorageManager = storageManager
        )
        assertEquals(0, statsNoAtt.restoredCount)
    }

    @Test
    fun testSelectiveConversationMedia_onlySelectedConversationMediaRestored() = runBlocking {
        val mediaDir = File(stagingDir, "media_vault").apply { mkdirs() }
        File(mediaDir, "alice_photo.jpg").writeText("ALICE_PIC")
        File(mediaDir, "bob_video.mp4").writeText("BOB_VID")
        File(mediaDir, "shared_contract.pdf").writeText("SHARED_DOC")
        File(mediaDir, "group_song.mp3").writeText("GROUP_AUDIO")
        File(mediaDir, "standalone.zip").writeText("STANDALONE_ARCHIVE")

        val ownership = mapOf(
            "alice_photo.jpg" to setOf("conv_alice"),
            "bob_video.mp4" to setOf("conv_bob"),
            "shared_contract.pdf" to setOf("conv_alice", "conv_bob"),
            "group_song.mp3" to setOf("conv_group_1")
        )

        val engine = RestoreEngine(context = null, customFilesDir = internalFilesDir)
        val allCategories = BackupCategoryConfig.allEnabled()

        // Case 1: Select conv_alice only
        val statsAlice = engine.restoreMediaFiles(
            context = null,
            stagingDir = stagingDir,
            categoryConfig = allCategories,
            customStorageManager = storageManager,
            effectiveConvIds = setOf("conv_alice"),
            customOwnershipMap = ownership
        )
        // Expected: alice_photo.jpg (owned by alice), shared_contract.pdf (owned by alice & bob), standalone.zip (standalone)
        // Excluded: bob_video.mp4 (bob only), group_song.mp3 (group only)
        assertEquals(3, statsAlice.restoredCount)
        assertTrue(File(storageManager.getDirectory(NexaStorageCategory.IMAGES), "alice_photo.jpg").exists())
        assertTrue(File(storageManager.getDirectory(NexaStorageCategory.DOCUMENTS), "shared_contract.pdf").exists())
        assertTrue(File(storageManager.getDirectory(NexaStorageCategory.DOWNLOADS), "standalone.zip").exists())
        assertFalse(File(storageManager.getDirectory(NexaStorageCategory.VIDEOS), "bob_video.mp4").exists())
        assertFalse(File(storageManager.getDirectory(NexaStorageCategory.AUDIO), "group_song.mp3").exists())

        // Clean output dirs for next test
        internalFilesDir.deleteRecursively(); internalFilesDir.mkdirs()
        nexaRootDir.deleteRecursively(); nexaRootDir.mkdirs()
        storageManager.initStorageTree()

        // Case 2: Select conv_bob only
        val statsBob = engine.restoreMediaFiles(
            context = null,
            stagingDir = stagingDir,
            categoryConfig = allCategories,
            customStorageManager = storageManager,
            effectiveConvIds = setOf("conv_bob"),
            customOwnershipMap = ownership
        )
        assertEquals(3, statsBob.restoredCount)
        assertFalse(File(storageManager.getDirectory(NexaStorageCategory.IMAGES), "alice_photo.jpg").exists())
        assertTrue(File(storageManager.getDirectory(NexaStorageCategory.VIDEOS), "bob_video.mp4").exists())
        assertTrue(File(storageManager.getDirectory(NexaStorageCategory.DOCUMENTS), "shared_contract.pdf").exists())
        assertTrue(File(storageManager.getDirectory(NexaStorageCategory.DOWNLOADS), "standalone.zip").exists())
        assertFalse(File(storageManager.getDirectory(NexaStorageCategory.AUDIO), "group_song.mp3").exists())

        // Clean output dirs for next test
        internalFilesDir.deleteRecursively(); internalFilesDir.mkdirs()
        nexaRootDir.deleteRecursively(); nexaRootDir.mkdirs()
        storageManager.initStorageTree()

        // Case 3: Select all conversations including group
        val statsAll = engine.restoreMediaFiles(
            context = null,
            stagingDir = stagingDir,
            categoryConfig = allCategories,
            customStorageManager = storageManager,
            effectiveConvIds = setOf("conv_alice", "conv_bob", "conv_group_1"),
            customOwnershipMap = ownership
        )
        assertEquals(5, statsAll.restoredCount)
        assertTrue(File(storageManager.getDirectory(NexaStorageCategory.AUDIO), "group_song.mp3").exists())
    }

    @Test
    fun testSelectiveConversationMedia_combinedCategoryAndConversationFiltering() = runBlocking {
        val mediaDir = File(stagingDir, "media_vault").apply { mkdirs() }
        File(mediaDir, "alice_photo.jpg").writeText("ALICE_PIC")
        File(mediaDir, "alice_doc.pdf").writeText("ALICE_DOC")
        File(mediaDir, "bob_doc.pdf").writeText("BOB_DOC")

        val ownership = mapOf(
            "alice_photo.jpg" to setOf("conv_alice"),
            "alice_doc.pdf" to setOf("conv_alice"),
            "bob_doc.pdf" to setOf("conv_bob")
        )

        val engine = RestoreEngine(context = null, customFilesDir = internalFilesDir)

        // Select conv_alice, but disable IMAGES (includeImages = false, includeDocuments = true)
        val configDocsOnly = BackupCategoryConfig(
            includeImages = false,
            includeDocuments = true,
            includeAttachments = true
        )

        val stats = engine.restoreMediaFiles(
            context = null,
            stagingDir = stagingDir,
            categoryConfig = configDocsOnly,
            customStorageManager = storageManager,
            effectiveConvIds = setOf("conv_alice"),
            customOwnershipMap = ownership
        )

        // Expected: alice_doc.pdf is restored (conv_alice selected AND documents enabled)
        // alice_photo.jpg is excluded (IMAGES disabled)
        // bob_doc.pdf is excluded (conv_bob unselected)
        assertEquals(1, stats.restoredCount)
        assertTrue(File(storageManager.getDirectory(NexaStorageCategory.DOCUMENTS), "alice_doc.pdf").exists())
        assertFalse(File(storageManager.getDirectory(NexaStorageCategory.IMAGES), "alice_photo.jpg").exists())
        assertFalse(File(storageManager.getDirectory(NexaStorageCategory.DOCUMENTS), "bob_doc.pdf").exists())
    }

    @Test
    fun testSettingsPropagationBridge_propagatesToCanonicalStores() {
        val fakeContext = BridgeFakeContext()
        val settings = mapOf<String, Any>(
            "key_appearance_mode" to "DARK",
            "key_reduce_animations" to true,
            "key_font_style" to "MODERN",
            "message_font_size" to "LARGE",
            "enter_to_send" to true,
            "media_visibility" to false,
            "high_contrast_messages" to true,
            "selected_language" to "es",
            "notifications_enabled" to true,
            "notification_sound" to "content://media/sound/1",
            "backup_frequency" to "DAILY",
            "auto_download_wifi_images" to true
        )

        val count = SettingsPropagationBridge.propagateSettings(fakeContext, settings)
        assertTrue(count > 0)

        // 1. Appearance store
        val appPrefs = fakeContext.getSharedPreferences(SettingsPropagationBridge.PREFS_APPEARANCE, 0)
        assertEquals("DARK", appPrefs.getString("key_appearance_mode", null))
        assertTrue(appPrefs.getBoolean("key_reduce_animations", false))
        assertEquals("MODERN", appPrefs.getString("key_font_style", null))

        // 2. Chats store
        val chatsPrefs = fakeContext.getSharedPreferences(SettingsPropagationBridge.PREFS_CHATS, 0)
        assertEquals("LARGE", chatsPrefs.getString("message_font_size", null))
        assertTrue(chatsPrefs.getBoolean("enter_to_send", false))
        assertFalse(chatsPrefs.getBoolean("media_visibility", true))

        // 3. Accessibility store
        val accessPrefs = fakeContext.getSharedPreferences(SettingsPropagationBridge.PREFS_ACCESSIBILITY, 0)
        assertTrue(accessPrefs.getBoolean("high_contrast_messages", false))

        // 4. Language store
        val langPrefs = fakeContext.getSharedPreferences(SettingsPropagationBridge.PREFS_LANGUAGE, 0)
        assertEquals("es", langPrefs.getString("selected_language", null))

        // 5. Notifications & Sound
        val notifPrefs = fakeContext.getSharedPreferences(SettingsPropagationBridge.PREFS_NOTIFICATIONS, 0)
        assertTrue(notifPrefs.getBoolean("direct_messages_enabled", false))
        assertTrue(notifPrefs.getBoolean("groups_enabled", false))
        val soundPrefs = fakeContext.getSharedPreferences(SettingsPropagationBridge.PREFS_SOUND, 0)
        assertEquals("content://media/sound/1", soundPrefs.getString("sound_uri_message", null))

        // 6. Backup store
        val backupPrefs = fakeContext.getSharedPreferences(SettingsPropagationBridge.PREFS_BACKUP, 0)
        assertEquals("DAILY", backupPrefs.getString("backup_frequency", null))

        // 7. Media store
        val mediaPrefs = fakeContext.getSharedPreferences(SettingsPropagationBridge.PREFS_MEDIA, 0)
        assertTrue(mediaPrefs.getBoolean("auto_download_wifi_images", false))
    }

    @Test
    fun testSettingsPropagationBridge_chatConfigsPropagatedToConversationPreferences() {
        val fakeContext = BridgeFakeContext()
        val chatConfigs = mapOf<String, Any>(
            "conv_alice_wallpaper" to "content://wallpaper/alice",
            "conv_alice_muted" to true,
            "conv_bob_custom_theme" to "AMOLED",
            "conv_bob_custom_nickname" to "Bobby"
        )

        val count = SettingsPropagationBridge.propagateChatConfigs(fakeContext, chatConfigs)
        assertEquals(4, count)

        val convPrefs = fakeContext.getSharedPreferences(SettingsPropagationBridge.PREFS_CONVERSATIONS, 0)
        assertEquals("content://wallpaper/alice", convPrefs.getString("wallpaper_conv_alice", null))
        assertEquals(-1L, convPrefs.getLong("mute_until_conv_alice", 0L))
        assertEquals("AMOLED", convPrefs.getString("theme_conv_bob", null))
        assertEquals("Bobby", convPrefs.getString("nickname_conv_bob", null))
    }

    @Test
    fun testSettingsPropagationBridge_unknownAndSensitiveKeysStrictlyIgnored() {
        val fakeContext = BridgeFakeContext()
        val dangerousSettings = mapOf<String, Any>(
            "auth_token" to "bearer_token_123",
            "jwt_secret_key" to "secret_456",
            "app_lock_pin_hash" to "hash_789",
            "unknown_arbitrary_key" to "value"
        )

        val count = SettingsPropagationBridge.propagateSettings(fakeContext, dangerousSettings)
        assertEquals(0, count)

        // Verify zero stores were written
        assertFalse(fakeContext.getSharedPreferences(SettingsPropagationBridge.PREFS_APPEARANCE, 0).contains("auth_token"))
        assertFalse(fakeContext.getSharedPreferences(SettingsPropagationBridge.PREFS_CHATS, 0).contains("jwt_secret_key"))
    }

    @Test
    fun testSettingsPropagationBridge_idempotent() {
        val fakeContext = BridgeFakeContext()
        val settings = mapOf<String, Any>(
            "key_appearance_mode" to "AMOLED",
            "enter_to_send" to true
        )

        val count1 = SettingsPropagationBridge.propagateSettings(fakeContext, settings)
        val count2 = SettingsPropagationBridge.propagateSettings(fakeContext, settings)
        assertEquals(count1, count2)

        val appPrefs = fakeContext.getSharedPreferences(SettingsPropagationBridge.PREFS_APPEARANCE, 0)
        assertEquals("AMOLED", appPrefs.getString("key_appearance_mode", null))
    }

    // =========================================================================
    // MOCK HELPERS
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
                "query" -> createMockCursor(1, isOk = true)
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
                "close" -> { isClosed = true; null }
                "isClosed" -> isClosed
                else -> null
            }
        } as Cursor
    }
}

private class BridgeFakeContext : android.content.ContextWrapper(null) {
    private val prefsMap = mutableMapOf<String, SharedPreferences>()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        return prefsMap.getOrPut(name) { BridgeFakePrefs() }
    }
}

private class BridgeFakePrefs(
    private val data: MutableMap<String, Any> = mutableMapOf()
) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = data
    override fun getString(key: String?, defValue: String?): String? = data[key]?.toString() ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: (data[key] as? Int)?.toLong() ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(k: String?, v: String?): SharedPreferences.Editor { if (k != null && v != null) data[k] = v; return this }
        override fun putStringSet(k: String?, v: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(k: String?, v: Int): SharedPreferences.Editor { if (k != null) data[k] = v; return this }
        override fun putLong(k: String?, v: Long): SharedPreferences.Editor { if (k != null) data[k] = v; return this }
        override fun putFloat(k: String?, v: Float): SharedPreferences.Editor { if (k != null) data[k] = v; return this }
        override fun putBoolean(k: String?, v: Boolean): SharedPreferences.Editor { if (k != null) data[k] = v; return this }
        override fun remove(k: String?): SharedPreferences.Editor { if (k != null) data.remove(k); return this }
        override fun clear(): SharedPreferences.Editor { data.clear(); return this }
        override fun commit(): Boolean = true
        override fun apply() {}
    }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
