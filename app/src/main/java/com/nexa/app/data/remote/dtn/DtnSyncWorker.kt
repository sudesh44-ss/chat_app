package com.nexa.app.data.remote.dtn

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexa.app.NexaApplication
import com.nexa.app.data.remote.api.DtnRelayApiImpl
import java.util.concurrent.TimeUnit

/**
 * Opportunistic Cloud Bridge Background Worker.
 * Automatically triggered by WorkManager as soon as an active Internet network is detected.
 * Batches held DTN envelopes from local SQLCipher storage and dumps them to the Cloud Relay.
 */
class DtnSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "DtnSyncWorker awakened by system network connectivity trigger")

        val app = applicationContext as? NexaApplication
        if (app == null) {
            Log.e(TAG, "Application context is not NexaApplication; aborting sync")
            return Result.failure()
        }

        val envelopeDao = app.database.dtnEnvelopeDao()
        val relayApi = app.dtnRelayApi

        val nowSec = System.currentTimeMillis() / 1000L

        // 1. Purge expired envelopes past their 48-hour TTL
        val purged = envelopeDao.purgeExpiredEnvelopes(nowSec)
        if (purged > 0) {
            Log.d(TAG, "Purged $purged expired envelopes from local storage")
        }

        // 2. Fetch pending active envelopes
        val pending = envelopeDao.getActiveEnvelopesForSync(nowSec)
        if (pending.isEmpty()) {
            Log.d(TAG, "No pending DTN envelopes to dump; exiting work successfully")
            return Result.success()
        }

        Log.i(TAG, "Preparing opportunistic cloud dump for ${pending.size} envelope(s)...")

        // 3. Batch POST to Cloud Bridge endpoint
        return try {
            val batch = pending.map { it.rawEnvelopeBytes }
            val dumpResult = relayApi.dumpOpportunisticEnvelopes(batch)

            if (dumpResult.isSuccess) {
                // Successfully forwarded to cloud bridge -> Reclaim local storage
                for (item in pending) {
                    envelopeDao.deleteEnvelope(item.envelopeIdHex)
                }
                Log.i(TAG, "Successfully dumped and purged ${pending.size} envelope(s) to cloud bridge")
                Result.success()
            } else {
                Log.w(TAG, "Opportunistic dump failed: ${dumpResult.exceptionOrNull()?.message}; scheduling retry")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during opportunistic cloud dump", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DtnSyncWorker"
        const val WORK_NAME = "nexa_dtn_opportunistic_dump"

        /**
         * Enqueues an opportunistic cloud dump job with CONNECTED network constraint.
         */
        fun scheduleOpportunisticDump(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<DtnSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.d(TAG, "Scheduled opportunistic cloud dump constraint with WorkManager")
        }
    }
}
