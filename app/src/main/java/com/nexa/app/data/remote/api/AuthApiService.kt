package com.nexa.app.data.remote.api

import android.os.Build
import android.util.Log
import com.nexa.app.core.network.ServerEndpointConfig
import com.nexa.app.data.remote.dto.RegisterInitRequest
import com.nexa.app.data.remote.dto.RegisterInitResponse
import com.nexa.app.data.remote.dto.RegistrationSuccessResponse
import com.nexa.app.data.remote.dto.VerifyOtpRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

interface AuthApiService {
    suspend fun requestOtp(request: RegisterInitRequest): Result<RegisterInitResponse>
    suspend fun verifyOtpAndRegister(request: VerifyOtpRequest): Result<RegistrationSuccessResponse>
    suspend fun verifyGoogleIdToken(idToken: String): Result<String>
    suspend fun updateFcmToken(sessionToken: String, deviceId: String, fcmToken: String): Result<Boolean>
    suspend fun revokeDevice(sessionToken: String, deviceId: String): Result<Boolean>
    suspend fun lookupContacts(sessionToken: String, phoneNumbers: List<String>): Result<List<com.nexa.app.data.remote.dto.ContactMatchDto>>
}

/**
 * Production implementation of AuthApiService connecting to NEXA Go Backend.
 * Uses OkHttpClient to communicate with:
 * - POST /api/v1/auth/otp/request
 * - POST /api/v1/auth/otp/verify
 * - POST /api/v1/auth/google/verify
 * - POST /api/v1/auth/register
 * - POST /api/v1/devices/fcm-token
 * - POST /api/v1/devices/{device_id}/revoke
 */
class AuthApiServiceImpl(
    private val baseUrl: String = ServerEndpointConfig.httpBaseUrl,
    private val isTestMode: Boolean = false,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) : AuthApiService {

    companion object {
        private const val TAG = "AuthApiService"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val secureRandom = SecureRandom()

    override suspend fun requestOtp(request: RegisterInitRequest): Result<RegisterInitResponse> = withContext(Dispatchers.IO) {
        if (isTestMode) {
            val challenge = UUID.randomUUID().toString()
            val expiresAt = System.currentTimeMillis() + (300 * 1000)
            return@withContext Result.success(
                RegisterInitResponse(
                    challenge = challenge,
                    otpSent = true,
                    expiresAt = expiresAt
                )
            )
        }

        try {
            val url = "$baseUrl/api/v1/auth/otp/request"
            val json = JSONObject().apply {
                put("phone_number", request.phoneNumber)
            }

            val httpRequest = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody).optString("error", "HTTP ${response.code}")
                } catch (e: Exception) {
                    "HTTP ${response.code}: $responseBody"
                }
                return@withContext Result.failure(IOException("OTP request failed: $errorMsg"))
            }

            val respJson = JSONObject(responseBody)
            val retryAfter = respJson.optInt("retry_after_seconds", 60)
            val expiresAt = System.currentTimeMillis() + (300 * 1000)

            Result.success(
                RegisterInitResponse(
                    challenge = "otp_req_${System.currentTimeMillis()}",
                    otpSent = respJson.optBoolean("success", true),
                    expiresAt = expiresAt
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch OTP request to backend", e)
            Result.failure(e)
        }
    }

    override suspend fun verifyOtpAndRegister(request: VerifyOtpRequest): Result<RegistrationSuccessResponse> = withContext(Dispatchers.IO) {
        if (isTestMode) {
            val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val randomChars = (1..8).map { charset[secureRandom.nextInt(charset.length)] }.joinToString("")
            val allocatedNxId = "NX-$randomChars"
            val sessionToken = "nexa_sess_" + UUID.randomUUID().toString().replace("-", "")

            return@withContext Result.success(
                RegistrationSuccessResponse(
                    nxId = allocatedNxId,
                    sessionAuthToken = sessionToken,
                    registeredTimestamp = System.currentTimeMillis()
                )
            )
        }

        try {
            // Step 1: Verify OTP code with backend to acquire AuthProofTicket
            val verifyUrl = "$baseUrl/api/v1/auth/otp/verify"
            val verifyJson = JSONObject().apply {
                put("phone_number", request.phoneNumber)
                put("otp_code", request.otpCode)
            }

            val verifyReq = Request.Builder()
                .url(verifyUrl)
                .post(verifyJson.toString().toRequestBody(JSON_MEDIA))
                .build()

            val verifyResp = client.newCall(verifyReq).execute()
            val verifyBody = verifyResp.body?.string() ?: ""

            if (!verifyResp.isSuccessful) {
                val errorMsg = try {
                    JSONObject(verifyBody).optString("error", "Invalid OTP verification")
                } catch (e: Exception) {
                    "HTTP ${verifyResp.code}: $verifyBody"
                }
                return@withContext Result.failure(SecurityException(errorMsg))
            }

            val verifyJsonResp = JSONObject(verifyBody)
            val authTicket = verifyJsonResp.optString("auth_proof_ticket").takeIf { it.isNotEmpty() }
                ?: verifyJsonResp.optString("auth_ticket")
            if (authTicket.isNullOrEmpty()) {
                return@withContext Result.failure(SecurityException("Missing auth_proof_ticket in server response"))
            }

            // Step 2: Register account & bind cryptographic public keys to server-allocated NX-ID
            val registerUrl = "$baseUrl/api/v1/auth/register"
            val deviceId = "dev_" + UUID.randomUUID().toString().take(12)
            val deviceName = try {
                "${Build.MANUFACTURER} ${Build.MODEL}"
            } catch (e: Throwable) {
                "Android Device"
            }

            val registerJson = JSONObject().apply {
                put("auth_proof_ticket", authTicket)
                put("auth_type", "PHONE_OTP")
                put("public_key_hex", request.publicKeyHex)
                put("device_id", deviceId)
                put("device_name", deviceName)
                put("profile_name", request.profileName)
                put("protocol_version", 1)
            }

            val registerReq = Request.Builder()
                .url(registerUrl)
                .post(registerJson.toString().toRequestBody(JSON_MEDIA))
                .build()

            val registerResp = client.newCall(registerReq).execute()
            val registerBody = registerResp.body?.string() ?: ""

            if (!registerResp.isSuccessful) {
                val errorMsg = try {
                    JSONObject(registerBody).optString("error", "Registration rejected")
                } catch (e: Exception) {
                    "HTTP ${registerResp.code}: $registerBody"
                }
                return@withContext Result.failure(SecurityException(errorMsg))
            }

            val regJsonResp = JSONObject(registerBody)
            val allocatedNxId = regJsonResp.getString("nx_id")
            val sessionToken = regJsonResp.getString("session_token")
            val registeredTs = regJsonResp.optLong("registered_timestamp", System.currentTimeMillis())

            Log.i(TAG, "Successfully registered identity with server: NX-ID=$allocatedNxId")

            Result.success(
                RegistrationSuccessResponse(
                    nxId = allocatedNxId,
                    sessionAuthToken = sessionToken,
                    registeredTimestamp = registeredTs
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "OTP verification & registration failed", e)
            Result.failure(e)
        }
    }

    override suspend fun verifyGoogleIdToken(idToken: String): Result<String> = withContext(Dispatchers.IO) {
        if (isTestMode) {
            return@withContext Result.success("test_ticket_" + UUID.randomUUID())
        }

        try {
            val url = "$baseUrl/api/v1/auth/google/verify"
            val json = JSONObject().apply {
                put("google_id_token", idToken)
            }

            val req = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON_MEDIA))
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                val errorMsg = try {
                    JSONObject(body).optString("error", "Google token verification failed")
                } catch (e: Exception) {
                    "HTTP ${resp.code}: $body"
                }
                return@withContext Result.failure(SecurityException(errorMsg))
            }

            val respJson = JSONObject(body)
            val authTicket = respJson.getString("auth_proof_ticket")
            Result.success(authTicket)
        } catch (e: Exception) {
            Log.e(TAG, "Google ID token verification failed", e)
            Result.failure(e)
        }
    }

    override suspend fun updateFcmToken(
        sessionToken: String,
        deviceId: String,
        fcmToken: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (isTestMode) return@withContext Result.success(true)

        try {
            val url = "$baseUrl/api/v1/devices/fcm-token"
            val json = JSONObject().apply {
                put("device_id", deviceId)
                put("fcm_token", fcmToken)
            }

            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $sessionToken")
                .post(json.toString().toRequestBody(JSON_MEDIA))
                .build()

            val resp = client.newCall(req).execute()
            Result.success(resp.isSuccessful)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update FCM device token on server: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun revokeDevice(
        sessionToken: String,
        deviceId: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (isTestMode) return@withContext Result.success(true)

        try {
            val url = "$baseUrl/api/v1/devices/$deviceId/revoke"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $sessionToken")
                .post("{}".toRequestBody(JSON_MEDIA))
                .build()

            val resp = client.newCall(req).execute()
            Result.success(resp.isSuccessful)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to revoke device $deviceId: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun lookupContacts(
        sessionToken: String,
        phoneNumbers: List<String>
    ): Result<List<com.nexa.app.data.remote.dto.ContactMatchDto>> = withContext(Dispatchers.IO) {
        if (phoneNumbers.isEmpty()) return@withContext Result.success(emptyList())

        if (isTestMode) {
            return@withContext Result.success(emptyList())
        }

        try {
            val url = "$baseUrl/api/v1/contacts/lookup"
            val jsonArray = org.json.JSONArray()
            phoneNumbers.forEach { jsonArray.put(it) }
            val reqJson = JSONObject().apply {
                put("phone_numbers", jsonArray)
            }

            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $sessionToken")
                .post(reqJson.toString().toRequestBody(JSON_MEDIA))
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return@withContext Result.failure(IOException("Server error HTTP ${resp.code}: $body"))
            }

            val jsonResp = JSONObject(body)
            val matchesJson = jsonResp.optJSONArray("matches") ?: org.json.JSONArray()
            val matches = mutableListOf<com.nexa.app.data.remote.dto.ContactMatchDto>()
            for (i in 0 until matchesJson.length()) {
                val item = matchesJson.getJSONObject(i)
                matches.add(
                    com.nexa.app.data.remote.dto.ContactMatchDto(
                        phoneNumber = item.optString("phone_number"),
                        nxId = item.optString("nx_id"),
                        displayName = item.optString("display_name"),
                        publicKeyHex = item.optString("public_key_hex")
                    )
                )
            }
            Result.success(matches)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to perform contact lookup against backend: ${e.message}")
            Result.failure(e)
        }
    }
}
