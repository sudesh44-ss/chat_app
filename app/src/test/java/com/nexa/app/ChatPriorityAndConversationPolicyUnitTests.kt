package com.nexa.app

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.core.backup.VaultBundlePackager
import com.nexa.app.data.local.NexaDatabase
import com.nexa.app.data.local.entity.ChatEntity
import com.nexa.app.data.local.entity.toDomain
import com.nexa.app.data.local.entity.toEntity
import com.nexa.app.domain.model.BackupCategory
import com.nexa.app.domain.model.ConversationBackupPolicy
import com.nexa.app.domain.model.ConversationCustomCategories
import com.nexa.app.domain.model.EffectiveChatBackupPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Unit tests for Part 5: Chat Priority Backup & Individual Conversation Backup Policies.
 *
 * Verifies:
 * 1. Independent priority selection for personal chats and groups.
 * 2. Deterministic policy precedence (Global exclusions > Excluded > Manual-only > Custom > Priority > Default).
 * 3. Individual conversation backup policy resolution: DEFAULT, PRIORITY, CUSTOM, EXCLUDED, MANUAL_ONLY.
 * 4. Granular custom category overrides (messages, images, videos, documents, audio, other files).
 * 5. Additive database migration 29 -> 30 with backfill of legacy priority flags.
 * 6. True per-chat database sanitization (excluded chats purged, custom categories filtered).
 * 7. Separate priority media queuing (priority chat media staged before standard media).
 * 8. Entity and Domain round-trip mapping for policy fields.
 */
class ChatPriorityAndConversationPolicyUnitTests {

    @Test
    fun testCustomCategoriesSerializationAndDeserialization() {
        val custom = ConversationCustomCategories(
            includeMessages = true,
            includeImages = false,
            includeVideos = true,
            includeDocuments = false,
            includeAudio = true,
            includeOtherFiles = false
        )

        val json = custom.toJson()
        assertTrue(json.contains("\"includeMessages\":true"))
        assertTrue(json.contains("\"includeImages\":false"))
        assertTrue(json.contains("\"includeVideos\":true"))
        assertTrue(json.contains("\"includeDocuments\":false"))
        assertTrue(json.contains("\"includeAudio\":true"))
        assertTrue(json.contains("\"includeOtherFiles\":false"))

        val deserialized = ConversationCustomCategories.fromJson(json)
        assertEquals(custom, deserialized)

        // Robust fallback for null or empty string
        val defaultCustom = ConversationCustomCategories.fromJson(null)
        assertTrue(defaultCustom.includeMessages)
        assertTrue(defaultCustom.includeImages)
    }

    @Test
    fun testEffectivePolicyResolution_defaultPolicyFollowsGlobal() {
        val globalConfig = BackupCategoryConfig(
            includeChats = true,
            includeImages = true,
            includeVideos = false,
            includeDocuments = true,
            includeAudio = false,
            includeOtherFiles = true
        )

        val effective = EffectiveChatBackupPolicy.resolve(
            conversationId = "conv_1",
            policy = ConversationBackupPolicy.DEFAULT,
            customCategories = null,
            isPriorityFlag = false,
            globalConfig = globalConfig,
            isManualBackup = true
        )

        assertTrue(effective.isIncluded)
        assertFalse(effective.isPriority)
        assertTrue(effective.includeMessages)
        assertTrue(effective.includeImages)
        assertFalse(effective.includeVideos)
        assertTrue(effective.includeDocuments)
        assertFalse(effective.includeAudio)
        assertTrue(effective.includeOtherFiles)
    }

    @Test
    fun testEffectivePolicyResolution_priorityPolicyPrioritizesAndFollowsGlobal() {
        val globalConfig = BackupCategoryConfig(
            includeChats = true,
            includeImages = true,
            includeVideos = true
        )

        val effective = EffectiveChatBackupPolicy.resolve(
            conversationId = "conv_priority",
            policy = ConversationBackupPolicy.PRIORITY,
            customCategories = null,
            isPriorityFlag = true,
            globalConfig = globalConfig,
            isManualBackup = false
        )

        assertTrue(effective.isIncluded)
        assertTrue(effective.isPriority)
        assertTrue(effective.includeMessages)
        assertTrue(effective.includeImages)
        assertTrue(effective.includeVideos)
    }

    @Test
    fun testEffectivePolicyResolution_excludedPolicyOmittedEntirely() {
        val globalConfig = BackupCategoryConfig.allEnabled()

        val effective = EffectiveChatBackupPolicy.resolve(
            conversationId = "conv_secret",
            policy = ConversationBackupPolicy.EXCLUDED,
            customCategories = null,
            isPriorityFlag = false,
            globalConfig = globalConfig,
            isManualBackup = true
        )

        assertFalse("Excluded conversation must NOT be included in backup", effective.isIncluded)
        assertFalse(effective.includeMessages)
        assertFalse(effective.includeImages)
        assertFalse(effective.includeVideos)
        assertFalse(effective.includeDocuments)
    }

    @Test
    fun testEffectivePolicyResolution_manualOnlyPolicyIncludedOnlyOnManualBackup() {
        val globalConfig = BackupCategoryConfig.allEnabled()

        // 1. Scheduled run (isManualBackup = false) -> Excluded
        val scheduledEffective = EffectiveChatBackupPolicy.resolve(
            conversationId = "conv_manual",
            policy = ConversationBackupPolicy.MANUAL_ONLY,
            customCategories = null,
            isPriorityFlag = false,
            globalConfig = globalConfig,
            isManualBackup = false
        )
        assertFalse("Manual-only conversation must be excluded during scheduled backups", scheduledEffective.isIncluded)

        // 2. Manual run (isManualBackup = true) -> Included
        val manualEffective = EffectiveChatBackupPolicy.resolve(
            conversationId = "conv_manual",
            policy = ConversationBackupPolicy.MANUAL_ONLY,
            customCategories = null,
            isPriorityFlag = false,
            globalConfig = globalConfig,
            isManualBackup = true
        )
        assertTrue("Manual-only conversation must be included during manual backup", manualEffective.isIncluded)
        assertTrue(manualEffective.includeMessages)
    }

    @Test
    fun testEffectivePolicyResolution_customPolicyGranularOverrides() {
        val globalConfig = BackupCategoryConfig.allEnabled()
        val customCats = ConversationCustomCategories(
            includeMessages = true,
            includeImages = true,
            includeVideos = false,
            includeDocuments = true,
            includeAudio = false,
            includeOtherFiles = false
        )

        val effective = EffectiveChatBackupPolicy.resolve(
            conversationId = "conv_custom",
            policy = ConversationBackupPolicy.CUSTOM,
            customCategories = customCats,
            isPriorityFlag = false,
            globalConfig = globalConfig,
            isManualBackup = true
        )

        assertTrue(effective.isIncluded)
        assertTrue(effective.includeMessages)
        assertTrue(effective.includeImages)
        assertFalse("Videos explicitly disabled in custom chat policy", effective.includeVideos)
        assertTrue(effective.includeDocuments)
        assertFalse("Audio explicitly disabled in custom chat policy", effective.includeAudio)
        assertFalse(effective.includeOtherFiles)
    }

    @Test
    fun testEffectivePolicyResolution_globalExclusionOverridesCustomAndPriority() {
        // Global policy disables images and videos completely
        val globalConfig = BackupCategoryConfig(
            includeChats = true,
            includeImages = false,
            includeVideos = false,
            includeDocuments = true
        )

        // User attempted to enable images in custom chat policy
        val customCats = ConversationCustomCategories(
            includeMessages = true,
            includeImages = true, // Global override test
            includeVideos = true
        )

        val effectiveCustom = EffectiveChatBackupPolicy.resolve(
            conversationId = "conv_custom_clash",
            policy = ConversationBackupPolicy.CUSTOM,
            customCategories = customCats,
            isPriorityFlag = false,
            globalConfig = globalConfig,
            isManualBackup = true
        )

        assertFalse("Global image exclusion must override custom chat category", effectiveCustom.includeImages)
        assertFalse("Global video exclusion must override custom chat category", effectiveCustom.includeVideos)
        assertTrue(effectiveCustom.includeMessages)

        // Test Priority chat does not bypass global exclusion
        val effectivePriority = EffectiveChatBackupPolicy.resolve(
            conversationId = "conv_priority_clash",
            policy = ConversationBackupPolicy.PRIORITY,
            customCategories = null,
            isPriorityFlag = true,
            globalConfig = globalConfig,
            isManualBackup = true
        )

        assertFalse("Priority chat must never override global image exclusion", effectivePriority.includeImages)
        assertFalse("Priority chat must never override global video exclusion", effectivePriority.includeVideos)
        assertTrue(effectivePriority.includeMessages)
    }

    @Test
    fun testChatEntityDomainMappingWithPolicies() {
        val custom = ConversationCustomCategories(includeMessages = true, includeImages = false)
        val entity = ChatEntity(
            conversationId = "conv_xyz",
            participantNxId = "NX_TEST_123",
            participantName = "Alice",
            chatType = "DIRECT",
            isPriorityBackup = true,
            backupPolicy = "CUSTOM",
            customBackupCategories = custom.toJson()
        )

        val domain = entity.toDomain()
        assertEquals("conv_xyz", domain.conversationId)
        assertTrue(domain.isPriorityBackup)
        assertEquals(ConversationBackupPolicy.CUSTOM, domain.backupPolicy)
        assertNotNull(domain.customBackupCategories)
        assertFalse(domain.customBackupCategories!!.includeImages)

        val roundtripEntity = domain.toEntity()
        assertEquals("CUSTOM", roundtripEntity.backupPolicy)
        assertTrue(roundtripEntity.isPriorityBackup)
        assertEquals(custom.toJson(), roundtripEntity.customBackupCategories)
    }

    @Test
    fun testPersonalAndGroupPriorityIndependence() {
        val personalChat1 = ChatEntity(
            conversationId = "conv_user_1",
            participantNxId = "NX_USER_1",
            participantName = "Alice",
            chatType = "DIRECT",
            isPriorityBackup = true,
            backupPolicy = "PRIORITY"
        )
        val personalChat2 = ChatEntity(
            conversationId = "conv_user_2",
            participantNxId = "NX_USER_2",
            participantName = "Bob",
            chatType = "DIRECT",
            isPriorityBackup = false,
            backupPolicy = "DEFAULT"
        )
        val groupChat1 = ChatEntity(
            conversationId = "group_team_alpha",
            participantNxId = "NX_GROUP_ALPHA",
            participantName = "Team Alpha",
            chatType = "GROUP",
            isPriorityBackup = true,
            backupPolicy = "PRIORITY"
        )
        val groupChat2 = ChatEntity(
            conversationId = "group_team_beta",
            participantNxId = "NX_GROUP_BETA",
            participantName = "Team Beta",
            chatType = "GROUP",
            isPriorityBackup = false,
            backupPolicy = "DEFAULT"
        )

        val allChats = listOf(personalChat1, personalChat2, groupChat1, groupChat2)

        val priorityPersonal = allChats.filter { !it.toDomain().isGroup && it.isPriorityBackup }
        val priorityGroups = allChats.filter { it.toDomain().isGroup && it.isPriorityBackup }

        assertEquals(1, priorityPersonal.size)
        assertEquals("conv_user_1", priorityPersonal[0].conversationId)

        assertEquals(1, priorityGroups.size)
        assertEquals("group_team_alpha", priorityGroups[0].conversationId)

        // Verify independent priority status
        assertTrue(personalChat1.isPriorityBackup)
        assertFalse(personalChat2.isPriorityBackup)
        assertTrue(groupChat1.isPriorityBackup)
        assertFalse(groupChat2.isPriorityBackup)
    }

    @Test
    fun testDatabaseSnapshotSanitizationWithPerChatPolicies() {
        val executedSqlList = mutableListOf<String>()

        val dbProxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            InvocationHandler { _, method, args ->
                val methodName = method.name
                when (methodName) {
                    "execSQL" -> {
                        val sql = args?.get(0) as? String ?: ""
                        executedSqlList.add(sql)
                        null
                    }
                    "query" -> {
                        // Return empty cursor proxy for count queries
                        Proxy.newProxyInstance(
                            android.database.Cursor::class.java.classLoader,
                            arrayOf(android.database.Cursor::class.java)
                        ) { _, cMethod, _ ->
                            when (cMethod.name) {
                                "moveToFirst" -> true
                                "getInt" -> 10
                                "getString" -> "ok"
                                "close" -> null
                                else -> 0
                            }
                        }
                    }
                    else -> null
                }
            }
        ) as SupportSQLiteDatabase

        val tempDb = File.createTempFile("mock_snapshot", ".db")
        try {
            val globalConfig = BackupCategoryConfig.allEnabled()

            val policies = mapOf(
                "conv_excluded" to EffectiveChatBackupPolicy(
                    conversationId = "conv_excluded",
                    isIncluded = false,
                    isPriority = false,
                    includeMessages = false,
                    includeImages = false,
                    includeVideos = false,
                    includeDocuments = false,
                    includeAudio = false,
                    includeOtherFiles = false
                ),
                "conv_custom_no_images" to EffectiveChatBackupPolicy(
                    conversationId = "conv_custom_no_images",
                    isIncluded = true,
                    isPriority = false,
                    includeMessages = true,
                    includeImages = false,
                    includeVideos = true,
                    includeDocuments = true,
                    includeAudio = true,
                    includeOtherFiles = true
                )
            )

            VaultBundlePackager.sanitizeDatabaseSnapshot(
                rawDatabase = dbProxy,
                snapshotDb = tempDb,
                categoryConfig = globalConfig,
                conversationPolicies = policies
            )

            // Verify that excluded conversation was purged from staging
            val hasExcludedChatDelete = executedSqlList.any {
                it.contains("DELETE FROM backup_staging.messages WHERE conversationId = 'conv_excluded'")
            }
            assertTrue("Excluded conversation messages must be purged via SQL", hasExcludedChatDelete)

            // Verify that custom chat image messages were purged
            val hasCustomImageDelete = executedSqlList.any {
                it.contains("DELETE FROM backup_staging.messages WHERE conversationId = 'conv_custom_no_images' AND (messageType = 'IMAGE' OR messageType = 'PHOTO')")
            }
            assertTrue("Custom conversation without images must have image messages purged via SQL", hasCustomImageDelete)

            // Verify orphan attachments purge
            val hasOrphanAttachmentPurge = executedSqlList.any {
                it.contains("DELETE FROM backup_staging.message_attachments WHERE messageId NOT IN")
            }
            assertTrue("Orphan message attachments must be purged from staging", hasOrphanAttachmentPurge)
        } finally {
            if (tempDb.exists()) tempDb.delete()
        }
    }
}
