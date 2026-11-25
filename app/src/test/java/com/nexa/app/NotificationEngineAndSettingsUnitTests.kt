package com.nexa.app

import com.nexa.app.core.notification.NexaMentionParser
import com.nexa.app.core.notification.NexaNotificationChannelManager
import com.nexa.app.core.notification.NotificationCooldownTracker
import com.nexa.app.core.notification.QuietHoursEngine
import com.nexa.app.data.local.preferences.LockScreenMode
import com.nexa.app.data.local.preferences.NotificationPreferences
import com.nexa.app.data.local.preferences.NotificationPrefs
import com.nexa.app.presentation.settings.notifications.NotificationSettingsEvent
import com.nexa.app.presentation.settings.notifications.NotificationSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationEngineAndSettingsUnitTests {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        NotificationCooldownTracker.clear()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        NotificationCooldownTracker.clear()
    }

    @Test
    fun quietHours_whenDisabled_neverSuppresses() {
        val settings = NotificationPreferences(
            isQuietHoursEnabled = false,
            quietHoursStartHour = 23,
            quietHoursStartMinute = 0,
            quietHoursEndHour = 7,
            quietHoursEndMinute = 0
        )

        // Test at midnight
        assertFalse(QuietHoursEngine.shouldSuppressNotification(false, settings, LocalTime.of(0, 0)))
        // Test at noon
        assertFalse(QuietHoursEngine.shouldSuppressNotification(false, settings, LocalTime.of(12, 0)))
    }

    @Test
    fun quietHours_crossMidnightWindow_correctlySuppressesWithinWindow() {
        val settings = NotificationPreferences(
            isQuietHoursEnabled = true,
            quietHoursStartHour = 23,
            quietHoursStartMinute = 0,
            quietHoursEndHour = 7,
            quietHoursEndMinute = 0
        )

        // 23:30 -> Within quiet hours window (Suppressed)
        assertTrue(QuietHoursEngine.shouldSuppressNotification(false, settings, LocalTime.of(23, 30)))

        // 03:00 -> Within quiet hours window (Suppressed)
        assertTrue(QuietHoursEngine.shouldSuppressNotification(false, settings, LocalTime.of(3, 0)))

        // 06:59 -> Within quiet hours window (Suppressed)
        assertTrue(QuietHoursEngine.shouldSuppressNotification(false, settings, LocalTime.of(6, 59)))

        // 07:01 -> Outside quiet hours window (Not suppressed)
        assertFalse(QuietHoursEngine.shouldSuppressNotification(false, settings, LocalTime.of(7, 1)))

        // 14:00 -> Outside quiet hours window (Not suppressed)
        assertFalse(QuietHoursEngine.shouldSuppressNotification(false, settings, LocalTime.of(14, 0)))

        // 22:59 -> Outside quiet hours window (Not suppressed)
        assertFalse(QuietHoursEngine.shouldSuppressNotification(false, settings, LocalTime.of(22, 59)))
    }

    @Test
    fun quietHours_sameDayWindow_correctlySuppressesWithinWindow() {
        val settings = NotificationPreferences(
            isQuietHoursEnabled = true,
            quietHoursStartHour = 13,
            quietHoursStartMinute = 0,
            quietHoursEndHour = 17,
            quietHoursEndMinute = 30
        )

        // 12:59 -> Outside (Not suppressed)
        assertFalse(QuietHoursEngine.shouldSuppressNotification(false, settings, LocalTime.of(12, 59)))

        // 13:00 -> Start boundary (Suppressed)
        assertTrue(QuietHoursEngine.shouldSuppressNotification(false, settings, LocalTime.of(13, 0)))

        // 15:00 -> Inside (Suppressed)
        assertTrue(QuietHoursEngine.shouldSuppressNotification(false, settings, LocalTime.of(15, 0)))

        // 17:30 -> End boundary (Not suppressed)
        assertFalse(QuietHoursEngine.shouldSuppressNotification(false, settings, LocalTime.of(17, 30)))

        // 20:00 -> Outside (Not suppressed)
        assertFalse(QuietHoursEngine.shouldSuppressNotification(false, settings, LocalTime.of(20, 0)))
    }

    @Test
    fun quietHours_weekdayOvernightScheduling_handlesDayTransitions() {
        // Monday only (day 1), 22:00 to 07:00
        val mondayOnlySettings = NotificationPreferences(
            isQuietHoursEnabled = true,
            quietHoursStartHour = 22,
            quietHoursStartMinute = 0,
            quietHoursEndHour = 7,
            quietHoursEndMinute = 0,
            quietHoursDays = setOf(1)
        )

        // Monday 2026-11-23 at 22:30 -> In window on selected day (Suppressed)
        val mondayNight = LocalDateTime.of(2026, 11, 23, 22, 30) // Monday
        assertEquals(1, mondayNight.dayOfWeek.value)
        assertTrue(QuietHoursEngine.shouldSuppressNotification(false, mondayOnlySettings, mondayNight))

        // Tuesday 2026-11-24 at 02:00 -> Belongs to Monday overnight window (Suppressed)
        val tuesdayEarly = LocalDateTime.of(2026, 11, 24, 2, 0) // Tuesday
        assertEquals(2, tuesdayEarly.dayOfWeek.value)
        assertTrue(QuietHoursEngine.shouldSuppressNotification(false, mondayOnlySettings, tuesdayEarly))

        // Tuesday 2026-11-24 at 07:05 -> Outside morning window (Not suppressed)
        val tuesdayMorning = LocalDateTime.of(2026, 11, 24, 7, 5)
        assertFalse(QuietHoursEngine.shouldSuppressNotification(false, mondayOnlySettings, tuesdayMorning))

        // Tuesday 2026-11-24 at 22:30 -> Tuesday not in selectedDays (Not suppressed)
        val tuesdayNight = LocalDateTime.of(2026, 11, 24, 22, 30)
        assertFalse(QuietHoursEngine.shouldSuppressNotification(false, mondayOnlySettings, tuesdayNight))

        // Wednesday 2026-11-25 at 02:00 -> Tuesday was not selected (Not suppressed)
        val wednesdayEarly = LocalDateTime.of(2026, 11, 25, 2, 0)
        assertFalse(QuietHoursEngine.shouldSuppressNotification(false, mondayOnlySettings, wednesdayEarly))
    }

    @Test
    fun quietHours_statusDescription() {
        val disabledSettings = NotificationPreferences(isQuietHoursEnabled = false)
        assertEquals("Disabled", QuietHoursEngine.getQuietHoursStatusDescription(disabledSettings))

        val activeSettings = NotificationPreferences(
            isQuietHoursEnabled = true,
            quietHoursStartHour = 0,
            quietHoursStartMinute = 0,
            quietHoursEndHour = 23,
            quietHoursEndMinute = 59,
            quietHoursDays = setOf(1, 2, 3, 4, 5, 6, 7)
        )
        assertTrue(QuietHoursEngine.getQuietHoursStatusDescription(activeSettings).startsWith("Active now"))
    }

    @Test
    fun quietHours_incomingCalls_respectsBypassSetting() {
        val allowCallBypassSettings = NotificationPreferences(
            isQuietHoursEnabled = true,
            allowCallsDuringQuietHours = true,
            quietHoursStartHour = 22,
            quietHoursStartMinute = 0,
            quietHoursEndHour = 8,
            quietHoursEndMinute = 0
        )

        val blockCallBypassSettings = allowCallBypassSettings.copy(allowCallsDuringQuietHours = false)

        val midnight = LocalTime.of(1, 0)

        // Message is always suppressed at 01:00
        assertTrue(QuietHoursEngine.shouldSuppressNotification(false, allowCallBypassSettings, midnight))

        // Call bypass enabled -> Call is NOT suppressed
        assertFalse(QuietHoursEngine.shouldSuppressNotification(true, allowCallBypassSettings, midnight))

        // Call bypass disabled -> Call IS suppressed
        assertTrue(QuietHoursEngine.shouldSuppressNotification(true, blockCallBypassSettings, midnight))
    }

    @Test
    fun mentionParser_detectsMentionsAccurately() {
        val myNxId = "NX-DEV-1234"
        val myName = "Alice Smith"

        // Direct handle match
        assertTrue(NexaMentionParser.isDirectMention("Hey @Alice check this", myNxId, myName))
        assertTrue(NexaMentionParser.isDirectMention("Hey @alicesmith look", myNxId, myName))

        // NX-ID match
        assertTrue(NexaMentionParser.isDirectMention("Call @NX-DEV-1234 now", myNxId, myName))
        assertTrue(NexaMentionParser.isDirectMention("Call @DEV-1234 now", myNxId, myName))

        // Broadcast mentions
        assertTrue(NexaMentionParser.isDirectMention("Meeting at 3 @all", myNxId, myName))
        assertTrue(NexaMentionParser.isDirectMention("@everyone please mute", myNxId, myName))

        // Non-mentions and email addresses
        assertFalse(NexaMentionParser.isDirectMention("Contact me at alice@domain.com", myNxId, myName))
        assertFalse(NexaMentionParser.isDirectMention("Hello Alice without at symbol", myNxId, myName))
        assertFalse(NexaMentionParser.isDirectMention("Talking to @Bob about lunch", myNxId, myName))
    }

    @Test
    fun meshCooldownTracker_rateLimitsPeerDiscoveryAlerts() {
        val peer = "NX-TEST-PEER-1"
        val t0 = 100000L

        // First alert allowed
        assertTrue(NotificationCooldownTracker.shouldAlertMeshDiscovery(peer, now = t0))

        // Immediate subsequent alert blocked (within 5 minutes)
        assertFalse(NotificationCooldownTracker.shouldAlertMeshDiscovery(peer, now = t0 + 1000L))
        assertFalse(NotificationCooldownTracker.shouldAlertMeshDiscovery(peer, now = t0 + 4 * 60 * 1000L))

        // After 5 minutes, alert is allowed again
        assertTrue(NotificationCooldownTracker.shouldAlertMeshDiscovery(peer, now = t0 + 5 * 60 * 1000L + 1L))
    }

    @Test
    fun fileTransferProgress_rateLimitsInterimUpdates_andAllowsCompletion() {
        val transferId = "tx-123"
        val t0 = 500000L

        // First update at 10% allowed
        assertTrue(NotificationCooldownTracker.shouldUpdateTransferProgress(transferId, 10, false, now = t0))

        // Rapid update within 100ms blocked
        assertFalse(NotificationCooldownTracker.shouldUpdateTransferProgress(transferId, 15, false, now = t0 + 100L))

        // Update after 350ms allowed
        assertTrue(NotificationCooldownTracker.shouldUpdateTransferProgress(transferId, 25, false, now = t0 + 350L))

        // Completion (100% or isCompleted=true) is ALWAYS allowed
        assertTrue(NotificationCooldownTracker.shouldUpdateTransferProgress(transferId, 100, true, now = t0 + 360L))
    }

    @Test
    fun notificationChannels_constantsMatchSpecification() {
        assertEquals("channel_messages_direct", NexaNotificationChannelManager.CHANNEL_MESSAGES_DIRECT)
        assertEquals("channel_messages_group", NexaNotificationChannelManager.CHANNEL_MESSAGES_GROUP)
        assertEquals("channel_calls_incoming", NexaNotificationChannelManager.CHANNEL_CALLS_INCOMING)
        assertEquals("channel_file_transfer", NexaNotificationChannelManager.CHANNEL_FILE_TRANSFER)
        assertEquals("channel_mesh_discovery", NexaNotificationChannelManager.CHANNEL_MESH_DISCOVERY)
        assertEquals("channel_connection_requests", NexaNotificationChannelManager.CHANNEL_CONNECTION_REQUESTS)
    }

    @Test
    fun lockScreenMode_hasRequiredTiers() {
        assertEquals(3, LockScreenMode.values().size)
        assertEquals(LockScreenMode.HIDE_CONTENT, NotificationPreferences().lockScreenVisibility)
    }

    @Test
    fun quietHoursDays_formattingAndDefaults() {
        val allDays = NotificationPreferences(quietHoursDays = setOf(1, 2, 3, 4, 5, 6, 7))
        assertEquals("Every day", allDays.quietHoursDaysFormatted)

        val weekdays = NotificationPreferences(quietHoursDays = setOf(1, 2, 3, 4, 5))
        assertEquals("Weekdays", weekdays.quietHoursDaysFormatted)

        val weekends = NotificationPreferences(quietHoursDays = setOf(6, 7))
        assertEquals("Weekends", weekends.quietHoursDaysFormatted)

        val custom = NotificationPreferences(quietHoursDays = setOf(1, 3, 5))
        assertEquals("Mon, Wed, Fri", custom.quietHoursDaysFormatted)
    }

    @Test
    fun notificationViewModel_eventsUpdateStateProperly() = runTest {
        val inMemoryStorage = mutableMapOf<String, Any>()
        val mockPrefs = object : android.content.SharedPreferences {
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

        val notificationPrefs = NotificationPrefs(mockPrefs)
        val viewModel = NotificationSettingsViewModel(notificationPrefs)

        // Verify initial defaults
        val initial = viewModel.preferencesState.value
        assertTrue(initial.directMessagesEnabled)
        assertTrue(initial.messagePreviewEnabled)
        assertEquals(LockScreenMode.HIDE_CONTENT, initial.lockScreenVisibility)
        assertFalse(initial.isQuietHoursEnabled)
        assertEquals(7, initial.quietHoursDays.size)

        // Toggle message preview off
        viewModel.onEvent(NotificationSettingsEvent.ToggleMessagePreview(false))
        advanceUntilIdle()
        assertFalse(viewModel.preferencesState.value.messagePreviewEnabled)

        // Change Lock Screen mode to SHOW_ALL
        viewModel.onEvent(NotificationSettingsEvent.SetLockScreenMode(LockScreenMode.SHOW_ALL))
        advanceUntilIdle()
        assertEquals(LockScreenMode.SHOW_ALL, viewModel.preferencesState.value.lockScreenVisibility)

        // Enable Quiet Hours and configure 22:30 to 06:45
        viewModel.onEvent(NotificationSettingsEvent.ToggleQuietHours(true))
        viewModel.onEvent(NotificationSettingsEvent.SetQuietHoursWindow(22, 30, 6, 45))
        advanceUntilIdle()

        val updated = viewModel.preferencesState.value
        assertTrue(updated.isQuietHoursEnabled)
        assertEquals(22, updated.quietHoursStartHour)
        assertEquals(30, updated.quietHoursStartMinute)
        assertEquals(6, updated.quietHoursEndHour)
        assertEquals(45, updated.quietHoursEndMinute)
        assertEquals("22:30 – 06:45", updated.quietHoursFormatted)

        // Toggle weekday selection
        viewModel.onEvent(NotificationSettingsEvent.SetQuietHoursDays(setOf(1, 2, 3, 4, 5)))
        advanceUntilIdle()
        assertEquals(setOf(1, 2, 3, 4, 5), viewModel.preferencesState.value.quietHoursDays)
        assertEquals("Weekdays", viewModel.preferencesState.value.quietHoursDaysFormatted)

        // Toggle day 1 (removes Monday)
        viewModel.onEvent(NotificationSettingsEvent.ToggleQuietHoursDay(1))
        advanceUntilIdle()
        assertFalse(viewModel.preferencesState.value.quietHoursDays.contains(1))

        // Verify system permission state tracking
        viewModel.onEvent(NotificationSettingsEvent.UpdateSystemPermissionState(false))
        advanceUntilIdle()
        assertFalse(viewModel.isSystemPermissionGranted.value)

        // Verify disabled channels tracking
        viewModel.onEvent(NotificationSettingsEvent.UpdateDisabledChannels(listOf(NexaNotificationChannelManager.CHANNEL_MESSAGES_DIRECT)))
        advanceUntilIdle()
        assertEquals(1, viewModel.disabledChannels.value.size)
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
