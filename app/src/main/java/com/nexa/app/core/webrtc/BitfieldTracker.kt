package com.nexa.app.core.webrtc

import java.util.BitSet

/**
 * Tracks received chunk indices using a thread-safe BitSet (Scenario 2 & 11).
 * Serializes progress into compact byte arrays for instant resumption across reconnects
 * and relay handoffs without needing to re-transfer already received chunks.
 */
class BitfieldTracker(val totalChunks: Int) {

    private val bitSet = BitSet(totalChunks)

    @Synchronized
    fun markReceived(chunkIndex: Int) {
        if (chunkIndex in 0 until totalChunks) {
            bitSet.set(chunkIndex)
        }
    }

    @Synchronized
    fun isChunkReceived(chunkIndex: Int): Boolean {
        return if (chunkIndex in 0 until totalChunks) bitSet.get(chunkIndex) else false
    }

    @Synchronized
    fun isComplete(): Boolean {
        return if (totalChunks == 0) true else bitSet.cardinality() == totalChunks
    }

    @Synchronized
    fun getReceivedCount(): Int = bitSet.cardinality()

    @Synchronized
    fun getFirstMissingChunk(): Int {
        val missing = bitSet.nextClearBit(0)
        return if (missing < totalChunks) missing else -1
    }

    @Synchronized
    fun getMissingChunks(): List<Int> {
        val missing = ArrayList<Int>()
        for (i in 0 until totalChunks) {
            if (!bitSet.get(i)) missing.add(i)
        }
        return missing
    }

    @Synchronized
    fun toByteArray(): ByteArray = bitSet.toByteArray()

    @Synchronized
    fun getCompletionPercentage(): Float {
        if (totalChunks == 0) return 100f
        return (bitSet.cardinality().toFloat() / totalChunks.toFloat()) * 100f
    }

    companion object {
        fun fromByteArray(bytes: ByteArray, totalChunks: Int): BitfieldTracker {
            val tracker = BitfieldTracker(totalChunks)
            if (bytes.isNotEmpty()) {
                val parsedSet = BitSet.valueOf(bytes)
                for (i in 0 until totalChunks) {
                    if (parsedSet.get(i)) tracker.markReceived(i)
                }
            }
            return tracker
        }
    }
}
