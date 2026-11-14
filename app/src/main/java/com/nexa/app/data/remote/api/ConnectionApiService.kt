package com.nexa.app.data.remote.api

import android.util.Log
import com.nexa.app.core.network.ServerEndpointConfig
import com.nexa.app.data.remote.dto.BlockedUserItemDto
import com.nexa.app.data.remote.dto.ConnectionItemDto
import com.nexa.app.data.remote.dto.ConnectionRequestItemDto
import com.nexa.app.data.remote.dto.DiscoveredUserDto
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

interface ConnectionApiService {
    suspend fun discoverUser(sessionToken: String, nxId: String): Result<DiscoveredUserDto>
    suspend fun sendRequest(sessionToken: String, recipientNxId: String, introMessage: String?): Result<ConnectionRequestItemDto>
    suspend fun getIncomingRequests(sessionToken: String, limit: Int = 50, offset: Int = 0): Result<List<ConnectionRequestItemDto>>
    suspend fun getOutgoingRequests(sessionToken: String, limit: Int = 50, offset: Int = 0): Result<List<ConnectionRequestItemDto>>
    suspend fun acceptRequest(sessionToken: String, requestId: String): Result<Boolean>
    suspend fun rejectRequest(sessionToken: String, requestId: String): Result<Boolean>
    suspend fun cancelRequest(sessionToken: String, requestId: String): Result<Boolean>
    suspend fun getConnections(sessionToken: String, limit: Int = 50, offset: Int = 0): Result<List<ConnectionItemDto>>
    suspend fun removeConnection(sessionToken: String, targetNxId: String): Result<Boolean>
    suspend fun blockUser(sessionToken: String, targetNxId: String): Result<Boolean>
    suspend fun unblockUser(sessionToken: String, targetNxId: String): Result<Boolean>
    suspend fun getBlockedUsers(sessionToken: String): Result<List<BlockedUserItemDto>>
}

class ConnectionApiServiceImpl(
    private val baseUrl: String = ServerEndpointConfig.httpBaseUrl,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) : ConnectionApiService {

    companion object {
        private const val TAG = "ConnectionApiService"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    override suspend fun discoverUser(sessionToken: String, nxId: String): Result<DiscoveredUserDto> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/users/discover?nx_id=$nxId"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $sessionToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Discover failed (HTTP ${response.code}): $body"))
                }
                val json = JSONObject(body)
                val dto = DiscoveredUserDto(
                    nxId = json.optString("nx_id", nxId),
                    displayName = json.optString("display_name", nxId),
                    avatarUrl = json.optString("avatar_url").takeIf { it.isNotEmpty() },
                    bio = json.optString("bio").takeIf { it.isNotEmpty() },
                    publicKeyHex = json.optString("public_key_hex", ""),
                    isConnected = json.optBoolean("is_connected", false),
                    isPending = json.optBoolean("is_pending", false),
                    isBlocked = json.optBoolean("is_blocked", false),
                    createdAt = json.optLong("created_at", 0L)
                )
                Result.success(dto)
            }
        } catch (e: Exception) {
            Log.e(TAG, "discoverUser failed", e)
            Result.failure(e)
        }
    }

    override suspend fun sendRequest(sessionToken: String, recipientNxId: String, introMessage: String?): Result<ConnectionRequestItemDto> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/connections/request"
            val payload = JSONObject().apply {
                put("recipient_nx_id", recipientNxId)
                if (!introMessage.isNullOrBlank()) {
                    put("intro_message", introMessage)
                }
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $sessionToken")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Send request failed (HTTP ${response.code}): $body"))
                }
                val json = JSONObject(body)
                val dto = ConnectionRequestItemDto(
                    id = json.optString("id"),
                    senderNxId = json.optString("sender_nx_id"),
                    recipientNxId = json.optString("recipient_nx_id"),
                    introMessage = json.optString("intro_message").takeIf { it.isNotEmpty() },
                    status = json.optString("status", "PENDING"),
                    createdAt = json.optLong("created_at", System.currentTimeMillis()),
                    expiresAt = json.optLong("expires_at", 0L)
                )
                Result.success(dto)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendRequest failed", e)
            Result.failure(e)
        }
    }

    override suspend fun getIncomingRequests(sessionToken: String, limit: Int, offset: Int): Result<List<ConnectionRequestItemDto>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/connections/requests/incoming?limit=$limit&offset=$offset"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $sessionToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Incoming requests failed (HTTP ${response.code}): $body"))
                }
                val json = JSONObject(body)
                val arr = json.optJSONArray("requests") ?: JSONArray()
                val list = mutableListOf<ConnectionRequestItemDto>()

                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val profJson = item.optJSONObject("profile")
                    val profileDto = profJson?.let {
                        DiscoveredUserDto(
                            nxId = it.optString("nx_id"),
                            displayName = it.optString("display_name"),
                            avatarUrl = it.optString("avatar_url").takeIf { a -> a.isNotEmpty() },
                            bio = it.optString("bio").takeIf { b -> b.isNotEmpty() },
                            publicKeyHex = it.optString("public_key_hex"),
                            isPending = true
                        )
                    }

                    list.add(
                        ConnectionRequestItemDto(
                            id = item.optString("id"),
                            senderNxId = item.optString("sender_nx_id"),
                            recipientNxId = item.optString("recipient_nx_id"),
                            introMessage = item.optString("intro_message").takeIf { it.isNotEmpty() },
                            status = item.optString("status", "PENDING"),
                            createdAt = item.optLong("created_at", 0L),
                            expiresAt = item.optLong("expires_at", 0L),
                            profile = profileDto
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getIncomingRequests failed", e)
            Result.failure(e)
        }
    }

    override suspend fun getOutgoingRequests(sessionToken: String, limit: Int, offset: Int): Result<List<ConnectionRequestItemDto>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/connections/requests/outgoing?limit=$limit&offset=$offset"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $sessionToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Outgoing requests failed (HTTP ${response.code}): $body"))
                }
                val json = JSONObject(body)
                val arr = json.optJSONArray("requests") ?: JSONArray()
                val list = mutableListOf<ConnectionRequestItemDto>()

                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val profJson = item.optJSONObject("profile")
                    val profileDto = profJson?.let {
                        DiscoveredUserDto(
                            nxId = it.optString("nx_id"),
                            displayName = it.optString("display_name"),
                            avatarUrl = it.optString("avatar_url").takeIf { a -> a.isNotEmpty() },
                            bio = it.optString("bio").takeIf { b -> b.isNotEmpty() },
                            publicKeyHex = it.optString("public_key_hex"),
                            isPending = true
                        )
                    }

                    list.add(
                        ConnectionRequestItemDto(
                            id = item.optString("id"),
                            senderNxId = item.optString("sender_nx_id"),
                            recipientNxId = item.optString("recipient_nx_id"),
                            introMessage = item.optString("intro_message").takeIf { it.isNotEmpty() },
                            status = item.optString("status", "PENDING"),
                            createdAt = item.optLong("created_at", 0L),
                            expiresAt = item.optLong("expires_at", 0L),
                            profile = profileDto
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getOutgoingRequests failed", e)
            Result.failure(e)
        }
    }

    override suspend fun acceptRequest(sessionToken: String, requestId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/connections/requests/accept"
            val payload = JSONObject().apply { put("request_id", requestId) }
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $sessionToken")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Accept failed: HTTP ${response.code}"))
                }
                Result.success(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "acceptRequest failed", e)
            Result.failure(e)
        }
    }

    override suspend fun rejectRequest(sessionToken: String, requestId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/connections/requests/reject"
            val payload = JSONObject().apply { put("request_id", requestId) }
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $sessionToken")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Reject failed: HTTP ${response.code}"))
                }
                Result.success(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "rejectRequest failed", e)
            Result.failure(e)
        }
    }

    override suspend fun cancelRequest(sessionToken: String, requestId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/connections/requests/cancel"
            val payload = JSONObject().apply { put("request_id", requestId) }
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $sessionToken")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Cancel failed: HTTP ${response.code}"))
                }
                Result.success(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "cancelRequest failed", e)
            Result.failure(e)
        }
    }

    override suspend fun getConnections(sessionToken: String, limit: Int, offset: Int): Result<List<ConnectionItemDto>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/connections?limit=$limit&offset=$offset"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $sessionToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Get connections failed (HTTP ${response.code}): $body"))
                }
                val json = JSONObject(body)
                val arr = json.optJSONArray("contacts") ?: JSONArray()
                val list = mutableListOf<ConnectionItemDto>()

                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    list.add(
                        ConnectionItemDto(
                            nxId = item.optString("nx_id"),
                            displayName = item.optString("display_name"),
                            avatarUrl = item.optString("avatar_url").takeIf { it.isNotEmpty() },
                            bio = item.optString("bio").takeIf { it.isNotEmpty() },
                            publicKeyHex = item.optString("public_key_hex"),
                            isConnected = item.optBoolean("is_connected", true),
                            createdAt = item.optLong("created_at", 0L)
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getConnections failed", e)
            Result.failure(e)
        }
    }

    override suspend fun removeConnection(sessionToken: String, targetNxId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/connections/remove"
            val payload = JSONObject().apply { put("target_nx_id", targetNxId) }
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $sessionToken")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Remove connection failed: HTTP ${response.code}"))
                }
                Result.success(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "removeConnection failed", e)
            Result.failure(e)
        }
    }

    override suspend fun blockUser(sessionToken: String, targetNxId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/users/block"
            val payload = JSONObject().apply { put("target_nx_id", targetNxId) }
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $sessionToken")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Block user failed: HTTP ${response.code}"))
                }
                Result.success(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "blockUser failed", e)
            Result.failure(e)
        }
    }

    override suspend fun unblockUser(sessionToken: String, targetNxId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/users/unblock"
            val payload = JSONObject().apply { put("target_nx_id", targetNxId) }
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $sessionToken")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Unblock user failed: HTTP ${response.code}"))
                }
                Result.success(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "unblockUser failed", e)
            Result.failure(e)
        }
    }

    override suspend fun getBlockedUsers(sessionToken: String): Result<List<BlockedUserItemDto>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/v1/users/blocked"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $sessionToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Get blocked users failed (HTTP ${response.code}): $body"))
                }
                val json = JSONObject(body)
                val arr = json.optJSONArray("blocked_users") ?: JSONArray()
                val list = mutableListOf<BlockedUserItemDto>()

                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    list.add(
                        BlockedUserItemDto(
                            nxId = item.optString("nx_id"),
                            displayName = item.optString("display_name"),
                            avatarUrl = item.optString("avatar_url").takeIf { it.isNotEmpty() },
                            bio = item.optString("bio").takeIf { it.isNotEmpty() },
                            publicKeyHex = item.optString("public_key_hex"),
                            createdAt = item.optLong("created_at", 0L)
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getBlockedUsers failed", e)
            Result.failure(e)
        }
    }
}
