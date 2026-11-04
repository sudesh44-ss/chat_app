package com.nexa.app.core.mesh.engine

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * 8-Byte Merkle Root Cluster Fingerprint Engine (Scenarios 6 & 7).
 * When isolated mesh partitions merge, nodes compare an 8-byte state hash,
 * triggering lightweight delta gossip only when topology states differ.
 */
class MerkleClusterSync {

    fun computeClusterFingerprint(visibleNodeIds: List<String>): ByteArray {
        val sortedNodes = visibleNodeIds.sorted()
        val digest = MessageDigest.getInstance("SHA-256")

        for (nodeId in sortedNodes) {
            digest.update(nodeId.toByteArray(Charsets.UTF_8))
        }

        val fullHash = digest.digest()
        // Truncate to compact 8-byte Merkle root
        val buffer = ByteBuffer.wrap(fullHash)
        val compactRoot = ByteArray(8)
        buffer.get(compactRoot)
        return compactRoot
    }

    fun shouldRequestDeltaSync(localFingerprint: ByteArray, remoteFingerprint: ByteArray): Boolean {
        if (localFingerprint.size != 8 || remoteFingerprint.size != 8) return true
        for (i in 0 until 8) {
            if (localFingerprint[i] != remoteFingerprint[i]) return true
        }
        return false // Clusters match; zero sync airtime consumed
    }
}
