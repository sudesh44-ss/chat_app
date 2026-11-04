package com.nexa.app.data.remote.api

import android.util.Base64
import android.util.Log
import com.nexa.app.core.network.ServerEndpointConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

interface DtnRelayApi {
    suspend fun dumpOpportunisticEnvelopes(envelopes: List<ByteArray>): Result<Int>
}

class DtnRelayApiImpl(
    private val baseUrl: String = DEFAULT_RELAY_URL,
    private val client: OkHttpClient = defaultClient
) : DtnRelayApi {

    override suspend fun dumpOpportunisticEnvelopes(envelopes: List<ByteArray>): Result<Int> = withContext(Dispatchers.IO) {
        if (envelopes.isEmpty()) return@withContext Result.success(0)

        try {
            val jsonArray = JSONArray()
            for (envelopeBytes in envelopes) {
                val b64 = Base64.encodeToString(envelopeBytes, Base64.NO_WRAP)
                jsonArray.put(b64)
            }

            val requestJson = JSONObject().apply {
                put("envelopes", jsonArray)
            }

            val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/v1/relay/opportunistic-dump")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val count = envelopes.size
                    Log.i(TAG, "Successfully dumped $count opportunistic envelopes to Cloud Bridge")
                    Result.success(count)
                } else {
                    val errorMsg = "Cloud Bridge rejected dump: HTTP ${resp.code} ${resp.message}"
                    Log.w(TAG, errorMsg)
                    Result.failure(IOException(errorMsg))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error during opportunistic cloud dump", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "DtnRelayApiImpl"
        val DEFAULT_RELAY_URL = ServerEndpointConfig.httpBaseUrl

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
