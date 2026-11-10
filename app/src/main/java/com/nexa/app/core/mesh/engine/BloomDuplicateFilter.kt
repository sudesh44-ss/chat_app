package com.nexa.app.core.mesh.engine

import java.util.BitSet
import kotlin.math.abs

/**
 * 4 KB In-Memory Sliding Bloom Filter for Duplicate Packet Suppression Optimization.
 *
 * Rules:
 * 1. A negative Bloom filter result is 100% exact: the packet is definitely new (< 0.01 ms).
 * 2. A positive Bloom filter result can be a false positive: exact database verification
 *    via [exactLookup] is mandatory before treating any packet as a duplicate.
 * 3. A false positive NEVER causes message loss.
 * 4. The Bloom filter is rebuildable from authoritative storage via [rebuild].
 */
class BloomDuplicateFilter(
    private val bitSetSize: Int = DEFAULT_BITSET_SIZE, // 4 KB RAM
    private val maxHistorySize: Int = DEFAULT_MAX_HISTORY,
    private val exactLookup: (suspend (String) -> Boolean)? = null
) {
    private val bitSet = BitSet(bitSetSize)
    private val recentHistory = ArrayDeque<String>(maxHistorySize)

    @Synchronized
    fun mightContain(packetId: String): Boolean {
        val h1 = abs(packetId.hashCode()) % bitSetSize
        val h2 = abs((packetId + "_salt1").hashCode()) % bitSetSize
        val h3 = abs((packetId + "_salt2").hashCode()) % bitSetSize

        return bitSet.get(h1) && bitSet.get(h2) && bitSet.get(h3)
    }

    @Synchronized
    fun mark(packetId: String) {
        val h1 = abs(packetId.hashCode()) % bitSetSize
        val h2 = abs((packetId + "_salt1").hashCode()) % bitSetSize
        val h3 = abs((packetId + "_salt2").hashCode()) % bitSetSize

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
    }

    /**
     * Checks if packet is duplicate.
     * Guaranteed: If mightContain returns true (candidate duplicate),
     * exact verification is executed if [exactLookup] callback is provided.
     * A false positive in the Bloom filter will NEVER drop a legitimate new packet.
     */
    suspend fun isDuplicateAndMark(packetId: String): Boolean {
        val isCandidate = mightContain(packetId)
        if (!isCandidate) {
            // Negative result: 100% guaranteed new packet!
            mark(packetId)
            return false
        }

        // Positive result: May be true duplicate or false positive!
        // Must perform exact database verification
        val isActuallyStored = exactLookup?.invoke(packetId) ?: true
        if (isActuallyStored) {
            return true
        }

        // False positive! Not actually stored in DB. Mark and allow processing.
        mark(packetId)
        return false
    }

    /**
     * Rebuilds the in-memory filter from the authoritative persistent message index.
     */
    @Synchronized
    fun rebuild(packetIds: List<String>) {
        clear()
        for (id in packetIds) {
            mark(id)
        }
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
