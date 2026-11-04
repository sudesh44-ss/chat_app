package com.nexa.app.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexa.app.NexaApplication
import com.nexa.app.core.network.ServerEndpointConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Opportunistic Cloud Offload Worker:
 * When an unmetered Internet connection becomes available, flushes DTN envelopes
 * from local store-and-forward outbox to the Go relay server.
 */
class DtnOpportunisticDumpWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? NexaApplication ?: return@withContext Result.failure()
        val dao = app.database.meshOutboxDao()
        val pending = dao.getPendingEnvelopesList()

        if (pending.isEmpty()) return@withContext Result.success()

        val jsonArray = JSONArray()
        pending.forEach { env ->
            val obj = JSONObject().apply {
                put("envelope_id", env.envelopeId)
                put("destination_id", env.destinationId)
                put("payload_hex", env.payload.joinToString("") { "%02x".format(it) })
                put("created_at", env.createdAt)
            }
            jsonArray.put(obj)
        }

        val requestBody = jsonArray.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${ServerEndpointConfig.httpBaseUrl}/api/v1/relay/opportunistic-dump")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 201) {
                    pending.forEach { dao.deleteEnvelope(it.envelopeId) }
                    Result.success()
                } else {
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "dtn_opportunistic_dump"

        fun scheduleOpportunisticDump(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<DtnOpportunisticDumpWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
