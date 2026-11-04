package com.nexa.app.core.mesh.model

enum class LinkState {
    HEALTHY,    // RSSI > -75 dBm: Full data permitted
    DEGRADED,   // -75 to -90 dBm: Low-byte control ACKs only
    TOMBSTONED, // < -90 dBm sustained for > 6s: Route evicted
    UNSTABLE    // High FER > 0.40 regardless of raw RSSI
}

/**
 * Real-time RF link quality metrics for physical neighbor tracking (Scenario 1 & 16).
 */
data class LinkQualityMetrics(
    val peerId: String,
    val state: LinkState,
    val smoothedRssi: Float,
    val fer: Float,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)
