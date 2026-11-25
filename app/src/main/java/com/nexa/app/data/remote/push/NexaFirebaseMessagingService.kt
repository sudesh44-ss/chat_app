package com.nexa.app.data.remote.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nexa.app.NexaApplication

/**
 * Service that receives Firebase Cloud Messaging tokens and silent wake-up pushes.
 *
 * PRIVACY INVARIANT:
 * FCM pushes contain ZERO plaintext, keys, sender identities, or message content.
 * The push is strictly an ephemeral wake-up signal that triggers WebSocketManager to pull
 * encrypted packets from the server's ephemeral Redis buffer over mutual TLS.
 */
class NexaFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "NexaFCMService"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "Refreshed FCM device registration token: ${token.take(12)}...")
        val app = applicationContext as? NexaApplication ?: return
        app.pushManager.onNewPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "Received FCM silent push wake-up from: ${message.from}")
        val app = applicationContext as? NexaApplication ?: return
        app.pushManager.handleIncomingPushWakeUp()
    }
}
