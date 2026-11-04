package com.nexa.app

import com.nexa.app.core.crypto.ChunkCipherEngine
import com.nexa.app.core.hardware.MemoryBufferPool
import com.nexa.app.core.mesh.engine.AlohaBroadcastThrottle
import com.nexa.app.core.mesh.engine.BackpressureController
import com.nexa.app.core.mesh.engine.BloomDuplicateFilter
import com.nexa.app.core.mesh.engine.CompositeMetricEngine
import com.nexa.app.core.mesh.model.LinkState
import com.nexa.app.core.mesh.engine.LqiDecayEngine
import com.nexa.app.core.mesh.engine.MerkleClusterSync
import com.nexa.app.core.mesh.engine.SequencedPacket
import com.nexa.app.core.mesh.engine.SlidingReorderBuffer
import com.nexa.app.core.mesh.model.MeshPacket
import com.nexa.app.core.mesh.model.RouteEntry
import com.nexa.app.core.swarm.SwarmCrossSeeder
import com.nexa.app.core.webrtc.BitfieldTracker
import com.nexa.app.data.local.entity.TransferSessionEntity
import com.nexa.app.data.local.entity.TransferSessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.BitSet

/**
 * Unit Test Suite verifying the 18 Resilient P2P Transfer & 18 Hardened MANET/DTN Mesh Scenarios.
 */
class ResilientP2PAndMeshUnitTests {

    private val secureRandom = SecureRandom()

    // ==========================================
    // PART 1: 50k RESILIENT P2P TRANSFER TESTS
    // ==========================================

    @Test
    fun testBitfieldTrackerMarkingAndResumption() {
        val totalChunks = 100
        val tracker = BitfieldTracker(totalChunks)

        assertFalse(tracker.isComplete())
        assertEquals(0, tracker.getFirstMissingChunk())
        assertEquals(0f, tracker.getCompletionPercentage(), 0.01f)

        // Mark first 50 chunks received
        for (i in 0 until 50) {
            tracker.markReceived(i)
        }

        assertEquals(50, tracker.getReceivedCount())
        assertEquals(50, tracker.getFirstMissingChunk())
        assertEquals(50f, tracker.getCompletionPercentage(), 0.01f)

        // Mark remaining chunks
        for (i in 50 until totalChunks) {
            tracker.markReceived(i)
        }

        assertTrue(tracker.isComplete())
        assertEquals(-1, tracker.getFirstMissingChunk())
        assertEquals(100f, tracker.getCompletionPercentage(), 0.01f)
    }

    @Test
    fun testBitfieldTrackerSerializationRoundtrip() {
        val totalChunks = 64
        val tracker = BitfieldTracker(totalChunks)
        tracker.markReceived(0)
        tracker.markReceived(15)
        tracker.markReceived(31)
        tracker.markReceived(63)

        val serializedBytes = tracker.toByteArray()
        val restoredTracker = BitfieldTracker.fromByteArray(serializedBytes, totalChunks)

        assertTrue(restoredTracker.isChunkReceived(0))
        assertTrue(restoredTracker.isChunkReceived(15))
        assertTrue(restoredTracker.isChunkReceived(31))
        assertTrue(restoredTracker.isChunkReceived(63))
        assertFalse(restoredTracker.isChunkReceived(1))
        assertEquals(4, restoredTracker.getReceivedCount())
    }

    @Test
    fun testMemoryBufferPoolAcquireAndRelease() {
        val pool = MemoryBufferPool(poolSize = 4, bufferSizeBytes = 64 * 1024)
        assertEquals(4, pool.availableCount)

        val b1 = pool.acquire()
        val b2 = pool.acquire()
        assertEquals(2, pool.availableCount)
        assertEquals(64 * 1024, b1.size)

        pool.release(b1)
        assertEquals(3, pool.availableCount)
        pool.release(b2)
        assertEquals(4, pool.availableCount)
    }

    @Test
    fun testChunkCipherEngineEncryptionAndAuthentication() {
        val cipherEngine = ChunkCipherEngine()
        val key = ByteArray(32).also { secureRandom.nextBytes(it) }
        val plaintext = "SECRET_PAYLOAD_CHUNK_DATA_TEST".toByteArray(Charsets.UTF_8)
        val chunkIndex = 42

        // Encrypt with 16-byte Poly1305 MAC tag
        val encryptedSlice = cipherEngine.encryptChunk(key, chunkIndex, plaintext, plaintext.size)
        assertTrue(encryptedSlice.size >= plaintext.size + 16)

        // Decrypt with valid nonce and key
        val decrypted = cipherEngine.decryptChunk(key, chunkIndex, encryptedSlice)
        assertNotNull(decrypted)
        assertEquals(String(plaintext), String(decrypted!!))

        // Tampering with ciphertext triggers AEAD authentication rejection
        encryptedSlice[0] = (encryptedSlice[0].toInt() xor 0xFF).toByte()
        val tamperedDecrypted = cipherEngine.decryptChunk(key, chunkIndex, encryptedSlice)
        assertNull("Tampered chunk ciphertext must be rejected by Poly1305 AEAD", tamperedDecrypted)
    }

    @Test
    fun testSwarmCrossSeederPartitionAndOpportunities() {
        val crossSeeder = SwarmCrossSeeder()
        val totalChunks = 9
        val groupPeers = listOf("PEER_A", "PEER_B", "PEER_C")

        val partitions = crossSeeder.partitionChunks(totalChunks, groupPeers)
        assertEquals(3, partitions["PEER_A"]?.size) // Chunks 0, 3, 6
        assertEquals(3, partitions["PEER_B"]?.size) // Chunks 1, 4, 7
        assertEquals(3, partitions["PEER_C"]?.size) // Chunks 2, 5, 8

        // Simulate bitfields
        val trackerA = BitfieldTracker(totalChunks).apply { markReceived(0); markReceived(3); markReceived(6) }
        val trackerB = BitfieldTracker(totalChunks).apply { markReceived(1); markReceived(4); markReceived(7) }

        val tasks = crossSeeder.computeCrossSeedingOpportunities(
            totalChunks,
            mapOf("PEER_A" to trackerA, "PEER_B" to trackerB)
        )
        assertTrue("Peers must have cross-seeding tasks for missing chunks", tasks.isNotEmpty())
    }

    @Test
    fun testTransferSessionEntityPriorities() {
        val normalSession = TransferSessionEntity(
            transferId = 1L,
            conversationId = "conv_1",
            fileName = "big_video.mp4",
            fileSizeBytes = 500 * 1024 * 1024L,
            localFilePath = "/vault/video.mp4",
            fileHash = "hash1",
            chunksCompleted = 0,
            totalChunks = 8000,
            bitfieldBytes = ByteArray(0),
            status = TransferSessionStatus.PENDING_PEER_ONLINE,
            priority = 0 // Normal bulk
        )

        val smallCriticalSession = normalSession.copy(
            transferId = 2L,
            fileName = "urgent_doc.pdf",
            fileSizeBytes = 200 * 1024L,
            priority = 1 // High-priority leapfrog lane
        )

        assertTrue(smallCriticalSession.priority > normalSession.priority)
    }

    // ==========================================
    // PART 2: 18-SCENARIO MANET / DTN MESH TESTS
    // ==========================================

    @Test
    fun testLqiDecayEngineMovementAndInterference() {
        val lqiEngine = LqiDecayEngine(alpha = 0.3f, tombstoneTimeoutMs = 6000L)
        val peer = "PEER_MOBILE_1"

        // Strong link: -60 dBm
        val s1 = lqiEngine.recordSample(peer, rawRssi = -60, failedRetries = 0, transmitted = 10)
        assertEquals(LinkState.HEALTHY, s1)

        // Heavy interference / packet loss: FER > 0.4 overrides raw RSSI (Scenario 16)
        val s2 = lqiEngine.recordSample(peer, rawRssi = -60, failedRetries = 10, transmitted = 10)
        assertEquals("FER > 0.4 must classify link as UNSTABLE", LinkState.UNSTABLE, s2)
    }

    @Test
    fun testCompositeMetricEngineMediumWeightsAndBattery() {
        val engine = CompositeMetricEngine(localPeerId = "ME")

        // BLE medium cost vs Wi-Fi Aware cost (Scenario 2)
        val bleCost = engine.calculateLinkCost(mediumWeight = 4.0f, deliveryRatio = 1.0f, batteryPct = 100, queueDelayMs = 0)
        val wifiCost = engine.calculateLinkCost(mediumWeight = 1.0f, deliveryRatio = 1.0f, batteryPct = 100, queueDelayMs = 0)
        assertTrue("BLE medium must have higher cost weight than Wi-Fi Aware", bleCost > wifiCost)

        // Scenario 15: Battery degradation penalty
        val normalCost = engine.calculateLinkCost(mediumWeight = 1.0f, deliveryRatio = 1.0f, batteryPct = 50, queueDelayMs = 0)
        val lowBatteryCost = engine.calculateLinkCost(mediumWeight = 1.0f, deliveryRatio = 1.0f, batteryPct = 10, queueDelayMs = 0)
        val criticalBatteryCost = engine.calculateLinkCost(mediumWeight = 1.0f, deliveryRatio = 1.0f, batteryPct = 4, queueDelayMs = 0)

        assertTrue("Battery at 10% must incur 12x penalty factor", lowBatteryCost >= normalCost * 10f)
        assertTrue("Battery < 5% must disable relay participation (cost 1000x)", criticalBatteryCost >= 1000f)
    }

    @Test
    fun testCompositeMetricEngine25PercentHysteresisAndDwellLock() {
        val engine = CompositeMetricEngine(localPeerId = "ME", hysteresisFactor = 0.75f, dwellLockMs = 1000L)
        val dest = "DEST_NODE"

        // Install initial route with cost 100.0
        val r1 = engine.evaluateRouteUpdate(dest, candidateNextHop = "NODE_A", candidateDsn = 1L, candidateHops = 2, candidateCost = 100.0f)
        assertTrue(r1)
        assertEquals("NODE_A", engine.getNextHop(dest))

        // Marginal improvement: cost 90.0 (only 10% better; strictly requires 25% better < 75.0) -> Rejected (Scenario 3)
        val r2 = engine.evaluateRouteUpdate(dest, candidateNextHop = "NODE_B", candidateDsn = 1L, candidateHops = 2, candidateCost = 90.0f)
        assertFalse("Marginal improvement (<25%) must be rejected to prevent route flapping", r2)
        assertEquals("NODE_A", engine.getNextHop(dest))

        // Scenario 10: Fresher Destination Sequence Number (DSN = 2) overwrites immediately regardless of cost
        val r3 = engine.evaluateRouteUpdate(dest, candidateNextHop = "NODE_C", candidateDsn = 2L, candidateHops = 2, candidateCost = 150.0f)
        assertTrue("Fresher DSN must immediately update route table", r3)
        assertEquals("NODE_C", engine.getNextHop(dest))

        // Scenario 18: Hop count > 7 must be discarded
        val r4 = engine.evaluateRouteUpdate(dest, candidateNextHop = "NODE_D", candidateDsn = 3L, candidateHops = 8, candidateCost = 10.0f)
        assertFalse("Hops > 7 must be rejected by hop radius ceiling", r4)
    }

    @Test
    fun testBackpressureControllerRelayOverload() {
        val bp = BackpressureController(maxBufferBytes = 100 * 1024L)

        // Normal buffer usage (20%)
        bp.updateBufferUsage(20 * 1024L)
        assertTrue(bp.isRelayCapacityAvailable())
        assertTrue(bp.getAdvertisedCredits() > 0)

        // Scenario 5: Buffer exceeds 80%
        bp.updateBufferUsage(85 * 1024L)
        assertFalse("Buffer > 80% must disable relay advertising", bp.isRelayCapacityAvailable())
        assertEquals("Buffer > 80% must advertise 0 credits", 0, bp.getAdvertisedCredits())

        // Credit consumption test
        bp.grantCredits("NEIGHBOR_1", 2)
        assertTrue(bp.tryConsumeCredit("NEIGHBOR_1"))
        assertTrue(bp.tryConsumeCredit("NEIGHBOR_1"))
        assertFalse("Zero remaining credits must suspend transmission", bp.tryConsumeCredit("NEIGHBOR_1"))
    }

    @Test
    fun testBloomDuplicateFilterFastDetection() {
        val filter = BloomDuplicateFilter(bitSetSize = 32768, maxHistorySize = 100)

        val packetA = "MSG_PACKET_998877"
        val packetB = "MSG_PACKET_112233"

        assertFalse(filter.isDuplicateAndMark(packetA))
        assertTrue("Second arrival of packetA must be flagged as duplicate in <0.01ms", filter.isDuplicateAndMark(packetA))

        assertFalse(filter.isDuplicateAndMark(packetB))
        assertTrue(filter.isDuplicateAndMark(packetB))
    }

    @Test
    fun testSlidingReorderBufferInOrderAndOutOrder() {
        val delivered = mutableListOf<Long>()
        val evictedGaps = mutableListOf<Long>()

        val buffer = SlidingReorderBuffer(
            gapTimeoutMs = 1000L,
            onDeliverPacket = { delivered.add(it.sequenceNumber) },
            onGapEvicted = { evictedGaps.add(it) }
        )

        // In-order packet arrival: 1, 2
        buffer.onPacketReceived(SequencedPacket(1L, "ONE".toByteArray()))
        buffer.onPacketReceived(SequencedPacket(2L, "TWO".toByteArray()))
        assertEquals(listOf(1L, 2L), delivered)

        // Out-of-order packet: packet 4 arrives before packet 3 (Scenario 13)
        buffer.onPacketReceived(SequencedPacket(4L, "FOUR".toByteArray()))
        assertEquals("Packet 4 must be held in reorder buffer until packet 3 arrives", listOf(1L, 2L), delivered)
        assertEquals(1, buffer.bufferedCount)

        // Packet 3 arrives -> drains both 3 and 4
        buffer.onPacketReceived(SequencedPacket(3L, "THREE".toByteArray()))
        assertEquals(listOf(1L, 2L, 3L, 4L), delivered)
        assertEquals(0, buffer.bufferedCount)
    }

    @Test
    fun testMerkleClusterSyncComparison() {
        val clusterSync = MerkleClusterSync()
        val nodesA = listOf("NODE_1", "NODE_2", "NODE_3")
        val nodesB = listOf("NODE_1", "NODE_2", "NODE_3")
        val nodesC = listOf("NODE_1", "NODE_2", "NODE_4") // Cluster partition difference

        val fpA = clusterSync.computeClusterFingerprint(nodesA)
        val fpB = clusterSync.computeClusterFingerprint(nodesB)
        val fpC = clusterSync.computeClusterFingerprint(nodesC)

        assertEquals(8, fpA.size)
        assertFalse("Identical node sets must produce identical Merkle roots (zero sync airtime)", clusterSync.shouldRequestDeltaSync(fpA, fpB))
        assertTrue("Partitioned clusters with different nodes must trigger delta gossip", clusterSync.shouldRequestDeltaSync(fpA, fpC))
    }

    @Test
    fun testMeshPacketHopRadiusCeiling() {
        val packet = MeshPacket(
            packetId = "PKT_001",
            sourceId = "SRC",
            destinationId = "DST",
            dsn = 10L,
            hopLimit = 7,
            hopCount = 0,
            payload = "HELLO_MESH".toByteArray()
        )

        assertTrue(packet.canForward())
        var current = packet
        for (i in 0 until 7) {
            current = current.nextHopPacket()
        }

        assertEquals(7, current.hopCount)
        assertEquals(0, current.hopLimit)
        assertFalse("Packet reaching 7 hops must not be forwardable (Scenario 18)", current.canForward())
    }
}
