package com.nexa.app.core.mesh.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.PriorityQueue

data class SequencedPacket(
    val sequenceNumber: Long,
    val payload: ByteArray
) : Comparable<SequencedPacket> {
    override fun compareTo(other: SequencedPacket): Int =
        this.sequenceNumber.compareTo(other.sequenceNumber)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SequencedPacket
        return sequenceNumber == other.sequenceNumber
    }

    override fun hashCode(): Int = sequenceNumber.hashCode()
}

/**
 * Sliding Reorder Buffer with 30-Second Gap Eviction Timer (Scenario 13).
 * Resolves out-of-order packets across asymmetric multi-hop paths.
 * If a packet gap persists beyond 30s, flushes buffer and notifies chat UI of missing slice.
 */
class SlidingReorderBuffer(
    private val gapTimeoutMs: Long = 30000L, // 30s hard limit
    private val onDeliverPacket: (SequencedPacket) -> Unit,
    private val onGapEvicted: (missingSeq: Long) -> Unit
) {
    private var expectedSeq: Long = 1L
    private val buffer = PriorityQueue<SequencedPacket>()
    private var gapJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Synchronized
    fun onPacketReceived(packet: SequencedPacket) {
        if (packet.sequenceNumber < expectedSeq) {
            return // Old duplicate; discard
        }

        if (packet.sequenceNumber == expectedSeq) {
            deliverDirect(packet)
            drainBuffer()
        } else {
            // Out of order: buffer and arm 30s timeout
            buffer.offer(packet)
            if (gapJob == null || gapJob?.isActive == false) {
                armGapTimeout()
            }
        }
    }

    private fun deliverDirect(packet: SequencedPacket) {
        expectedSeq++
        onDeliverPacket(packet)
    }

    private fun drainBuffer() {
        while (buffer.isNotEmpty() && buffer.peek()?.sequenceNumber == expectedSeq) {
            val next = buffer.poll() ?: break
            deliverDirect(next)
        }
        if (buffer.isEmpty()) {
            gapJob?.cancel()
            gapJob = null
        }
    }

    private fun armGapTimeout() {
        gapJob = scope.launch {
            delay(gapTimeoutMs)
            synchronized(this@SlidingReorderBuffer) {
                if (buffer.isNotEmpty() && (buffer.peek()?.sequenceNumber ?: 0L) > expectedSeq) {
                    onGapEvicted(expectedSeq)
                    expectedSeq = buffer.peek()?.sequenceNumber ?: (expectedSeq + 1)
                    drainBuffer()
                }
            }
        }
    }

    @Synchronized
    fun reset(startSeq: Long = 1L) {
        expectedSeq = startSeq
        buffer.clear()
        gapJob?.cancel()
        gapJob = null
    }

    val bufferedCount: Int
        get() = buffer.size
}
