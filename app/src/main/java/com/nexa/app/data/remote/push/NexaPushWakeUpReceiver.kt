package com.nexa.app.data.remote.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexa.app.NexaApplication

/**
 * Background wake-up receiver triggered when a generic silent push signal is delivered.
 * CRITICAL PRIVACY INVARIANT: The push payload contains ZERO message content, keys, or metadata.
 * Payload is strictly: { "data": { "type": "WAKE_UP" } }
 * Upon receipt, this receiver instructs the WebSocketManager to connect to the relay
 * and pull any pending encrypted wire packets from the ephemeral RAM/Redis buffer.
 */
class NexaPushWakeUpReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PUSH_WAKE_UP = "com.nexa.app.PUSH_WAKE_UP"
        private const val TAG = "NexaPushReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received push wake-up broadcast: action=$action")

        val app = context.applicationContext as? NexaApplication ?: return
        val activeNxId = app.secureStorage.getActiveNxId()

        if (!activeNxId.isNullOrBlank()) {
            Log.d(TAG, "Triggering instant WebSocket connection for active NX-ID: $activeNxId")
            app.webSocketManager.connect(activeNxId)
        } else {
            Log.w(TAG, "Push wake-up received but no active NX-ID is registered")
        }
    }
}
