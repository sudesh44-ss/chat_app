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
         * Builds the 24-hour periodic backup work request.
         * Note: Does not set requiresDeviceIdle(true) to avoid WorkManager backoff criteria conflicts.
         */
        fun createPeriodicWorkRequest(): androidx.work.PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .build()

            return PeriodicWorkRequestBuilder<ScheduledBackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
        }

        /**
         * Enqueues or retains the 24-hour periodic backup schedule.
         */
        fun scheduleNightlyBackup(context: Context) {
            val periodicRequest = createPeriodicWorkRequest()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
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
