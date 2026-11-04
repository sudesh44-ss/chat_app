package com.nexa.app.data.remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Android Foreground Service holding high-performance WakeLocks & WifiLocks.
 * Protects active P2P WebRTC file transfers from being throttled or terminated by OS battery optimization.
 */
class P2PTransferService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val activeTransfers = ConcurrentHashMap<Long, String>() // transferId -> fileName

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val transferId = intent?.getLongExtra(EXTRA_TRANSFER_ID, -1L) ?: -1L
        val fileName = intent?.getStringExtra(EXTRA_FILE_NAME) ?: "File"
        val progressPercent = intent?.getIntExtra(EXTRA_PROGRESS_PERCENT, 0) ?: 0

        when (action) {
            ACTION_START_TRANSFER -> {
                if (transferId > 0) {
                    activeTransfers[transferId] = fileName
                    updateNotification(fileName, progressPercent)
                }
            }
            ACTION_UPDATE_PROGRESS -> {
                if (transferId > 0 && activeTransfers.containsKey(transferId)) {
                    updateNotification(activeTransfers[transferId] ?: fileName, progressPercent)
                }
            }
            ACTION_STOP_TRANSFER -> {
                if (transferId > 0) {
                    activeTransfers.remove(transferId)
                    if (activeTransfers.isEmpty()) {
                        stopSelf()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "nexa:p2p_transfer_wakelock"
            ).apply {
                acquire(2 * 60 * 60 * 1000L) // 2 hours max safety timeout
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "nexa:p2p_wifi_lock"
            ).apply {
                acquire()
            }
        } catch (e: Exception) {
            // Log and tolerate if lock permission restricted
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NEXA Direct P2P File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live progress for zero-cloud peer-to-peer file streaming"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(fileName: String, progress: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Direct P2P Transfer: $fileName")
            .setContentText("Transferring directly between devices ($progress%)")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            // Ignored on cleanup
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "p2p_transfer_channel"
        const val NOTIFICATION_ID = 202

        const val ACTION_START_TRANSFER = "com.nexa.app.action.START_TRANSFER"
        const val ACTION_UPDATE_PROGRESS = "com.nexa.app.action.UPDATE_PROGRESS"
        const val ACTION_STOP_TRANSFER = "com.nexa.app.action.STOP_TRANSFER"

        const val EXTRA_TRANSFER_ID = "extra_transfer_id"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_PROGRESS_PERCENT = "extra_progress_percent"

        fun start(context: Context, transferId: Long, fileName: String) {
            val intent = Intent(context, P2PTransferService::class.java).apply {
                action = ACTION_START_TRANSFER
                putExtra(EXTRA_TRANSFER_ID, transferId)
                putExtra(EXTRA_FILE_NAME, fileName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateProgress(context: Context, transferId: Long, percent: Int) {
            val intent = Intent(context, P2PTransferService::class.java).apply {
                action = ACTION_UPDATE_PROGRESS
                putExtra(EXTRA_TRANSFER_ID, transferId)
                putExtra(EXTRA_PROGRESS_PERCENT, percent)
            }
            context.startService(intent)
        }

        fun stop(context: Context, transferId: Long) {
            val intent = Intent(context, P2PTransferService::class.java).apply {
                action = ACTION_STOP_TRANSFER
                putExtra(EXTRA_TRANSFER_ID, transferId)
            }
            context.startService(intent)
        }
    }
}
