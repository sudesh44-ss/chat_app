package com.nexa.app.data.remote.security

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexa.app.NexaApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker executing weekly differential OTA signature synchronization.
 * Fetches compiled YARA rules and Bloom filter delta blobs over unmetered Wi-Fi networks.
 */
class RuleSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val prefs by lazy {
        applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? NexaApplication
        val apiService = SecurityApiServiceImpl()
        val storedEtag = prefs.getString(KEY_ETAG, null)

        val bundle = apiService.downloadRuleBundle(storedEtag) ?: return@withContext Result.retry()

        if (bundle.notModified) {
            return@withContext Result.success()
        }

        // Save compiled YARA rules binary
        bundle.yaraRulesBytes?.let { yaraData ->
            val securityDir = File(applicationContext.filesDir, "security").apply { if (!exists()) mkdirs() }
            val rulesFile = File(securityDir, "rules.yac")
            FileOutputStream(rulesFile).use { it.write(yaraData) }

            // If app is running, reload into YaraNativeBridge
            app?.yaraNativeBridge?.loadRules(rulesFile)
        }

        // Merge Bloom Filter delta
        bundle.bloomDeltaBytes?.let { deltaBytes ->
            if (deltaBytes.isNotEmpty()) {
                app?.threatBloomFilter?.let { filter ->
                    // Merge new bits into active in-memory filter
                    val existing = filter.exportBytes()
                    val merged = ByteArray(maxOf(existing.size, deltaBytes.size))
                    for (i in merged.indices) {
                        val b1 = if (i < existing.size) existing[i] else 0
                        val b2 = if (i < deltaBytes.size) deltaBytes[i] else 0
                        merged[i] = (b1.toInt() or b2.toInt()).toByte()
                    }
                    filter.loadFromBytes(merged)
                }
            }
        }

        // Save updated ETag and Version
        prefs.edit()
            .putString(KEY_ETAG, bundle.etag)
            .putString(KEY_VERSION, bundle.version)
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .apply()

        Result.success()
    }

    companion object {
        private const val PREFS_NAME = "nexa_security_sync_prefs"
        private const val KEY_ETAG = "rule_etag"
        private const val KEY_VERSION = "rule_version"
        private const val KEY_LAST_SYNC = "last_sync_timestamp"
        private const val WORK_NAME = "NexaSecurityRuleSyncWork"

        fun scheduleWeeklySync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi only
                .setRequiresBatteryNotLow(true)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<RuleSyncWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        }
    }
}
