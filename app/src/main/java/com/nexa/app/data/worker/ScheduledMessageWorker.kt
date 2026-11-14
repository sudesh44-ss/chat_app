package com.nexa.app.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nexa.app.NexaApplication

/**
 * Background worker executed by Android WorkManager when a scheduled message instant arrives.
 *
 * Execution Rules:
 * 1. Checks if scheduled instant has genuinely arrived.
 * 2. Checks if any newer normal outgoing message was sent in the same chat (Critical Auto-Cancellation Rule).
 * 3. Atomically claims the schedule for sending.
 * 4. Dispatches the message via NEXA's existing outbox & E2EE transport pipeline.
 * 5. If Internet is unavailable at execution time, preserves status as PENDING and relies on
 *    WorkManager network constraints for automatic delivery when connectivity returns.
 */
class ScheduledMessageWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val scheduledId = inputData.getString(KEY_SCHEDULED_MESSAGE_ID)
        if (scheduledId.isNullOrBlank()) {
            Log.e(TAG, "ScheduledMessageWorker invoked without scheduled_message_id input")
            return Result.failure()
        }

        val app = applicationContext as? NexaApplication
        if (app == null) {
            Log.e(TAG, "Application context is not NexaApplication")
            return Result.retry()
        }

        val manager = app.scheduledMessageManager
        Log.i(TAG, "Executing scheduled message worker for ID: $scheduledId (runAttemptCount: $runAttemptCount)")

        return try {
            val result = manager.executeScheduledMessage(scheduledId)
            if (result.isSuccess) {
                Result.success()
            } else {
                val error = result.exceptionOrNull()
                Log.w(TAG, "Scheduled message $scheduledId execution returned failure: ${error?.message}")
                if (runAttemptCount < MAX_RETRIES) {
                    Result.retry()
                } else {
                    manager.markScheduleFailed(scheduledId, error?.message ?: "Max retry attempts reached")
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error executing scheduled message $scheduledId", e)
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                manager.markScheduleFailed(scheduledId, e.message ?: "Unexpected execution exception")
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "ScheduledMessageWorker"
        const val KEY_SCHEDULED_MESSAGE_ID = "scheduled_message_id"
        const val TAG_SCHEDULED_MESSAGE = "nexa_scheduled_message"
        private const val MAX_RETRIES = 5

        fun getWorkName(scheduledId: String): String = "scheduled_msg_$scheduledId"
    }
}
