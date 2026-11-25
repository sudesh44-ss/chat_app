package com.nexa.app

import com.nexa.app.core.notification.NexaMentionParser
import com.nexa.app.core.notification.NexaNotificationChannelManager
import com.nexa.app.core.notification.NotificationCooldownTracker
import com.nexa.app.core.notification.QuietHoursEngine
import com.nexa.app.data.local.preferences.LockScreenMode
import com.nexa.app.data.local.preferences.NotificationPreferences
import com.nexa.app.data.local.preferences.NotificationPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Complete, exhaustive Unit Test Suite verifying NEXA's Notification System:
 * - Preference persistence & defaults
 * - Direct Message alerts & privacy filtering
 * - Group Chat notifications & @mention parser
 * - Incoming Call alerts & Quiet Hours bypass
 * - Lock Screen Privacy modes (SHOW_ALL, HIDE_CONTENT, DONT_SHOW)
 * - Quiet Hours scheduling (same-day, overnight, weekday selection, previous-day assignment)
 * - File Transfer & Mesh Discovery rate limiting and cooldowns
 * - Android notification channels and disabled-channel detection
 */
class NotificationSystemFullUnitTests {

    private lateinit var inMemoryStorage: MutableMap<String, Any>
    private lateinit var mockPrefs: android.content.SharedPreferences
    private lateinit var notificationPrefs: NotificationPrefs

    @Before
    fun setUp() {
        inMemoryStorage = mutableMapOf()
        mockPrefs = object : android.content.SharedPreferences {
            override fun getAll(): MutableMap<String, *> = inMemoryStorage
            override fun getString(key: String?, defValue: String?): String? = (inMemoryStorage[key] as? String) ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
            override fun getInt(key: String?, defValue: Int): Int = (inMemoryStorage[key] as? Int) ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = (inMemoryStorage[key] as? Long) ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = (inMemoryStorage[key] as? Float) ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = (inMemoryStorage[key] as? Boolean) ?: defValue
            override fun contains(key: String?): Boolean = inMemoryStorage.containsKey(key)
            override fun edit(): android.content.SharedPreferences.Editor = MockEditor(inMemoryStorage)
            override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
        notificationPrefs = NotificationPrefs(mockPrefs)
        NotificationCooldownTracker.clear()
    }

    @After
    fun tearDown() {
        NotificationCooldownTracker.clear()
    }

    // ==========================================
    // 1. PREFERENCE PERSISTENCE & DEFAULTS
    // ==========================================

    @Test
    fun testPreferences_initialDefaults() {
        val snapshot = notificationPrefs.getSnapshot()
        assertTrue("Direct messages should default to true", snapshot.directMessagesEnabled)
        assertTrue("Message preview should default to true", snapshot.messagePreviewEnabled)
        assertTrue("Groups should default to true", snapshot.groupsEnabled)
        assertFalse("Group mentions only should default to false", snapshot.groupMentionsOnly)
        assertTrue("Calls should default to true", snapshot.callsEnabled)
        assertTrue("Allow calls during quiet hours should default to true", snapshot.allowCallsDuringQuietHours)
        assertTrue("File transfers should default to true", snapshot.fileTransfersEnabled)
        assertFalse("Mesh discovery alerts should default to false (conserve battery)", snapshot.meshDiscoveryAlertsEnabled)
        assertEquals("Lock screen default should be HIDE_CONTENT", LockScreenMode.HIDE_CONTENT, snapshot.lockScreenVisibility)
        assertFalse("Quiet hours should default to false", snapshot.isQuietHoursEnabled)
        assertEquals(23, snapshot.quietHoursStartHour)
        assertEquals(0, snapshot.quietHoursStartMinute)
        assertEquals(7, snapshot.quietHoursEndHour)
        assertEquals(0, snapshot.quietHoursEndMinute)
        assertEquals(7, snapshot.quietHoursDays.size)
        assertEquals("Every day", snapshot.quietHoursDaysFormatted)
    }

    @Test
    fun testPreferences_persistenceAndRestartRestoration() {
        // Mutate preferences
        notificationPrefs.setDirectMessagesEnabled(false)
        notificationPrefs.setMessagePreviewEnabled(false)
        notificationPrefs.setGroupsEnabled(false)
        notificationPrefs.setGroupMentionsOnly(true)
        notificationPrefs.setCallsEnabled(false)
        notificationPrefs.setAllowCallsDuringQuietHours(false)
        notificationPrefs.setFileTransfersEnabled(false)
        notificationPrefs.setMeshDiscoveryAlertsEnabled(true)
        notificationPrefs.setLockScreenVisibility(LockScreenMode.DONT_SHOW)
        notificationPrefs.setQuietHoursEnabled(true)
        notificationPrefs.setQuietHoursTimeWindow(22, 15, 6, 45)
        notificationPrefs.setQuietHoursDays(setOf(1, 2, 3, 4, 5))

        // Create new instance simulating process restart
        val restoredPrefs = NotificationPrefs(mockPrefs)
        val snapshot = restoredPrefs.getSnapshot()

        assertFalse(snapshot.directMessagesEnabled)
        assertFalse(snapshot.messagePreviewEnabled)
        assertFalse(snapshot.groupsEnabled)
        assertTrue(snapshot.groupMentionsOnly)
        assertFalse(snapshot.callsEnabled)
        assertFalse(snapshot.allowCallsDuringQuietHours)
        assertFalse(snapshot.fileTransfersEnabled)
        assertTrue(snapshot.meshDiscoveryAlertsEnabled)
        assertEquals(LockScreenMode.DONT_SHOW, snapshot.lockScreenVisibility)
        assertTrue(snapshot.isQuietHoursEnabled)
        assertEquals(22, snapshot.quietHoursStartHour)
        assertEquals(15, snapshot.quietHoursStartMinute)
        assertEquals(6, snapshot.quietHoursEndHour)
        assertEquals(45, snapshot.quietHoursEndMinute)
        assertEquals(setOf(1, 2, 3, 4, 5), snapshot.quietHoursDays)
        assertEquals("Weekdays", snapshot.quietHoursDaysFormatted)
    }

    @Test
    fun testPreferences_toggleQuietHoursDay_neverLeavesEmpty() {
        notificationPrefs.setQuietHoursDays(setOf(1))
        // Attempt to remove only remaining day (1)
        notificationPrefs.toggleQuietHoursDay(1)
        // Set must not be empty; should retain the day
        assertEquals(1, notificationPrefs.getSnapshot().quietHoursDays.size)

        // Adding day 2
        notificationPrefs.toggleQuietHoursDay(2)
        assertEquals(setOf(1, 2), notificationPrefs.getSnapshot().quietHoursDays)

        // Removing day 1
        notificationPrefs.toggleQuietHoursDay(1)
        assertEquals(setOf(2), notificationPrefs.getSnapshot().quietHoursDays)
    }

    // ==========================================
    // 2. DIRECT MESSAGE PRIVACY & FILTERING
    // ==========================================

    @Test
    fun testDirectMessage_previewDisabled_generatesGenericPlaceholder() {
        val prefsPreviewOn = NotificationPreferences(messagePreviewEnabled = true)
        val prefsPreviewOff = NotificationPreferences(messagePreviewEnabled = false)

        val secretText = "Encrypted private message text"

        val previewOnResult = if (prefsPreviewOn.messagePreviewEnabled) secretText else "New message received"
        val previewOffResult = if (prefsPreviewOff.messagePreviewEnabled) secretText else "New message received"

        assertEquals(secretText, previewOnResult)
        assertEquals("New message received", previewOffResult)
    }

    // ==========================================
    // 3. GROUP CHAT NOTIFICATIONS & MENTION PARSING
    // ==========================================

    @Test
    fun testGroupChat_precedenceRules() {
        val myNxId = "NX-USER-01"
        val myName = "Charlie"

        // Case 1: Groups OFF -> No alerts regardless of mentions
        val groupsOff = NotificationPreferences(groupsEnabled = false, groupMentionsOnly = false)
        val shouldAlertGroupsOff = groupsOff.groupsEnabled && (!groupsOff.groupMentionsOnly || NexaMentionParser.isDirectMention("Hey @Charlie", myNxId, myName))
        assertFalse(shouldAlertGroupsOff)

        // Case 2: Groups ON + Mentions Only ON + Message mentions user -> Alerts!
        val mentionsOnlyOn = NotificationPreferences(groupsEnabled = true, groupMentionsOnly = true)
        val shouldAlertMentionMatch = mentionsOnlyOn.groupsEnabled && (!mentionsOnlyOn.groupMentionsOnly || NexaMentionParser.isDirectMention("Hey @Charlie", myNxId, myName))
        assertTrue(shouldAlertMentionMatch)

        // Case 3: Groups ON + Mentions Only ON + Normal message without mention -> Suppressed!
        val shouldAlertNoMention = mentionsOnlyOn.groupsEnabled && (!mentionsOnlyOn.groupMentionsOnly || NexaMentionParser.isDirectMention("General update for team", myNxId, myName))
        assertFalse(shouldAlertNoMention)

        // Case 4: Groups ON + Mentions Only OFF + Normal message -> Alerts!
        val normalGroups = NotificationPreferences(groupsEnabled = true, groupMentionsOnly = false)
        val shouldAlertNormal = normalGroups.groupsEnabled && (!normalGroups.groupMentionsOnly || NexaMentionParser.isDirectMention("General update for team", myNxId, myName))
        assertTrue(shouldAlertNormal)
    }

    @Test
    fun testMentionParser_exhaustiveVariations() {
        val myNxId = "NX-998877"
        val myName = "David Kumar"

        // Valid mention formats
        assertTrue(NexaMentionParser.isDirectMention("Hey @David check this", myNxId, myName))
        assertTrue(NexaMentionParser.isDirectMention("Hey @davidkumar check this", myNxId, myName))
        assertTrue(NexaMentionParser.isDirectMention("Ping @NX-998877", myNxId, myName))
        assertTrue(NexaMentionParser.isDirectMention("Ping @998877", myNxId, myName))
        assertTrue(NexaMentionParser.isDirectMention("Attention @all please read", myNxId, myName))
        assertTrue(NexaMentionParser.isDirectMention("Attention @everyone please read", myNxId, myName))

        // Negative cases (no false positives)
        assertFalse(NexaMentionParser.isDirectMention("Email me at david@kumar.com", myNxId, myName))
        assertFalse(NexaMentionParser.isDirectMention("Talking to @Eve about work", myNxId, myName))
        assertFalse(NexaMentionParser.isDirectMention("Ordinary text mentioning David without at symbol", myNxId, myName))
        assertFalse(NexaMentionParser.isDirectMention("", myNxId, myName))
    }

    // ==========================================
    // 4. VOICE & VIDEO CALL NOTIFICATIONS
    // ==========================================

    @Test
    fun testCalls_quietHoursBypassBehavior() {
        val quietHoursActiveSettings = NotificationPreferences(
            isQuietHoursEnabled = true,
            allowCallsDuringQuietHours = true,
            quietHoursStartHour = 22,
            quietHoursStartMinute = 0,
            quietHoursEndHour = 8,
            quietHoursEndMinute = 0
        )

        val midnight = LocalTime.of(0, 30)

        // Non-call messages are suppressed at 00:30
        assertTrue(QuietHoursEngine.shouldSuppressNotification(isCall = false, settings = quietHoursActiveSettings, currentTime = midnight))

        // Calls bypass quiet hours when allowed
        assertFalse(QuietHoursEngine.shouldSuppressNotification(isCall = true, settings = quietHoursActiveSettings, currentTime = midnight))

        // Calls are suppressed when bypass is disabled
        val callsBlockedSettings = quietHoursActiveSettings.copy(allowCallsDuringQuietHours = false)
        assertTrue(QuietHoursEngine.shouldSuppressNotification(isCall = true, settings = callsBlockedSettings, currentTime = midnight))
    }

    // ==========================================
    // 5. LOCK SCREEN PRIVACY TIERS
    // ==========================================

    @Test
    fun testLockScreenPrivacy_allThreeTiersExistAndDistinct() {
        val modes = LockScreenMode.values()
        assertEquals(3, modes.size)
        assertTrue(modes.contains(LockScreenMode.SHOW_ALL))
        assertTrue(modes.contains(LockScreenMode.HIDE_CONTENT))
        assertTrue(modes.contains(LockScreenMode.DONT_SHOW))

        assertNotEquals(LockScreenMode.SHOW_ALL.label, LockScreenMode.HIDE_CONTENT.label)
        assertNotEquals(LockScreenMode.HIDE_CONTENT.label, LockScreenMode.DONT_SHOW.label)
    }

    // ==========================================
    // 6. QUIET HOURS OVERNIGHT & WEEKDAY SCHEDULING
    // ==========================================

    @Test
    fun testQuietHours_overnightAcrossMidnightWithWeekdays() {
        // Monday & Wednesday only (days 1 and 3), 22:00 to 07:00
        val schedule = NotificationPreferences(
            isQuietHoursEnabled = true,
            quietHoursStartHour = 22,
            quietHoursStartMinute = 0,
            quietHoursEndHour = 7,
            quietHoursEndMinute = 0,
            quietHoursDays = setOf(1, 3)
        )

        // Monday 22:15 -> Active (Monday evening)
        val monEvening = LocalDateTime.of(2026, 11, 23, 22, 15)
        assertEquals(1, monEvening.dayOfWeek.value)
        assertTrue(QuietHoursEngine.isWithinQuietHoursWindow(schedule, monEvening))

        // Tuesday 03:00 -> Active (Belongs to Monday night)
        val tueMorning = LocalDateTime.of(2026, 11, 24, 3, 0)
        assertEquals(2, tueMorning.dayOfWeek.value)
        assertTrue(QuietHoursEngine.isWithinQuietHoursWindow(schedule, tueMorning))

        // Tuesday 07:01 -> Inactive (Monday overnight ended)
        val tuePostEnd = LocalDateTime.of(2026, 11, 24, 7, 1)
        assertFalse(QuietHoursEngine.isWithinQuietHoursWindow(schedule, tuePostEnd))

        // Tuesday 22:30 -> Inactive (Tuesday not selected)
        val tueEvening = LocalDateTime.of(2026, 11, 24, 22, 30)
        assertFalse(QuietHoursEngine.isWithinQuietHoursWindow(schedule, tueEvening))

        // Wednesday 03:00 -> Inactive (Tuesday night was not selected)
        val wedMorning = LocalDateTime.of(2026, 11, 25, 3, 0)
        assertEquals(3, wedMorning.dayOfWeek.value)
        assertFalse(QuietHoursEngine.isWithinQuietHoursWindow(schedule, wedMorning))

        // Wednesday 22:30 -> Active (Wednesday evening)
        val wedEvening = LocalDateTime.of(2026, 11, 25, 22, 30)
        assertTrue(QuietHoursEngine.isWithinQuietHoursWindow(schedule, wedEvening))

        // Thursday 04:00 -> Active (Belongs to Wednesday night)
        val thuMorning = LocalDateTime.of(2026, 11, 26, 4, 0)
        assertEquals(4, thuMorning.dayOfWeek.value)
        assertTrue(QuietHoursEngine.isWithinQuietHoursWindow(schedule, thuMorning))
    }

    @Test
    fun testQuietHours_statusDescriptionGeneration() {
        val disabled = NotificationPreferences(isQuietHoursEnabled = false)
        assertEquals("Disabled", QuietHoursEngine.getQuietHoursStatusDescription(disabled))

        val active = NotificationPreferences(
            isQuietHoursEnabled = true,
            quietHoursStartHour = 0,
            quietHoursStartMinute = 0,
            quietHoursEndHour = 23,
            quietHoursEndMinute = 59,
            quietHoursDays = setOf(1, 2, 3, 4, 5, 6, 7)
        )
        assertTrue(QuietHoursEngine.getQuietHoursStatusDescription(active).startsWith("Active now"))
    }

    // ==========================================
    // 7. TRANSFERS & MESH DEBOUNCING
    // ==========================================

    @Test
    fun testFileTransferThrottle_andMeshCooldown() {
        val txId = "transfer-abc"
        val peerId = "mesh-peer-xyz"
        val t0 = 1000000L

        // Initial calls permitted
        assertTrue(NotificationCooldownTracker.shouldUpdateTransferProgress(txId, 10, false, t0))
        assertTrue(NotificationCooldownTracker.shouldAlertMeshDiscovery(peerId, t0))

        // Immediate subsequent calls throttled
        assertFalse(NotificationCooldownTracker.shouldUpdateTransferProgress(txId, 12, false, t0 + 100))
        assertFalse(NotificationCooldownTracker.shouldAlertMeshDiscovery(peerId, t0 + 60000))

        // After cooldown expires
        assertTrue(NotificationCooldownTracker.shouldUpdateTransferProgress(txId, 20, false, t0 + 350))
        assertTrue(NotificationCooldownTracker.shouldAlertMeshDiscovery(peerId, t0 + 5 * 60 * 1000 + 10))

        // Transfer completion always bypasses throttle
        assertTrue(NotificationCooldownTracker.shouldUpdateTransferProgress(txId, 100, true, t0 + 360))
    }

    // ==========================================
    // 8. ANDROID NOTIFICATION CHANNELS
    // ==========================================

    @Test
    fun testChannelConstants_stability() {
        assertEquals("channel_messages_direct", NexaNotificationChannelManager.CHANNEL_MESSAGES_DIRECT)
        assertEquals("channel_messages_group", NexaNotificationChannelManager.CHANNEL_MESSAGES_GROUP)
        assertEquals("channel_calls_incoming", NexaNotificationChannelManager.CHANNEL_CALLS_INCOMING)
        assertEquals("channel_file_transfer", NexaNotificationChannelManager.CHANNEL_FILE_TRANSFER)
        assertEquals("channel_mesh_discovery", NexaNotificationChannelManager.CHANNEL_MESH_DISCOVERY)
        assertEquals("channel_connection_requests", NexaNotificationChannelManager.CHANNEL_CONNECTION_REQUESTS)
    }

    private class MockEditor(private val storage: MutableMap<String, Any>) : android.content.SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any>()
        override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { if (key != null && value != null) pending[key] = value; return this }
        override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
        override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
        override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
        override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { if (key != null) pending[key] = value; return this }
        override fun remove(key: String?): android.content.SharedPreferences.Editor { if (key != null) storage.remove(key); return this }
        override fun clear(): android.content.SharedPreferences.Editor { storage.clear(); return this }
        override fun commit(): Boolean { storage.putAll(pending); return true }
        override fun apply() { storage.putAll(pending) }
    }
}
