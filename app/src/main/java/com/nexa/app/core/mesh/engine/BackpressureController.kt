package com.nexa.app.core.mesh.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Credit-Based Leaky Bucket Backpressure & Relay Overload Controller (Scenario 4 & 5).
 * Monitors relay memory/buffer capacity, grants credit tokens to neighbors,
 * and cuts off relay advertising when buffer utilization exceeds 80%.
 */
class BackpressureController(
    private val maxBufferBytes: Long = 50 * 1024 * 1024L // 50 MB RAM buffer ceiling
) {
    private val peerCredits = ConcurrentHashMap<String, AtomicInteger>()
    private var currentBufferUsedBytes: Long = 0L

    @Synchronized
    fun updateBufferUsage(usedBytes: Long) {
        currentBufferUsedBytes = usedBytes
    }

    fun getBufferUtilizationRatio(): Float {
        if (maxBufferBytes <= 0) return 0f
        return (currentBufferUsedBytes.toFloat() / maxBufferBytes.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Scenario 5: Relay Overload
     * When buffer crosses 80%, stop participating as a relay node.
     */
    fun isRelayCapacityAvailable(): Boolean {
        return getBufferUtilizationRatio() < 0.80f
    }

    fun getAdvertisedCredits(): Int {
        val ratio = getBufferUtilizationRatio()
        return when {
            ratio >= 0.80f -> 0
            ratio >= 0.50f -> 5
            else -> 20
        }
    }

    fun grantCredits(peerId: String, count: Int) {
        peerCredits.getOrPut(peerId) { AtomicInteger(0) }.addAndGet(count)
    }

    fun getAvailableCredits(peerId: String): Int {
        return peerCredits[peerId]?.get() ?: 0
    }

    fun tryConsumeCredit(peerId: String): Boolean {
        val credits = peerCredits.getOrPut(peerId) { AtomicInteger(0) }
        while (true) {
            val current = credits.get()
            if (current <= 0) return false
            if (credits.compareAndSet(current, current - 1)) return true
        }
    }

    fun resetPeerCredits(peerId: String) {
        peerCredits.remove(peerId)
    }
}
