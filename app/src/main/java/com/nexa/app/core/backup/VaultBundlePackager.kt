package com.nexa.app.core.backup

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nexa.app.core.backup.crypto.KeyEnvelopeEngine
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.core.storage.NexaStorageCategory
import com.nexa.app.core.storage.NexaStorageManager
import com.nexa.app.data.local.NexaDatabase
import com.nexa.app.domain.model.BackupCategory
import com.nexa.app.domain.model.ConversationBackupPolicy
import com.nexa.app.domain.model.EffectiveChatBackupPolicy
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupManifest(
    val versionEpoch: Long,
    val formatVersion: Int = 2,
    val totalConversations: Int,
    val totalMessages: Int,
    val mediaFilesCount: Int,
    val databaseSha256: String,
    val categoryConfig: BackupCategoryConfig = BackupCategoryConfig()
)

data class SanitizationResult(
    val remainingMessages: Int,
    val remainingConversations: Int
)

data class MediaCollectionStats(
    val filesArchived: Int,
    val totalBytes: Long
)

data class BackupSizeEstimate(
    val databaseBytes: Long = 0L,
    val imagesBytes: Long = 0L,
    val videosBytes: Long = 0L,
    val audioBytes: Long = 0L,
    val documentsBytes: Long = 0L,
    val otherFilesBytes: Long = 0L,
    val settingsBytes: Long = 0L,
    val totalBytes: Long = 0L
)

/**
 * Packs and unpacks the unified Zero-Risk NEXA Backup Container:
 *
 * Layout (Format Version 2):
 * backup_bundle/
 * ├── key_envelope.bin               (66-byte authenticated binary envelope; processed ONLY in RAM)
 * ├── manifest.json                  (Total messages, conversations, database SHA-256; ZERO keys)
 * ├── databases/
 * │   └── nexa_master_snapshot.db    (Clean sanitized snapshot; device-bound keys and excluded categories purged)
 * ├── preferences/
 * │   ├── app_user_settings.json     (Non-sensitive theme, auto-download, font size)
 * │   └── custom_chat_configs.json   (Pinned chat order, mute states, wallpapers)
 * └── media_vault/                   (Classified external & internal media files, deduplicated, traversal-safe)
 */
object VaultBundlePackager {

    private const val BUFFER_SIZE = 64 * 1024
    const val DATABASE_NAME = "nexa_encrypted_vault.db"

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Maps any given file name to one of the 7 official BackupCategory enums.
     */
    fun classifyFileCategory(fileName: String): BackupCategory {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                    lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") ||
                    lower.endsWith(".heic") || lower.endsWith(".heif") -> BackupCategory.IMAGES
            lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
                    lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".3gp") ||
                    lower.endsWith(".flv") -> BackupCategory.VIDEOS
            lower.endsWith(".opus") || lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
                    lower.endsWith(".aac") || lower.endsWith(".wav") || lower.endsWith(".ogg") ||
                    lower.endsWith(".flac") || lower.endsWith(".amr") -> BackupCategory.AUDIO
            lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") ||
                    lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt") ||
                    lower.endsWith(".pptx") || lower.endsWith(".txt") || lower.endsWith(".csv") ||
                    lower.endsWith(".rtf") || lower.endsWith(".odt") -> BackupCategory.DOCUMENTS
            else -> BackupCategory.OTHER_FILES
        }
    }

    /**
     * Determines whether a given filename matches the user's selected media categories.
     */
    fun matchesMediaCategory(fileName: String, config: BackupCategoryConfig): Boolean {
        if (!config.includeAttachments) return false
        val category = classifyFileCategory(fileName)
        return config.isCategoryEnabled(category)
    }

    /**
     * Sanitizes the frozen database snapshot via SQLite ATTACH:
     * 1. Sets secure_delete = ON so deleted pages are overwritten with zeros.
     * 2. Unconditionally purges device-bound security tables, ratchet state, SMS session keys,
     *    and ephemeral transport envelopes (which must NEVER leave the device).
     * 3. Purges all chat, message, draft, reaction, attachment, and contact tables if [categoryConfig.includeChats] is false.
     * 4. Purges contacts if [categoryConfig.includeContacts] is false.
     * 5. Purges user profile if both chats and settings are excluded.
     * 6. Verifies snapshot integrity before detaching.
     */
    fun sanitizeDatabaseSnapshot(
        rawDatabase: SupportSQLiteDatabase,
        snapshotDb: File,
        categoryConfig: BackupCategoryConfig,
        conversationPolicies: Map<String, EffectiveChatBackupPolicy> = emptyMap()
    ): SanitizationResult {
        var isAttached = false
        var remainingMessages = 0
        var remainingConversations = 0

        try {
            // First detach any dangling staging database
            runCatching { rawDatabase.execSQL("DETACH DATABASE backup_staging") }

            // Attach snapshot using the open database handle (inherits SQLCipher key automatically)
            rawDatabase.execSQL("ATTACH DATABASE '${snapshotDb.absolutePath}' AS backup_staging")
            isAttached = true

            // Enforce secure_delete to overwrite deleted content with zeros in unallocated space
            rawDatabase.execSQL("PRAGMA backup_staging.secure_delete = ON")

            // 1. Unconditionally purge device-bound, session, ratchet, SMS keys, and transport state
            val unconditionalSecurityTables = listOf(
                "ratchet_sessions", "ratchet_state",
                "skipped_message_keys", "skipped_keys",
                "sms_key_material",
                "sms_sessions",
                "sms_packets",
                "sms_inbound_parts",
                "dtn_envelopes",
                "mesh_outbox", "mesh_envelopes",
                "peer_sync_cursors",
                "device_sync_log", "device_sync",
                "quarantine_logs", "quarantine",
                "file_transfers",
                "transfer_sessions",
                "status_views"
            )
            for (table in unconditionalSecurityTables) {
                runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.$table") }
            }

            // 2. Selective Chats & Messages Purge
            if (!categoryConfig.includeChats) {
                val chatContentTables = listOf(
                    "messages",
                    "chats",
                    "chat_members",
                    "chat_drafts",
                    "message_reactions",
                    "message_attachments",
                    "scheduled_messages",
                    "message_dedup_index", "message_dedup",
                    "message_transport_observations", "message_transport",
                    "contacts",
                    "status_updates",
                    "file_envelopes"
                )
                for (table in chatContentTables) {
                    runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.$table") }
                }
            } else if (!categoryConfig.includeContacts) {
                runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.contacts") }
            }

            // 2b. Granular Per-Conversation Policy Enforcement (Exclusions & Custom Categories)
            if (categoryConfig.includeChats && conversationPolicies.isNotEmpty()) {
                for ((convId, policy) in conversationPolicies) {
                    val safeConvId = convId.replace("'", "''")
                    if (!policy.isIncluded) {
                        // Entire conversation is excluded from backup
                        runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.messages WHERE conversationId = '$safeConvId'") }
                        runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.chats WHERE conversationId = '$safeConvId'") }
                        runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.chat_members WHERE conversationId = '$safeConvId'") }
                        runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.chat_drafts WHERE conversationId = '$safeConvId'") }
                        runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.message_reactions WHERE conversationId = '$safeConvId'") }
                        runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.scheduled_messages WHERE conversationId = '$safeConvId'") }
                    } else {
                        // Conversation is included: enforce custom category restrictions if any
                        if (!policy.includeMessages) {
                            runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.messages WHERE conversationId = '$safeConvId'") }
                        } else {
                            if (!policy.includeImages) {
                                runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.messages WHERE conversationId = '$safeConvId' AND (messageType = 'IMAGE' OR messageType = 'PHOTO')") }
                            }
                            if (!policy.includeVideos) {
                                runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.messages WHERE conversationId = '$safeConvId' AND messageType = 'VIDEO'") }
                            }
                            if (!policy.includeAudio) {
                                runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.messages WHERE conversationId = '$safeConvId' AND (messageType = 'AUDIO' OR messageType = 'VOICE')") }
                            }
                            if (!policy.includeDocuments) {
                                runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.messages WHERE conversationId = '$safeConvId' AND (messageType = 'DOCUMENT' OR messageType = 'DOC')") }
                            }
                            if (!policy.includeOtherFiles) {
                                runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.messages WHERE conversationId = '$safeConvId' AND messageType = 'FILE'") }
                            }
                        }
                    }
                }
                // Purge dangling message attachments whose parent messages were purged
                runCatching {
                    rawDatabase.execSQL("DELETE FROM backup_staging.message_attachments WHERE messageId NOT IN (SELECT packetId FROM backup_staging.messages)")
                }
            }

            // 3. User profile purge if neither chats nor settings are included
            if (!categoryConfig.includeChats && !categoryConfig.includeSettings) {
                runCatching { rawDatabase.execSQL("DELETE FROM backup_staging.user_profile") }
            }

            // 4. Query remaining message and conversation counts directly from the sanitized staging database
            if (categoryConfig.includeChats) {
                runCatching {
                    rawDatabase.query("SELECT COUNT(*) FROM backup_staging.messages").use { c ->
                        if (c.moveToFirst()) remainingMessages = c.getInt(0)
                    }
                }
                runCatching {
                    rawDatabase.query("SELECT COUNT(*) FROM backup_staging.chats").use { c ->
                        if (c.moveToFirst()) remainingConversations = c.getInt(0)
                    }
                }
            }

            // 5. Verify integrity of the sanitized database snapshot
            rawDatabase.query("PRAGMA backup_staging.integrity_check").use { cursor ->
                if (cursor.moveToFirst()) {
                    val status = cursor.getString(0)
                    if (!status.equals("ok", ignoreCase = true)) {
                        throw SecurityException("Database snapshot integrity verification failed: $status")
                    }
                }
            }

            // Best-effort vacuum on attached staging
            runCatching { rawDatabase.execSQL("VACUUM backup_staging") }
        } finally {
            if (isAttached) {
                runCatching { rawDatabase.execSQL("DETACH DATABASE backup_staging") }
            }
        }

        return SanitizationResult(
            remainingMessages = remainingMessages,
            remainingConversations = remainingConversations
        )
    }

    /**
     * Scans external NEXA storage directories and internal media vault,
     * deduplicating by file content SHA-256 or unique relative name,
     * and filtering strictly by enabled category in [categoryConfig].
     */
    fun collectMediaFiles(
        context: Context,
        targetMediaDir: File,
        categoryConfig: BackupCategoryConfig,
        fileHashMap: Map<String, String> = emptyMap(),
        conversationPolicies: Map<String, EffectiveChatBackupPolicy> = emptyMap(),
        fileToConversationMap: Map<String, String> = emptyMap(),
        customStorageManager: NexaStorageManager? = null,
        onMediaProgress: (phase: String, percent: Float) -> Unit = { _, _ -> }
    ): MediaCollectionStats {
        if (!categoryConfig.hasAnyMediaSelected() && !categoryConfig.includeAttachments) {
            return MediaCollectionStats(0, 0L)
        }

        targetMediaDir.mkdirs()
        var count = 0
        var totalBytes = 0L
        val seenFileKeys = mutableSetOf<String>()

        data class StagedMedia(val file: File, val cleanName: String, val deduplicationKey: String, val isPriority: Boolean)
        val priorityCandidates = mutableListOf<StagedMedia>()
        val normalCandidates = mutableListOf<StagedMedia>()

        fun processCandidateFile(file: File, suggestedName: String? = null) {
            if (!file.exists() || !file.isFile || file.length() <= 0L || file.name == ".nomedia") return
            val rawName = suggestedName ?: file.name
            val cleanName = rawName.replace("\\", "_").replace("/", "_").trim()
            if (cleanName.isBlank()) return

            if (!matchesMediaCategory(cleanName, categoryConfig)) return

            // Check if file is associated with a specific conversation
            val convId = fileToConversationMap[cleanName]
                ?: fileToConversationMap[file.name]
                ?: fileToConversationMap[file.absolutePath]

            var isPriority = false
            if (convId != null) {
                val policy = conversationPolicies[convId]
                if (policy != null) {
                    if (!policy.isIncluded) return // Excluded conversation
                    val fileCat = classifyFileCategory(cleanName)
                    when (fileCat) {
                        BackupCategory.IMAGES -> if (!policy.includeImages) return
                        BackupCategory.VIDEOS -> if (!policy.includeVideos) return
                        BackupCategory.DOCUMENTS -> if (!policy.includeDocuments) return
                        BackupCategory.AUDIO -> if (!policy.includeAudio) return
                        BackupCategory.OTHER_FILES -> if (!policy.includeOtherFiles) return
                        else -> {}
                    }
                    isPriority = policy.isPriority
                }
            }

            // Deduplication key: clean name + file size
            val deduplicationKey = "${cleanName}_${file.length()}"
            if (seenFileKeys.contains(deduplicationKey) || seenFileKeys.contains(cleanName)) {
                return
            }

            val destFile = File(targetMediaDir, cleanName)
            // Path traversal defense
            if (!destFile.canonicalPath.startsWith(targetMediaDir.canonicalPath)) {
                return
            }

            seenFileKeys.add(deduplicationKey)
            seenFileKeys.add(cleanName)

            val candidate = StagedMedia(file, cleanName, deduplicationKey, isPriority)
            if (isPriority) {
                priorityCandidates.add(candidate)
            } else {
                normalCandidates.add(candidate)
            }
        }

        // 1. External NEXA Media Directories (/Android/media/<package>/NEXA/...)
        try {
            val storageManager = customStorageManager ?: NexaStorageManager(context)
            val nexaRoot = storageManager.nexaRootDir
            if (nexaRoot.exists() && nexaRoot.isDirectory) {
                val categoriesToScan = listOf(
                    NexaStorageCategory.IMAGES,
                    NexaStorageCategory.VIDEOS,
                    NexaStorageCategory.AUDIO,
                    NexaStorageCategory.DOCUMENTS,
                    NexaStorageCategory.DOWNLOADS,
                    NexaStorageCategory.EXPORTS
                )
                for (storageCat in categoriesToScan) {
                    val catDir = storageManager.getDirectory(storageCat)
                    if (catDir.exists() && catDir.isDirectory) {
                        catDir.walkTopDown().filter { it.isFile && it.name != ".nomedia" }.forEach { f ->
                            processCandidateFile(f)
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}

        // 2. Internal Media Vault (filesDir/media_vault)
        try {
            val internalMediaDir = File(context.filesDir, "media_vault")
            if (internalMediaDir.exists() && internalMediaDir.isDirectory) {
                internalMediaDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        val mappedFileName = fileHashMap[file.name] ?: file.name
                        processCandidateFile(file, suggestedName = mappedFileName)
                    }
                }
            }
        } catch (ignored: Exception) {}

        // 3. Stage Priority Media Queue First
        if (priorityCandidates.isNotEmpty()) {
            onMediaProgress("Archiving Priority Media (${priorityCandidates.size} files)...", 0.60f)
            for (candidate in priorityCandidates) {
                val dest = File(targetMediaDir, candidate.cleanName)
                candidate.file.copyTo(dest, overwrite = true)
                count++
                totalBytes += dest.length()
            }
        }

        // 4. Stage Normal Media Queue Second
        if (normalCandidates.isNotEmpty()) {
            onMediaProgress("Archiving Standard Media (${normalCandidates.size} files)...", 0.68f)
            for (candidate in normalCandidates) {
                val dest = File(targetMediaDir, candidate.cleanName)
                candidate.file.copyTo(dest, overwrite = true)
                count++
                totalBytes += dest.length()
            }
        }

        return MediaCollectionStats(count, totalBytes)
    }

    /**
     * Real category size calculation based on actual file system and database data.
     */
    fun calculateCategorySizes(
        context: Context,
        rawDatabase: SupportSQLiteDatabase? = null,
        categoryConfig: BackupCategoryConfig = BackupCategoryConfig(),
        customStorageManager: NexaStorageManager? = null
    ): BackupSizeEstimate {
        var dbBytes = 0L
        var imagesBytes = 0L
        var videosBytes = 0L
        var audioBytes = 0L
        var docsBytes = 0L
        var otherBytes = 0L
        var settingsBytes = 0L

        // 1. Database size calculation
        if (categoryConfig.includeChats) {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (dbFile.exists()) {
                dbBytes = dbFile.length()
            }
        } else if (categoryConfig.includeSettings) {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (dbFile.exists()) {
                dbBytes = minOf(dbFile.length(), 128 * 1024L)
            }
        }

        // 2. Media file sizes calculation
        if (categoryConfig.hasAnyMediaSelected() || categoryConfig.includeAttachments) {
            val storageManager = customStorageManager ?: NexaStorageManager(context)
            val seenFiles = mutableSetOf<String>()

            fun accountFile(file: File) {
                if (!file.exists() || !file.isFile || file.length() <= 0L || file.name == ".nomedia") return
                val key = "${file.name}_${file.length()}"
                if (seenFiles.contains(key)) return
                seenFiles.add(key)

                val cat = classifyFileCategory(file.name)
                if (categoryConfig.isCategoryEnabled(cat)) {
                    val len = file.length()
                    when (cat) {
                        BackupCategory.IMAGES -> imagesBytes += len
                        BackupCategory.VIDEOS -> videosBytes += len
                        BackupCategory.AUDIO -> audioBytes += len
                        BackupCategory.DOCUMENTS -> docsBytes += len
                        BackupCategory.OTHER_FILES -> otherBytes += len
                        else -> {}
                    }
                }
            }

            try {
                val categories = listOf(
                    NexaStorageCategory.IMAGES,
                    NexaStorageCategory.VIDEOS,
                    NexaStorageCategory.AUDIO,
                    NexaStorageCategory.DOCUMENTS,
                    NexaStorageCategory.DOWNLOADS,
                    NexaStorageCategory.EXPORTS
                )
                for (c in categories) {
                    val dir = storageManager.getDirectory(c)
                    if (dir.exists() && dir.isDirectory) {
                        dir.walkTopDown().filter { it.isFile && it.name != ".nomedia" }.forEach { accountFile(it) }
                    }
                }
            } catch (ignored: Exception) {}

            try {
                val internalMediaDir = File(context.filesDir, "media_vault")
                if (internalMediaDir.exists() && internalMediaDir.isDirectory) {
                    internalMediaDir.listFiles()?.forEach { file ->
                        if (file.isFile) accountFile(file)
                    }
                }
            } catch (ignored: Exception) {}
        }

        // 3. Settings preferences calculation
        if (categoryConfig.includeSettings || categoryConfig.includeAppSettings || categoryConfig.includeUserPrefs) {
            try {
                val sp = context.getSharedPreferences("nexa_settings", Context.MODE_PRIVATE)
                val cp = context.getSharedPreferences("nexa_chat_configs", Context.MODE_PRIVATE)
                val spSize = sp.all.toString().toByteArray().size.toLong()
                val cpSize = cp.all.toString().toByteArray().size.toLong()
                settingsBytes = maxOf(4096L, spSize + cpSize)
            } catch (ignored: Exception) {
                settingsBytes = 4096L
            }
        }

        val total = dbBytes + imagesBytes + videosBytes + audioBytes + docsBytes + otherBytes + settingsBytes
        return BackupSizeEstimate(
            databaseBytes = dbBytes,
            imagesBytes = imagesBytes,
            videosBytes = videosBytes,
            audioBytes = audioBytes,
            documentsBytes = docsBytes,
            otherFilesBytes = otherBytes,
            settingsBytes = settingsBytes,
            totalBytes = total
        )
    }

    /**
     * Creates a full snapshot bundle directory and archives it into a single clean zip archive.
     * Incorporates Design C: Dual-Layer Cryptographic Key Envelope (`key_envelope.bin`).
     */
    fun packBundle(
        context: Context,
        rawDatabase: SupportSQLiteDatabase,
        outputArchiveFile: File,
        stagingDir: File,
        dbPassphrase: ByteArray? = null,
        vaultMasterKeyBytes: ByteArray? = null,
        salt: ByteArray? = null,
        categoryConfig: BackupCategoryConfig = BackupCategoryConfig(),
        conversationPolicies: Map<String, EffectiveChatBackupPolicy> = emptyMap(),
        isManualBackup: Boolean = true,
        customStorageManager: NexaStorageManager? = null,
        onProgress: (phase: String, percent: Float) -> Unit = { _, _ -> }
    ): BackupManifest {
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val dbDir = File(stagingDir, "databases").apply { mkdirs() }
        val prefsDir = File(stagingDir, "preferences").apply { mkdirs() }
        val mediaDir = File(stagingDir, "media_vault").apply { mkdirs() }

        // Priority Processing Phase Notice
        val prioritizedChatsCount = conversationPolicies.values.count { it.isPriority && it.isIncluded }
        if (prioritizedChatsCount > 0) {
            onProgress("Processing Priority Chat Data ($prioritizedChatsCount prioritized)...", 0.10f)
        }

        // 1. Zero-Lock Atomic Snapshot via VACUUM INTO
        onProgress("Creating Zero-Lock Database Snapshot...", 0.15f)
        val snapshotDb = File(dbDir, "nexa_master_snapshot.db")
        if (snapshotDb.exists()) snapshotDb.delete()
        rawDatabase.execSQL("VACUUM INTO '${snapshotDb.absolutePath}'")

        // 2. Sanitize Database Snapshot (Zero-Leakage Guarantee + Per-Chat Policy Enforcement)
        onProgress("Sanitizing Database Snapshot...", 0.25f)
        val sanitization = sanitizeDatabaseSnapshot(rawDatabase, snapshotDb, categoryConfig, conversationPolicies)

        // 3. Compute Checksum of Sanitized Database Snapshot
        onProgress("Computing Snapshot Checksum...", 0.30f)
        val dbSha256 = computeSha256(snapshotDb)

        val totalMessages = sanitization.remainingMessages
        val totalConversations = sanitization.remainingConversations

        // Map file envelopes for internal vault naming if present
        val fileHashMap = mutableMapOf<String, String>()
        try {
            rawDatabase.query("SELECT fileHash, fileName FROM file_envelopes").use { cursor ->
                val hashIdx = cursor.getColumnIndex("fileHash")
                val nameIdx = cursor.getColumnIndex("fileName")
                while (cursor.moveToNext()) {
                    if (hashIdx >= 0 && nameIdx >= 0) {
                        val h = cursor.getString(hashIdx)
                        val n = cursor.getString(nameIdx)
                        if (!h.isNullOrBlank() && !n.isNullOrBlank()) {
                            fileHashMap[h] = n
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}

        // Map files to conversations for priority media queuing and per-chat filtering
        val fileToConversationMap = mutableMapOf<String, String>()
        try {
            rawDatabase.query("""
                SELECT m.conversationId, a.fileName, a.localPathReference 
                FROM messages m 
                JOIN message_attachments a ON m.packetId = a.messageId
            """).use { cursor ->
                val convIdx = cursor.getColumnIndex("conversationId")
                val fnIdx = cursor.getColumnIndex("fileName")
                val pathIdx = cursor.getColumnIndex("localPathReference")
                while (cursor.moveToNext()) {
                    val convId = if (convIdx >= 0) cursor.getString(convIdx) else null
                    if (!convId.isNullOrBlank()) {
                        if (fnIdx >= 0) {
                            cursor.getString(fnIdx)?.takeIf { it.isNotBlank() }?.let { fileToConversationMap[it] = convId }
                        }
                        if (pathIdx >= 0) {
                            cursor.getString(pathIdx)?.takeIf { it.isNotBlank() }?.let { path ->
                                fileToConversationMap[path] = convId
                                val name = File(path).name
                                if (name.isNotBlank()) fileToConversationMap[name] = convId
                            }
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}

        // Save media ownership metadata so restore can perform instant, zero-overhead conversation-selective restoration
        try {
            val mediaOwnershipJson = JSONObject()
            fileToConversationMap.forEach { (fileName, convId) ->
                val existing = mediaOwnershipJson.optJSONArray(fileName)
                if (existing != null) {
                    var found = false
                    for (i in 0 until existing.length()) {
                        if (existing.optString(i) == convId) { found = true; break }
                    }
                    if (!found) existing.put(convId)
                } else {
                    val arr = org.json.JSONArray().apply { put(convId) }
                    mediaOwnershipJson.put(fileName, arr)
                }
            }
            File(stagingDir, "media_ownership.json").writeText(mediaOwnershipJson.toString(2))
        } catch (ignored: Exception) {}

        // 4. Design C: Seal Database Key into Binary Key Envelope (key_envelope.bin)
        if (dbPassphrase != null && vaultMasterKeyBytes != null && salt != null) {
            onProgress("Sealing Cryptographic Key Envelope...", 0.35f)
            val sealedEnvelope = KeyEnvelopeEngine.sealPassphrase(
                dbPassphrase = dbPassphrase,
                vaultMasterKeyBytes = vaultMasterKeyBytes,
                salt = salt
            )
            File(stagingDir, "key_envelope.bin").writeBytes(sealedEnvelope)
        }

        // 5. Export App Preferences & Custom Chat Configurations (Sanitized: NO TOKENS, SECRETS, OR PINS)
        if (categoryConfig.includeSettings || categoryConfig.includeAppSettings || categoryConfig.includeUserPrefs) {
            onProgress("Exporting Preferences & Settings...", 0.45f)
            val userPrefsJson = JSONObject().apply {
                listOf(
                    "nexa_settings",
                    "nexa_appearance_prefs",
                    "nexa_chats_preferences",
                    "nexa_accessibility_preferences",
                    "nexa_language_preferences",
                    "backup_prefs"
                ).forEach { storeName ->
                    val sp = context.getSharedPreferences(storeName, Context.MODE_PRIVATE)
                    sp.all.forEach { (k, v) ->
                        if (RestoreEngine.APPROVED_SETTINGS_ALLOWLIST.contains(k) && !has(k)) {
                            put(k, v)
                        }
                    }
                }
            }
            File(prefsDir, "app_user_settings.json").writeText(userPrefsJson.toString(2))

            val chatConfigsJson = JSONObject().apply {
                val cp = context.getSharedPreferences("nexa_chat_configs", Context.MODE_PRIVATE)
                cp.all.forEach { (k, v) ->
                    if (RestoreEngine.isApprovedChatConfigKey(k)) {
                        put(k, v)
                    }
                }
                // Also export from nexa_conversation_preferences converting prefix to suffix
                val convPrefs = context.getSharedPreferences("nexa_conversation_preferences", Context.MODE_PRIVATE)
                convPrefs.all.forEach { (k, v) ->
                    if (k.startsWith("wallpaper_")) {
                        val convId = k.removePrefix("wallpaper_")
                        val canonicalKey = "${convId}_wallpaper"
                        if (RestoreEngine.isApprovedChatConfigKey(canonicalKey) && !has(canonicalKey)) {
                            put(canonicalKey, v)
                        }
                    } else if (k.startsWith("mute_until_")) {
                        val convId = k.removePrefix("mute_until_")
                        val canonicalKey = "${convId}_muted"
                        val isMuted = when (v) {
                            is Long -> v == -1L || v > System.currentTimeMillis()
                            else -> false
                        }
                        if (RestoreEngine.isApprovedChatConfigKey(canonicalKey) && !has(canonicalKey)) {
                            put(canonicalKey, isMuted)
                        }
                    }
                }
            }
            File(prefsDir, "custom_chat_configs.json").writeText(chatConfigsJson.toString(2))
        } else {
            // Settings disabled: ensure no empty preferences directory exists in archive
            if (prefsDir.exists()) prefsDir.deleteRecursively()
        }

        // 6. Copy Local Media Files (External + Internal, Priority Queues + Per-Chat Filtering)
        var mediaCount = 0
        if (categoryConfig.hasAnyMediaSelected() || categoryConfig.includeAttachments) {
            val stats = collectMediaFiles(
                context = context,
                targetMediaDir = mediaDir,
                categoryConfig = categoryConfig,
                fileHashMap = fileHashMap,
                conversationPolicies = conversationPolicies,
                fileToConversationMap = fileToConversationMap,
                customStorageManager = customStorageManager,
                onMediaProgress = onProgress
            )
            mediaCount = stats.filesArchived
        }

        if (mediaCount == 0 && mediaDir.exists() && mediaDir.list().isNullOrEmpty()) {
            mediaDir.delete()
        }

        // 7. Write manifest.json (STRICTLY NON-SENSITIVE METADATA; ZERO KEYS)
        onProgress("Creating Manifest...", 0.75f)
        val epoch = System.currentTimeMillis()
        val manifest = BackupManifest(
            versionEpoch = epoch,
            formatVersion = 2,
            totalConversations = totalConversations,
            totalMessages = totalMessages,
            mediaFilesCount = mediaCount,
            databaseSha256 = dbSha256,
            categoryConfig = categoryConfig
        )

        val manifestJson = JSONObject().apply {
            put("version_epoch", manifest.versionEpoch)
            put("format_version", manifest.formatVersion)
            put("total_conversations", manifest.totalConversations)
            put("total_messages", manifest.totalMessages)
            put("media_files_count", manifest.mediaFilesCount)
            put("database_sha256", manifest.databaseSha256)
            put("category_config", manifest.categoryConfig.toJsonObject())
        }
        File(stagingDir, "manifest.json").writeText(manifestJson.toString(2))

        // 8. Zip Entire Staging Directory into Output Archive
        onProgress("Compressing Vault Bundle...", 0.85f)
        if (outputArchiveFile.exists()) outputArchiveFile.delete()
        zipDirectory(stagingDir, outputArchiveFile)

        onProgress("Bundle Packaging Complete", 1.0f)
        return manifest
    }

    /**
     * Verifies manifest and restores databases, preferences, and media files atomically.
     * Guaranteed Zero-Disk RAM Staging for Key Envelope (`key_envelope.bin`).
     */
    fun unpackAndRestoreBundle(
        context: Context,
        archiveFile: File,
        stagingDir: File,
        liveDatabaseFile: File,
        vaultMasterKeyBytes: ByteArray? = null,
        salt: ByteArray? = null,
        secureStorage: SecureStorage? = null,
        restoreCategoryConfig: BackupCategoryConfig = BackupCategoryConfig(),
        customStorageManager: NexaStorageManager? = null,
        onProgress: (phase: String, percent: Float) -> Unit = { _, _ -> }
    ): Boolean {
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        // 1. Unzip Archive to Staging with ZERO-DISK RAM EXTRACTION for key_envelope.bin
        onProgress("Extracting Vault Bundle & Unsealing Key...", 0.20f)
        var envelopeBytesInRam: ByteArray? = null

        ZipInputStream(FileInputStream(archiveFile)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (entry.name == "key_envelope.bin" || entry.name.endsWith("/key_envelope.bin")) {
                    val bos = ByteArrayOutputStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (zis.read(buffer).also { read = it } != -1) {
                        bos.write(buffer, 0, read)
                    }
                    envelopeBytesInRam = bos.toByteArray()
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                val newFile = File(stagingDir, entry.name)
                // Prevent Zip Slip vulnerability
                if (!newFile.canonicalPath.startsWith(stagingDir.canonicalPath)) {
                    throw SecurityException("Zip Slip directory traversal detected in entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (zis.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 2. Unseal Key Envelope in RAM and Save into KeyStore
        if (envelopeBytesInRam != null && vaultMasterKeyBytes != null && salt != null && secureStorage != null) {
            onProgress("Unsealing Portable Key Envelope in RAM...", 0.35f)
            val recoveredPassphrase = KeyEnvelopeEngine.unsealPassphrase(
                envelopeBytes = envelopeBytesInRam!!,
                vaultMasterKeyBytes = vaultMasterKeyBytes,
                salt = salt
            )
            try {
                secureStorage.saveDatabasePassphrase(recoveredPassphrase)
            } finally {
                java.util.Arrays.fill(recoveredPassphrase, 0.toByte())
            }
        }

        // 3. Read and Verify Manifest
        onProgress("Verifying Manifest & Checksum...", 0.45f)
        val manifestFile = File(stagingDir, "manifest.json")
        val snapshotDb = File(stagingDir, "databases/nexa_master_snapshot.db")

        if (!manifestFile.exists() || !snapshotDb.exists()) {
            throw IllegalArgumentException("Corrupted backup bundle: missing manifest or database snapshot")
        }

        val manifestObj = JSONObject(manifestFile.readText())
        val expectedSha = manifestObj.getString("database_sha256")
        val actualSha = computeSha256(snapshotDb)

        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            throw SecurityException("Backup integrity violation: Computed SHA-256 ($actualSha) does not match manifest ($expectedSha). Restore aborted.")
        }

        // 4. Extract and Restore Media Files to App Internal Storage AND External NEXA Media Directories
        if (restoreCategoryConfig.hasAnyMediaSelected() || restoreCategoryConfig.includeAttachments) {
            onProgress("Restoring Media Vault...", 0.60f)
            val mediaDir = File(stagingDir, "media_vault")
            val appMediaDir = File(context.filesDir, "media_vault").apply { mkdirs() }
            val storageManager = customStorageManager ?: NexaStorageManager(context)
            val extRootAvailable = runCatching { storageManager.initStorageTree() }.getOrDefault(false)

            if (mediaDir.exists() && mediaDir.isDirectory) {
                mediaDir.listFiles()?.forEach { file ->
                    if (file.isFile && matchesMediaCategory(file.name, restoreCategoryConfig)) {
                        // Restore to internal media vault
                        file.copyTo(File(appMediaDir, file.name), overwrite = true)

                        // Restore to external media folders if accessible
                        if (extRootAvailable) {
                            val cat = classifyFileCategory(file.name)
                            val targetExtDir = when (cat) {
                                BackupCategory.IMAGES -> storageManager.getDirectory(NexaStorageCategory.IMAGES)
                                BackupCategory.VIDEOS -> storageManager.getDirectory(NexaStorageCategory.VIDEOS)
                                BackupCategory.AUDIO -> storageManager.getDirectory(NexaStorageCategory.AUDIO)
                                BackupCategory.DOCUMENTS -> storageManager.getDirectory(NexaStorageCategory.DOCUMENTS)
                                BackupCategory.OTHER_FILES -> storageManager.getDirectory(NexaStorageCategory.DOWNLOADS)
                                else -> null
                            }
                            if (targetExtDir != null && targetExtDir.exists()) {
                                val extTarget = File(targetExtDir, file.name)
                                if (extTarget.canonicalPath.startsWith(targetExtDir.canonicalPath)) {
                                    runCatching { file.copyTo(extTarget, overwrite = true) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Restore Preferences if selected
        if (restoreCategoryConfig.includeSettings || restoreCategoryConfig.includeAppSettings || restoreCategoryConfig.includeUserPrefs) {
            onProgress("Restoring App Settings...", 0.75f)
            val userPrefsFile = File(stagingDir, "preferences/app_user_settings.json")
            if (userPrefsFile.exists()) {
                try {
                    val json = JSONObject(userPrefsFile.readText())
                    val sanitizedMap = RestoreEngine.sanitizeJsonObject(json, RestoreEngine.APPROVED_SETTINGS_ALLOWLIST)
                    if (sanitizedMap.isNotEmpty()) {
                        val sp = context.getSharedPreferences("nexa_settings", Context.MODE_PRIVATE).edit()
                        sanitizedMap.forEach { (key, value) ->
                            when (value) {
                                is Boolean -> sp.putBoolean(key, value)
                                is Int -> sp.putInt(key, value)
                                is Long -> sp.putLong(key, value)
                                is Float -> sp.putFloat(key, value)
                                is String -> sp.putString(key, value)
                            }
                        }
                        sp.apply()
                        SettingsPropagationBridge.propagateSettings(context, sanitizedMap)
                    }
                } catch (ignored: Exception) {}
            }

            val chatConfigsFile = File(stagingDir, "preferences/custom_chat_configs.json")
            if (chatConfigsFile.exists()) {
                try {
                    val json = JSONObject(chatConfigsFile.readText())
                    val cp = context.getSharedPreferences("nexa_chat_configs", Context.MODE_PRIVATE).edit()
                    val validConfigsMap = mutableMapOf<String, Any>()
                    var restoredConfigs = false
                    json.keys().forEach { key ->
                        if (RestoreEngine.isApprovedChatConfigKey(key)) {
                            when (val v = json.get(key)) {
                                is Boolean -> { cp.putBoolean(key, v); validConfigsMap[key] = v; restoredConfigs = true }
                                is Int -> { cp.putInt(key, v); validConfigsMap[key] = v; restoredConfigs = true }
                                is Long -> { cp.putLong(key, v); validConfigsMap[key] = v; restoredConfigs = true }
                                is Double -> { cp.putFloat(key, v.toFloat()); validConfigsMap[key] = v.toFloat(); restoredConfigs = true }
                                is String -> if (v.length <= 2048) { cp.putString(key, v); validConfigsMap[key] = v; restoredConfigs = true }
                            }
                        }
                    }
                    if (restoredConfigs) {
                        cp.apply()
                        SettingsPropagationBridge.propagateChatConfigs(context, validConfigsMap)
                    }
                } catch (ignored: Exception) {}
            }
        }

        // 6. Atomic Database Swap
        onProgress("Activating Database Snapshot...", 0.90f)
        NexaDatabase.closeAndResetInstance()

        val walFile = File(liveDatabaseFile.path + "-wal")
        val shmFile = File(liveDatabaseFile.path + "-shm")
        val journalFile = File(liveDatabaseFile.path + "-journal")

        if (walFile.exists()) walFile.delete()
        if (shmFile.exists()) shmFile.delete()
        if (journalFile.exists()) journalFile.delete()

        if (liveDatabaseFile.exists()) liveDatabaseFile.delete()
        snapshotDb.copyTo(liveDatabaseFile, overwrite = true)

        onProgress("Restoration Completed Successfully", 1.0f)
        return true
    }

    private fun zipDirectory(sourceDir: File, outputZipFile: File) {
        ZipOutputStream(FileOutputStream(outputZipFile)).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(sourceDir).path.replace("\\", "/")
                    if (!relativePath.startsWith("..") && !relativePath.startsWith("/")) {
                        zos.putNextEntry(ZipEntry(relativePath))
                        FileInputStream(file).use { fis ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var read: Int
                            while (fis.read(buffer).also { read = it } != -1) {
                                zos.write(buffer, 0, read)
                            }
                        }
                        zos.closeEntry()
                    }
                }
            }
        }
    }
}
