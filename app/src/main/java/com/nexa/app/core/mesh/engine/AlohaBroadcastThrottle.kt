package com.nexa.app.core.mesh.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/**
 * Probabilistic Slotted-Aloha Broadcast Throttle with Overhear Suppression (Scenario 8).
 * Prevents 2.4 GHz airtime broadcast storms by randomizing forward delays
 * and canceling pending transmissions if an identical packet is overheard from a peer.
 */
class AlohaBroadcastThrottle(
    private val onBroadcastExecute: suspend (packetId: String) -> Unit
) {
    private val scheduledTransmissions = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Synchronized
    fun scheduleRouteRequestBroadcast(packetId: String, currentRssi: Int) {
        if (scheduledTransmissions.containsKey(packetId)) return

        // Slotted delay scaled inversely by proximity
        val rssiFactor = (1f - (abs(currentRssi).coerceIn(30, 100) / 100f))
        val delayMs = (Random.nextLong(30, 200) * rssiFactor).toLong().coerceAtLeast(15L)

        val job = scope.launch {
            delay(delayMs)
            synchronized(this@AlohaBroadcastThrottle) {
                scheduledTransmissions.remove(packetId)
            }
            onBroadcastExecute(packetId)
        }
        scheduledTransmissions[packetId] = job
    }

    @Synchronized
    fun snoopOverheardPacket(packetId: String) {
        // Overhear Rule: Peer already transmitted this packet; cancel ours
        scheduledTransmissions[packetId]?.let { activeJob ->
            activeJob.cancel()
            scheduledTransmissions.remove(packetId)
        }
    }

    @Synchronized
    fun isScheduled(packetId: String): Boolean = scheduledTransmissions.containsKey(packetId)
}
