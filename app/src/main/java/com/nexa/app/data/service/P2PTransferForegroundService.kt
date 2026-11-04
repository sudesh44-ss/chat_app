package com.nexa.app.data.service

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
 * Foreground Service preserving OS process life during active 50k-scale P2P transfers (Scenario 3).
 * Acquires PARTIAL_WAKE_LOCK and WIFI_MODE_FULL_HIGH_PERF, updating a sticky notification
 * with throughput metrics and overall chunk progress.
 */
class P2PTransferForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val activeTransfers = ConcurrentHashMap<Long, String>()

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
        val throughput = intent?.getStringExtra(EXTRA_THROUGHPUT) ?: ""

        when (action) {
            ACTION_START -> {
                if (transferId > 0) {
                    activeTransfers[transferId] = fileName
                    updateNotification(fileName, progressPercent, throughput)
                }
            }
            ACTION_UPDATE -> {
                if (transferId > 0 && activeTransfers.containsKey(transferId)) {
                    updateNotification(activeTransfers[transferId] ?: fileName, progressPercent, throughput)
                }
            }
            ACTION_STOP -> {
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

    private fun updateNotification(fileName: String, progress: Int, throughput: String) {
        val title = "NEXA P2P Transfer ($progress%)"
        val text = if (throughput.isNotBlank()) "$fileName • $throughput" else fileName

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nexa:P2PTransferWakeLock")?.apply {
            setReferenceCounted(false)
            acquire(3 * 3600 * 1000L) // 3-hour safety timeout
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager?.createWifiLock(wifiMode, "Nexa:P2PTransferWifiLock")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onDestroy() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (ignored: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (ignored: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "P2P File Transfer Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress and keeps background transfer pipe active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "nexa_p2p_transfer_channel"
        const val NOTIFICATION_ID = 2002

        const val ACTION_START = "com.nexa.app.action.START_P2P_TRANSFER"
        const val ACTION_UPDATE = "com.nexa.app.action.UPDATE_P2P_TRANSFER"
        const val ACTION_STOP = "com.nexa.app.action.STOP_P2P_TRANSFER"

        const val EXTRA_TRANSFER_ID = "extra_transfer_id"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_PROGRESS_PERCENT = "extra_progress_percent"
        const val EXTRA_THROUGHPUT = "extra_throughput"

        fun start(context: Context, transferId: Long, fileName: String) {
            val intent = Intent(context, P2PTransferForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TRANSFER_ID, transferId)
                putExtra(EXTRA_FILE_NAME, fileName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun update(context: Context, transferId: Long, progress: Int, throughput: String) {
            val intent = Intent(context, P2PTransferForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TRANSFER_ID, transferId)
                putExtra(EXTRA_PROGRESS_PERCENT, progress)
                putExtra(EXTRA_THROUGHPUT, throughput)
            }
            context.startService(intent)
        }

        fun stop(context: Context, transferId: Long) {
            val intent = Intent(context, P2PTransferForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_TRANSFER_ID, transferId)
            }
            context.startService(intent)
        }
    }
}
