package com.nexa.app.domain.manager

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nexa.app.NexaApplication
import com.nexa.app.data.local.preferences.BackupFrequency
import com.nexa.app.data.local.preferences.BackupPreferencesState
import com.nexa.app.data.worker.ScheduledBackupWorker
import com.nexa.app.domain.model.BackupMode
import com.nexa.app.domain.model.BackupScheduleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Manages automated cloud backup scheduling, WorkManager periodic & calendar requests,
 * next-run delay calculations, network/charging constraints, and Manual Only mode gating.
 *
 * Guarantees zero calendar drift across:
 * 1. Variable calendar month lengths (28, 29, 30, 31 days).
 * 2. Leap years (February 29 vs 28).
 * 3. Daylight Saving Time (DST) spring-forward (23h) and fall-back (25h) shifts.
 * 4. System timezone and clock changes.
 */
object BackupScheduleManager {

    private const val TAG = "BackupScheduleManager"

    /** Primary unique work identifier for calendar-accurate automated backups */
    const val UNIQUE_WORK_NAME = ScheduledBackupWorker.UNIQUE_WORK_NAME

    /** Legacy work identifier to purge during migration */
    const val LEGACY_WORK_NAME = "nexa_nightly_backup_periodic"

    const val TAG_BACKUP_WORK = "nexa_backup_work"
    const val TAG_AUTOMATED_BACKUP = "nexa_automated_backup"

    const val DEFAULT_BACKOFF_DELAY_MINUTES = 15L

    /**
     * Computes the initial delay in milliseconds from [now] until the next scheduled occurrence
     * matching the [scheduleConfig] in local [timeZone].
     */
    fun computeInitialDelay(
        scheduleConfig: BackupScheduleConfig,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        val nextScheduled = computeNextScheduledTime(scheduleConfig, now, timeZone)
        return (nextScheduled - now).coerceAtLeast(0L)
    }

    /**
     * Calculates the exact epoch timestamp in milliseconds of the next scheduled backup run
     * matching the given [scheduleConfig] relative to [now].
     *
     * Ensures strict wall-clock accuracy: hours and minutes are enforced in the target date's
     * local timezone even across Daylight Saving Time boundaries.
     */
    fun computeNextScheduledTime(
        scheduleConfig: BackupScheduleConfig,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = now
        }

        return when (scheduleConfig.frequency) {
            BackupFrequency.DAILY -> {
                val targetToday = Calendar.getInstance(timeZone).apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, scheduleConfig.dailyHour)
                    set(Calendar.MINUTE, scheduleConfig.dailyMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                val daysToAdd = if (targetToday.timeInMillis <= now) 1 else 0
                val target = Calendar.getInstance(timeZone).apply {
                    timeInMillis = now
                    add(Calendar.DAY_OF_YEAR, daysToAdd)
                    set(Calendar.HOUR_OF_DAY, scheduleConfig.dailyHour)
                    set(Calendar.MINUTE, scheduleConfig.dailyMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                target.timeInMillis
            }

            BackupFrequency.WEEKLY -> {
                val currentIsoDay = when (calendar.get(Calendar.DAY_OF_WEEK)) {
                    Calendar.MONDAY -> 1
                    Calendar.TUESDAY -> 2
                    Calendar.WEDNESDAY -> 3
                    Calendar.THURSDAY -> 4
                    Calendar.FRIDAY -> 5
                    Calendar.SATURDAY -> 6
                    Calendar.SUNDAY -> 7
                    else -> 1
                }

                var daysUntil = (scheduleConfig.weeklyDayOfWeek - currentIsoDay) % 7
                if (daysUntil < 0) daysUntil += 7

                val targetToday = Calendar.getInstance(timeZone).apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, scheduleConfig.dailyHour)
                    set(Calendar.MINUTE, scheduleConfig.dailyMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                if (daysUntil == 0 && targetToday.timeInMillis <= now) {
                    daysUntil = 7
                }

                val target = Calendar.getInstance(timeZone).apply {
                    timeInMillis = now
                    add(Calendar.DAY_OF_YEAR, daysUntil)
                    set(Calendar.HOUR_OF_DAY, scheduleConfig.dailyHour)
                    set(Calendar.MINUTE, scheduleConfig.dailyMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                target.timeInMillis
            }

            BackupFrequency.MONTHLY -> {
                val target = Calendar.getInstance(timeZone).apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, scheduleConfig.dailyHour)
                    set(Calendar.MINUTE, scheduleConfig.dailyMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                val maxDaysThisMonth = target.getActualMaximum(Calendar.DAY_OF_MONTH)
                val targetDayThisMonth = minOf(scheduleConfig.monthlyDayOfMonth, maxDaysThisMonth)
                target.set(Calendar.DAY_OF_MONTH, targetDayThisMonth)

                if (target.timeInMillis <= now) {
                    // Target has passed this month -> advance to next month
                    target.set(Calendar.DAY_OF_MONTH, 1) // prevent month overflow before addition
                    target.add(Calendar.MONTH, 1)
                    val maxDaysNextMonth = target.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val targetDayNextMonth = minOf(scheduleConfig.monthlyDayOfMonth, maxDaysNextMonth)
                    target.set(Calendar.DAY_OF_MONTH, targetDayNextMonth)
                    target.set(Calendar.HOUR_OF_DAY, scheduleConfig.dailyHour)
                    target.set(Calendar.MINUTE, scheduleConfig.dailyMinute)
                    target.set(Calendar.SECOND, 0)
                    target.set(Calendar.MILLISECOND, 0)
                }

                target.timeInMillis
            }
        }
    }

    /**
     * Formats next scheduled backup timestamp into a user-friendly display string.
     */
    fun formatNextScheduledTime(
        nextEpochMillis: Long,
        locale: Locale = Locale.getDefault()
    ): String {
        if (nextEpochMillis <= 0L) return "Not Scheduled"
        val formatter = SimpleDateFormat("EEE, MMM d, yyyy 'at' hh:mm a", locale)
        return formatter.format(Date(nextEpochMillis))
    }

    /**
     * Builds Android WorkManager Constraints based on user's smart conditions.
     */
    fun buildConstraints(prefsState: BackupPreferencesState): Constraints {
        val networkType = if (prefsState.backupOverWifiOnly) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }

        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(prefsState.backupWhenCharging)
            .setRequiresBatteryNotLow(true)
            .build()
    }

    /**
     * Constructs a calendar-accurate OneTimeWorkRequest targeting the exact next occurrence.
     * Guaranteed zero-drift across Daylight Saving Time (DST) shifts and variable calendar month lengths (28, 29, 30, 31 days).
     */
    fun buildCalendarWorkRequest(
        prefsState: BackupPreferencesState,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): OneTimeWorkRequest {
        val constraints = buildConstraints(prefsState)
        val initialDelay = computeInitialDelay(prefsState.scheduleConfig, now, timeZone)

        return OneTimeWorkRequestBuilder<ScheduledBackupWorker>()
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                DEFAULT_BACKOFF_DELAY_MINUTES,
                TimeUnit.MINUTES
            )
            .addTag(TAG_BACKUP_WORK)
            .addTag(TAG_AUTOMATED_BACKUP)
            .build()
    }

    /**
     * Legacy periodic builder maintained for backwards compatibility.
     */
    fun buildPeriodicWorkRequest(
        prefsState: BackupPreferencesState,
        now: Long = System.currentTimeMillis()
    ): PeriodicWorkRequest {
        val constraints = buildConstraints(prefsState)
        val initialDelay = computeInitialDelay(prefsState.scheduleConfig, now)

        val (repeatInterval, timeUnit) = when (prefsState.backupFrequency) {
            BackupFrequency.DAILY -> Pair(24L, TimeUnit.HOURS)
            BackupFrequency.WEEKLY -> Pair(7L, TimeUnit.DAYS)
            BackupFrequency.MONTHLY -> Pair(30L, TimeUnit.DAYS)
        }

        return PeriodicWorkRequestBuilder<ScheduledBackupWorker>(repeatInterval, timeUnit)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                DEFAULT_BACKOFF_DELAY_MINUTES,
                TimeUnit.MINUTES
            )
            .addTag(TAG_BACKUP_WORK)
            .addTag(TAG_AUTOMATED_BACKUP)
            .build()
    }

    /**
     * Reconciles and enqueues or cancels the periodic backup work with WorkManager.
     * Strictly enforces MANUAL_ONLY mode by cancelling any scheduled work.
     */
    fun reschedule(context: Context) {
        val app = context.applicationContext as? NexaApplication ?: return
        val prefsState = app.backupPrefs.state.value
        rescheduleWithState(context, prefsState)
    }

    /**
     * Reconciles work with an explicitly provided [BackupPreferencesState].
     * Uses calendar-accurate unique work requests to eliminate periodic drift.
     */
    fun rescheduleWithState(context: Context, prefsState: BackupPreferencesState) {
        val workManager = try {
            WorkManager.getInstance(context)
        } catch (e: Exception) {
            Log.w(TAG, "WorkManager instance not available: ${e.message}")
            return
        }

        if (!prefsState.autoBackupEnabled || prefsState.backupMode == BackupMode.MANUAL_ONLY) {
            Log.i(TAG, "Automatic backup disabled or in MANUAL_ONLY mode. Cancelling scheduled work.")
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(LEGACY_WORK_NAME)
            return
        }

        val initialDelay = computeInitialDelay(prefsState.scheduleConfig)
        val workRequest = buildCalendarWorkRequest(prefsState)
        Log.i(
            TAG,
            "Enqueuing calendar-accurate backup work: frequency=${prefsState.backupFrequency}, " +
            "wifiOnly=${prefsState.backupOverWifiOnly}, chargingOnly=${prefsState.backupWhenCharging}, " +
            "initialDelayMs=$initialDelay"
        )

        // Cancel legacy periodic work if present, then enqueue calendar-accurate unique work
        workManager.cancelUniqueWork(LEGACY_WORK_NAME)
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    /**
     * Schedules the next calendar occurrence after completion of an active backup execution.
     * Eliminates periodic interval drift by recalculating the exact next date.
     */
    fun scheduleNextOccurrence(context: Context) {
        val app = context.applicationContext as? NexaApplication ?: return
        val prefsState = app.backupPrefs.state.value

        if (!prefsState.autoBackupEnabled || prefsState.backupMode == BackupMode.MANUAL_ONLY) {
            cancelScheduledBackup(context)
            return
        }

        rescheduleWithState(context, prefsState)
    }

    /**
     * Cancels any active periodic or calendar scheduled backup work.
     */
    fun cancelScheduledBackup(context: Context) {
        try {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(LEGACY_WORK_NAME)
            Log.i(TAG, "Successfully cancelled unique backup work: $UNIQUE_WORK_NAME")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel unique work: ${e.message}")
        }
    }

    /**
     * Observes whether the backup work is currently enqueued or running.
     */
    fun observeWorkScheduled(context: Context): Flow<Boolean>? {
        return try {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
                .map { list ->
                    list.any {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                    }
                }
        } catch (e: Exception) {
            null
        }
    }
}
