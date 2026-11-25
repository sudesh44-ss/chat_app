package com.nexa.app.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.nexa.app.NexaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles interactive Android Notification Actions:
 * - Mark as Read: Clears unread count and dismisses notification.
 * - Decline Call: Cancels incoming call notification and silences ringtone.
 * - Reply (RemoteInput): Captures quick reply text.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_MARK_AS_READ = "com.nexa.app.ACTION_NOTIFICATION_MARK_AS_READ"
        const val ACTION_DECLINE_CALL = "com.nexa.app.ACTION_NOTIFICATION_DECLINE_CALL"
        const val ACTION_REPLY = "com.nexa.app.ACTION_NOTIFICATION_REPLY"

        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_PARTICIPANT_NX_ID = "extra_participant_nx_id"
        const val KEY_TEXT_REPLY = "key_text_reply"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notificationManager = NotificationManagerCompat.from(context)
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)

        when (intent.action) {
            ACTION_MARK_AS_READ -> {
                if (notifId != -1) {
                    notificationManager.cancel(notifId)
                }
                if (!conversationId.isNullOrBlank()) {
                    val app = context.applicationContext as? NexaApplication
                    if (app != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                app.database.chatDao().resetUnreadCount(conversationId)
                            } catch (ignored: Exception) {}
                        }
                    }
                }
            }
            ACTION_DECLINE_CALL -> {
                if (notifId != -1) {
                    notificationManager.cancel(notifId)
                }
                val app = context.applicationContext as? NexaApplication
                app?.soundManager?.stopCallRingtone()
            }
            ACTION_REPLY -> {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val replyText = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()
                if (notifId != -1) {
                    notificationManager.cancel(notifId)
                }
                if (!conversationId.isNullOrBlank() && !replyText.isNullOrBlank()) {
                    val app = context.applicationContext as? NexaApplication
                    if (app != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                app.database.chatDao().resetUnreadCount(conversationId)
                                val participantNxId = intent.getStringExtra(EXTRA_PARTICIPANT_NX_ID) ?: ""
                                if (participantNxId.isNotBlank()) {
                                    app.chatRepository.sendMessage(
                                        recipientNxId = participantNxId,
                                        text = replyText
                                    )
                                }
                            } catch (ignored: Exception) {}
                        }
                    }
                }
            }
        }
    }
}
