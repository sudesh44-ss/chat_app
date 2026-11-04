package com.nexa.app.data.remote.security

import com.nexa.app.core.network.ServerEndpointConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RulesVersionResponse(
    val version: String,
    val bloomEntries: Int,
    val timestamp: Long
)

data class RuleBundleResponse(
    val notModified: Boolean,
    val etag: String?,
    val yaraRulesBytes: ByteArray?,
    val bloomDeltaBytes: ByteArray?,
    val version: String?
)

interface SecurityApiService {
    suspend fun fetchRulesVersion(): RulesVersionResponse?
    suspend fun downloadRuleBundle(etag: String? = null): RuleBundleResponse?
}

class SecurityApiServiceImpl(
    private val baseUrl: String = ServerEndpointConfig.securityRulesUrl,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) : SecurityApiService {

    override suspend fun fetchRulesVersion(): RulesVersionResponse? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/security/rules/version")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)
                RulesVersionResponse(
                    version = json.optString("version", "2026.11.03"),
                    bloomEntries = json.optInt("bloom_entries", 0),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun downloadRuleBundle(etag: String?): RuleBundleResponse? = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder()
            .url("$baseUrl/api/v1/security/rules/bundle")
            .get()

        if (!etag.isNullOrBlank()) {
            reqBuilder.header("If-None-Match", etag)
        }

        try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                if (response.code == 304) {
                    return@withContext RuleBundleResponse(
                        notModified = true,
                        etag = etag,
                        yaraRulesBytes = null,
                        bloomDeltaBytes = null,
                        version = null
                    )
                }

                if (!response.isSuccessful) return@withContext null

                val newEtag = response.header("ETag")
                val version = response.header("X-Rules-Version") ?: "2026.11.03"
                val bundleBytes = response.body?.bytes() ?: return@withContext null

                // Bundle is structured as: [4 bytes YARA len][YARA rules bytes][Remaining: Bloom Delta bytes]
                if (bundleBytes.size >= 4) {
                    val yaraLen = ((bundleBytes[0].toInt() and 0xFF) shl 24) or
                            ((bundleBytes[1].toInt() and 0xFF) shl 16) or
                            ((bundleBytes[2].toInt() and 0xFF) shl 8) or
                            (bundleBytes[3].toInt() and 0xFF)

                    val yaraBytes = bundleBytes.copyOfRange(4, 4 + yaraLen)
                    val bloomBytes = bundleBytes.copyOfRange(4 + yaraLen, bundleBytes.size)

                    RuleBundleResponse(
                        notModified = false,
                        etag = newEtag,
                        yaraRulesBytes = yaraBytes,
                        bloomDeltaBytes = bloomBytes,
                        version = version
                    )
                } else {
                    RuleBundleResponse(
                        notModified = false,
                        etag = newEtag,
                        yaraRulesBytes = bundleBytes,
                        bloomDeltaBytes = ByteArray(0),
                        version = version
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
