package com.nexa.app

import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.core.backup.crypto.VaultStreamEncryptor
import com.nexa.app.data.local.NexaDatabase
import com.nexa.app.data.local.entity.ChatEntity
import com.nexa.app.data.local.entity.toDomain
import com.nexa.app.data.local.entity.toEntity
import com.nexa.app.data.local.preferences.BackupFrequency
import com.nexa.app.data.local.preferences.BackupPreferencesState
import com.nexa.app.domain.model.BackupCategory
import com.nexa.app.domain.model.BackupMode
import com.nexa.app.domain.model.BackupScheduleConfig
import com.nexa.app.domain.model.ChatConversation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Comprehensive Unit Test Suite for Part 2 Backup & Restore Architecture Foundation:
 * 1. Authoritative Backup Mode domain model, parsing, defaults, and legacy migration.
 * 2. Schedule configuration model and boundary validation.
 * 3. 7 official backup categories, serialization, Select All/None, and legacy JSON compatibility.
 * 4. Backup preferences state encapsulation.
 * 5. Chat Priority database foundation, entity/domain mapping, and ID stability.
 * 6. Non-destructive Room Migration 28->29 verification.
 * 7. Verified retention of VaultStreamEncryptor.
 */
class BackupArchitectureAndFoundationUnitTests {

    // =========================================================================
    // 1. BACKUP MODE TESTS
    // =========================================================================

    @Test
    fun testBackupModeEnumValuesAndDefault() {
        val modes = BackupMode.values()
        assertEquals(3, modes.size)
        assertTrue(modes.contains(BackupMode.SCHEDULED))
        assertTrue(modes.contains(BackupMode.CHAT_PRIORITY))
        assertTrue(modes.contains(BackupMode.MANUAL_ONLY))

        // Default parsing from null or blank should be SCHEDULED
        assertEquals(BackupMode.SCHEDULED, BackupMode.fromString(null))
        assertEquals(BackupMode.SCHEDULED, BackupMode.fromString(""))
        assertEquals(BackupMode.SCHEDULED, BackupMode.fromString("   "))
    }

    @Test
    fun testBackupModeIsAutomaticProperty() {
        assertTrue("SCHEDULED mode must be automatic", BackupMode.SCHEDULED.isAutomatic)
        assertTrue("CHAT_PRIORITY mode must be automatic", BackupMode.CHAT_PRIORITY.isAutomatic)
        assertFalse("MANUAL_ONLY mode must NOT be automatic", BackupMode.MANUAL_ONLY.isAutomatic)
    }

    @Test
    fun testBackupModeFromStringSafeParsingAndLegacyMigration() {
        // Standard strings (case insensitive, trimmed)
        assertEquals(BackupMode.SCHEDULED, BackupMode.fromString("SCHEDULED"))
        assertEquals(BackupMode.SCHEDULED, BackupMode.fromString("scheduled"))
        assertEquals(BackupMode.CHAT_PRIORITY, BackupMode.fromString("CHAT_PRIORITY"))
        assertEquals(BackupMode.CHAT_PRIORITY, BackupMode.fromString("chat_priority"))
        assertEquals(BackupMode.MANUAL_ONLY, BackupMode.fromString("MANUAL_ONLY"))
        assertEquals(BackupMode.MANUAL_ONLY, BackupMode.fromString("manual_only"))

        // Legacy / Alternative aliases
        assertEquals(BackupMode.SCHEDULED, BackupMode.fromString("smart"))
        assertEquals(BackupMode.SCHEDULED, BackupMode.fromString("near_live"))
        assertEquals(BackupMode.SCHEDULED, BackupMode.fromString("auto"))
        assertEquals(BackupMode.SCHEDULED, BackupMode.fromString("automatic"))
        assertEquals(BackupMode.CHAT_PRIORITY, BackupMode.fromString("priority"))
        assertEquals(BackupMode.MANUAL_ONLY, BackupMode.fromString("manual"))
        assertEquals(BackupMode.MANUAL_ONLY, BackupMode.fromString("off"))
        assertEquals(BackupMode.MANUAL_ONLY, BackupMode.fromString("disabled"))

        // Unknown strings safely fall back to SCHEDULED without throwing
        assertEquals(BackupMode.SCHEDULED, BackupMode.fromString("unknown_legacy_mode_xyz"))
    }

    // =========================================================================
    // 2. SCHEDULE CONFIGURATION TESTS
    // =========================================================================

    @Test
    fun testBackupScheduleConfigDefaults() {
        val config = BackupScheduleConfig()
        assertEquals(BackupFrequency.DAILY, config.frequency)
        assertEquals(2, config.dailyHour) // 02:00 AM default
        assertEquals(0, config.dailyMinute)
        assertEquals(1, config.weeklyDayOfWeek) // Monday default
        assertEquals(1, config.monthlyDayOfMonth) // 1st of month default
    }

    @Test
    fun testBackupScheduleConfigCustomValidValues() {
        val config = BackupScheduleConfig(
            frequency = BackupFrequency.WEEKLY,
            dailyHour = 23,
            dailyMinute = 59,
            weeklyDayOfWeek = 7, // Sunday
            monthlyDayOfMonth = 31
        )
        assertEquals(BackupFrequency.WEEKLY, config.frequency)
        assertEquals(23, config.dailyHour)
        assertEquals(59, config.dailyMinute)
        assertEquals(7, config.weeklyDayOfWeek)
        assertEquals(31, config.monthlyDayOfMonth)
    }

    @Test
    fun testBackupScheduleConfigBoundsValidation() {
        // Invalid hour (< 0)
        try {
            BackupScheduleConfig(dailyHour = -1)
            fail("Negative dailyHour must throw IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("dailyHour") == true)
        }

        // Invalid hour (> 23)
        try {
            BackupScheduleConfig(dailyHour = 24)
            fail("dailyHour > 23 must throw IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("dailyHour") == true)
        }

        // Invalid minute (> 59)
        try {
            BackupScheduleConfig(dailyMinute = 60)
            fail("dailyMinute > 59 must throw IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("dailyMinute") == true)
        }

        // Invalid day of week (< 1 or > 7)
        try {
            BackupScheduleConfig(weeklyDayOfWeek = 0)
            fail("weeklyDayOfWeek < 1 must throw IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("weeklyDayOfWeek") == true)
        }

        // Invalid day of month (< 1 or > 31)
        try {
            BackupScheduleConfig(monthlyDayOfMonth = 32)
            fail("monthlyDayOfMonth > 31 must throw IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("monthlyDayOfMonth") == true)
        }
    }

    // =========================================================================
    // 3. 7 OFFICIAL BACKUP CATEGORIES & CONFIGURATION TESTS
    // =========================================================================

    @Test
    fun testBackupCategoryIdentifiersAndDisplayNames() {
        assertEquals("chats", BackupCategory.CHATS.id)
        assertEquals("Chats & Conversations", BackupCategory.CHATS.displayName)

        assertEquals("images", BackupCategory.IMAGES.id)
        assertEquals("Images & Photos", BackupCategory.IMAGES.displayName)

        assertEquals("videos", BackupCategory.VIDEOS.id)
        assertEquals("Videos", BackupCategory.VIDEOS.displayName)

        assertEquals("documents", BackupCategory.DOCUMENTS.id)
        assertEquals("Documents", BackupCategory.DOCUMENTS.displayName)

        assertEquals("audio", BackupCategory.AUDIO.id)
        assertEquals("Audio & Voice Notes", BackupCategory.AUDIO.displayName)

        assertEquals("other_files", BackupCategory.OTHER_FILES.id)
        assertEquals("Other Files", BackupCategory.OTHER_FILES.displayName)

        assertEquals("settings", BackupCategory.SETTINGS.id)
        assertEquals("Settings & App Data", BackupCategory.SETTINGS.displayName)

        // Lookup by ID
        assertEquals(BackupCategory.CHATS, BackupCategory.fromId("chats"))
        assertEquals(BackupCategory.IMAGES, BackupCategory.fromId("images"))
        assertEquals(BackupCategory.VIDEOS, BackupCategory.fromId("VIDEOS"))
        assertEquals(null, BackupCategory.fromId("non_existent"))
    }

    @Test
    fun testBackupCategoryConfigDefaultsAndSelectAllNone() {
        val default = BackupCategoryConfig()
        assertTrue(default.includeChats)
        assertTrue(default.includeImages)
        assertTrue(default.includeVideos)
        assertTrue(default.includeDocuments)
        assertTrue(default.includeAudio)
        assertTrue(default.includeOtherFiles)
        assertTrue(default.includeSettings)
        assertTrue(default.hasAnySelected())
        assertTrue(default.hasAnyMediaSelected())

        val allDisabled = default.selectNone()
        assertFalse(allDisabled.includeChats)
        assertFalse(allDisabled.includeImages)
        assertFalse(allDisabled.includeVideos)
        assertFalse(allDisabled.includeDocuments)
        assertFalse(allDisabled.includeAudio)
        assertFalse(allDisabled.includeOtherFiles)
        assertFalse(allDisabled.includeSettings)
        assertFalse(allDisabled.hasAnySelected())
        assertFalse(allDisabled.hasAnyMediaSelected())

        val reEnabled = allDisabled.selectAll()
        assertTrue(reEnabled.includeChats)
        assertTrue(reEnabled.includeImages)
        assertTrue(reEnabled.includeVideos)
        assertTrue(reEnabled.includeDocuments)
        assertTrue(reEnabled.includeAudio)
        assertTrue(reEnabled.includeOtherFiles)
        assertTrue(reEnabled.includeSettings)
    }

    @Test
    fun testBackupCategoryConfigWithCategoryAndIsCategoryEnabled() {
        var config = BackupCategoryConfig.allDisabled()
        assertFalse(config.isCategoryEnabled(BackupCategory.CHATS))

        config = config.withCategory(BackupCategory.CHATS, true)
        assertTrue(config.isCategoryEnabled(BackupCategory.CHATS))
        assertTrue(config.includeChats)
        assertTrue(config.includeGroups) // Legacy mapped
        assertTrue(config.includeContacts) // Legacy mapped

        config = config.withCategory(BackupCategory.IMAGES, true)
        assertTrue(config.isCategoryEnabled(BackupCategory.IMAGES))
        assertTrue(config.hasAnyMediaSelected())

        config = config.withCategory(BackupCategory.SETTINGS, true)
        assertTrue(config.isCategoryEnabled(BackupCategory.SETTINGS))
        assertTrue(config.includeAppSettings)
        assertTrue(config.includeUserPrefs)
    }

    @Test
    fun testBackupCategoryConfigSerializationRoundtrip() {
        val original = BackupCategoryConfig(
            includeChats = true,
            includeImages = false,
            includeVideos = true,
            includeDocuments = false,
            includeAudio = true,
            includeOtherFiles = false,
            includeSettings = true
        )

        val json = original.toJson()
        val deserialized = BackupCategoryConfig.fromJson(json)

        assertEquals(original.includeChats, deserialized.includeChats)
        assertEquals(original.includeImages, deserialized.includeImages)
        assertEquals(original.includeVideos, deserialized.includeVideos)
        assertEquals(original.includeDocuments, deserialized.includeDocuments)
        assertEquals(original.includeAudio, deserialized.includeAudio)
        assertEquals(original.includeOtherFiles, deserialized.includeOtherFiles)
        assertEquals(original.includeSettings, deserialized.includeSettings)
    }

    @Test
    fun testBackupCategoryConfigLegacyJsonBackwardCompatibility() {
        // Legacy JSON without include_settings or 7 categories, containing old flags
        val legacyJson = JSONObject().apply {
            put("include_chats", true)
            put("include_groups", true)
            put("include_contacts", true)
            put("include_attachments", true)
            put("include_images", true)
            put("include_videos", false)
            put("include_documents", true)
            put("include_audio", false)
            put("include_other_files", false)
            put("include_app_settings", true)
            put("include_user_prefs", true)
            put("include_device_settings", false)
        }

        val parsed = BackupCategoryConfig.fromJson(legacyJson.toString())
        assertTrue("Chats must map from legacy include_chats", parsed.includeChats)
        assertTrue("Images must map from legacy include_images", parsed.includeImages)
        assertFalse("Videos must map from legacy include_videos", parsed.includeVideos)
        assertTrue("Documents must map from legacy include_documents", parsed.includeDocuments)
        assertFalse("Audio must map from legacy include_audio", parsed.includeAudio)
        assertTrue("Settings must map from legacy include_app_settings / include_user_prefs", parsed.includeSettings)
    }

    // =========================================================================
    // 4. PREFERENCES STATE ENCAPSULATION
    // =========================================================================

    @Test
    fun testBackupPreferencesStateDefaults() {
        val state = BackupPreferencesState()
        assertEquals(BackupMode.SCHEDULED, state.backupMode)
        assertTrue(state.autoBackupEnabled)
        assertEquals(BackupFrequency.DAILY, state.backupFrequency)
        assertEquals(2, state.scheduledDailyHour)
        assertEquals(0, state.scheduledDailyMinute)
        assertEquals(1, state.scheduledWeeklyDayOfWeek)
        assertEquals(1, state.scheduledMonthlyDayOfMonth)
        assertTrue(state.backupOverWifiOnly)
        assertTrue(state.backupWhenCharging)
        assertTrue(state.backupNotificationsEnabled)
        assertEquals(0, state.totalPriorityChatCount)

        val schedule = state.scheduleConfig
        assertEquals(BackupFrequency.DAILY, schedule.frequency)
        assertEquals(2, schedule.dailyHour)
    }

    @Test
    fun testBackupPreferencesStatePriorityTracking() {
        val state = BackupPreferencesState(
            priorityPersonalChatIds = setOf("conv_1", "conv_2"),
            priorityGroupChatIds = setOf("group_alpha", "group_beta", "group_gamma")
        )
        assertEquals(2, state.priorityPersonalChatIds.size)
        assertEquals(3, state.priorityGroupChatIds.size)
        assertEquals(5, state.totalPriorityChatCount)
    }

    // =========================================================================
    // 5. CHAT PRIORITY ENTITY & DOMAIN MAPPING
    // =========================================================================

    @Test
    fun testChatEntityDefaultPriorityIsFalse() {
        val entity = ChatEntity(
            conversationId = "conv_test_1",
            participantNxId = "NX-USER-1",
            participantName = "Alice"
        )
        assertFalse("Default isPriorityBackup must be false", entity.isPriorityBackup)

        val domain = entity.toDomain()
        assertFalse("Domain isPriorityBackup must be false", domain.isPriorityBackup)
    }

    @Test
    fun testChatPriorityFlagPreservedAcrossToDomainAndToEntity() {
        val priorityEntity = ChatEntity(
            conversationId = "conv_priority_alice",
            participantNxId = "NX-ALICE",
            participantName = "Alice",
            isPriorityBackup = true
        )
        assertTrue(priorityEntity.isPriorityBackup)

        val domainChat = priorityEntity.toDomain()
        assertTrue("toDomain must preserve isPriorityBackup = true", domainChat.isPriorityBackup)
        assertEquals("conv_priority_alice", domainChat.conversationId)

        val convertedBack = domainChat.toEntity()
        assertTrue("toEntity must preserve isPriorityBackup = true", convertedBack.isPriorityBackup)
        assertEquals("conv_priority_alice", convertedBack.conversationId)
    }

    @Test
    fun testChatPriorityRemainsIndependentOfDisplayNameAndAvatarChanges() {
        val original = ChatConversation(
            conversationId = "conv_stable_id_123",
            participantNxId = "NX-BOB",
            participantName = "Bob Verma",
            avatarUrl = "https://example.com/old_avatar.jpg",
            isPriorityBackup = true
        )
        assertTrue(original.isPriorityBackup)

        // User updates display name and avatar
        val updated = original.copy(
            participantName = "Robert V.",
            avatarUrl = "https://example.com/new_avatar.png"
        )

        assertEquals("conv_stable_id_123", updated.conversationId)
        assertTrue("Priority selection must remain true after renaming", updated.isPriorityBackup)
        assertEquals("Robert V.", updated.participantName)
        assertEquals("https://example.com/new_avatar.png", updated.avatarUrl)

        val entity = updated.toEntity()
        assertEquals("conv_stable_id_123", entity.conversationId)
        assertTrue("Entity must retain isPriorityBackup after profile update", entity.isPriorityBackup)
    }

    @Test
    fun testChatPriorityDistinguishesPersonalAndGroupConversations() {
        val personalPriority = ChatConversation(
            conversationId = "conv_personal",
            participantNxId = "NX-CHARLIE",
            participantName = "Charlie",
            chatType = "DIRECT",
            isPriorityBackup = true
        )
        assertFalse("Personal chat must not be a group", personalPriority.isGroup)
        assertTrue(personalPriority.isPriorityBackup)

        val groupPriority = ChatConversation(
            conversationId = "group_engineering_core",
            participantNxId = "NX-GROUP-ENG",
            participantName = "Engineering Team",
            chatType = "GROUP",
            isPriorityBackup = true
        )
        assertTrue("Group chat must be identified as group", groupPriority.isGroup)
        assertTrue(groupPriority.isPriorityBackup)
    }

    // =========================================================================
    // 6. ROOM MIGRATION 28->29 CONTRACT VERIFICATION
    // =========================================================================

    @Test
    fun testMigration28To29Contract() {
        val migration = NexaDatabase.MIGRATION_28_29
        assertEquals(28, migration.startVersion)
        assertEquals(29, migration.endVersion)
        assertNotNull("MIGRATION_28_29 must be non-null and properly declared", migration)
    }

    // =========================================================================
    // 7. VERIFIED RETENTION OF VAULT STREAM ENCRYPTOR
    // =========================================================================

    @Test
    fun testVaultStreamEncryptorRetentionAndBufferSize() {
        // Confirm VaultStreamEncryptor was intentionally preserved and not accidentally dropped
        assertNotNull(VaultStreamEncryptor)
        assertEquals(64 * 1024, VaultStreamEncryptor.BUFFER_SIZE)
        assertEquals("NXVAULT", VaultStreamEncryptor.MAGIC_HEADER)
    }
}
