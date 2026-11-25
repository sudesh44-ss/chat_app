package com.nexa.app.core.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.nexa.app.MainActivity
import com.nexa.app.R
import com.nexa.app.core.sound.NexaSoundManager
import com.nexa.app.data.local.preferences.ConversationPreferences
import com.nexa.app.data.local.preferences.LockScreenMode
import com.nexa.app.data.local.preferences.NotificationPrefs
import com.nexa.app.data.local.preferences.SoundCategory
import java.util.Collections
import java.util.LinkedHashSet

/**
 * Production-Grade Notification Dispatcher for NEXA.
 * Enforces:
 * 1. Zero E2EE Content Leaks on Lock Screen (NotificationCompat.VISIBILITY_PRIVATE with public placeholder).
 * 2. Dedicated Notification Channels (API 26+).
 * 3. Client-Side Quiet Hours & DND Suppression (same-day and overnight weekday scheduling).
 * 4. Deduplication & rate-limiting for high-frequency messages, Mesh Discovery, and P2P File Transfers.
 * 5. Android Notification Grouping with privacy-safe group summary.
 * 6. Actionable notifications (Mark as Read, RemoteInput Reply, Decline Call).
 */
class NexaNotificationDispatcher(
    private val context: Context,
    private val notificationPrefs: NotificationPrefs,
    private val soundManager: NexaSoundManager? = null,
    private val conversationPreferences: ConversationPreferences? = null
) {

    companion object {
        const val GROUP_KEY_MESSAGES = "com.nexa.app.MESSAGES"
        private const val SUMMARY_NOTIFICATION_ID = 1000
        private const val DIRECT_MSG_NOTIF_BASE_ID = 10000
        private const val GROUP_MSG_NOTIF_BASE_ID = 20000
        private const val CALL_NOTIF_ID = 30001
        private const val FILE_TRANSFER_BASE_ID = 40000
        private const val MESH_DISCOVERY_BASE_ID = 50000
        private const val CONNECTION_REQUEST_BASE_ID = 60000

        private const val MAX_DEDUP_CACHE_SIZE = 500
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    // Deduplication tracking to prevent duplicate alerts on re-transmissions
    private val processedMessageIds = Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * Dispatches an incoming direct message notification.
     * Guarantees zero plaintext exposure on lock screen unless user explicitly selected SHOW_ALL.
     */
    fun postDirectMessageNotification(
        conversationId: String,
        senderNxId: String,
        senderDisplayName: String,
        decryptedText: String,
        messageId: String
    ) {
        if (conversationPreferences?.isMuted(conversationId) == true) return

        val settings = notificationPrefs.getSnapshot()
        if (!settings.directMessagesEnabled) return

        // Deduplication: prevent alert spam if message was already processed
        if (messageId.isNotBlank()) {
            synchronized(processedMessageIds) {
                if (!processedMessageIds.add(messageId)) {
                    return
                }
                if (processedMessageIds.size > MAX_DEDUP_CACHE_SIZE) {
                    val it = processedMessageIds.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
            }
        }

        val isSuppressedByQuietHours = QuietHoursEngine.shouldSuppressNotification(
            isCall = false,
            settings = settings
        )

        // Generate intent to open chat
        val contentIntent = createChatPendingIntent(conversationId, senderNxId, senderDisplayName)

        val previewText = if (settings.messagePreviewEnabled) {
            decryptedText
        } else {
            "New message received"
        }

        val channelId = NexaNotificationChannelManager.CHANNEL_MESSAGES_DIRECT
        val notifId = DIRECT_MSG_NOTIF_BASE_ID + (senderNxId.hashCode() and 0x7FFF)

        val baseBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(senderDisplayName)
            .setContentText(previewText)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(
                if (isSuppressedByQuietHours) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_KEY_MESSAGES)

        if (isSuppressedByQuietHours) {
            baseBuilder.setSilent(true)
        }

        // Notification Actions: Mark as Read & Reply
        baseBuilder.addAction(createMarkAsReadAction(notifId, conversationId))
        baseBuilder.addAction(createReplyAction(notifId, conversationId, senderNxId))

        // Apply Lock Screen Visibility mode
        applyLockScreenPolicy(
            builder = baseBuilder,
            channelId = channelId,
            contentIntent = contentIntent
        )

        try {
            notificationManager.notify(notifId, baseBuilder.build())
            postGroupSummaryNotification(channelId)
            if (!isSuppressedByQuietHours) {
                soundManager?.playEventSound(SoundCategory.MESSAGE, eventKey = messageId)
            }
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted
        }
    }

    /**
     * Dispatches group chat notifications respecting mentions-only filter and quiet hours.
     */
    fun postGroupMessageNotification(
        groupId: String,
        groupName: String,
        senderName: String,
        decryptedText: String,
        isMention: Boolean = false,
        messageId: String = "",
        myNxId: String? = null,
        myDisplayName: String? = null
    ) {
        if (conversationPreferences?.isMuted(groupId) == true) return

        val settings = notificationPrefs.getSnapshot()
        if (!settings.groupsEnabled) return

        // Perform structured mention detection if not explicitly flagged
        val effectiveMention = isMention || NexaMentionParser.isDirectMention(decryptedText, myNxId, myDisplayName)
        if (settings.groupMentionsOnly && !effectiveMention) return

        // Deduplication
        if (messageId.isNotBlank()) {
            synchronized(processedMessageIds) {
                if (!processedMessageIds.add(messageId)) {
                    return
                }
                if (processedMessageIds.size > MAX_DEDUP_CACHE_SIZE) {
                    val it = processedMessageIds.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
            }
        }

        val isSuppressed = QuietHoursEngine.shouldSuppressNotification(isCall = false, settings = settings)

        val contentIntent = createGroupPendingIntent(groupId, groupName)
        val preview = if (settings.messagePreviewEnabled) {
            if (effectiveMention) "Mentioned you: $decryptedText" else "$senderName: $decryptedText"
        } else {
            if (effectiveMention) "$senderName mentioned you" else "$senderName sent a message"
        }
        val channelId = NexaNotificationChannelManager.CHANNEL_MESSAGES_GROUP
        val notifId = GROUP_MSG_NOTIF_BASE_ID + (groupId.hashCode() and 0x7FFF)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(groupName)
            .setContentText(preview)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(if (isSuppressed) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_KEY_MESSAGES)

        if (isSuppressed) {
            builder.setSilent(true)
        }

        // Notification Actions: Mark as Read & Reply
        builder.addAction(createMarkAsReadAction(notifId, groupId))
        builder.addAction(createReplyAction(notifId, groupId, groupId))

        applyLockScreenPolicy(
            builder = builder,
            channelId = channelId,
            contentIntent = contentIntent
        )

        try {
            notificationManager.notify(notifId, builder.build())
            postGroupSummaryNotification(channelId)
            if (!isSuppressed) {
                soundManager?.playEventSound(SoundCategory.GROUP_MESSAGE)
            }
        } catch (e: SecurityException) {
            // Permission missing
        }
    }

    /**
     * Dispatches full-screen incoming voice/video call notifications.
     */
    fun postIncomingCallNotification(
        callId: String,
        callerNxId: String,
        callerDisplayName: String
    ) {
        val settings = notificationPrefs.getSnapshot()
        if (!settings.callsEnabled) return

        val isSuppressed = QuietHoursEngine.shouldSuppressNotification(isCall = true, settings = settings)
        if (isSuppressed) return

        val fullScreenIntent = createCallPendingIntent(callId, callerNxId, callerDisplayName)

        val builder = NotificationCompat.Builder(context, NexaNotificationChannelManager.CHANNEL_CALLS_INCOMING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Incoming NEXA Call")
            .setContentText(callerDisplayName)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)

        // Add Decline and Answer actions
        builder.addAction(createDeclineCallAction())
        builder.addAction(
            R.drawable.ic_nexa_phone_call,
            "Answer",
            fullScreenIntent
        )

        try {
            notificationManager.notify(CALL_NOTIF_ID, builder.build())
            soundManager?.startCallRingtone()
        } catch (e: SecurityException) {
            // Permission missing
        }
    }

    /**
     * Dispatches or updates single ongoing file transfer progress bar.
     */
    fun postFileTransferProgress(
        transferId: String,
        fileName: String,
        progressPercent: Int,
        isCompleted: Boolean
    ) {
        val settings = notificationPrefs.getSnapshot()
        if (!settings.fileTransfersEnabled) return

        if (!NotificationCooldownTracker.shouldUpdateTransferProgress(transferId, progressPercent, isCompleted)) {
            return
        }

        val notifId = FILE_TRANSFER_BASE_ID + (transferId.hashCode() and 0x7FFF)

        if (isCompleted) {
            val completedBuilder = NotificationCompat.Builder(context, NexaNotificationChannelManager.CHANNEL_FILE_TRANSFER)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Transfer Complete")
                .setContentText(fileName)
                .setAutoCancel(true)
                .setOngoing(false)

            try {
                notificationManager.notify(notifId, completedBuilder.build())
                soundManager?.playEventSound(
                    SoundCategory.TRANSFER_COMPLETED,
                    eventKey = "transfer_completed_$transferId"
                )
            } catch (e: SecurityException) {}
            return
        }

        val progressBuilder = NotificationCompat.Builder(context, NexaNotificationChannelManager.CHANNEL_FILE_TRANSFER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Transferring: $fileName")
            .setContentText("$progressPercent%")
            .setProgress(100, progressPercent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        try {
            notificationManager.notify(notifId, progressBuilder.build())
        } catch (e: SecurityException) {}
    }

    /**
     * Dispatches silent low-priority Mesh Discovery alert with 5-minute debounce cooldown.
     */
    fun postMeshDiscoveryAlert(peerNxId: String, peerDisplayName: String) {
        val settings = notificationPrefs.getSnapshot()
        if (!settings.meshDiscoveryAlertsEnabled) return

        if (!NotificationCooldownTracker.shouldAlertMeshDiscovery(peerNxId)) {
            return
        }

        val isSuppressed = QuietHoursEngine.shouldSuppressNotification(isCall = false, settings = settings)
        if (isSuppressed) return

        val notifId = MESH_DISCOVERY_BASE_ID + (peerNxId.hashCode() and 0x7FFF)

        val builder = NotificationCompat.Builder(context, NexaNotificationChannelManager.CHANNEL_MESH_DISCOVERY)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Nearby Mesh Peer Connected")
            .setContentText(peerDisplayName)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setAutoCancel(true)

        try {
            notificationManager.notify(notifId, builder.build())
            soundManager?.playEventSound(SoundCategory.NEARBY_DEVICE, eventKey = peerNxId)
        } catch (e: SecurityException) {}
    }

    /**
     * Dispatches connection request notification.
     */
    fun postConnectionRequest(requesterNxId: String, requesterName: String) {
        val settings = notificationPrefs.getSnapshot()
        if (!settings.connectionRequestsEnabled) return

        val isSuppressed = QuietHoursEngine.shouldSuppressNotification(isCall = false, settings = settings)

        val notifId = CONNECTION_REQUEST_BASE_ID + (requesterNxId.hashCode() and 0x7FFF)

        val builder = NotificationCompat.Builder(context, NexaNotificationChannelManager.CHANNEL_CONNECTION_REQUESTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("New Connection Request")
            .setContentText("$requesterName wants to connect on NEXA")
            .setPriority(if (isSuppressed) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        if (isSuppressed) {
            builder.setSilent(true)
        }

        try {
            notificationManager.notify(notifId, builder.build())
            if (!isSuppressed) {
                soundManager?.playEventSound(SoundCategory.CONNECTION_REQUEST, eventKey = requesterNxId)
            }
        } catch (e: SecurityException) {}
    }

    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
        if (notificationId == CALL_NOTIF_ID) {
            soundManager?.stopCallRingtone()
        }
    }

    fun cancelAll() {
        notificationManager.cancelAll()
        soundManager?.stopCallRingtone()
    }

    private fun applyLockScreenPolicy(
        builder: NotificationCompat.Builder,
        channelId: String,
        contentIntent: PendingIntent
    ) {
        val settings = notificationPrefs.getSnapshot()
        when (settings.lockScreenVisibility) {
            LockScreenMode.SHOW_ALL -> {
                builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            }
            LockScreenMode.HIDE_CONTENT -> {
                val publicNotification = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("NEXA")
                    .setContentText("1 new encrypted message")
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .build()

                builder
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(publicNotification)
            }
            LockScreenMode.DONT_SHOW -> {
                builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
            }
        }
    }

    private fun postGroupSummaryNotification(channelId: String) {
        val summaryIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val summaryPendingIntent = PendingIntent.getActivity(
            context,
            SUMMARY_NOTIFICATION_ID,
            summaryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val summaryBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NEXA")
            .setContentText("New encrypted messages")
            .setContentIntent(summaryPendingIntent)
            .setGroup(GROUP_KEY_MESSAGES)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        applyLockScreenPolicy(summaryBuilder, channelId, summaryPendingIntent)

        try {
            notificationManager.notify(SUMMARY_NOTIFICATION_ID, summaryBuilder.build())
        } catch (ignored: SecurityException) {}
    }

    private fun createMarkAsReadAction(notifId: Int, conversationId: String): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_AS_READ
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
            putExtra(NotificationActionReceiver.EXTRA_CONVERSATION_ID, conversationId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 100000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_nexa_mark_read,
            "Mark as Read",
            pendingIntent
        ).build()
    }

    private fun createReplyAction(notifId: Int, conversationId: String, participantNxId: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT_REPLY)
            .setLabel("Reply")
            .build()

        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
            putExtra(NotificationActionReceiver.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(NotificationActionReceiver.EXTRA_PARTICIPANT_NX_ID, participantNxId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 200000,
            intent,
            flags
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_nexa_reply,
            "Reply",
            pendingIntent
        ).addRemoteInput(remoteInput).build()
    }

    private fun createDeclineCallAction(): NotificationCompat.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_DECLINE_CALL
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, CALL_NOTIF_ID)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            CALL_NOTIF_ID + 300000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_nexa_close,
            "Decline",
            pendingIntent
        ).build()
    }

    private fun createChatPendingIntent(
        conversationId: String,
        participantNxId: String,
        participantName: String
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversationId", conversationId)
            putExtra("participantNxId", participantNxId)
            putExtra("participantName", participantName)
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createGroupPendingIntent(groupId: String, groupName: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("groupId", groupId)
            putExtra("groupName", groupName)
        }
        return PendingIntent.getActivity(
            context,
            groupId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createCallPendingIntent(callId: String, callerNxId: String, callerName: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("callId", callId)
            putExtra("callerNxId", callerNxId)
            putExtra("callerName", callerName)
        }
        return PendingIntent.getActivity(
            context,
            callId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
