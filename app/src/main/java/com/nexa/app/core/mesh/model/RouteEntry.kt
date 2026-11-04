package com.nexa.app.core.mesh.model

/**
 * Dynamic mesh routing table entry with DSN freshness stamp and composite cost metric (Scenario 2, 3 & 10).
 */
data class RouteEntry(
    val destination: String,
    val nextHop: String,
    val dsn: Long,
    val hopCount: Int,
    val cost: Float,
    val medium: String = "BLE", // "BLE" or "WIFI_AWARE"
    val updatedAt: Long = System.currentTimeMillis()
)
