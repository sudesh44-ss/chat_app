package com.nexa.app.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexa.app.NexaApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Daily maintenance worker reclaiming storage from abandoned partial downloads (Scenario 18).
 * Deletes orphaned .nxdl files inactive for more than 7 days.
 */
class OrphanCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? NexaApplication ?: return@withContext Result.failure()
        val dao = app.database.transferSessionDao()

        val sevenDaysAgoEpoch = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        val abandonedTransfers = dao.getIncompleteTransfersOlderThan(sevenDaysAgoEpoch)

        abandonedTransfers.forEach { transfer ->
            val file = File(transfer.localFilePath)
            if (file.exists() && file.name.endsWith(".nxdl")) {
                file.delete()
            }
            dao.deleteTransfer(transfer.transferId)
        }

        Result.success()
    }

    companion object {
        const val WORK_NAME = "orphan_transfer_cleanup"

        fun schedulePeriodicCleanup(context: Context) {
            val request = PeriodicWorkRequestBuilder<OrphanCleanupWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
