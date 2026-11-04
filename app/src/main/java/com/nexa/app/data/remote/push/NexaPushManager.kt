package com.nexa.app.data.remote.push

import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.data.remote.api.AuthApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manages Push Notification Token lifecycle and token registration to NEXA relay server.
 */
class NexaPushManager(
    private val context: Context,
    private val authApiService: AuthApiService,
    private val secureStorage: SecureStorage,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    companion object {
        private const val TAG = "NexaPushManager"
        private const val PREFS_PUSH = "nexa_push_prefs"
        private const val KEY_FCM_TOKEN = "cached_fcm_token"
    }

    private val prefs = context.getSharedPreferences(PREFS_PUSH, Context.MODE_PRIVATE)

    fun getCachedToken(): String? = prefs.getString(KEY_FCM_TOKEN, null)

    fun onNewPushToken(newToken: String) {
        if (newToken.isBlank()) return
        Log.i(TAG, "New push token received: ${newToken.take(12)}...")
        prefs.edit().putString(KEY_FCM_TOKEN, newToken).apply()

        // Sync with backend if user has an active session
        val sessionToken = secureStorage.getAuthToken()
        val deviceId = secureStorage.getDeviceId() ?: "dev_default"

        if (!sessionToken.isNullOrBlank()) {
            scope.launch {
                val result = authApiService.updateFcmToken(sessionToken, deviceId, newToken)
                if (result.isSuccess) {
                    Log.i(TAG, "Successfully registered push token with relay backend")
                } else {
                    Log.w(TAG, "Failed to register push token with backend: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    fun handleIncomingPushWakeUp() {
        Log.d(TAG, "Dispatching internal push wake-up broadcast")
        val intent = Intent(context, NexaPushWakeUpReceiver::class.java).apply {
            action = NexaPushWakeUpReceiver.ACTION_PUSH_WAKE_UP
        }
        context.sendBroadcast(intent)
    }
}
