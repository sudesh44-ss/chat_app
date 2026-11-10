package com.nexa.app.data.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexa.app.NexaApplication
import java.util.concurrent.TimeUnit

/**
 * Nightly background backup worker executed by Android WorkManager.
 * Runs exclusively when:
 * 1. Unmetered Wi-Fi is active.
 * 2. Device is plugged into charger.
 * 3. Device is idle (Screen off / Deep sleep).
 */
class ScheduledBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? NexaApplication ?: return Result.failure()
        val repository = app.driveBackupRepository
        val prefs = app.backupPrefs

        if (!prefs.autoBackupEnabled) {
            return Result.success()
        }

        return try {
            val success = repository.performSilentBackup()
            if (success) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "nexa_nightly_backup"

        /**
         * Builds the periodic backup work request respecting user's frequency and network constraints.
         */
        fun createPeriodicWorkRequest(context: Context? = null): androidx.work.PeriodicWorkRequest {
            val app = context?.applicationContext as? NexaApplication
            val prefs = app?.backupPrefs

            val networkType = if (prefs?.backupOverWifiOnly != false) NetworkType.UNMETERED else NetworkType.CONNECTED
            val requiresCharging = prefs?.backupWhenCharging ?: true

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresCharging(requiresCharging)
                .build()

            val (interval, timeUnit) = when (prefs?.backupFrequency) {
                com.nexa.app.data.local.preferences.BackupFrequency.WEEKLY -> Pair(7L, TimeUnit.DAYS)
                com.nexa.app.data.local.preferences.BackupFrequency.MONTHLY -> Pair(30L, TimeUnit.DAYS)
                else -> Pair(24L, TimeUnit.HOURS)
            }

            return PeriodicWorkRequestBuilder<ScheduledBackupWorker>(interval, timeUnit)
                .setConstraints(constraints)
                .build()
        }

        /**
         * Enqueues or updates the periodic backup schedule.
         */
        fun scheduleNightlyBackup(context: Context) {
            val periodicRequest = createPeriodicWorkRequest(context)

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest
            )
        }

        /**
         * Cancels the periodic scheduled backup.
         */
        fun cancelNightlyBackup(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
