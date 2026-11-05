package com.nexa.app.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi

/**
 * Manages Android 8.0+ (API 26+) Notification Channels for NEXA.
 * Synchronizes client preferences with Android OS system-level notification settings.
 */
object NexaNotificationChannelManager {

    const val CHANNEL_MESSAGES_DIRECT = "channel_messages_direct"
    const val CHANNEL_MESSAGES_GROUP = "channel_messages_group"
    const val CHANNEL_CALLS_INCOMING = "channel_calls_incoming"
    const val CHANNEL_FILE_TRANSFER = "channel_file_transfer"
    const val CHANNEL_MESH_DISCOVERY = "channel_mesh_discovery"
    const val CHANNEL_CONNECTION_REQUESTS = "channel_connection_requests"

    /**
     * Registers all dedicated Notification Channels with Android NotificationManager.
     * Safe to call repeatedly (idempotent).
     */
    fun createAllNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val channels = listOf(
            createDirectMessagesChannel(),
            createGroupMessagesChannel(),
            createIncomingCallsChannel(),
            createFileTransferChannel(),
            createMeshDiscoveryChannel(),
            createConnectionRequestsChannel()
        )

        notificationManager.createNotificationChannels(channels)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createDirectMessagesChannel(): NotificationChannel {
        return NotificationChannel(
            CHANNEL_MESSAGES_DIRECT,
            "Direct Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Heads-up alerts and sound for private end-to-end encrypted direct chats"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 150, 250)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createGroupMessagesChannel(): NotificationChannel {
        return NotificationChannel(
            CHANNEL_MESSAGES_GROUP,
            "Group Chats",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notification tray alerts and soft chimes for group conversations"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 150)
            setShowBadge(true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createIncomingCallsChannel(): NotificationChannel {
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build()

        return NotificationChannel(
            CHANNEL_CALLS_INCOMING,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Full-screen ringtones and continuous vibration for incoming P2P voice & video calls"
            setSound(ringtoneUri, audioAttributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 1000, 1000)
            setShowBadge(true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createFileTransferChannel(): NotificationChannel {
        return NotificationChannel(
            CHANNEL_FILE_TRANSFER,
            "File Transfers",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Silent ongoing progress notifications for WebRTC & P2P file transfers"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createMeshDiscoveryChannel(): NotificationChannel {
        return NotificationChannel(
            CHANNEL_MESH_DISCOVERY,
            "Nearby Mesh Discovery",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Passive silent background pings when nearby Bluetooth LE / Wi-Fi Aware mesh nodes connect"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createConnectionRequestsChannel(): NotificationChannel {
        return NotificationChannel(
            CHANNEL_CONNECTION_REQUESTS,
            "Connection Requests",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when a peer sends an end-to-end encrypted connection request"
            enableVibration(true)
            setShowBadge(true)
        }
    }

    /**
     * Opens the system notification settings page for a specific channel.
     */
    fun openChannelSettings(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } else {
            openAppNotificationSettings(context)
        }
    }

    /**
     * Opens the Android OS application notification settings page.
     */
    fun openAppNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }
}
