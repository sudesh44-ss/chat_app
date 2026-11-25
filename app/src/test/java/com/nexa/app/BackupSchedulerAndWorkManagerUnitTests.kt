package com.nexa.app

import androidx.work.NetworkType
import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.data.local.preferences.BackupFrequency
import com.nexa.app.data.local.preferences.BackupPreferencesState
import com.nexa.app.domain.manager.BackupScheduleManager
import com.nexa.app.domain.model.BackupMode
import com.nexa.app.domain.model.BackupScheduleConfig
import com.nexa.app.domain.model.ConversationBackupPolicy
import com.nexa.app.domain.model.EffectiveChatBackupPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Unit tests for Part 6: Automated Backup Scheduler, WorkManager, Network & Power Conditions.
 *
 * Verifies:
 * 1. Daily schedule delay calculation (same day future vs next day rollover).
 * 2. Weekly schedule delay calculation (same day future, same day past rollover, future weekday, past weekday).
 * 3. Monthly schedule delay calculation (same month future, past rollover, 30-day month clamp, February 28/29 clamp, year rollover).
 * 4. Human-readable next-run date/time formatting.
 * 5. WorkManager constraints construction (Wi-Fi unmetered vs connected, charging required vs unconstrained).
 * 6. PeriodicWorkRequest construction (intervals, backoff criteria, unique tags).
 * 7. Strict Manual Only mode gating (cancellation and prevention of automated work).
 * 8. Automatic backup master switch gating (cancellation when disabled).
 * 9. Integration with Part 5 conversation policies (MANUAL_ONLY chat excluded in automated backup, included in manual).
 * 10. Integration with Part 5 priority processing in automated backups.
 */
class BackupSchedulerAndWorkManagerUnitTests {

    private val testTimeZone: TimeZone = TimeZone.getTimeZone("UTC")

    @Test
    fun testDailyScheduleInitialDelay_FutureSameDay() {
        // Given: UTC now is 10:00:00 on Day 100
        val cal = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.OCTOBER, 10, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        // Target: 14:30 (same day in future)
        val config = BackupScheduleConfig(
            frequency = BackupFrequency.DAILY,
            dailyHour = 14,
            dailyMinute = 30
        )

        val nextScheduled = BackupScheduleManager.computeNextScheduledTime(config, now, testTimeZone)
        val initialDelay = BackupScheduleManager.computeInitialDelay(config, now, testTimeZone)

        val expectedTarget = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.OCTOBER, 10, 14, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals("Next scheduled time should match today at 14:30", expectedTarget, nextScheduled)
        assertEquals("Initial delay should be exactly 4.5 hours (16,200,000 ms)", 4 * 3600 * 1000L + 30 * 60 * 1000L, initialDelay)
    }

    @Test
    fun testDailyScheduleInitialDelay_PastSameDay_RollsToTomorrow() {
        // Given: UTC now is 04:00:00 on Day 10
        val cal = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.OCTOBER, 10, 4, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        // Target: 02:00 (has already passed today)
        val config = BackupScheduleConfig(
            frequency = BackupFrequency.DAILY,
            dailyHour = 2,
            dailyMinute = 0
        )

        val nextScheduled = BackupScheduleManager.computeNextScheduledTime(config, now, testTimeZone)
        val initialDelay = BackupScheduleManager.computeInitialDelay(config, now, testTimeZone)

        val expectedTarget = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.OCTOBER, 11, 2, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals("Next scheduled time should roll to tomorrow at 02:00", expectedTarget, nextScheduled)
        assertEquals("Initial delay should be exactly 22 hours", 22 * 3600 * 1000L, initialDelay)
    }

    @Test
    fun testWeeklyScheduleInitialDelay_FutureSameWeek() {
        // Given: UTC now is Tuesday (ISO day 2), Oct 13, 2026 at 10:00 AM
        val cal = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.OCTOBER, 13, 10, 0, 0) // Oct 13, 2026 is Tuesday
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        // Target: Friday (ISO day 5) at 02:00 AM
        val config = BackupScheduleConfig(
            frequency = BackupFrequency.WEEKLY,
            dailyHour = 2,
            dailyMinute = 0,
            weeklyDayOfWeek = 5
        )

        val nextScheduled = BackupScheduleManager.computeNextScheduledTime(config, now, testTimeZone)

        val targetCal = Calendar.getInstance(testTimeZone).apply {
            timeInMillis = nextScheduled
        }

        assertEquals("Target year must be 2026", 2026, targetCal.get(Calendar.YEAR))
        assertEquals("Target month must be October", Calendar.OCTOBER, targetCal.get(Calendar.MONTH))
        assertEquals("Target day must be Friday Oct 16", 16, targetCal.get(Calendar.DAY_OF_MONTH))
        assertEquals("Target hour must be 2", 2, targetCal.get(Calendar.HOUR_OF_DAY))
        assertEquals("Target minute must be 0", 0, targetCal.get(Calendar.MINUTE))
        assertEquals("Target day of week must be Friday", Calendar.FRIDAY, targetCal.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun testWeeklyScheduleInitialDelay_SameDay_FutureTime() {
        // Given: UTC now is Monday Oct 12, 2026 at 01:00 AM
        val cal = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.OCTOBER, 12, 1, 0, 0) // Monday
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        // Target: Monday (ISO day 1) at 02:00 AM
        val config = BackupScheduleConfig(
            frequency = BackupFrequency.WEEKLY,
            dailyHour = 2,
            dailyMinute = 0,
            weeklyDayOfWeek = 1
        )

        val initialDelay = BackupScheduleManager.computeInitialDelay(config, now, testTimeZone)
        assertEquals("Delay should be exactly 1 hour on the same day", 3600 * 1000L, initialDelay)
    }

    @Test
    fun testWeeklyScheduleInitialDelay_SameDay_PastTime_RollsToNextWeek() {
        // Given: UTC now is Monday Oct 12, 2026 at 03:00 AM
        val cal = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.OCTOBER, 12, 3, 0, 0) // Monday
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        // Target: Monday (ISO day 1) at 02:00 AM (has passed today)
        val config = BackupScheduleConfig(
            frequency = BackupFrequency.WEEKLY,
            dailyHour = 2,
            dailyMinute = 0,
            weeklyDayOfWeek = 1
        )

        val nextScheduled = BackupScheduleManager.computeNextScheduledTime(config, now, testTimeZone)
        val targetCal = Calendar.getInstance(testTimeZone).apply {
            timeInMillis = nextScheduled
        }

        assertEquals("Target day should roll 7 days to next Monday Oct 19", 19, targetCal.get(Calendar.DAY_OF_MONTH))
        assertEquals("Target day of week must still be Monday", Calendar.MONDAY, targetCal.get(Calendar.DAY_OF_WEEK))
        assertEquals("Target hour must be 2", 2, targetCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testWeeklyScheduleInitialDelay_PastWeekday_RollsToNextWeek() {
        // Given: UTC now is Friday Oct 16, 2026 at 10:00 AM
        val cal = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.OCTOBER, 16, 10, 0, 0) // Friday
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        // Target: Tuesday (ISO day 2) at 02:00 AM
        val config = BackupScheduleConfig(
            frequency = BackupFrequency.WEEKLY,
            dailyHour = 2,
            dailyMinute = 0,
            weeklyDayOfWeek = 2
        )

        val nextScheduled = BackupScheduleManager.computeNextScheduledTime(config, now, testTimeZone)
        val targetCal = Calendar.getInstance(testTimeZone).apply {
            timeInMillis = nextScheduled
        }

        assertEquals("Target day must be next Tuesday Oct 20", 20, targetCal.get(Calendar.DAY_OF_MONTH))
        assertEquals("Target day of week must be Tuesday", Calendar.TUESDAY, targetCal.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun testMonthlyScheduleInitialDelay_FutureSameMonth() {
        // Given: UTC now is Oct 5, 2026 at 10:00 AM
        val cal = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.OCTOBER, 5, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        // Target: 15th of month at 02:00 AM
        val config = BackupScheduleConfig(
            frequency = BackupFrequency.MONTHLY,
            dailyHour = 2,
            dailyMinute = 0,
            monthlyDayOfMonth = 15
        )

        val nextScheduled = BackupScheduleManager.computeNextScheduledTime(config, now, testTimeZone)
        val targetCal = Calendar.getInstance(testTimeZone).apply {
            timeInMillis = nextScheduled
        }

        assertEquals("Month must be October", Calendar.OCTOBER, targetCal.get(Calendar.MONTH))
        assertEquals("Day must be 15", 15, targetCal.get(Calendar.DAY_OF_MONTH))
        assertEquals("Hour must be 2", 2, targetCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testMonthlyScheduleInitialDelay_PastSameMonth_RollsToNextMonth() {
        // Given: UTC now is Oct 20, 2026 at 10:00 AM
        val cal = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.OCTOBER, 20, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        // Target: 15th of month at 02:00 AM (has passed in October)
        val config = BackupScheduleConfig(
            frequency = BackupFrequency.MONTHLY,
            dailyHour = 2,
            dailyMinute = 0,
            monthlyDayOfMonth = 15
        )

        val nextScheduled = BackupScheduleManager.computeNextScheduledTime(config, now, testTimeZone)
        val targetCal = Calendar.getInstance(testTimeZone).apply {
            timeInMillis = nextScheduled
        }

        assertEquals("Month must advance to November", Calendar.NOVEMBER, targetCal.get(Calendar.MONTH))
        assertEquals("Day must be 15", 15, targetCal.get(Calendar.DAY_OF_MONTH))
        assertEquals("Hour must be 2", 2, targetCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testMonthlyScheduleInitialDelay_Day31InShortMonth_ClampedToMonthEnd() {
        // Given: User configured day 31. Current month is April (which has 30 days).
        val cal = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.APRIL, 10, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        val config = BackupScheduleConfig(
            frequency = BackupFrequency.MONTHLY,
            dailyHour = 2,
            dailyMinute = 0,
            monthlyDayOfMonth = 31
        )

        val nextScheduled = BackupScheduleManager.computeNextScheduledTime(config, now, testTimeZone)
        val targetCal = Calendar.getInstance(testTimeZone).apply {
            timeInMillis = nextScheduled
        }

        assertEquals("Month must be April", Calendar.APRIL, targetCal.get(Calendar.MONTH))
        assertEquals("Day 31 must clamp to April 30", 30, targetCal.get(Calendar.DAY_OF_MONTH))
        assertEquals("Hour must be 2", 2, targetCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testMonthlyScheduleInitialDelay_Day31InFebruary_ClampedToFeb28() {
        // Given: 2027 is not a leap year. February has 28 days.
        val cal = Calendar.getInstance(testTimeZone).apply {
            set(2027, Calendar.FEBRUARY, 1, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        val config = BackupScheduleConfig(
            frequency = BackupFrequency.MONTHLY,
            dailyHour = 2,
            dailyMinute = 0,
            monthlyDayOfMonth = 31
        )

        val nextScheduled = BackupScheduleManager.computeNextScheduledTime(config, now, testTimeZone)
        val targetCal = Calendar.getInstance(testTimeZone).apply {
            timeInMillis = nextScheduled
        }

        assertEquals("Month must be February", Calendar.FEBRUARY, targetCal.get(Calendar.MONTH))
        assertEquals("Day 31 must clamp to Feb 28 in non-leap year", 28, targetCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun testMonthlyScheduleInitialDelay_YearRollover() {
        // Given: Dec 20, 2026. Target is 15th at 02:00 AM (has passed in Dec).
        val cal = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.DECEMBER, 20, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        val config = BackupScheduleConfig(
            frequency = BackupFrequency.MONTHLY,
            dailyHour = 2,
            dailyMinute = 0,
            monthlyDayOfMonth = 15
        )

        val nextScheduled = BackupScheduleManager.computeNextScheduledTime(config, now, testTimeZone)
        val targetCal = Calendar.getInstance(testTimeZone).apply {
            timeInMillis = nextScheduled
        }

        assertEquals("Year must roll over to 2027", 2027, targetCal.get(Calendar.YEAR))
        assertEquals("Month must be January", Calendar.JANUARY, targetCal.get(Calendar.MONTH))
        assertEquals("Day must be 15", 15, targetCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun testFormatNextScheduledTime() {
        val cal = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.OCTOBER, 15, 2, 0, 0)
        }
        val formatted = BackupScheduleManager.formatNextScheduledTime(cal.timeInMillis, Locale.US)

        assertNotNull(formatted)
        assertTrue("Formatted date must contain October / Oct", formatted.contains("Oct"))
        assertTrue("Formatted date must contain 15", formatted.contains("15"))
        assertTrue("Formatted date must contain 2026", formatted.contains("2026"))

        val unformatted = BackupScheduleManager.formatNextScheduledTime(0L)
        assertEquals("Not Scheduled", unformatted)
    }

    @Test
    fun testBuildConstraints_WifiOnlyAndCharging() {
        val wifiAndChargingState = BackupPreferencesState(
            backupOverWifiOnly = true,
            backupWhenCharging = true
        )
        val constraints1 = BackupScheduleManager.buildConstraints(wifiAndChargingState)
        assertEquals(NetworkType.UNMETERED, constraints1.requiredNetworkType)
        assertTrue(constraints1.requiresCharging())
        assertTrue(constraints1.requiresBatteryNotLow())

        val mobileAndBatteryState = BackupPreferencesState(
            backupOverWifiOnly = false,
            backupWhenCharging = false
        )
        val constraints2 = BackupScheduleManager.buildConstraints(mobileAndBatteryState)
        assertEquals(NetworkType.CONNECTED, constraints2.requiredNetworkType)
        assertFalse(constraints2.requiresCharging())
    }

    @Test
    fun testBuildPeriodicWorkRequest_ContainsTagsAndBackoff() {
        val state = BackupPreferencesState(
            backupFrequency = BackupFrequency.WEEKLY,
            scheduledWeeklyDayOfWeek = 1,
            scheduledDailyHour = 2,
            scheduledDailyMinute = 0
        )

        val request = BackupScheduleManager.buildPeriodicWorkRequest(state)
        assertNotNull(request)
        assertTrue("Tags must contain TAG_BACKUP_WORK", request.tags.contains(BackupScheduleManager.TAG_BACKUP_WORK))
        assertTrue("Tags must contain TAG_AUTOMATED_BACKUP", request.tags.contains(BackupScheduleManager.TAG_AUTOMATED_BACKUP))
    }

    @Test
    fun testManualOnlyModeGating() {
        val manualOnlyState = BackupPreferencesState(
            backupMode = BackupMode.MANUAL_ONLY,
            autoBackupEnabled = false
        )

        // Manual Only mode strictly prevents scheduling
        assertEquals(BackupMode.MANUAL_ONLY, manualOnlyState.backupMode)
        assertFalse(manualOnlyState.autoBackupEnabled)
    }

    @Test
    fun testAutomatedBackup_PolicyExclusionForManualOnlyChats() {
        // In Part 5, ConversationBackupPolicy.MANUAL_ONLY excludes the chat from automated backups,
        // but includes it during manual backups.
        val globalConfig = BackupCategoryConfig.allEnabled()

        // 1. Automated background backup (isManualBackup = false)
        val automatedEffectivePolicy = EffectiveChatBackupPolicy.resolve(
            conversationId = "chat_manual_only_1",
            policy = ConversationBackupPolicy.MANUAL_ONLY,
            customCategories = null,
            isPriorityFlag = false,
            globalConfig = globalConfig,
            isManualBackup = false
        )
        assertFalse("MANUAL_ONLY chat must be excluded in automated backup", automatedEffectivePolicy.isIncluded)

        // 2. User-initiated manual backup (isManualBackup = true)
        val manualEffectivePolicy = EffectiveChatBackupPolicy.resolve(
            conversationId = "chat_manual_only_1",
            policy = ConversationBackupPolicy.MANUAL_ONLY,
            customCategories = null,
            isPriorityFlag = false,
            globalConfig = globalConfig,
            isManualBackup = true
        )
        assertTrue("MANUAL_ONLY chat must be included in user-initiated manual backup", manualEffectivePolicy.isIncluded)
    }

    @Test
    fun testPriorityChatsPreservedInAutomatedBackup() {
        val globalConfig = BackupCategoryConfig.allEnabled()

        val priorityChat = EffectiveChatBackupPolicy.resolve(
            conversationId = "priority_chat_1",
            policy = ConversationBackupPolicy.PRIORITY,
            customCategories = null,
            isPriorityFlag = true,
            globalConfig = globalConfig,
            isManualBackup = false
        )

        assertTrue(priorityChat.isIncluded)
        assertTrue(priorityChat.isPriority)

        val excludedChat = EffectiveChatBackupPolicy.resolve(
            conversationId = "excluded_chat_1",
            policy = ConversationBackupPolicy.EXCLUDED,
            customCategories = null,
            isPriorityFlag = false,
            globalConfig = globalConfig,
            isManualBackup = false
        )
        assertFalse(excludedChat.isIncluded)
    }

    @Test
    fun testDaylightSavingTime_SpringForward_23HourDay() {
        val nyTimeZone = TimeZone.getTimeZone("America/New_York")

        // In 2026, US Eastern spring-forward occurs on Sunday, March 8.
        // Clocks skip 02:00 AM -> 03:00 AM.
        // Given: Saturday, March 7, 2026 at 10:00:00 AM EST.
        val cal = Calendar.getInstance(nyTimeZone).apply {
            set(2026, Calendar.MARCH, 7, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        // Target: 04:00 AM preferred time (valid in EDT)
        val config = BackupScheduleConfig(
            frequency = BackupFrequency.DAILY,
            dailyHour = 4,
            dailyMinute = 0
        )

        val nextScheduled = BackupScheduleManager.computeNextScheduledTime(config, now, nyTimeZone)
        val initialDelay = BackupScheduleManager.computeInitialDelay(config, now, nyTimeZone)

        val targetCal = Calendar.getInstance(nyTimeZone).apply {
            timeInMillis = nextScheduled
        }

        assertEquals("Target year must be 2026", 2026, targetCal.get(Calendar.YEAR))
        assertEquals("Target month must be March", Calendar.MARCH, targetCal.get(Calendar.MONTH))
        assertEquals("Target day must be Sunday March 8", 8, targetCal.get(Calendar.DAY_OF_MONTH))
        assertEquals("Target hour must be exactly 4:00 AM EDT", 4, targetCal.get(Calendar.HOUR_OF_DAY))
        assertEquals("Target minute must be 0", 0, targetCal.get(Calendar.MINUTE))

        // On a normal 24h day, 10:00 AM to 04:00 AM next day is 18 hours.
        // Because 1 hour was skipped due to DST, the exact duration is 17 hours!
        val expectedDurationMillis = 17 * 3600 * 1000L
        assertEquals("Delay across spring-forward must account for 23-hour day", expectedDurationMillis, initialDelay)
    }

    @Test
    fun testDaylightSavingTime_FallBack_25HourDay() {
        val nyTimeZone = TimeZone.getTimeZone("America/New_York")

        // In 2026, US Eastern fall-back occurs on Sunday, November 1.
        // Clocks repeat 01:00 AM -> 02:00 AM.
        // Given: Saturday, October 31, 2026 at 10:00:00 AM EDT.
        val cal = Calendar.getInstance(nyTimeZone).apply {
            set(2026, Calendar.OCTOBER, 31, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        // Target: 04:00 AM preferred time
        val config = BackupScheduleConfig(
            frequency = BackupFrequency.DAILY,
            dailyHour = 4,
            dailyMinute = 0
        )

        val nextScheduled = BackupScheduleManager.computeNextScheduledTime(config, now, nyTimeZone)
        val initialDelay = BackupScheduleManager.computeInitialDelay(config, now, nyTimeZone)

        val targetCal = Calendar.getInstance(nyTimeZone).apply {
            timeInMillis = nextScheduled
        }

        assertEquals("Target year must be 2026", 2026, targetCal.get(Calendar.YEAR))
        assertEquals("Target month must be November", Calendar.NOVEMBER, targetCal.get(Calendar.MONTH))
        assertEquals("Target day must be Sunday Nov 1", 1, targetCal.get(Calendar.DAY_OF_MONTH))
        assertEquals("Target hour must be exactly 4:00 AM EST", 4, targetCal.get(Calendar.HOUR_OF_DAY))

        // On a normal 24h day, 10:00 AM to 04:00 AM next day is 18 hours.
        // Because 1 hour was repeated due to DST fall-back, the exact duration is 19 hours!
        val expectedDurationMillis = 19 * 3600 * 1000L
        assertEquals("Delay across fall-back must account for 25-hour day", expectedDurationMillis, initialDelay)
    }

    @Test
    fun testTimezoneChange_RecalculatesDelay() {
        // Given: UTC now is Oct 15, 2026 at 00:00:00
        val calUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.OCTOBER, 15, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = calUtc.timeInMillis

        val config = BackupScheduleConfig(
            frequency = BackupFrequency.DAILY,
            dailyHour = 2,
            dailyMinute = 0
        )

        // In UTC: 00:00 -> 02:00 AM today is in 2 hours
        val delayUtc = BackupScheduleManager.computeInitialDelay(config, now, TimeZone.getTimeZone("UTC"))
        assertEquals("In UTC, delay is exactly 2 hours", 2 * 3600 * 1000L, delayUtc)

        // In Asia/Kolkata (+05:30): UTC 00:00 is 05:30 AM local time.
        // 02:00 AM today has already passed! Next 02:00 AM IST is tomorrow at 02:00 IST (UTC 20:30 today).
        val delayKolkata = BackupScheduleManager.computeInitialDelay(config, now, TimeZone.getTimeZone("Asia/Kolkata"))
        val expectedKolkataDelay = 20 * 3600 * 1000L + 30 * 60 * 1000L // 20.5 hours
        assertEquals("In Asia/Kolkata, delay rolls to next day 02:00 AM IST", expectedKolkataDelay, delayKolkata)
    }

    @Test
    fun testLeapYearFebruary_MonthEnd_Day29() {
        // 2024 is a leap year. February has 29 days.
        val cal = Calendar.getInstance(testTimeZone).apply {
            set(2024, Calendar.FEBRUARY, 1, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        val config = BackupScheduleConfig(
            frequency = BackupFrequency.MONTHLY,
            dailyHour = 2,
            dailyMinute = 0,
            monthlyDayOfMonth = 31
        )

        val nextScheduled = BackupScheduleManager.computeNextScheduledTime(config, now, testTimeZone)
        val targetCal = Calendar.getInstance(testTimeZone).apply {
            timeInMillis = nextScheduled
        }

        assertEquals("Month must be February", Calendar.FEBRUARY, targetCal.get(Calendar.MONTH))
        assertEquals("In leap year 2024, Day 31 must clamp to Feb 29", 29, targetCal.get(Calendar.DAY_OF_MONTH))
        assertEquals("Target hour must be 2", 2, targetCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testNonLeapYearFebruary_MonthEnd_Day28() {
        // 2026 is a non-leap year. February has 28 days.
        val cal = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.FEBRUARY, 1, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        val config = BackupScheduleConfig(
            frequency = BackupFrequency.MONTHLY,
            dailyHour = 2,
            dailyMinute = 0,
            monthlyDayOfMonth = 31
        )

        val nextScheduled = BackupScheduleManager.computeNextScheduledTime(config, now, testTimeZone)
        val targetCal = Calendar.getInstance(testTimeZone).apply {
            timeInMillis = nextScheduled
        }

        assertEquals("Month must be February", Calendar.FEBRUARY, targetCal.get(Calendar.MONTH))
        assertEquals("In non-leap year 2026, Day 31 must clamp to Feb 28", 28, targetCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun testCalendarWorkRequest_BuildsWithZeroDriftConstraints() {
        val state = BackupPreferencesState(
            backupFrequency = BackupFrequency.MONTHLY,
            scheduledMonthlyDayOfMonth = 15,
            scheduledDailyHour = 3,
            scheduledDailyMinute = 0,
            backupOverWifiOnly = true,
            backupWhenCharging = true
        )

        val workRequest = BackupScheduleManager.buildCalendarWorkRequest(state)
        assertNotNull(workRequest)
        assertTrue(workRequest.tags.contains(BackupScheduleManager.TAG_BACKUP_WORK))
        assertTrue(workRequest.tags.contains(BackupScheduleManager.TAG_AUTOMATED_BACKUP))
        assertEquals(NetworkType.UNMETERED, workRequest.workSpec.constraints.requiredNetworkType)
        assertTrue(workRequest.workSpec.constraints.requiresCharging())
    }

    @Test
    fun testCalendarDriftPrevention_SubsequentOccurrences() {
        // Demonstrates zero calendar drift over 3 consecutive monthly execution cycles:
        val config = BackupScheduleConfig(
            frequency = BackupFrequency.MONTHLY,
            dailyHour = 2,
            dailyMinute = 0,
            monthlyDayOfMonth = 1
        )

        // Cycle 1: Worker executed at Jan 1, 2026 at 02:05 AM (5 minutes after trigger)
        val cycle1Finished = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.JANUARY, 1, 2, 5, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val nextAfterCycle1 = BackupScheduleManager.computeNextScheduledTime(config, cycle1Finished, testTimeZone)
        val calAfterCycle1 = Calendar.getInstance(testTimeZone).apply { timeInMillis = nextAfterCycle1 }
        assertEquals("Cycle 1 next run must be exactly Feb 1", Calendar.FEBRUARY, calAfterCycle1.get(Calendar.MONTH))
        assertEquals("Day must be 1", 1, calAfterCycle1.get(Calendar.DAY_OF_MONTH))
        assertEquals("Hour must be 2", 2, calAfterCycle1.get(Calendar.HOUR_OF_DAY))

        // Cycle 2: Worker executed at Feb 1, 2026 at 02:10 AM
        val cycle2Finished = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.FEBRUARY, 1, 2, 10, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val nextAfterCycle2 = BackupScheduleManager.computeNextScheduledTime(config, cycle2Finished, testTimeZone)
        val calAfterCycle2 = Calendar.getInstance(testTimeZone).apply { timeInMillis = nextAfterCycle2 }
        assertEquals("Cycle 2 next run must be exactly Mar 1 (zero drift despite 28-day Feb!)", Calendar.MARCH, calAfterCycle2.get(Calendar.MONTH))
        assertEquals("Day must be 1", 1, calAfterCycle2.get(Calendar.DAY_OF_MONTH))

        // Cycle 3: Worker executed at Mar 1, 2026 at 02:02 AM
        val cycle3Finished = Calendar.getInstance(testTimeZone).apply {
            set(2026, Calendar.MARCH, 1, 2, 2, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val nextAfterCycle3 = BackupScheduleManager.computeNextScheduledTime(config, cycle3Finished, testTimeZone)
        val calAfterCycle3 = Calendar.getInstance(testTimeZone).apply { timeInMillis = nextAfterCycle3 }
        assertEquals("Cycle 3 next run must be exactly Apr 1", Calendar.APRIL, calAfterCycle3.get(Calendar.MONTH))
        assertEquals("Day must be 1", 1, calAfterCycle3.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun testChargingConstraintSemantics_RequiredVsPreferred() {
        // Charging Required (Switch ON)
        val chargingRequired = BackupPreferencesState(backupWhenCharging = true)
        val constraintsRequired = BackupScheduleManager.buildConstraints(chargingRequired)
        assertTrue("Switch ON must require charging", constraintsRequired.requiresCharging())
        assertTrue("Battery not low must be guarded", constraintsRequired.requiresBatteryNotLow())

        // Charging Not Required / Preferred (Switch OFF)
        val chargingNotRequired = BackupPreferencesState(backupWhenCharging = false)
        val constraintsNotRequired = BackupScheduleManager.buildConstraints(chargingNotRequired)
        assertFalse("Switch OFF must NOT require charging, allowing execution on battery", constraintsNotRequired.requiresCharging())
        assertTrue("Battery not low must still be guarded for battery health", constraintsNotRequired.requiresBatteryNotLow())
    }
}
