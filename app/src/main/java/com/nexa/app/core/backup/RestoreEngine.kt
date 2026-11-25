package com.nexa.app.core.backup

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.core.storage.NexaStorageCategory
import com.nexa.app.core.storage.NexaStorageManager
import com.nexa.app.domain.model.BackupCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Operating mode for backup restoration.
 */
enum class RestoreMode {
    /**
     * Non-destructive merge (default): preserves existing local conversations and messages,
     * imports missing chats, deduplicates messages using stable packetId, and updates snippets.
     */
    MERGE,

    /**
     * Scoped replacement: destructively replaces selected chats and categories in the live
     * database with the backup data, preserving unselected chats, local user credentials,
     * and hardware encryption keys.
     */
    REPLACE
}

/**
 * Detailed metadata for an individual chat/conversation discovered inside the backup vault.
 */
data class ChatRestoreInventoryItem(
    val conversationId: String,
    val title: String,
    val chatType: String = "DIRECT", // DIRECT, GROUP
    val isGroup: Boolean = false,
    val messageCount: Int = 0,
    val attachmentCount: Int = 0,
    val existsLocally: Boolean = false,
    val localMessageCount: Int = 0,
    val lastTimestamp: Long = 0L
)

/**
 * Parsed and validated inventory of data available inside a staged backup vault.
 */
data class RestoreInventory(
    val backupId: String,
    val createdAt: Long,
    val formatVersion: Int,
    val totalConversations: Int,
    val totalMessages: Int,
    val mediaFilesCount: Int,
    val availableCategories: BackupCategoryConfig,
    val chatItems: List<ChatRestoreInventoryItem>,
    val stagingDir: File,
    val stagedDbFile: File,
    val isLegacy: Boolean = false,
    val unsealedDbPassphrase: ByteArray? = null
)

/**
 * Configuration plan specifying user selections for the restore operation.
 */
data class RestorePlan(
    val mode: RestoreMode = RestoreMode.MERGE,
    val selectedCategories: BackupCategoryConfig = BackupCategoryConfig(),
    val selectedConversationIds: Set<String> = emptySet(),
    val autoDownloadMedia: Boolean = true
)

/**
 * Final execution summary emitted upon restore completion.
 */
data class RestoreResultSummary(
    val mode: RestoreMode,
    val totalChatsRestored: Int = 0,
    val totalMessagesRestored: Int = 0,
    val duplicateMessagesSkipped: Int = 0,
    val mediaFilesRestored: Int = 0,
    val mediaFilesFailed: Int = 0,
    val settingsRestored: Boolean = false,
    val contactsRestored: Int = 0,
    val groupsRestored: Int = 0,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val elapsedMillis: Long = 0L,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null
)

/**
 * Production-grade Restore Engine for NEXA encrypted backup vaults.
 *
 * Implements:
 * 1. Staged vault inspection and zero-leakage inventory analysis.
 * 2. Category-selective and chat-selective data filtering.
 * 3. Non-destructive MERGE mode with stable message ID deduplication.
 * 4. Scoped REPLACE mode with atomic rollback safety.
 * 5. Media routing to [NexaStorageManager] user directories with path traversal defense.
 * 6. Sanitized portable settings restoration (strict security allowlist).
 * 7. Referential and SQLite database integrity verification.
 */
class RestoreEngine(
    private val context: Context? = null,
    private val customFilesDir: File? = null
) {

    /**
     * Inspects a staged unencrypted database snapshot and bundle directory to build
     * a complete [RestoreInventory] comparing backup records with the live database.
     */
    suspend fun inspectStagedBundle(
        stagingDir: File,
        liveDatabase: SupportSQLiteDatabase?,
        backupId: String,
        unsealedDbPassphrase: ByteArray? = null,
        onProgress: suspend (phase: String, percent: Float) -> Unit = { _, _ -> }
    ): RestoreInventory = withContext(Dispatchers.IO) {
        onProgress("Inspecting Backup Manifest...", 0.10f)

        val manifestFile = File(stagingDir, "manifest.json")
        var formatVersion = 2
        var createdAt = System.currentTimeMillis()
        var totalConversations = 0
        var totalMessages = 0
        var mediaFilesCount = 0
        var availableCategories = BackupCategoryConfig()
        var isLegacy = false

        if (manifestFile.exists()) {
            try {
                val json = JSONObject(manifestFile.readText())
                createdAt = json.optLong("version_epoch", createdAt)
                formatVersion = json.optInt("format_version", 2)
                totalConversations = json.optInt("total_conversations", 0)
                totalMessages = json.optInt("total_messages", 0)
                mediaFilesCount = json.optInt("media_files_count", 0)
                if (json.has("category_config")) {
                    availableCategories = BackupCategoryConfig.fromJson(json.getJSONObject("category_config").toString())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse manifest.json, deriving from payload", e)
            }
        } else {
            isLegacy = true
            formatVersion = 1
        }

        val snapshotDb = File(stagingDir, "databases/nexa_master_snapshot.db").let {
            if (it.exists()) it else File(stagingDir, "nexa_master_snapshot.db")
        }

        if (!snapshotDb.exists()) {
            throw IllegalArgumentException("Corrupted backup bundle: database snapshot not found")
        }

        onProgress("Analyzing Conversation Inventory...", 0.35f)

        val chatItems = mutableListOf<ChatRestoreInventoryItem>()

        if (liveDatabase != null) {
            var isAttached = false
            try {
                runCatching { liveDatabase.execSQL("DETACH DATABASE staged_db") }
                liveDatabase.execSQL("ATTACH DATABASE '${snapshotDb.absolutePath}' AS staged_db")
                isAttached = true

                // Query all chats in staged_db
                val queryChatsSql = """
                    SELECT conversationId, participantName, title, chatType, lastMessageTimestamp 
                    FROM staged_db.chats 
                    ORDER BY lastMessageTimestamp DESC
                """.trimIndent()

                val stagedChats = mutableListOf<Triple<String, String, String>>()
                val stagedTimestamps = mutableMapOf<String, Long>()

                liveDatabase.query(queryChatsSql).use { cursor ->
                    val idIdx = cursor.getColumnIndex("conversationId")
                    val nameIdx = cursor.getColumnIndex("participantName")
                    val titleIdx = cursor.getColumnIndex("title")
                    val typeIdx = cursor.getColumnIndex("chatType")
                    val timeIdx = cursor.getColumnIndex("lastMessageTimestamp")

                    while (cursor.moveToNext()) {
                        val convId = if (idIdx >= 0) cursor.getString(idIdx) else null
                        if (!convId.isNullOrBlank()) {
                            val partName = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "" else ""
                            val title = if (titleIdx >= 0) cursor.getString(titleIdx) ?: "" else ""
                            val chatType = if (typeIdx >= 0) cursor.getString(typeIdx) ?: "DIRECT" else "DIRECT"
                            val timestamp = if (timeIdx >= 0) cursor.getLong(timeIdx) else 0L

                            val resolvedTitle = when {
                                title.isNotBlank() -> title
                                partName.isNotBlank() -> partName
                                else -> convId
                            }
                            stagedChats.add(Triple(convId, resolvedTitle, chatType))
                            stagedTimestamps[convId] = timestamp
                        }
                    }
                }

                // Query message and attachment counts per conversation
                for ((convId, title, chatType) in stagedChats) {
                    var msgCount = 0
                    var attCount = 0
                    var existsLocally = false
                    var localMsgCount = 0

                    runCatching {
                        liveDatabase.query("SELECT COUNT(*) FROM staged_db.messages WHERE conversationId = '$convId'").use { c ->
                            if (c.moveToFirst()) msgCount = c.getInt(0)
                        }
                    }

                    runCatching {
                        val attSql = """
                            SELECT COUNT(*) FROM staged_db.message_attachments 
                            WHERE messageId IN (SELECT packetId FROM staged_db.messages WHERE conversationId = '$convId')
                        """.trimIndent()
                        liveDatabase.query(attSql).use { c ->
                            if (c.moveToFirst()) attCount = c.getInt(0)
                        }
                    }

                    // Check if chat exists locally
                    runCatching {
                        liveDatabase.query("SELECT COUNT(*) FROM main.chats WHERE conversationId = '$convId'").use { c ->
                            if (c.moveToFirst()) existsLocally = c.getInt(0) > 0
                        }
                    }

                    if (existsLocally) {
                        runCatching {
                            liveDatabase.query("SELECT COUNT(*) FROM main.messages WHERE conversationId = '$convId'").use { c ->
                                if (c.moveToFirst()) localMsgCount = c.getInt(0)
                            }
                        }
                    }

                    val isGroup = chatType.equals("GROUP", ignoreCase = true) ||
                            convId.contains("group", ignoreCase = true) ||
                            title.contains("group", ignoreCase = true)

                    chatItems.add(
                        ChatRestoreInventoryItem(
                            conversationId = convId,
                            title = title,
                            chatType = chatType,
                            isGroup = isGroup,
                            messageCount = msgCount,
                            attachmentCount = attCount,
                            existsLocally = existsLocally,
                            localMessageCount = localMsgCount,
                            lastTimestamp = stagedTimestamps[convId] ?: 0L
                        )
                    )
                }
            } finally {
                if (isAttached) {
                    runCatching { liveDatabase.execSQL("DETACH DATABASE staged_db") }
                }
            }
        }

        // Count media files in staging
        val mediaDir = File(stagingDir, "media_vault")
        if (mediaDir.exists() && mediaDir.isDirectory) {
            val mediaCount = mediaDir.listFiles()?.count { it.isFile && it.name != ".nomedia" } ?: 0
            if (mediaFilesCount == 0) mediaFilesCount = mediaCount
        }

        if (totalConversations == 0) totalConversations = chatItems.size
        if (totalMessages == 0) totalMessages = chatItems.sumOf { it.messageCount }

        onProgress("Inventory Ready", 1.0f)

        RestoreInventory(
            backupId = backupId,
            createdAt = createdAt,
            formatVersion = formatVersion,
            totalConversations = totalConversations,
            totalMessages = totalMessages,
            mediaFilesCount = mediaFilesCount,
            availableCategories = availableCategories,
            chatItems = chatItems,
            stagingDir = stagingDir,
            stagedDbFile = snapshotDb,
            isLegacy = isLegacy,
            unsealedDbPassphrase = unsealedDbPassphrase
        )
    }

    /**
     * Executes the configured [RestorePlan] against the live database and storage tree.
     */
    suspend fun executeRestore(
        inventory: RestoreInventory,
        liveDatabase: SupportSQLiteDatabase,
        plan: RestorePlan,
        secureStorage: SecureStorage? = null,
        customStorageManager: NexaStorageManager? = null,
        onProgress: suspend (phase: String, percent: Float) -> Unit = { _, _ -> }
    ): RestoreResultSummary = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        var totalChatsRestored = 0
        var totalMessagesRestored = 0
        var duplicateMessagesSkipped = 0
        var contactsRestored = 0
        var groupsRestored = 0
        var mediaRestored = 0
        var mediaFailed = 0
        var settingsRestored = false

        try {
            onProgress("Attaching Staged Snapshot...", 0.05f)

            // 1. Resolve selected conversations taking into account includeChats and includeGroups
            val groupConvIds = inventory.chatItems.filter { it.isGroup }.map { it.conversationId }.toSet()
            val baseConvIds = if (plan.selectedConversationIds.isNotEmpty()) {
                plan.selectedConversationIds
            } else if (plan.selectedCategories.includeChats) {
                inventory.chatItems.map { it.conversationId }.toSet()
            } else {
                emptySet()
            }

            val effectiveConvIds = if (plan.selectedCategories.includeChats) {
                if (!plan.selectedCategories.includeGroups) {
                    baseConvIds.filter { convId ->
                        !groupConvIds.contains(convId) && !convId.contains("group", ignoreCase = true)
                    }.toSet()
                } else {
                    baseConvIds
                }
            } else {
                emptySet()
            }

            // 2. Perform Database Restoration (MERGE vs REPLACE)
            if (plan.selectedCategories.includeChats && effectiveConvIds.isNotEmpty()) {
                if (plan.mode == RestoreMode.MERGE) {
                    onProgress("Merging Conversations & Messages...", 0.20f)
                    val mergeResult = executeMerge(
                        liveDatabase = liveDatabase,
                        stagedDbFile = inventory.stagedDbFile,
                        targetConvIds = effectiveConvIds,
                        includeContacts = plan.selectedCategories.includeContacts,
                        includeGroups = plan.selectedCategories.includeGroups
                    )
                    totalChatsRestored = mergeResult.chatsImported
                    totalMessagesRestored = mergeResult.messagesImported
                    duplicateMessagesSkipped = mergeResult.duplicatesSkipped
                    contactsRestored = mergeResult.contactsImported
                    groupsRestored = mergeResult.groupsImported
                } else {
                    onProgress("Executing Scoped Replace on Selected Chats...", 0.20f)
                    val replaceResult = executeReplace(
                        liveDatabase = liveDatabase,
                        stagedDbFile = inventory.stagedDbFile,
                        targetConvIds = effectiveConvIds,
                        includeContacts = plan.selectedCategories.includeContacts,
                        includeGroups = plan.selectedCategories.includeGroups
                    )
                    totalChatsRestored = replaceResult.chatsImported
                    totalMessagesRestored = replaceResult.messagesImported
                    contactsRestored = replaceResult.contactsImported
                    groupsRestored = replaceResult.groupsImported
                }
            } else if (plan.selectedCategories.includeContacts) {
                onProgress("Restoring Contacts Metadata...", 0.25f)
                contactsRestored = restoreContactsOnly(liveDatabase, inventory.stagedDbFile)
            }

            // 3. Media Restoration (Images, Videos, Audio, Documents, Other)
            if (plan.selectedCategories.hasAnyMediaSelected() || plan.selectedCategories.includeAttachments) {
                onProgress("Restoring Media Vault...", 0.60f)
                val mediaResult = restoreMediaFiles(
                    context = context,
                    stagingDir = inventory.stagingDir,
                    categoryConfig = plan.selectedCategories,
                    customStorageManager = customStorageManager,
                    effectiveConvIds = if (plan.selectedCategories.includeChats) effectiveConvIds else null,
                    stagedDbFile = inventory.stagedDbFile,
                    liveDatabase = liveDatabase,
                    onMediaProgress = { phase, p -> onProgress(phase, 0.60f + p * 0.25f) }
                )
                mediaRestored = mediaResult.restoredCount
                mediaFailed = mediaResult.failedCount
                if (mediaFailed > 0) {
                    warnings.add("$mediaFailed media file(s) could not be restored")
                }
            }

            // 4. Settings Restoration (Strict Allowlist; NO TOKENS, SECRETS, OR PINS)
            if (plan.selectedCategories.includeSettings || plan.selectedCategories.includeAppSettings || plan.selectedCategories.includeUserPrefs) {
                onProgress("Restoring App Settings...", 0.88f)
                if (context != null) {
                    settingsRestored = restoreSanitizedSettings(context, inventory.stagingDir)
                } else {
                    settingsRestored = true
                }
            }

            // 5. Database Integrity & Constraints Verification
            onProgress("Verifying Database Integrity...", 0.95f)
            verifyDatabaseIntegrity(liveDatabase)

            val elapsed = System.currentTimeMillis() - startTime
            onProgress("Restoration Complete", 1.0f)

            RestoreResultSummary(
                mode = plan.mode,
                totalChatsRestored = totalChatsRestored,
                totalMessagesRestored = totalMessagesRestored,
                duplicateMessagesSkipped = duplicateMessagesSkipped,
                mediaFilesRestored = mediaRestored,
                mediaFilesFailed = mediaFailed,
                settingsRestored = settingsRestored,
                contactsRestored = contactsRestored,
                groupsRestored = groupsRestored,
                warnings = warnings,
                errors = errors,
                elapsedMillis = elapsed,
                isSuccess = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Restore execution failed", e)
            val elapsed = System.currentTimeMillis() - startTime
            errors.add(e.localizedMessage ?: "Unknown restore error")
            RestoreResultSummary(
                mode = plan.mode,
                warnings = warnings,
                errors = errors,
                elapsedMillis = elapsed,
                isSuccess = false,
                errorMessage = e.localizedMessage
            )
        }
    }

    /**
     * Executes non-destructive MERGE mode inside a single atomic SQLite transaction.
     */
    private fun executeMerge(
        liveDatabase: SupportSQLiteDatabase,
        stagedDbFile: File,
        targetConvIds: Set<String>,
        includeContacts: Boolean,
        includeGroups: Boolean = true
    ): MergeExecutionStats {
        var isAttached = false
        var chatsImported = 0
        var messagesImported = 0
        var duplicatesSkipped = 0
        var contactsImported = 0
        var groupsImported = 0

        try {
            runCatching { liveDatabase.execSQL("DETACH DATABASE staged_db") }
            liveDatabase.execSQL("ATTACH DATABASE '${stagedDbFile.absolutePath}' AS staged_db")
            isAttached = true

            val convIdInClause = targetConvIds.joinToString(separator = ",", prefix = "(", postfix = ")") { "'$it'" }

            liveDatabase.beginTransaction()
            try {
                // Count initial local message count for target conversations
                var initialMsgCount = 0
                liveDatabase.query("SELECT COUNT(*) FROM main.messages WHERE conversationId IN $convIdInClause").use { c ->
                    if (c.moveToFirst()) initialMsgCount = c.getInt(0)
                }

                // Count total staged messages for target conversations
                var stagedMsgCount = 0
                liveDatabase.query("SELECT COUNT(*) FROM staged_db.messages WHERE conversationId IN $convIdInClause").use { c ->
                    if (c.moveToFirst()) stagedMsgCount = c.getInt(0)
                }

                // 1. Insert or Ignore Chats
                liveDatabase.execSQL("""
                    INSERT OR IGNORE INTO main.chats (
                        conversationId, participantNxId, participantName, lastMessageText,
                        lastMessageTimestamp, unreadCount, chatType, title, avatarUrl,
                        lastMessageId, lastMessageSenderNxId, isPinned, pinPosition, pinnedAt,
                        isArchived, archivedAt, isMuted, mutedUntil, createdAt, updatedAt,
                        hasManualUnread, isPriorityBackup, backupPolicy, customBackupCategories
                    )
                    SELECT 
                        conversationId, participantNxId, participantName, lastMessageText,
                        lastMessageTimestamp, unreadCount, chatType, title, avatarUrl,
                        lastMessageId, lastMessageSenderNxId, isPinned, pinPosition, pinnedAt,
                        isArchived, archivedAt, isMuted, mutedUntil, createdAt, updatedAt,
                        hasManualUnread, isPriorityBackup, backupPolicy, customBackupCategories
                    FROM staged_db.chats
                    WHERE conversationId IN $convIdInClause
                """.trimIndent())

                // 2. For existing chats, update snippet if staged message is newer
                liveDatabase.execSQL("""
                    UPDATE main.chats
                    SET lastMessageText = (SELECT lastMessageText FROM staged_db.chats WHERE staged_db.chats.conversationId = main.chats.conversationId),
                        lastMessageTimestamp = (SELECT lastMessageTimestamp FROM staged_db.chats WHERE staged_db.chats.conversationId = main.chats.conversationId),
                        lastMessageId = (SELECT lastMessageId FROM staged_db.chats WHERE staged_db.chats.conversationId = main.chats.conversationId),
                        updatedAt = (SELECT updatedAt FROM staged_db.chats WHERE staged_db.chats.conversationId = main.chats.conversationId)
                    WHERE conversationId IN $convIdInClause
                      AND (SELECT lastMessageTimestamp FROM staged_db.chats WHERE staged_db.chats.conversationId = main.chats.conversationId) > main.chats.lastMessageTimestamp
                """.trimIndent())

                // 3. Insert or Ignore Messages (packetId deduplication)
                liveDatabase.execSQL("""
                    INSERT OR IGNORE INTO main.messages (
                        packetId, conversationId, senderNxId, recipientNxId, plainTextContent,
                        status, timestamp, isOutgoing, transportType, isPinned, pinnedExpiresAt,
                        pinnedAt, replyToMessageId, replyToSenderNxId, replyToSnippet,
                        messageType, localSequence, isDeleted, isStarred, editedAt, localState
                    )
                    SELECT 
                        packetId, conversationId, senderNxId, recipientNxId, plainTextContent,
                        status, timestamp, isOutgoing, transportType, isPinned, pinnedExpiresAt,
                        pinnedAt, replyToMessageId, replyToSenderNxId, replyToSnippet,
                        messageType, localSequence, isDeleted, isStarred, editedAt, localState
                    FROM staged_db.messages
                    WHERE conversationId IN $convIdInClause
                """.trimIndent())

                // 4. Insert or Ignore Message Attachments
                liveDatabase.execSQL("""
                    INSERT OR IGNORE INTO main.message_attachments (
                        attachmentId, messageId, fileName, mimeType, detectedFileType,
                        fileSize, localPathReference, thumbnailReference, width, height,
                        durationMs, transferState, integrityState, securityScanState, createdAt
                    )
                    SELECT 
                        attachmentId, messageId, fileName, mimeType, detectedFileType,
                        fileSize, localPathReference, thumbnailReference, width, height,
                        durationMs, transferState, integrityState, securityScanState, createdAt
                    FROM staged_db.message_attachments
                    WHERE messageId IN (SELECT packetId FROM main.messages WHERE conversationId IN $convIdInClause)
                """.trimIndent())

                // 5. Insert or Ignore Chat Members (only if groups are enabled)
                if (includeGroups) {
                    runCatching {
                        liveDatabase.execSQL("""
                            INSERT OR IGNORE INTO main.chat_members (
                                chatId, userNxId, role, joinedAt, leftAt, membershipState
                            )
                            SELECT 
                                chatId, userNxId, role, joinedAt, leftAt, membershipState
                            FROM staged_db.chat_members
                            WHERE chatId IN $convIdInClause
                        """.trimIndent())
                    }
                }

                // 6. Insert or Ignore Message Reactions
                runCatching {
                    liveDatabase.execSQL("""
                        INSERT OR IGNORE INTO main.message_reactions (
                            reactionId, messageId, userNxId, emoji, timestamp
                        )
                        SELECT 
                            reactionId, messageId, userNxId, emoji, timestamp
                        FROM staged_db.message_reactions
                        WHERE messageId IN (SELECT packetId FROM main.messages WHERE conversationId IN $convIdInClause)
                    """.trimIndent())
                }

                // 7. Insert or Ignore Contacts if selected
                if (includeContacts) {
                    runCatching {
                        val initialContacts = queryCount(liveDatabase, "SELECT COUNT(*) FROM main.contacts")
                        liveDatabase.execSQL("""
                            INSERT OR IGNORE INTO main.contacts (
                                nxId, displayName, publicKeyHex, introMessage, status, blePeerId,
                                createdAtTimestamp, customNickname, deviceContactName, phoneNumber,
                                isPhoneInLocalAddressBook, isPhoneSharedByPeer, connectionSource,
                                avatarLocalPath, deviceContactPhotoUri, nexaAvatarUrl, isStatusMuted
                            )
                            SELECT 
                                nxId, displayName, publicKeyHex, introMessage, status, blePeerId,
                                createdAtTimestamp, customNickname, deviceContactName, phoneNumber,
                                isPhoneInLocalAddressBook, isPhoneSharedByPeer, connectionSource,
                                avatarLocalPath, deviceContactPhotoUri, nexaAvatarUrl, isStatusMuted
                            FROM staged_db.contacts
                        """.trimIndent())
                        val afterContacts = queryCount(liveDatabase, "SELECT COUNT(*) FROM main.contacts")
                        contactsImported = maxOf(0, afterContacts - initialContacts)
                    }
                }

                // Calculate metrics
                var finalMsgCount = 0
                liveDatabase.query("SELECT COUNT(*) FROM main.messages WHERE conversationId IN $convIdInClause").use { c ->
                    if (c.moveToFirst()) finalMsgCount = c.getInt(0)
                }

                chatsImported = targetConvIds.size
                messagesImported = maxOf(0, finalMsgCount - initialMsgCount)
                duplicatesSkipped = maxOf(0, stagedMsgCount - messagesImported)

                groupsImported = if (includeGroups) {
                    queryCount(liveDatabase, """
                        SELECT COUNT(*) FROM main.chats 
                        WHERE conversationId IN $convIdInClause 
                          AND (chatType = 'GROUP' OR conversationId LIKE '%group%')
                    """.trimIndent())
                } else {
                    0
                }

                liveDatabase.setTransactionSuccessful()
            } finally {
                liveDatabase.endTransaction()
            }
        } finally {
            if (isAttached) {
                runCatching { liveDatabase.execSQL("DETACH DATABASE staged_db") }
            }
        }

        return MergeExecutionStats(
            chatsImported = chatsImported,
            messagesImported = messagesImported,
            duplicatesSkipped = duplicatesSkipped,
            contactsImported = contactsImported,
            groupsImported = groupsImported
        )
    }

    /**
     * Executes scoped REPLACE mode: deletes existing records belonging to selected chats
     * in the live database, then imports the snapshot records inside a single transaction.
     */
    private fun executeReplace(
        liveDatabase: SupportSQLiteDatabase,
        stagedDbFile: File,
        targetConvIds: Set<String>,
        includeContacts: Boolean,
        includeGroups: Boolean = true
    ): MergeExecutionStats {
        var isAttached = false
        var chatsImported = 0
        var messagesImported = 0
        var contactsImported = 0
        var groupsImported = 0

        try {
            runCatching { liveDatabase.execSQL("DETACH DATABASE staged_db") }
            liveDatabase.execSQL("ATTACH DATABASE '${stagedDbFile.absolutePath}' AS staged_db")
            isAttached = true

            val convIdInClause = targetConvIds.joinToString(separator = ",", prefix = "(", postfix = ")") { "'$it'" }

            liveDatabase.beginTransaction()
            try {
                // 1. Scoped Deletion: delete existing records for selected chats only
                liveDatabase.execSQL("""
                    DELETE FROM main.message_attachments 
                    WHERE messageId IN (SELECT packetId FROM main.messages WHERE conversationId IN $convIdInClause)
                """.trimIndent())

                runCatching {
                    liveDatabase.execSQL("""
                        DELETE FROM main.message_reactions 
                        WHERE messageId IN (SELECT packetId FROM main.messages WHERE conversationId IN $convIdInClause)
                    """.trimIndent())
                }

                liveDatabase.execSQL("DELETE FROM main.messages WHERE conversationId IN $convIdInClause")
                runCatching { liveDatabase.execSQL("DELETE FROM main.chat_members WHERE chatId IN $convIdInClause") }
                runCatching { liveDatabase.execSQL("DELETE FROM main.chat_drafts WHERE conversationId IN $convIdInClause") }
                runCatching { liveDatabase.execSQL("DELETE FROM main.scheduled_messages WHERE conversationId IN $convIdInClause") }
                liveDatabase.execSQL("DELETE FROM main.chats WHERE conversationId IN $convIdInClause")

                // 2. Insert from staged database
                liveDatabase.execSQL("""
                    INSERT INTO main.chats (
                        conversationId, participantNxId, participantName, lastMessageText,
                        lastMessageTimestamp, unreadCount, chatType, title, avatarUrl,
                        lastMessageId, lastMessageSenderNxId, isPinned, pinPosition, pinnedAt,
                        isArchived, archivedAt, isMuted, mutedUntil, createdAt, updatedAt,
                        hasManualUnread, isPriorityBackup, backupPolicy, customBackupCategories
                    )
                    SELECT 
                        conversationId, participantNxId, participantName, lastMessageText,
                        lastMessageTimestamp, unreadCount, chatType, title, avatarUrl,
                        lastMessageId, lastMessageSenderNxId, isPinned, pinPosition, pinnedAt,
                        isArchived, archivedAt, isMuted, mutedUntil, createdAt, updatedAt,
                        hasManualUnread, isPriorityBackup, backupPolicy, customBackupCategories
                    FROM staged_db.chats
                    WHERE conversationId IN $convIdInClause
                """.trimIndent())

                liveDatabase.execSQL("""
                    INSERT INTO main.messages (
                        packetId, conversationId, senderNxId, recipientNxId, plainTextContent,
                        status, timestamp, isOutgoing, transportType, isPinned, pinnedExpiresAt,
                        pinnedAt, replyToMessageId, replyToSenderNxId, replyToSnippet,
                        messageType, localSequence, isDeleted, isStarred, editedAt, localState
                    )
                    SELECT 
                        packetId, conversationId, senderNxId, recipientNxId, plainTextContent,
                        status, timestamp, isOutgoing, transportType, isPinned, pinnedExpiresAt,
                        pinnedAt, replyToMessageId, replyToSenderNxId, replyToSnippet,
                        messageType, localSequence, isDeleted, isStarred, editedAt, localState
                    FROM staged_db.messages
                    WHERE conversationId IN $convIdInClause
                """.trimIndent())

                liveDatabase.execSQL("""
                    INSERT INTO main.message_attachments (
                        attachmentId, messageId, fileName, mimeType, detectedFileType,
                        fileSize, localPathReference, thumbnailReference, width, height,
                        durationMs, transferState, integrityState, securityScanState, createdAt
                    )
                    SELECT 
                        attachmentId, messageId, fileName, mimeType, detectedFileType,
                        fileSize, localPathReference, thumbnailReference, width, height,
                        durationMs, transferState, integrityState, securityScanState, createdAt
                    FROM staged_db.message_attachments
                    WHERE messageId IN (SELECT packetId FROM main.messages WHERE conversationId IN $convIdInClause)
                """.trimIndent())

                if (includeGroups) {
                    runCatching {
                        liveDatabase.execSQL("""
                            INSERT OR IGNORE INTO main.chat_members (
                                chatId, userNxId, role, joinedAt, leftAt, membershipState
                            )
                            SELECT 
                                chatId, userNxId, role, joinedAt, leftAt, membershipState
                            FROM staged_db.chat_members
                            WHERE chatId IN $convIdInClause
                        """.trimIndent())
                    }
                }

                if (includeContacts) {
                    runCatching {
                        liveDatabase.execSQL("""
                            INSERT OR IGNORE INTO main.contacts (
                                nxId, displayName, publicKeyHex, introMessage, status, blePeerId,
                                createdAtTimestamp, customNickname, deviceContactName, phoneNumber,
                                isPhoneInLocalAddressBook, isPhoneSharedByPeer, connectionSource,
                                avatarLocalPath, deviceContactPhotoUri, nexaAvatarUrl, isStatusMuted
                            )
                            SELECT 
                                nxId, displayName, publicKeyHex, introMessage, status, blePeerId,
                                createdAtTimestamp, customNickname, deviceContactName, phoneNumber,
                                isPhoneInLocalAddressBook, isPhoneSharedByPeer, connectionSource,
                                avatarLocalPath, deviceContactPhotoUri, nexaAvatarUrl, isStatusMuted
                            FROM staged_db.contacts
                        """.trimIndent())
                    }
                }

                chatsImported = targetConvIds.size
                messagesImported = queryCount(liveDatabase, "SELECT COUNT(*) FROM main.messages WHERE conversationId IN $convIdInClause")
                contactsImported = if (includeContacts) queryCount(liveDatabase, "SELECT COUNT(*) FROM main.contacts") else 0
                groupsImported = if (includeGroups) {
                    queryCount(liveDatabase, """
                        SELECT COUNT(*) FROM main.chats 
                        WHERE conversationId IN $convIdInClause 
                          AND (chatType = 'GROUP' OR conversationId LIKE '%group%')
                    """.trimIndent())
                } else {
                    0
                }

                liveDatabase.setTransactionSuccessful()
            } finally {
                liveDatabase.endTransaction()
            }
        } finally {
            if (isAttached) {
                runCatching { liveDatabase.execSQL("DETACH DATABASE staged_db") }
            }
        }

        return MergeExecutionStats(
            chatsImported = chatsImported,
            messagesImported = messagesImported,
            duplicatesSkipped = 0,
            contactsImported = contactsImported,
            groupsImported = groupsImported
        )
    }

    private fun restoreContactsOnly(liveDatabase: SupportSQLiteDatabase, stagedDbFile: File): Int {
        var isAttached = false
        var count = 0
        try {
            runCatching { liveDatabase.execSQL("DETACH DATABASE staged_db") }
            liveDatabase.execSQL("ATTACH DATABASE '${stagedDbFile.absolutePath}' AS staged_db")
            isAttached = true

            val initialContacts = queryCount(liveDatabase, "SELECT COUNT(*) FROM main.contacts")
            liveDatabase.execSQL("""
                INSERT OR IGNORE INTO main.contacts (
                    nxId, displayName, publicKeyHex, introMessage, status, blePeerId,
                    createdAtTimestamp, customNickname, deviceContactName, phoneNumber,
                    isPhoneInLocalAddressBook, isPhoneSharedByPeer, connectionSource,
                    avatarLocalPath, deviceContactPhotoUri, nexaAvatarUrl, isStatusMuted
                )
                SELECT 
                    nxId, displayName, publicKeyHex, introMessage, status, blePeerId,
                    createdAtTimestamp, customNickname, deviceContactName, phoneNumber,
                    isPhoneInLocalAddressBook, isPhoneSharedByPeer, connectionSource,
                    avatarLocalPath, deviceContactPhotoUri, nexaAvatarUrl, isStatusMuted
                FROM staged_db.contacts
            """.trimIndent())
            val afterContacts = queryCount(liveDatabase, "SELECT COUNT(*) FROM main.contacts")
            count = maxOf(0, afterContacts - initialContacts)
        } finally {
            if (isAttached) {
                runCatching { liveDatabase.execSQL("DETACH DATABASE staged_db") }
            }
        }
        return count
    }

    /**
     * Extracts and routes media files from staging into [NexaStorageManager] scoped directories
     * with strict path traversal checks and collision prevention.
     *
     * Remediations applied:
     * - MED-1: Independent category filtering. Each media category toggle is independently respected.
     *   Global includeAttachments cannot bypass individual category selection.
     * - MED-2: Selective conversation media filtering. Media belonging exclusively to unselected
     *   conversations is skipped, preventing orphaned media files on disk. Shared media is preserved
     *   if at least one owner conversation is selected. Standalone media is preserved.
     */
    suspend fun restoreMediaFiles(
        context: Context?,
        stagingDir: File,
        categoryConfig: BackupCategoryConfig,
        customStorageManager: NexaStorageManager? = null,
        effectiveConvIds: Set<String>? = null,
        stagedDbFile: File? = null,
        liveDatabase: SupportSQLiteDatabase? = null,
        customOwnershipMap: Map<String, Set<String>>? = null,
        onMediaProgress: suspend (phase: String, percent: Float) -> Unit = { _, _ -> }
    ): MediaRestoreStats {
        val mediaDir = File(stagingDir, "media_vault")
        if (!mediaDir.exists() || !mediaDir.isDirectory) {
            return MediaRestoreStats(0, 0)
        }

        val storageManager = customStorageManager ?: NexaStorageManager(context)
        val extAvailable = runCatching { storageManager.initStorageTree() }.getOrDefault(false)
        val internalVaultDir = customFilesDir?.let { File(it, "media_vault").apply { mkdirs() } }
            ?: context?.let { File(it.filesDir, "media_vault").apply { mkdirs() } }
            ?: File(stagingDir, "internal_media_vault").apply { mkdirs() }

        val candidateFiles = mediaDir.listFiles()?.filter { it.isFile && it.name != ".nomedia" } ?: emptyList()
        val total = candidateFiles.size
        var restored = 0
        var failed = 0

        // MED-2: Resolve media-to-conversation ownership mapping
        val ownershipMap: Map<String, Set<String>> = customOwnershipMap
            ?: loadOwnershipFromStaging(stagingDir)
            ?: resolveMediaOwnershipFromDb(liveDatabase, stagedDbFile)
            ?: emptyMap()

        for ((index, file) in candidateFiles.withIndex()) {
            val progress = if (total > 0) index.toFloat() / total.toFloat() else 1.0f
            onMediaProgress("Restoring media file ${index + 1}/$total...", progress)

            try {
                val cleanName = file.name.replace("\\", "_").replace("/", "_").trim()
                if (cleanName.isBlank()) continue

                // MED-1: Strict independent media category filtering
                val category = VaultBundlePackager.classifyFileCategory(cleanName)
                if (!categoryConfig.includeAttachments || !categoryConfig.isCategoryEnabled(category)) {
                    continue
                }

                // MED-2: Selective conversation media filtering
                if (effectiveConvIds != null) {
                    val owners = ownershipMap[cleanName] ?: ownershipMap[file.name]
                    if (owners != null && owners.isNotEmpty()) {
                        // Media belongs to one or more conversations: restore ONLY if at least one owner conversation is selected
                        if (owners.none { it in effectiveConvIds }) {
                            continue
                        }
                    }
                    // Standalone media (owners == null or empty) is restored subject to category filtering
                }

                // 1. Copy to internal media vault
                val internalDest = File(internalVaultDir, cleanName)
                if (internalDest.canonicalPath.startsWith(internalVaultDir.canonicalPath)) {
                    file.copyTo(internalDest, overwrite = true)
                }

                // 2. Route to external NEXA scoped storage directory
                if (extAvailable) {
                    val targetExtCategory = when (category) {
                        BackupCategory.IMAGES -> NexaStorageCategory.IMAGES
                        BackupCategory.VIDEOS -> NexaStorageCategory.VIDEOS
                        BackupCategory.AUDIO -> NexaStorageCategory.AUDIO
                        BackupCategory.DOCUMENTS -> NexaStorageCategory.DOCUMENTS
                        BackupCategory.OTHER_FILES -> NexaStorageCategory.DOWNLOADS
                        else -> null
                    }

                    if (targetExtCategory != null) {
                        val targetDir = storageManager.getDirectory(targetExtCategory)
                        val safeDest = storageManager.resolveSafeFile(targetExtCategory, cleanName)
                        if (safeDest.canonicalPath.startsWith(targetDir.canonicalPath)) {
                            file.copyTo(safeDest, overwrite = false)
                        }
                    }
                }

                restored++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore media file: ${file.name}", e)
                failed++
            }
        }

        return MediaRestoreStats(restored, failed)
    }

    /**
     * Loads conversation ownership mapping from staging metadata if present.
     */
    private fun loadOwnershipFromStaging(stagingDir: File): Map<String, Set<String>>? {
        val file = File(stagingDir, "media_ownership.json").takeIf { it.exists() }
            ?: File(stagingDir, "preferences/media_ownership.json").takeIf { it.exists() }
            ?: return null
        return try {
            val json = JSONObject(file.readText())
            val map = mutableMapOf<String, Set<String>>()
            json.keys().forEach { key ->
                val arr = json.optJSONArray(key)
                if (arr != null) {
                    val set = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        val convId = arr.optString(i)
                        if (convId.isNotBlank()) set.add(convId)
                    }
                    map[key] = set
                } else {
                    val single = json.optString(key)
                    if (single.isNotBlank()) map[key] = setOf(single)
                }
            }
            map
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading media_ownership.json from staging", e)
            null
        }
    }

    /**
     * Queries staged SQLite database snapshot to extract media-to-conversation mapping.
     */
    private fun resolveMediaOwnershipFromDb(
        liveDatabase: SupportSQLiteDatabase?,
        stagedDbFile: File?
    ): Map<String, Set<String>>? {
        if (liveDatabase == null || stagedDbFile == null || !stagedDbFile.exists()) return null
        val ownership = mutableMapOf<String, MutableSet<String>>()
        var isAttached = false
        try {
            runCatching { liveDatabase.execSQL("DETACH DATABASE staged_db") }
            liveDatabase.execSQL("ATTACH DATABASE '${stagedDbFile.absolutePath}' AS staged_db")
            isAttached = true
            val sql = """
                SELECT m.conversationId, a.fileName, a.localPathReference 
                FROM staged_db.messages m 
                JOIN staged_db.message_attachments a ON m.packetId = a.messageId
            """.trimIndent()
            liveDatabase.query(sql).use { cursor ->
                val convIdx = cursor.getColumnIndex("conversationId")
                val fnIdx = cursor.getColumnIndex("fileName")
                val pathIdx = cursor.getColumnIndex("localPathReference")
                while (cursor.moveToNext()) {
                    val convId = if (convIdx >= 0) cursor.getString(convIdx) else null
                    if (!convId.isNullOrBlank()) {
                        if (fnIdx >= 0) {
                            cursor.getString(fnIdx)?.takeIf { it.isNotBlank() }?.let { name ->
                                ownership.getOrPut(name) { mutableSetOf() }.add(convId)
                                val baseName = File(name).name
                                if (baseName.isNotBlank()) ownership.getOrPut(baseName) { mutableSetOf() }.add(convId)
                            }
                        }
                        if (pathIdx >= 0) {
                            cursor.getString(pathIdx)?.takeIf { it.isNotBlank() }?.let { path ->
                                ownership.getOrPut(path) { mutableSetOf() }.add(convId)
                                val baseName = File(path).name
                                if (baseName.isNotBlank()) ownership.getOrPut(baseName) { mutableSetOf() }.add(convId)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve media ownership from staged database", e)
        } finally {
            if (isAttached) {
                runCatching { liveDatabase.execSQL("DETACH DATABASE staged_db") }
            }
        }
        return if (ownership.isNotEmpty()) ownership else null
    }

    /**
     * Restores portable settings from staging, applying a strict explicit security allowlist
     * and propagating restored values to canonical partitioned stores via [SettingsPropagationBridge].
     *
     * Rejects all unknown keys, authentication credentials, tokens, session secrets, PINs,
     * biometric flags, device identifiers, and hardware keys.
     */
    private fun restoreSanitizedSettings(context: Context, stagingDir: File): Boolean {
        var restoredAny = false
        val userPrefsFile = File(stagingDir, "preferences/app_user_settings.json")
        if (userPrefsFile.exists()) {
            try {
                val json = JSONObject(userPrefsFile.readText())
                val sanitizedMap = sanitizeJsonObject(json, APPROVED_SETTINGS_ALLOWLIST)
                if (sanitizedMap.isNotEmpty()) {
                    // 1. Maintain consolidated store for backward compatibility
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

                    // 2. SET-1: Propagate to canonical partitioned stores used by app viewmodels & singletons
                    SettingsPropagationBridge.propagateSettings(context, sanitizedMap)
                    restoredAny = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed restoring user preferences", e)
            }
        }

        val chatConfigsFile = File(stagingDir, "preferences/custom_chat_configs.json")
        if (chatConfigsFile.exists()) {
            try {
                val json = JSONObject(chatConfigsFile.readText())
                val cp = context.getSharedPreferences("nexa_chat_configs", Context.MODE_PRIVATE).edit()
                val validConfigsMap = mutableMapOf<String, Any>()
                var restoredConfigs = false
                json.keys().forEach { key ->
                    if (isApprovedChatConfigKey(key)) {
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
                    // 2. SET-1: Propagate chat configs to ConversationPreferences
                    SettingsPropagationBridge.propagateChatConfigs(context, validConfigsMap)
                    restoredAny = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed restoring chat configs", e)
            }
        }

        return restoredAny
    }

    private fun verifyDatabaseIntegrity(liveDatabase: SupportSQLiteDatabase) {
        liveDatabase.query("PRAGMA integrity_check").use { cursor ->
            if (cursor.moveToFirst()) {
                val status = cursor.getString(0)
                if (!status.equals("ok", ignoreCase = true)) {
                    throw SecurityException("Database integrity check failed: $status")
                }
            }
        }
        liveDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            if (cursor.count > 0) {
                Log.w(TAG, "Warning: foreign key check reported ${cursor.count} violation(s)")
            }
        }
    }

    private fun queryCount(db: SupportSQLiteDatabase, sql: String): Int {
        return try {
            db.query(sql).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private data class MergeExecutionStats(
        val chatsImported: Int,
        val messagesImported: Int,
        val duplicatesSkipped: Int,
        val contactsImported: Int,
        val groupsImported: Int
    )

    data class MediaRestoreStats(
        val restoredCount: Int,
        val failedCount: Int
    )

    companion object {
        private const val TAG = "RestoreEngine"

        /**
         * Strict explicit allowlist of approved safe portable settings keys.
         * Unknown keys are rejected by default.
         */
        val APPROVED_SETTINGS_ALLOWLIST: Set<String> = setOf(
            "app_theme_mode",
            "appearance_mode",
            "key_appearance_mode",
            "font_size_scale",
            "chat_bubble_style",
            "chat_wallpaper",
            "notifications_enabled",
            "notification_sound",
            "vibration_enabled",
            "enter_to_send",
            "media_visibility_enabled",
            "high_contrast_messages",
            "selected_language",
            "auto_download_wifi_images",
            "auto_download_wifi_videos",
            "auto_download_wifi_documents",
            "auto_download_wifi_audio",
            "auto_download_cellular_images",
            "auto_download_cellular_videos",
            "auto_download_cellular_documents",
            "auto_download_cellular_audio",
            "reduce_animations",
            "key_reduce_animations",
            "font_style",
            "key_font_style",
            "app_text_size",
            "key_app_text_size",
            "chat_text_size",
            "key_chat_text_size",
            "message_font_size",
            "media_visibility",
            "high_contrast",
            "backup_frequency",
            "backup_over_wifi_only",
            "backup_when_charging",
            "backup_notifications_enabled",
            "backup_mode"
        )

        /**
         * Approved conversation configuration property suffixes.
         * Keys in custom_chat_configs.json must end with one of these suffixes.
         */
        val APPROVED_CHAT_CONFIG_PROPERTIES: Set<String> = setOf(
            "wallpaper",
            "pinned_order",
            "muted",
            "custom_theme",
            "notifications",
            "bubble_style",
            "custom_nickname"
        )

        /**
         * Validates whether a given key in custom_chat_configs conforms to `<convId>_<approvedProp>`.
         */
        fun isApprovedChatConfigKey(key: String): Boolean {
            val lower = key.lowercase(Locale.ROOT)
            return APPROVED_CHAT_CONFIG_PROPERTIES.any { prop ->
                lower.endsWith("_$prop") && lower.length > prop.length + 1
            }
        }

        /**
         * Recursively sanitizes a JSONObject against a strict allowlist.
         */
        fun sanitizeJsonObject(
            json: JSONObject,
            allowlist: Set<String>
        ): Map<String, Any> {
            val sanitized = mutableMapOf<String, Any>()
            json.keys().forEach { key ->
                if (allowlist.contains(key)) {
                    when (val v = json.get(key)) {
                        is Boolean -> sanitized[key] = v
                        is Int -> sanitized[key] = v
                        is Long -> sanitized[key] = v
                        is Double -> sanitized[key] = v.toFloat()
                        is String -> if (v.length <= 2048) sanitized[key] = v
                        is JSONObject -> {
                            val nested = sanitizeJsonObject(v, allowlist)
                            if (nested.isNotEmpty()) sanitized[key] = nested
                        }
                    }
                }
            }
            return sanitized
        }
    }
}
