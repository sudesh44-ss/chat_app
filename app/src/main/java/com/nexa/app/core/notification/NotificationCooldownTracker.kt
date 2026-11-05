package com.nexa.app.core.notification

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks debouncing and rate-limiting cooldowns for high-frequency notification sources
 * such as Bluetooth LE Mesh peer discoveries and P2P chunk transfers.
 */
object NotificationCooldownTracker {

    private const val MESH_DISCOVERY_COOLDOWN_MS = 5 * 60 * 1000L // 5-minute cooldown
    private const val FILE_TRANSFER_THROTTLE_MS = 300L            // 300ms UI update throttle

    private val lastMeshAlertTimestamps = ConcurrentHashMap<String, Long>()
    private val lastTransferUpdateTimestamps = ConcurrentHashMap<String, Long>()

    /**
     * Checks if a mesh discovery notification should be emitted for [peerNxId].
     * Returns true if 5 minutes have elapsed since the last alert for this peer.
     */
    fun shouldAlertMeshDiscovery(peerNxId: String, now: Long = System.currentTimeMillis()): Boolean {
        val lastAlert = lastMeshAlertTimestamps[peerNxId]
        if (lastAlert == null || now - lastAlert >= MESH_DISCOVERY_COOLDOWN_MS) {
            lastMeshAlertTimestamps[peerNxId] = now
            return true
        }
        return false
    }

    /**
     * Rate-limits continuous file transfer progress updates to prevent IPC thrashing.
     * Returns true if enough time has passed or if the transfer has completed.
     */
    fun shouldUpdateTransferProgress(
        transferId: String,
        progressPercent: Int,
        isCompleted: Boolean,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (isCompleted || progressPercent == 100) {
            lastTransferUpdateTimestamps.remove(transferId)
            return true
        }

        val lastUpdate = lastTransferUpdateTimestamps[transferId]
        if (lastUpdate == null || now - lastUpdate >= FILE_TRANSFER_THROTTLE_MS) {
            lastTransferUpdateTimestamps[transferId] = now
            return true
        }
        return false
    }

    fun clear() {
        lastMeshAlertTimestamps.clear()
        lastTransferUpdateTimestamps.clear()
    }
}
