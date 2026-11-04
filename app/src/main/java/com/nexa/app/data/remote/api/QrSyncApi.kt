package com.nexa.app.data.remote.api

import com.nexa.app.core.network.ServerEndpointConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

interface QrSyncApi {
    suspend fun syncQrHandshake(
        scannerNxId: String,
        scannedNxId: String,
        scannedSignature: String
    ): Boolean
}

class QrSyncApiImpl(
    private val baseUrl: String = ServerEndpointConfig.httpBaseUrl,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
) : QrSyncApi {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun syncQrHandshake(
        scannerNxId: String,
        scannedNxId: String,
        scannedSignature: String
    ): Boolean = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/contacts/sync-qr"
        val json = JSONObject().apply {
            put("scanner_nx_id", scannerNxId)
            put("scanned_nx_id", scannedNxId)
            put("scanned_signature", scannedSignature)
        }

        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code in 200..204
            }
        } catch (e: Exception) {
            false // Safe graceful degradation when offline
        }
    }
}
