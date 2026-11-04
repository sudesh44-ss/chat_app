package com.nexa.app.core.mesh.engine

import java.util.BitSet
import kotlin.math.abs

/**
 * 4 KB In-Memory Sliding Bloom Filter for Duplicate Packet Suppression (Scenario 12).
 * Discards duplicate multi-hop packets in < 0.01 ms with zero disk I/O.
 * Uses a circular history of 2,048 packet IDs to prevent saturation.
 */
class BloomDuplicateFilter(
    private val bitSetSize: Int = DEFAULT_BITSET_SIZE, // 4 KB RAM
    private val maxHistorySize: Int = DEFAULT_MAX_HISTORY
) {
    private val bitSet = BitSet(bitSetSize)
    private val recentHistory = ArrayDeque<String>(maxHistorySize)

    @Synchronized
    fun isDuplicateAndMark(packetId: String): Boolean {
        val h1 = abs(packetId.hashCode()) % bitSetSize
        val h2 = abs((packetId + "_salt1").hashCode()) % bitSetSize
        val h3 = abs((packetId + "_salt2").hashCode()) % bitSetSize

        if (bitSet.get(h1) && bitSet.get(h2) && bitSet.get(h3)) {
            return true // Duplicate found; drop immediately
        }

        bitSet.set(h1)
        bitSet.set(h2)
        bitSet.set(h3)

        recentHistory.addLast(packetId)
        if (recentHistory.size > maxHistorySize) {
            val evicted = recentHistory.removeFirst()
            val eh1 = abs(evicted.hashCode()) % bitSetSize
            val eh2 = abs((evicted + "_salt1").hashCode()) % bitSetSize
            val eh3 = abs((evicted + "_salt2").hashCode()) % bitSetSize
            bitSet.clear(eh1)
            bitSet.clear(eh2)
            bitSet.clear(eh3)
        }

        return false
    }

    @Synchronized
    fun clear() {
        bitSet.clear()
        recentHistory.clear()
    }

    companion object {
        const val DEFAULT_BITSET_SIZE = 32768 // 4096 * 8 = 4 KB
        const val DEFAULT_MAX_HISTORY = 2048
    }
}
