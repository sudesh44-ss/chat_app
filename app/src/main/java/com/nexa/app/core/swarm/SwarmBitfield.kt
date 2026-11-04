package com.nexa.app.core.swarm

import java.util.BitSet

/**
 * Bitmap tracker for 64 KB file chunks across a decentralized seeder swarm.
 * Backed by java.util.BitSet for compact transmission and fast set operations.
 */
class SwarmBitfield(val totalChunks: Int) {

    private val bitSet = BitSet(totalChunks)

    fun markAvailable(chunkIndex: Int) {
        if (chunkIndex in 0 until totalChunks) {
            bitSet.set(chunkIndex)
        }
    }

    fun isAvailable(chunkIndex: Int): Boolean {
        return if (chunkIndex in 0 until totalChunks) {
            bitSet.get(chunkIndex)
        } else {
            false
        }
    }

    fun isComplete(): Boolean {
        return bitSet.cardinality() == totalChunks
    }

    fun completedCount(): Int {
        return bitSet.cardinality()
    }

    fun getMissingChunks(): List<Int> {
        val missing = ArrayList<Int>(totalChunks - bitSet.cardinality())
        for (i in 0 until totalChunks) {
            if (!bitSet.get(i)) {
                missing.add(i)
            }
        }
        return missing
    }

    fun serialize(): ByteArray {
        return bitSet.toByteArray()
    }

    companion object {
        fun deserialize(totalChunks: Int, bytes: ByteArray): SwarmBitfield {
            val bitfield = SwarmBitfield(totalChunks)
            val incoming = BitSet.valueOf(bytes)
            for (i in 0 until totalChunks) {
                if (incoming.get(i)) {
                    bitfield.markAvailable(i)
                }
            }
            return bitfield
        }
    }
}
