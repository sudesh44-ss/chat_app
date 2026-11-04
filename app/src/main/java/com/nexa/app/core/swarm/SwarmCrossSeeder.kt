package com.nexa.app.core.swarm

import com.nexa.app.core.webrtc.BitfieldTracker

data class CrossSeedTask(
    val sourcePeerId: String,
    val targetPeerId: String,
    val chunkIndex: Int
)

/**
 * BitTorrent-Style Swarm Cross-Seeding Coordinator (Scenario 17).
 * When 1 sender distributes large files to multiple group members, chunks are partitioned
 * across recipients, enabling peers to cross-seed missing slices among themselves
 * and conserving the original sender's mobile upload data.
 */
class SwarmCrossSeeder {

    fun partitionChunks(totalChunks: Int, groupPeerIds: List<String>): Map<String, List<Int>> {
        if (groupPeerIds.isEmpty() || totalChunks <= 0) return emptyMap()

        val partitions = mutableMapOf<String, MutableList<Int>>()
        groupPeerIds.forEach { partitions[it] = mutableListOf() }

        for (chunkIdx in 0 until totalChunks) {
            val assignedPeer = groupPeerIds[chunkIdx % groupPeerIds.size]
            partitions[assignedPeer]?.add(chunkIdx)
        }
        return partitions
    }

    fun computeCrossSeedingOpportunities(
        totalChunks: Int,
        peerBitfields: Map<String, BitfieldTracker>
    ): List<CrossSeedTask> {
        val tasks = mutableListOf<CrossSeedTask>()
        val peers = peerBitfields.keys.toList()

        for (i in peers.indices) {
            val targetPeer = peers[i]
            val targetTracker = peerBitfields[targetPeer] ?: continue

            for (chunkIdx in 0 until totalChunks) {
                if (!targetTracker.isChunkReceived(chunkIdx)) {
                    // Find any peer in the swarm that has this chunk
                    val sourcePeer = peers.firstOrNull { it != targetPeer && peerBitfields[it]?.isChunkReceived(chunkIdx) == true }
                    if (sourcePeer != null) {
                        tasks.add(CrossSeedTask(sourcePeer, targetPeer, chunkIdx))
                    }
                }
            }
        }
        return tasks
    }
}
