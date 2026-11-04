package com.nexa.app.core.mesh.engine

import com.nexa.app.core.mesh.model.RouteEntry

/**
 * Composite Mesh Routing Engine with Hysteresis and Battery Penalties:
 * - Scenario 2: Evaluates medium weights (BLE=4.0, Wi-Fi=1.0) and queue delay
 * - Scenario 3: 25% strictly better hysteresis rule + 3-second Dwell Lock to prevent route flapping
 * - Scenario 10: Monotonic Destination Sequence Number (DSN) updates
 * - Scenario 15: Graceful battery degradation penalty curve
 * - Scenario 18: Strict 7-hop limit ceiling
 */
class CompositeMetricEngine(
    private val localPeerId: String,
    private val hysteresisFactor: Float = 0.75f, // 25% strictly better rule
    private val dwellLockMs: Long = 3000L        // 3-second dwell timer
) {
    private val routingTable = mutableMapOf<String, RouteEntry>()
    private var lastSwitchTimestamp: Long = 0L

    fun calculateLinkCost(
        mediumWeight: Float, // BLE = 4.0, Wi-Fi = 1.0
        deliveryRatio: Float,
        batteryPct: Int,
        queueDelayMs: Long
    ): Float {
        val safeDelivery = deliveryRatio.coerceIn(0.01f, 1.0f)

        // Scenario 15: Battery degradation penalty curve
        val batteryFactor = when {
            batteryPct > 30 -> 1.0f
            batteryPct in 15..30 -> 3.0f
            batteryPct in 5..14 -> 12.0f
            else -> 1000.0f // Effectively disables relay participation
        }

        return ((mediumWeight / safeDelivery) * batteryFactor * (1f + (queueDelayMs / 100f)))
    }

    @Synchronized
    fun evaluateRouteUpdate(
        destination: String,
        candidateNextHop: String,
        candidateDsn: Long,
        candidateHops: Int,
        candidateCost: Float,
        medium: String = "BLE"
    ): Boolean {
        // Scenario 18: Hop radius ceiling
        if (candidateHops > 7) return false

        val currentRoute = routingTable[destination]
        val now = System.currentTimeMillis()

        if (currentRoute == null) {
            installRoute(destination, candidateNextHop, candidateDsn, candidateHops, candidateCost, medium)
            return true
        }

        // Scenario 10: Destination Sequence Number (DSN) fresher route rule
        if (candidateDsn > currentRoute.dsn) {
            installRoute(destination, candidateNextHop, candidateDsn, candidateHops, candidateCost, medium)
            lastSwitchTimestamp = now
            return true
        }

        // Scenario 3: 25% Hysteresis & Dwell Lock
        val isDwellLocked = (now - lastSwitchTimestamp) < dwellLockMs
        val isSignificantlyBetter = candidateCost < (currentRoute.cost * hysteresisFactor)

        if (!isDwellLocked && candidateDsn == currentRoute.dsn && isSignificantlyBetter) {
            installRoute(destination, candidateNextHop, candidateDsn, candidateHops, candidateCost, medium)
            lastSwitchTimestamp = now
            return true
        }

        return false
    }

    private fun installRoute(
        dest: String,
        nextHop: String,
        dsn: Long,
        hops: Int,
        cost: Float,
        medium: String
    ) {
        routingTable[dest] = RouteEntry(
            destination = dest,
            nextHop = nextHop,
            dsn = dsn,
            hopCount = hops,
            cost = cost,
            medium = medium,
            updatedAt = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun getNextHop(destination: String): String? = routingTable[destination]?.nextHop

    @Synchronized
    fun getRoute(destination: String): RouteEntry? = routingTable[destination]

    @Synchronized
    fun invalidateRoute(destination: String) {
        routingTable.remove(destination)
    }

    @Synchronized
    fun getAllRoutes(): List<RouteEntry> = routingTable.values.toList()
}
