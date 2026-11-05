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
    suspend fun googleAuth(idToken: String, deviceId: String? = null, publicKeyHex: String? = null): Result<com.nexa.app.data.remote.dto.GoogleAuthResponse>
    suspend fun initiateReverseVerify(request: com.nexa.app.data.remote.dto.InitiateReverseVerifyRequestDto): Result<com.nexa.app.data.remote.dto.InitiateReverseVerifyResponseDto>
    suspend fun getVerificationStatus(nonce: String): Result<com.nexa.app.data.remote.dto.VerificationStatusDto>
    suspend fun resolveUser(target: String, sessionToken: String? = null): Result<com.nexa.app.data.remote.dto.UserResolveDto>
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
                if (!request.googleAuthToken.isNullOrBlank()) {
                    put("google_auth_token", request.googleAuthToken)
                }
            }

            val httpRequest = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorJson = try { JSONObject(responseBody) } catch (e: Exception) { null }
                val errorCode = errorJson?.optString("code", "") ?: ""
                val errorMsg = errorJson?.optString("error", "HTTP ${response.code}") ?: "HTTP ${response.code}: $responseBody"
                val finalMsg = when {
                    response.code == 503 || errorCode == "SMS_UNAVAILABLE" ->
                        "SMS service is temporarily unavailable. Please check backend SMS provider configuration."
                    response.code == 429 || errorCode == "RATE_LIMITED" ->
                        errorMsg
                    else -> errorMsg
                }
                return@withContext Result.failure(IOException(finalMsg))
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
            val sessionToken = regJsonResp.optString("session_auth_token").takeIf { it.isNotEmpty() }
                ?: regJsonResp.getString("session_token")
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

    override suspend fun googleAuth(
        idToken: String,
        deviceId: String?,
        publicKeyHex: String?
    ): Result<com.nexa.app.data.remote.dto.GoogleAuthResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/auth/google"
            val json = JSONObject().apply {
                put("id_token", idToken)
                if (!deviceId.isNullOrBlank()) put("device_id", deviceId)
                if (!publicKeyHex.isNullOrBlank()) put("public_key_hex", publicKeyHex)
            }

            val req = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON_MEDIA))
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(IOException("Google authentication rejected (HTTP ${resp.code}): $body"))
            }

            val respJson = JSONObject(body)
            val userJson = respJson.optJSONObject("user")
            val status = respJson.optString("status", "NEW_USER")

            val responseDto = com.nexa.app.data.remote.dto.GoogleAuthResponse(
                status = status,
                googleUid = respJson.optString("google_uid").takeIf { it.isNotEmpty() },
                email = respJson.optString("email").takeIf { it.isNotEmpty() },
                prefillName = respJson.optString("prefill_name").takeIf { it.isNotEmpty() },
                prefillAvatar = respJson.optString("prefill_avatar").takeIf { it.isNotEmpty() },
                sessionAuthToken = respJson.optString("session_auth_token").takeIf { it.isNotEmpty() }
                    ?: respJson.optString("session_token").takeIf { it.isNotEmpty() },
                nxId = respJson.optString("nx_id").takeIf { it.isNotEmpty() }
                    ?: userJson?.optString("nx_id"),
                registeredPhoneMasked = respJson.optString("registered_phone_masked").takeIf { it.isNotEmpty() },
                phoneNumber = respJson.optString("phone_number").takeIf { it.isNotEmpty() }
                    ?: userJson?.optString("phone"),
                inboundReceiverNumber = respJson.optString("inbound_receiver_number").takeIf { it.isNotEmpty() }
            )
            Result.success(responseDto)
        } catch (e: Exception) {
            Log.e(TAG, "Google auth call failed", e)
            Result.failure(e)
        }
    }

    override suspend fun initiateReverseVerify(
        request: com.nexa.app.data.remote.dto.InitiateReverseVerifyRequestDto
    ): Result<com.nexa.app.data.remote.dto.InitiateReverseVerifyResponseDto> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/auth/initiate-reverse-verify"
            val json = JSONObject().apply {
                put("google_uid", request.googleUid)
                if (!request.googleAuthToken.isNullOrBlank()) put("google_auth_token", request.googleAuthToken)
                if (!request.email.isNullOrBlank()) put("email", request.email)
                put("phone_number", request.phoneNumber)
                put("name", request.name)
                if (!request.avatarUrl.isNullOrBlank()) put("avatar_url", request.avatarUrl)
                if (!request.gender.isNullOrBlank()) put("gender", request.gender)
                if (request.age != null) put("age", request.age)
                if (!request.bio.isNullOrBlank()) put("bio", request.bio)
                put("public_key_hex", request.publicKeyHex)
                if (!request.dhPublicKeyHex.isNullOrBlank()) put("dh_public_key_hex", request.dhPublicKeyHex)
                if (!request.deviceId.isNullOrBlank()) put("device_id", request.deviceId)
                if (!request.deviceName.isNullOrBlank()) put("device_name", request.deviceName)
                put("is_reverify", request.isReverify)
            }

            val req = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON_MEDIA))
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                val errorMsg = try { JSONObject(body).optString("error", "HTTP ${resp.code}") } catch (_: Exception) { body }
                return@withContext Result.failure(IOException("Reverse verification request failed: $errorMsg"))
            }

            val respJson = JSONObject(body)
            val result = com.nexa.app.data.remote.dto.InitiateReverseVerifyResponseDto(
                status = respJson.optString("status", "PENDING"),
                nonce = respJson.getString("nonce"),
                inboundReceiverNumber = respJson.getString("inbound_receiver_number"),
                smsPayload = respJson.optString("sms_payload", "NX-VERIFY " + respJson.optString("nonce")),
                expiresInSeconds = respJson.optInt("expires_in_seconds", 90)
            )
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate reverse verification", e)
            Result.failure(e)
        }
    }

    override suspend fun getVerificationStatus(
        nonce: String
    ): Result<com.nexa.app.data.remote.dto.VerificationStatusDto> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/auth/verification-status?nonce=${java.net.URLEncoder.encode(nonce, "UTF-8")}"
            val req = Request.Builder()
                .url(url)
                .get()
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(IOException("Status query failed (HTTP ${resp.code})"))
            }

            val respJson = JSONObject(body)
            val userJson = respJson.optJSONObject("user")
            val status = respJson.optString("status", "PENDING")

            val dto = com.nexa.app.data.remote.dto.VerificationStatusDto(
                status = status,
                expiresInSeconds = respJson.optInt("expires_in_seconds"),
                nxId = respJson.optString("nx_id").takeIf { it.isNotEmpty() } ?: userJson?.optString("nx_id"),
                sessionAuthToken = respJson.optString("session_auth_token").takeIf { it.isNotEmpty() }
                    ?: respJson.optString("session_token").takeIf { it.isNotEmpty() },
                name = userJson?.optString("profile_name"),
                phone = userJson?.optString("phone_number"),
                avatarUrl = userJson?.optString("avatar_url")
            )
            Result.success(dto)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun resolveUser(
        target: String,
        sessionToken: String?
    ): Result<com.nexa.app.data.remote.dto.UserResolveDto> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/users/resolve?target=${java.net.URLEncoder.encode(target, "UTF-8")}"
            val builder = Request.Builder().url(url).get()
            if (!sessionToken.isNullOrBlank()) {
                builder.addHeader("Authorization", "Bearer $sessionToken")
            }

            val resp = client.newCall(builder.build()).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(IOException("User not found or lookup failed (HTTP ${resp.code})"))
            }

            val respJson = JSONObject(body)
            val dto = com.nexa.app.data.remote.dto.UserResolveDto(
                nxId = respJson.getString("nx_id"),
                name = respJson.getString("name"),
                avatarUrl = respJson.optString("avatar_url").takeIf { it.isNotEmpty() },
                bio = respJson.optString("bio").takeIf { it.isNotEmpty() },
                isFriend = respJson.optBoolean("is_friend", false)
            )
            Result.success(dto)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
