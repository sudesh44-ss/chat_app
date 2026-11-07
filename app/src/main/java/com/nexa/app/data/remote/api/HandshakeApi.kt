package com.nexa.app.data.remote.api

import com.nexa.app.core.crypto.PrekeyBundle
import com.nexa.app.core.model.HandshakeEnvelope
import com.nexa.app.data.remote.dto.ClaimResponseDto
import com.nexa.app.data.remote.dto.DispatchRequestDto
import com.nexa.app.data.remote.dto.PrekeyBundleResponse
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
import java.util.UUID
import java.util.concurrent.TimeUnit

interface HandshakeApi {
    suspend fun getUserPrekey(nxId: String): PrekeyBundle
    suspend fun claimPrekey(targetNxId: String, targetDeviceId: String? = null, claimId: String = UUID.randomUUID().toString()): PrekeyBundle
    suspend fun dispatchHandshake(request: DispatchRequestDto): Boolean
    suspend fun getPendingRequests(nxId: String): List<HandshakeEnvelope>
    suspend fun registerPrekeyBundle(bundle: PrekeyBundle): Boolean
}

class HandshakeApiImpl(
    private val baseUrl: String = ServerEndpointConfig.httpBaseUrl,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) : HandshakeApi {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun getUserPrekey(nxId: String): PrekeyBundle = withContext(Dispatchers.IO) {
        // 1. Try Step 3 bundle endpoint first
        val bundleUrl = "$baseUrl/api/v1/users/$nxId/prekey-bundle"
        val bundleRequest = Request.Builder().url(bundleUrl).get().build()
        try {
            client.newCall(bundleRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val dto = PrekeyBundleResponse.fromJson(body)
                    return@withContext PrekeyBundle(
                        nxId = dto.nxId,
                        deviceId = dto.deviceId,
                        identityKeyHex = dto.identityKeyHex,
                        signingKeyHex = dto.signingKeyHex,
                        signedPrekeyHex = dto.signedPrekeyHex,
                        signedPrekeyId = dto.signedPrekeyId,
                        signedPrekeySignatureHex = dto.signedPrekeySignatureHex,
                        oneTimePrekeyHex = dto.oneTimePrekeyHex,
                        oneTimePrekeyId = dto.oneTimePrekeyId
                    )
                }
            }
        } catch (ignored: Exception) {}

        // 2. Fallback to legacy prekey endpoint
        val legacyUrl = "$baseUrl/api/v1/users/$nxId/prekey"
        val request = Request.Builder().url(legacyUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to fetch prekey for $nxId: HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val dto = PrekeyBundleResponse.fromJson(body)
            PrekeyBundle(
                nxId = dto.nxId,
                deviceId = dto.deviceId,
                identityKeyHex = dto.identityKeyHex,
                signingKeyHex = dto.signingKeyHex,
                signedPrekeyHex = dto.signedPrekeyHex,
                signedPrekeyId = dto.signedPrekeyId,
                signedPrekeySignatureHex = dto.signedPrekeySignatureHex,
                oneTimePrekeyHex = dto.oneTimePrekeyHex,
                oneTimePrekeyId = dto.oneTimePrekeyId
            )
        }
    }

    override suspend fun claimPrekey(
        targetNxId: String,
        targetDeviceId: String?,
        claimId: String
    ): PrekeyBundle = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/prekeys/claim"
        val json = JSONObject().apply {
            put("recipient_nx_id", targetNxId)
            put("target_nx_id", targetNxId)
            if (!targetDeviceId.isNullOrBlank()) {
                put("target_device_id", targetDeviceId)
            }
            put("claim_id", claimId)
        }
        val reqBody = json.toString().toRequestBody(jsonMediaType)
        val httpRequest = Request.Builder().url(url).post(reqBody).build()

        try {
            client.newCall(httpRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val dto = ClaimResponseDto.fromJson(body)
                    return@withContext PrekeyBundle(
                        nxId = dto.targetNxId,
                        deviceId = dto.targetDeviceId,
                        identityKeyHex = dto.identityKeyHex,
                        signingKeyHex = dto.signingKeyHex,
                        signedPrekeyHex = dto.signedPrekeyHex,
                        signedPrekeyId = dto.signedPrekeyId,
                        signedPrekeySignatureHex = dto.signedPrekeySignatureHex,
                        oneTimePrekeyHex = dto.oneTimePrekeyHex,
                        oneTimePrekeyId = dto.oneTimePrekeyId
                    )
                }
            }
        } catch (ignored: Exception) {}

        // Fallback to bundle query if claim endpoint fails
        getUserPrekey(targetNxId)
    }

    override suspend fun dispatchHandshake(request: DispatchRequestDto): Boolean = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/requests/dispatch"
        val reqBody = request.toJson().toRequestBody(jsonMediaType)
        val httpRequest = Request.Builder().url(url).post(reqBody).build()

        client.newCall(httpRequest).execute().use { response ->
            if (response.code == 429) {
                throw IOException("Rate limit exceeded: Max 15 friend requests per hour allowed")
            }
            if (response.code == 400) {
                throw IOException("Invalid Proof-of-Work nonce or expired challenge timestamp")
            }
            response.isSuccessful || response.code in 200..204
        }
    }

    override suspend fun getPendingRequests(nxId: String): List<HandshakeEnvelope> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/requests/pending?nx_id=$nxId"
        val request = Request.Builder().url(url).get().build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val array = json.optJSONArray("requests") ?: JSONArray()
            val result = mutableListOf<HandshakeEnvelope>()
            for (i in 0 until array.length()) {
                val envelopeStr = array.getString(i)
                HandshakeEnvelope.fromJson(envelopeStr)?.let { result.add(it) }
            }
            result
        }
    }

    override suspend fun registerPrekeyBundle(bundle: PrekeyBundle): Boolean = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/v1/users/prekey"
        val json = JSONObject().apply {
            put("nx_id", bundle.nxId)
            put("identity_key", bundle.identityKeyHex)
            put("signing_key", bundle.signingKeyHex)
            put("signed_prekey", bundle.signedPrekeyHex)
            put("signed_prekey_sig", bundle.signedPrekeySignatureHex)
            put("one_time_prekey", bundle.oneTimePrekeyHex ?: "")
            put("one_time_prekey_id", bundle.oneTimePrekeyId ?: "")
        }
        val reqBody = json.toString().toRequestBody(jsonMediaType)
        val httpRequest = Request.Builder().url(url).post(reqBody).build()

        client.newCall(httpRequest).execute().use { response ->
            response.isSuccessful
        }
    }
}
