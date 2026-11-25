package com.nexa.app.data.worker

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import com.nexa.app.NexaApplication
import com.nexa.app.core.notification.NexaNotificationChannelManager
import com.nexa.app.domain.manager.BackupScheduleManager
import com.nexa.app.domain.model.BackupMode

/**
 * Resilient background backup worker executed by Android WorkManager.
 * Runs on user-configured schedule (Daily, Weekly, Monthly) under smart network and power constraints.
 * Operates as a Foreground Service with persistent progress notifications during execution.
 * Enforces Manual Only mode gating and cooperative cancellation.
 */
class ScheduledBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? NexaApplication ?: return Result.failure()
        val prefsState = app.backupPrefs.state.value

        // 1. Strict Manual Only & Auto-Backup Disabled Gating
        if (!prefsState.autoBackupEnabled || prefsState.backupMode == BackupMode.MANUAL_ONLY) {
            Log.i(TAG, "Automatic backup triggered while disabled or in MANUAL_ONLY mode. Aborting and cancelling schedule.")
            BackupScheduleManager.cancelScheduledBackup(applicationContext)
            return Result.success()
        }

        // 1b. Validate Categories Selected
        if (!prefsState.categoryConfig.hasAnySelected()) {
            Log.i(TAG, "All backup categories are disabled. Skipping automated scheduled backup.")
            return Result.success()
        }

        // 2. Validate Google Drive Authentication (Non-retryable fatal error)
        if (!app.googleDriveAuthManager.isAuthorized()) {
            Log.e(TAG, "Google Drive account is not authorized. Fatal error - aborting without retry.")
            showFailureNotification("Backup failed: Google Drive account not linked")
            return Result.failure()
        }

        // 3. Validate E2EE Backup Credential (Non-retryable fatal error)
        val passphrase = app.driveBackupRepository.getActiveBackupCredential()
            ?: app.driveBackupRepository.getRecoveryPassphrase()
        if (passphrase.isNullOrBlank()) {
            Log.e(TAG, "E2EE backup credentials not configured. Fatal error - aborting without retry.")
            showFailureNotification("Backup failed: E2EE backup credentials missing")
            return Result.failure()
        }

        // 4. Cooperative Cancellation Check
        if (isStopped) {
            Log.i(TAG, "Worker stopped before beginning backup execution.")
            return Result.retry()
        }

        // 5. Elevate to Foreground Service with Progress Notification
        setForegroundSafely("Preparing automated cloud backup...", 0)

        // 6. Execute Silent Backup Pipeline with Real Engine Progress
        val repository = app.driveBackupRepository
        val success = try {
            repository.performSilentBackup { phase, percent ->
                if (!isStopped) {
                    val intPercent = (percent * 100).toInt().coerceIn(0, 100)
                    setForegroundSafely(phase, intPercent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Silent background backup encountered an unexpected exception: ${e.message}", e)
            false
        }

        // 7. Handle Cooperative Cancellation during Execution
        if (isStopped) {
            Log.i(TAG, "Worker stopped cooperatively during backup execution.")
            return Result.retry()
        }

        // 8. Handle Success or Exponential Backoff Retry
        return if (success) {
            Log.i(TAG, "Scheduled cloud backup completed successfully.")
            showSuccessNotification("Cloud backup completed successfully")
            // Schedule next calendar occurrence with zero drift
            BackupScheduleManager.scheduleNextOccurrence(applicationContext)
            Result.success()
        } else {
            Log.w(TAG, "Silent backup failed. Run attempt: $runAttemptCount of $MAX_RETRIES")
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                showFailureNotification("Automated backup failed after $runAttemptCount attempts")
                // On exhausted retries, schedule next calendar occurrence so subsequent cycles continue
                BackupScheduleManager.scheduleNextOccurrence(applicationContext)
                Result.failure()
            }
        }
    }

    private suspend fun setForegroundSafely(statusText: String, progressPercent: Int) {
        val app = applicationContext as? NexaApplication ?: return
        if (!app.backupPrefs.backupNotificationsEnabled) return

        try {
            val foregroundInfo = createForegroundInfo(statusText, progressPercent)
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground info: ${e.message}")
        }
    }

    private fun createForegroundInfo(statusText: String, progressPercent: Int): ForegroundInfo {
        NexaNotificationChannelManager.createAllNotificationChannels(applicationContext)

        val channelId = NexaNotificationChannelManager.CHANNEL_BACKUP_RESTORE
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("NEXA Cloud Backup")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, progressPercent, progressPercent == 0)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID_FOREGROUND, notification)
        }
    }

    private fun showSuccessNotification(message: String) {
        val app = applicationContext as? NexaApplication ?: return
        if (!app.backupPrefs.backupNotificationsEnabled) return

        try {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val channelId = NexaNotificationChannelManager.CHANNEL_BACKUP_RESTORE
            val notification = NotificationCompat.Builder(applicationContext, channelId)
                .setContentTitle("NEXA Backup Complete")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            notificationManager.notify(NOTIFICATION_ID_RESULT, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to display success notification: ${e.message}")
        }
    }

    private fun showFailureNotification(message: String) {
        val app = applicationContext as? NexaApplication ?: return
        if (!app.backupPrefs.backupNotificationsEnabled) return

        try {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val channelId = NexaNotificationChannelManager.CHANNEL_BACKUP_RESTORE
            val notification = NotificationCompat.Builder(applicationContext, channelId)
                .setContentTitle("NEXA Backup Issue")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            notificationManager.notify(NOTIFICATION_ID_RESULT, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to display failure notification: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ScheduledBackupWorker"
        const val UNIQUE_WORK_NAME = "nexa_nightly_backup"

        private const val NOTIFICATION_ID_FOREGROUND = 2003
        private const val NOTIFICATION_ID_RESULT = 2004
        private const val MAX_RETRIES = 3

        /**
         * Builds periodic work request delegating to [BackupScheduleManager].
         */
        fun createPeriodicWorkRequest(context: Context? = null): PeriodicWorkRequest {
            val app = context?.applicationContext as? NexaApplication
            val prefs = app?.backupPrefs?.state?.value ?: com.nexa.app.data.local.preferences.BackupPreferencesState()
            return BackupScheduleManager.buildPeriodicWorkRequest(prefs)
        }

        /**
         * Enqueues or updates the periodic backup schedule.
         */
        fun scheduleNightlyBackup(context: Context) {
            BackupScheduleManager.reschedule(context)
        }

        /**
         * Cancels the periodic scheduled backup.
         */
        fun cancelNightlyBackup(context: Context) {
            BackupScheduleManager.cancelScheduledBackup(context)
        }
    }
}
