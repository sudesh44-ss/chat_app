package com.nexa.app.core.mesh.engine

import com.nexa.app.core.mesh.model.LinkQualityMetrics
import com.nexa.app.core.mesh.model.LinkState

/**
 * 3-State LQI Decay Engine with Exponential Moving Average (EMA) RSSI smoothing
 * and Frame Error Rate (FER) monitoring (Scenario 1 & 16).
 */
class LqiDecayEngine(
    private val alpha: Float = 0.3f,
    private val tombstoneTimeoutMs: Long = 6000L
) {
    private val linkStates = mutableMapOf<String, PeerLinkMetrics>()

    data class PeerLinkMetrics(
        var smoothedRssi: Float,
        var totalPackets: Long = 0,
        var failedRetries: Long = 0,
        var lastHealthyTimestamp: Long = System.currentTimeMillis()
    ) {
        val fer: Float
            get() = if (totalPackets == 0L) 0f else failedRetries.toFloat() / totalPackets.toFloat()
    }

    @Synchronized
    fun recordSample(peerId: String, rawRssi: Int, failedRetries: Int = 0, transmitted: Int = 1): LinkState {
        val now = System.currentTimeMillis()
        val metrics = linkStates.getOrPut(peerId) {
            PeerLinkMetrics(smoothedRssi = rawRssi.toFloat(), lastHealthyTimestamp = now)
        }

        // 1. Exponential Moving Average for RSSI
        metrics.smoothedRssi = (alpha * rawRssi) + ((1f - alpha) * metrics.smoothedRssi)
        metrics.failedRetries += failedRetries
        metrics.totalPackets += transmitted

        // 2. State Classification with FER Overrides (Scenario 16)
        val state = when {
            metrics.fer > 0.40f -> LinkState.UNSTABLE // High packet loss / interference
            metrics.smoothedRssi > -75f -> {
                metrics.lastHealthyTimestamp = now
                LinkState.HEALTHY
            }
            metrics.smoothedRssi in -90f..-75f -> LinkState.DEGRADED
            else -> {
                if (now - metrics.lastHealthyTimestamp > tombstoneTimeoutMs) {
                    LinkState.TOMBSTONED
                } else {
                    LinkState.DEGRADED
                }
            }
        }
        return state
    }

    @Synchronized
    fun getMetrics(peerId: String): LinkQualityMetrics? {
        val m = linkStates[peerId] ?: return null
        val state = when {
            m.fer > 0.40f -> LinkState.UNSTABLE
            m.smoothedRssi > -75f -> LinkState.HEALTHY
            m.smoothedRssi in -90f..-75f -> LinkState.DEGRADED
            else -> LinkState.TOMBSTONED
        }
        return LinkQualityMetrics(peerId, state, m.smoothedRssi, m.fer, m.lastHealthyTimestamp)
    }

    @Synchronized
    fun evictPeer(peerId: String) {
        linkStates.remove(peerId)
    }

    @Synchronized
    fun clear() {
        linkStates.clear()
    }
}
