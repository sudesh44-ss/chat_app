package com.nexa.app

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.core.backup.BackupManifest
import com.nexa.app.core.backup.VaultBundlePackager
import com.nexa.app.core.storage.NexaStorageCategory
import com.nexa.app.core.storage.NexaStorageManager
import com.nexa.app.domain.model.BackupCategory
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
import java.io.FileInputStream
import java.util.zip.ZipInputStream

class BackupSelectivePackagingAndPrivacyUnitTests {

    private lateinit var tempDir: File
    private lateinit var nexaRootDir: File
    private lateinit var internalFilesDir: File
    private lateinit var cacheDir: File
    private lateinit var storageManager: NexaStorageManager

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "nexa_packager_test_${System.currentTimeMillis()}").apply {
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
    // 1. Category Classification & Mapping Tests
    // =========================================================================

    @Test
    fun testClassifyFileCategory_classifiesAllFileTypesAccurately() {
        // Images
        assertEquals(BackupCategory.IMAGES, VaultBundlePackager.classifyFileCategory("vacation.jpg"))
        assertEquals(BackupCategory.IMAGES, VaultBundlePackager.classifyFileCategory("photo.JPEG"))
        assertEquals(BackupCategory.IMAGES, VaultBundlePackager.classifyFileCategory("screenshot.png"))
        assertEquals(BackupCategory.IMAGES, VaultBundlePackager.classifyFileCategory("graphic.webp"))
        assertEquals(BackupCategory.IMAGES, VaultBundlePackager.classifyFileCategory("anim.gif"))
        assertEquals(BackupCategory.IMAGES, VaultBundlePackager.classifyFileCategory("bitmap.bmp"))
        assertEquals(BackupCategory.IMAGES, VaultBundlePackager.classifyFileCategory("apple_photo.heic"))
        assertEquals(BackupCategory.IMAGES, VaultBundlePackager.classifyFileCategory("apple_photo.heif"))

        // Videos
        assertEquals(BackupCategory.VIDEOS, VaultBundlePackager.classifyFileCategory("video.mp4"))
        assertEquals(BackupCategory.VIDEOS, VaultBundlePackager.classifyFileCategory("movie.MKV"))
        assertEquals(BackupCategory.VIDEOS, VaultBundlePackager.classifyFileCategory("clip.webm"))
        assertEquals(BackupCategory.VIDEOS, VaultBundlePackager.classifyFileCategory("old.avi"))
        assertEquals(BackupCategory.VIDEOS, VaultBundlePackager.classifyFileCategory("recording.mov"))
        assertEquals(BackupCategory.VIDEOS, VaultBundlePackager.classifyFileCategory("mobile.3gp"))
        assertEquals(BackupCategory.VIDEOS, VaultBundlePackager.classifyFileCategory("stream.flv"))

        // Audio
        assertEquals(BackupCategory.AUDIO, VaultBundlePackager.classifyFileCategory("voicenote.opus"))
        assertEquals(BackupCategory.AUDIO, VaultBundlePackager.classifyFileCategory("track.mp3"))
        assertEquals(BackupCategory.AUDIO, VaultBundlePackager.classifyFileCategory("song.m4a"))
        assertEquals(BackupCategory.AUDIO, VaultBundlePackager.classifyFileCategory("audio.aac"))
        assertEquals(BackupCategory.AUDIO, VaultBundlePackager.classifyFileCategory("recording.wav"))
        assertEquals(BackupCategory.AUDIO, VaultBundlePackager.classifyFileCategory("stream.ogg"))
        assertEquals(BackupCategory.AUDIO, VaultBundlePackager.classifyFileCategory("lossless.flac"))
        assertEquals(BackupCategory.AUDIO, VaultBundlePackager.classifyFileCategory("call.amr"))

        // Documents
        assertEquals(BackupCategory.DOCUMENTS, VaultBundlePackager.classifyFileCategory("report.pdf"))
        assertEquals(BackupCategory.DOCUMENTS, VaultBundlePackager.classifyFileCategory("word.doc"))
        assertEquals(BackupCategory.DOCUMENTS, VaultBundlePackager.classifyFileCategory("word.docx"))
        assertEquals(BackupCategory.DOCUMENTS, VaultBundlePackager.classifyFileCategory("sheet.xls"))
        assertEquals(BackupCategory.DOCUMENTS, VaultBundlePackager.classifyFileCategory("sheet.xlsx"))
        assertEquals(BackupCategory.DOCUMENTS, VaultBundlePackager.classifyFileCategory("slides.ppt"))
        assertEquals(BackupCategory.DOCUMENTS, VaultBundlePackager.classifyFileCategory("slides.pptx"))
        assertEquals(BackupCategory.DOCUMENTS, VaultBundlePackager.classifyFileCategory("notes.txt"))
        assertEquals(BackupCategory.DOCUMENTS, VaultBundlePackager.classifyFileCategory("data.csv"))
        assertEquals(BackupCategory.DOCUMENTS, VaultBundlePackager.classifyFileCategory("rich.rtf"))
        assertEquals(BackupCategory.DOCUMENTS, VaultBundlePackager.classifyFileCategory("open.odt"))

        // Other Files
        assertEquals(BackupCategory.OTHER_FILES, VaultBundlePackager.classifyFileCategory("archive.zip"))
        assertEquals(BackupCategory.OTHER_FILES, VaultBundlePackager.classifyFileCategory("archive.tar.gz"))
        assertEquals(BackupCategory.OTHER_FILES, VaultBundlePackager.classifyFileCategory("binary.apk"))
        assertEquals(BackupCategory.OTHER_FILES, VaultBundlePackager.classifyFileCategory("unknown.xyz"))
    }

    @Test
    fun testMatchesMediaCategory_respectsGranularCategoryFlags() {
        val imagesOnly = BackupCategoryConfig(
            includeImages = true,
            includeVideos = false,
            includeAudio = false,
            includeDocuments = false,
            includeOtherFiles = false
        )
        assertTrue(VaultBundlePackager.matchesMediaCategory("p.jpg", imagesOnly))
        assertFalse(VaultBundlePackager.matchesMediaCategory("v.mp4", imagesOnly))
        assertFalse(VaultBundlePackager.matchesMediaCategory("a.mp3", imagesOnly))
        assertFalse(VaultBundlePackager.matchesMediaCategory("d.pdf", imagesOnly))
        assertFalse(VaultBundlePackager.matchesMediaCategory("o.zip", imagesOnly))

        val audioAndDocs = BackupCategoryConfig(
            includeImages = false,
            includeVideos = false,
            includeAudio = true,
            includeDocuments = true,
            includeOtherFiles = false
        )
        assertFalse(VaultBundlePackager.matchesMediaCategory("p.png", audioAndDocs))
        assertFalse(VaultBundlePackager.matchesMediaCategory("v.mkv", audioAndDocs))
        assertTrue(VaultBundlePackager.matchesMediaCategory("a.opus", audioAndDocs))
        assertTrue(VaultBundlePackager.matchesMediaCategory("d.docx", audioAndDocs))
        assertFalse(VaultBundlePackager.matchesMediaCategory("o.7z", audioAndDocs))

        val noAttachments = BackupCategoryConfig.allDisabled()
        assertFalse(VaultBundlePackager.matchesMediaCategory("p.jpg", noAttachments))
        assertFalse(VaultBundlePackager.matchesMediaCategory("v.mp4", noAttachments))
    }

    // =========================================================================
    // 2. Media Collection & External Storage Scraping Tests
    // =========================================================================

    @Test
    fun testCollectMediaFiles_filtersByCategoriesAndCopiesFromExternalAndInternal() {
        // Setup external files
        val imgDir = storageManager.getDirectory(NexaStorageCategory.IMAGES)
        val vidDir = storageManager.getDirectory(NexaStorageCategory.VIDEOS)
        val docDir = storageManager.getDirectory(NexaStorageCategory.DOCUMENTS)

        File(imgDir, "ext_photo.jpg").writeText("JPEG_DATA_123456")
        File(vidDir, "ext_movie.mp4").writeText("MP4_DATA_123456789")
        File(docDir, "ext_contract.pdf").writeText("PDF_DATA_123")

        // Setup internal media vault file
        val internalVault = File(internalFilesDir, "media_vault").apply { mkdirs() }
        File(internalVault, "internal_voice.opus").writeText("OPUS_AUDIO_123")

        val targetStagingMedia = File(tempDir, "staging_media").apply { mkdirs() }

        // Test 1: Only Images enabled
        val configImages = BackupCategoryConfig(
            includeImages = true,
            includeVideos = false,
            includeAudio = false,
            includeDocuments = false,
            includeOtherFiles = false
        )

        val fakeContext = FakeContext(internalFilesDir)
        val statsImages = VaultBundlePackager.collectMediaFiles(
            context = fakeContext,
            targetMediaDir = targetStagingMedia,
            categoryConfig = configImages,
            customStorageManager = storageManager
        )

        assertEquals(1, statsImages.filesArchived)
        assertTrue(File(targetStagingMedia, "ext_photo.jpg").exists())
        assertFalse(File(targetStagingMedia, "ext_movie.mp4").exists())
        assertFalse(File(targetStagingMedia, "ext_contract.pdf").exists())
        assertFalse(File(targetStagingMedia, "internal_voice.opus").exists())

        // Test 2: Audio + Documents enabled
        targetStagingMedia.deleteRecursively()
        targetStagingMedia.mkdirs()

        val configAudioDocs = BackupCategoryConfig(
            includeImages = false,
            includeVideos = false,
            includeAudio = true,
            includeDocuments = true,
            includeOtherFiles = false
        )

        val statsAudioDocs = VaultBundlePackager.collectMediaFiles(
            context = fakeContext,
            targetMediaDir = targetStagingMedia,
            categoryConfig = configAudioDocs,
            customStorageManager = storageManager
        )

        assertEquals(2, statsAudioDocs.filesArchived)
        assertFalse(File(targetStagingMedia, "ext_photo.jpg").exists())
        assertFalse(File(targetStagingMedia, "ext_movie.mp4").exists())
        assertTrue(File(targetStagingMedia, "ext_contract.pdf").exists())
        assertTrue(File(targetStagingMedia, "internal_voice.opus").exists())
    }

    @Test
    fun testCollectMediaFiles_deduplicationAndPathTraversalDefense() {
        val imgDir = storageManager.getDirectory(NexaStorageCategory.IMAGES)
        val internalVault = File(internalFilesDir, "media_vault").apply { mkdirs() }

        // Same file in both external and internal storage
        File(imgDir, "duplicate_pic.jpg").writeText("IDENTICAL_PHOTO_PAYLOAD")
        File(internalVault, "duplicate_pic.jpg").writeText("IDENTICAL_PHOTO_PAYLOAD")

        // Malicious file name traversal candidate
        File(imgDir, ".._sneaky.png").writeText("SNEAKY_PNG")

        // Empty file
        File(imgDir, "empty.jpg").writeText("")

        // .nomedia file
        File(imgDir, ".nomedia").writeText("")

        val targetMedia = File(tempDir, "target_dedup").apply { mkdirs() }
        val fakeContext = FakeContext(internalFilesDir)

        val stats = VaultBundlePackager.collectMediaFiles(
            context = fakeContext,
            targetMediaDir = targetMedia,
            categoryConfig = BackupCategoryConfig.allEnabled(),
            customStorageManager = storageManager
        )

        // Only duplicate_pic.jpg (once) and cleaned sneaky.png should be archived (total 2 files)
        assertEquals(2, stats.filesArchived)
        assertTrue(File(targetMedia, "duplicate_pic.jpg").exists())
        assertFalse(File(targetMedia, "empty.jpg").exists())
        assertFalse(File(targetMedia, ".nomedia").exists())
    }

    // =========================================================================
    // 3. Category Size Calculation Tests
    // =========================================================================

    @Test
    fun testCalculateCategorySizes_accuratelySumsRealFileBytes() {
        val imgDir = storageManager.getDirectory(NexaStorageCategory.IMAGES)
        val vidDir = storageManager.getDirectory(NexaStorageCategory.VIDEOS)
        val docDir = storageManager.getDirectory(NexaStorageCategory.DOCUMENTS)

        val imgBytes = ByteArray(10_000) { 1 }
        val vidBytes = ByteArray(50_000) { 2 }
        val docBytes = ByteArray(5_000) { 3 }

        File(imgDir, "photo1.jpg").writeBytes(imgBytes)
        File(vidDir, "video1.mp4").writeBytes(vidBytes)
        File(docDir, "doc1.pdf").writeBytes(docBytes)

        val fakeContext = FakeContext(internalFilesDir)

        // 1. Calculate All Enabled
        val allSizes = VaultBundlePackager.calculateCategorySizes(
            context = fakeContext,
            categoryConfig = BackupCategoryConfig.allEnabled(),
            customStorageManager = storageManager
        )
        assertEquals(10_000L, allSizes.imagesBytes)
        assertEquals(50_000L, allSizes.videosBytes)
        assertEquals(5_000L, allSizes.documentsBytes)
        assertEquals(0L, allSizes.audioBytes)
        assertTrue(allSizes.totalBytes >= 65_000L)

        // 2. Calculate Images Only
        val imagesOnlySizes = VaultBundlePackager.calculateCategorySizes(
            context = fakeContext,
            categoryConfig = BackupCategoryConfig(
                includeChats = false,
                includeImages = true,
                includeVideos = false,
                includeAudio = false,
                includeDocuments = false,
                includeOtherFiles = false,
                includeSettings = false
            ),
            customStorageManager = storageManager
        )
        assertEquals(10_000L, imagesOnlySizes.imagesBytes)
        assertEquals(0L, imagesOnlySizes.videosBytes)
        assertEquals(0L, imagesOnlySizes.documentsBytes)
        assertEquals(10_000L, imagesOnlySizes.totalBytes)
    }

    // =========================================================================
    // 4. Critical Privacy Issue — Database Sanitizer Verification
    // =========================================================================

    @Test
    fun testSanitizeDatabaseSnapshot_chatsDisabled_executesCompletePurge() {
        val fakeDbHelper = RecordingDbHelper(messageCount = 50, chatCount = 10)
        val fakeDb = fakeDbHelper.createProxy()
        val dummySnapshot = File(tempDir, "dummy_snapshot.db").apply { writeText("SQLITE_HEADER") }

        val configChatsDisabled = BackupCategoryConfig(
            includeChats = false,
            includeSettings = true
        )

        val result = VaultBundlePackager.sanitizeDatabaseSnapshot(
            rawDatabase = fakeDb,
            snapshotDb = dummySnapshot,
            categoryConfig = configChatsDisabled
        )

        // 1. Result counts must be strictly 0
        assertEquals(0, result.remainingMessages)
        assertEquals(0, result.remainingConversations)

        // 2. Verify ATTACH and secure_delete
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("ATTACH DATABASE") && it.contains("backup_staging") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("PRAGMA backup_staging.secure_delete = ON") })

        // 3. Verify unconditional security table purges
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.ratchet_sessions") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.sms_sessions") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.sms_key_material") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.dtn_envelopes") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.mesh_outbox") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.device_sync_log") })

        // 4. Verify chat table purges when Chats = OFF
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.messages") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.chats") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.chat_members") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.chat_drafts") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.message_reactions") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.message_attachments") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.scheduled_messages") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.contacts") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.file_envelopes") })

        // 5. Verify integrity check and DETACH
        assertTrue(fakeDbHelper.queriedSqls.any { it.contains("PRAGMA backup_staging.integrity_check") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DETACH DATABASE backup_staging") })
    }

    @Test
    fun testSanitizeDatabaseSnapshot_chatsEnabled_preservesChatTablesAndPurgesSecurityOnly() {
        val fakeDbHelper = RecordingDbHelper(messageCount = 250, chatCount = 15)
        val fakeDb = fakeDbHelper.createProxy()
        val dummySnapshot = File(tempDir, "dummy_snapshot2.db").apply { writeText("SQLITE_HEADER") }

        val configChatsEnabled = BackupCategoryConfig(
            includeChats = true,
            includeSettings = true
        )

        val result = VaultBundlePackager.sanitizeDatabaseSnapshot(
            rawDatabase = fakeDb,
            snapshotDb = dummySnapshot,
            categoryConfig = configChatsEnabled
        )

        // 1. Result reflects cursor count
        assertEquals(250, result.remainingMessages)
        assertEquals(15, result.remainingConversations)

        // 2. Unconditional security tables are purged
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.ratchet_sessions") })
        assertTrue(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.sms_sessions") })

        // 3. Chat tables are NOT deleted
        assertFalse(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.messages") })
        assertFalse(fakeDbHelper.executedSqls.any { it.contains("DELETE FROM backup_staging.chats") })
    }

    // =========================================================================
    // 5. Pack Bundle Staging, Manifest & Preferences Sanitization
    // =========================================================================

    @Test
    fun testPackBundle_manifestZeroLeakageWhenChatsDisabled() {
        val fakeDbHelper = RecordingDbHelper(messageCount = 100, chatCount = 5)
        val fakeDb = fakeDbHelper.createProxy()
        val staging = File(tempDir, "staging_pack").apply { mkdirs() }
        val outputArchive = File(tempDir, "output_bundle.zip")

        val configNoChats = BackupCategoryConfig(
            includeChats = false,
            includeImages = false,
            includeSettings = false
        )

        val fakeContext = FakeContext(internalFilesDir)

        val manifest = VaultBundlePackager.packBundle(
            context = fakeContext,
            rawDatabase = fakeDb,
            outputArchiveFile = outputArchive,
            stagingDir = staging,
            categoryConfig = configNoChats,
            customStorageManager = storageManager
        )

        // Total messages and conversations reported in manifest must be strictly 0
        assertEquals(0, manifest.totalMessages)
        assertEquals(0, manifest.totalConversations)
        assertEquals(2, manifest.formatVersion)

        // Inspect zip entries
        val entryNames = mutableListOf<String>()
        ZipInputStream(FileInputStream(outputArchive)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryNames.add(entry.name)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        assertTrue(entryNames.contains("manifest.json"))
        assertTrue(entryNames.contains("databases/nexa_master_snapshot.db"))
        // preferences/ must NOT be packed when includeSettings is false
        assertFalse(entryNames.any { it.startsWith("preferences/") })
        // media_vault/ must NOT be packed when attachments are disabled
        assertFalse(entryNames.any { it.startsWith("media_vault/") })
    }

    @Test
    fun testPackBundle_settingsSanitizationStripsAuthTokensAndSecrets() {
        val fakeDbHelper = RecordingDbHelper(messageCount = 10, chatCount = 2)
        val fakeDb = fakeDbHelper.createProxy()
        val staging = File(tempDir, "staging_prefs").apply { mkdirs() }
        val outputArchive = File(tempDir, "prefs_bundle.zip")

        val settingsPrefs = mapOf(
            "appearance_mode" to "DARK",
            "reduce_animations" to true,
            "message_font_size" to "MEDIUM",
            "auth_token" to "SECRET_TOKEN_DO_NOT_EXPORT",
            "google_drive_token" to "DRIVE_TOKEN_12345",
            "app_lock_pin_hash" to "PIN_HASH_9999",
            "session_credentials" to "SESSION_CREDENTIALS_SECRET"
        )

        val fakeContext = FakeContext(internalFilesDir, initialSettings = settingsPrefs)

        val configWithSettings = BackupCategoryConfig(
            includeChats = true,
            includeSettings = true,
            includeAttachments = false
        )

        VaultBundlePackager.packBundle(
            context = fakeContext,
            rawDatabase = fakeDb,
            outputArchiveFile = outputArchive,
            stagingDir = staging,
            categoryConfig = configWithSettings,
            customStorageManager = storageManager
        )

        // Inspect app_user_settings.json extracted from the bundle
        var settingsContent: String? = null
        ZipInputStream(FileInputStream(outputArchive)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "preferences/app_user_settings.json") {
                    settingsContent = zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        assertNotNull("preferences/app_user_settings.json must exist in archive", settingsContent)
        val json = JSONObject(settingsContent!!)

        // Non-sensitive preferences must be present
        assertEquals("DARK", json.getString("appearance_mode"))
        assertTrue(json.getBoolean("reduce_animations"))
        assertEquals("MEDIUM", json.getString("message_font_size"))

        // Sensitive credentials, tokens, pins, and auth keys must be strictly stripped
        assertFalse("auth_token must be stripped", json.has("auth_token"))
        assertFalse("google_drive_token must be stripped", json.has("google_drive_token"))
        assertFalse("app_lock_pin_hash must be stripped", json.has("app_lock_pin_hash"))
        assertFalse("session_credentials must be stripped", json.has("session_credentials"))
    }
}

// =========================================================================
// Test Support Helpers & Recording SQLite Database Proxy
// =========================================================================

private class RecordingDbHelper(
    private val messageCount: Int = 0,
    private val chatCount: Int = 0
) {
    val executedSqls = mutableListOf<String>()
    val queriedSqls = mutableListOf<String>()

    fun createProxy(): SupportSQLiteDatabase {
        val loader = SupportSQLiteDatabase::class.java.classLoader
        return java.lang.reflect.Proxy.newProxyInstance(
            loader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    val sql = args?.get(0) as? String ?: ""
                    executedSqls.add(sql)
                    if (sql.contains("VACUUM INTO")) {
                        val path = sql.substringAfter("VACUUM INTO '").substringBefore("'")
                        File(path).writeText("SQLITE_SNAPSHOT_DATA_MOCK")
                    }
                    null
                }
                "query" -> {
                    val sql = when (val arg0 = args?.get(0)) {
                        is String -> arg0
                        is SupportSQLiteQuery -> arg0.sql
                        else -> arg0?.toString() ?: ""
                    }
                    queriedSqls.add(sql)
                    val upper = sql.uppercase()
                    val count = when {
                        upper.contains("MESSAGES") -> messageCount
                        upper.contains("CHATS") -> chatCount
                        else -> 1
                    }
                    createCursorProxy(count, isIntegrityCheck = upper.contains("INTEGRITY_CHECK"))
                }
                "isOpen" -> true
                "isReadOnly" -> false
                "getVersion" -> 29
                "getPageSize" -> 4096L
                "isDatabaseIntegrityOk" -> true
                "inTransaction" -> false
                else -> {
                    val returnType = method.returnType
                    when {
                        returnType == Boolean::class.javaPrimitiveType -> false
                        returnType == Int::class.javaPrimitiveType -> 0
                        returnType == Long::class.javaPrimitiveType -> 0L
                        returnType == Float::class.javaPrimitiveType -> 0f
                        returnType == Double::class.javaPrimitiveType -> 0.0
                        returnType == Void.TYPE -> null
                        else -> null
                    }
                }
            }
        } as SupportSQLiteDatabase
    }

    private fun createCursorProxy(intResult: Int, isIntegrityCheck: Boolean): Cursor {
        var isFirst = true
        var isClosed = false
        val loader = Cursor::class.java.classLoader
        return java.lang.reflect.Proxy.newProxyInstance(
            loader,
            arrayOf(Cursor::class.java)
        ) { _, method, args ->
            when (method.name) {
                "moveToFirst" -> true
                "moveToNext" -> {
                    if (isFirst) {
                        isFirst = false
                        true
                    } else {
                        false
                    }
                }
                "getInt" -> intResult
                "getLong" -> intResult.toLong()
                "getString" -> if (isIntegrityCheck) "ok" else intResult.toString()
                "getCount" -> 1
                "getColumnCount" -> 1
                "getColumnIndex" -> 0
                "close" -> {
                    isClosed = true
                    null
                }
                "isClosed" -> isClosed
                else -> {
                    val returnType = method.returnType
                    when {
                        returnType == Boolean::class.javaPrimitiveType -> false
                        returnType == Int::class.javaPrimitiveType -> 0
                        returnType == Long::class.javaPrimitiveType -> 0L
                        returnType == Float::class.javaPrimitiveType -> 0f
                        returnType == Double::class.javaPrimitiveType -> 0.0
                        returnType == Void.TYPE -> null
                        else -> null
                    }
                }
            }
        } as Cursor
    }
}

private class FakeContext(
    private val filesDirFile: File,
    private val initialSettings: Map<String, Any> = emptyMap()
) : android.content.ContextWrapper(null) {

    private val sharedPrefsMap = mutableMapOf<String, SharedPreferences>()

    override fun getFilesDir(): File = filesDirFile
    override fun getCacheDir(): File = File(filesDirFile, "cache").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(filesDirFile, name)

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        return sharedPrefsMap.getOrPut(name) {
            FakeSharedPreferences(if (name == "nexa_settings") initialSettings.toMutableMap() else mutableMapOf())
        }
    }
}

private class FakeSharedPreferences(
    private val data: MutableMap<String, Any> = mutableMapOf()
) : SharedPreferences {

    override fun getAll(): MutableMap<String, *> = data
    override fun getString(key: String?, defValue: String?): String? = data[key]?.toString() ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(data)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private class FakeEditor(private val data: MutableMap<String, Any>) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null && value != null) data[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) data[key] = value
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) data[key] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) data[key] = value
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) data[key] = value
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) data.remove(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            data.clear()
            return this
        }
        override fun commit(): Boolean = true
        override fun apply() {}
    }
}
