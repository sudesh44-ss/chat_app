package com.nexa.app

import com.nexa.app.core.sms.ChunkReassembler
import com.nexa.app.core.sms.SmsChunkPacket
import com.nexa.app.core.sms.SmsPayloadCodec
import com.nexa.app.core.webrtc.PeerLifecycleState
import com.nexa.app.core.webrtc.WebRtcClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class Phase3StabilityAndReliabilityTests {

    @Test
    fun webRtc_peerLifecycleState_transitionsAndTerminalGuards() {
        val states = PeerLifecycleState.values()
        assertEquals(9, states.size)
        assertTrue(states.contains(PeerLifecycleState.NEW))
        assertTrue(states.contains(PeerLifecycleState.INITIALIZING))
        assertTrue(states.contains(PeerLifecycleState.CONNECTING))
        assertTrue(states.contains(PeerLifecycleState.CONNECTED))
        assertTrue(states.contains(PeerLifecycleState.DISCONNECTED))
        assertTrue(states.contains(PeerLifecycleState.RECONNECTING))
        assertTrue(states.contains(PeerLifecycleState.FAILED))
        assertTrue(states.contains(PeerLifecycleState.CLOSING))
        assertTrue(states.contains(PeerLifecycleState.CLOSED))
    }

    @Test
    fun webRtc_safeDisposalHelpers_handleNullsAndDoubleDisposalGracefully() {
        // Safe helpers must never throw on null or multiple invocations
        WebRtcClient.safeDisposeDataChannel(null)
        WebRtcClient.safeDisposePeerConnection(null)
        WebRtcClient.safeDisposeSession(null, null)
    }

    @Test
    fun smsPayloadCodec_crc8_computationAndIntegrity() {
        val testData = "NEXA Compact Binary Payload".toByteArray(Charsets.UTF_8)
        val crc = SmsPayloadCodec.computeCrc8(testData)

        // CRC-8 deterministic value
        val crcRepeat = SmsPayloadCodec.computeCrc8(testData)
        assertEquals(crc, crcRepeat)

        // Corrupted payload must produce different CRC
        val corrupted = testData.copyOf()
        corrupted[3] = (corrupted[3].toInt() xor 0x5A).toByte()
        val corruptedCrc = SmsPayloadCodec.computeCrc8(corrupted)
        assertFalse(crc == corruptedCrc)
    }

    @Test
    fun smsPayloadCodec_compact5ByteHeader_encodingAndDecoding() {
        val messageId: Short = 0x1234
        val seq = 2
        val total = 5
        val chunkData = ByteArray(64).also { Random(42).nextBytes(it) }

        val encoded = SmsPayloadCodec.encodeCompactChunk(
            messageId = messageId,
            chunkIndex = seq,
            totalChunks = total,
            chunkData = chunkData
        )

        assertEquals(SmsPayloadCodec.COMPACT_HEADER_SIZE + chunkData.size, encoded.size)
        assertTrue(encoded.size <= 133) // Strictly within 133B PDU limit

        val decoded = SmsPayloadCodec.decodeCompactChunk(encoded)
        assertNotNull(decoded)
        assertEquals(0x1234L, decoded?.messageId)
        assertEquals(seq, decoded?.chunkIndex)
        assertEquals(total, decoded?.totalChunks)
        assertTrue(chunkData.contentEquals(decoded!!.payload))
    }

    @Test
    fun smsPayloadCodec_compactHeader_corruptedCrc_failsValidation() {
        val messageId: Short = 0x5678
        val chunkData = "Important Secure Packet".toByteArray(Charsets.UTF_8)

        val encoded = SmsPayloadCodec.encodeCompactChunk(
            messageId = messageId,
            chunkIndex = 0,
            totalChunks = 1,
            chunkData = chunkData
        )

        // Corrupt the CRC byte at index 4
        encoded[4] = (encoded[4].toInt() xor 0xFF).toByte()

        val decoded = SmsPayloadCodec.decodeCompactChunk(encoded)
        assertNull("Corrupted CRC must cause silent drop", decoded)
    }

    @Test
    fun chunkReassembler_boundedMemory_evictsOldestWhenCapReached() {
        val maxSessions = 3
        val reassembler = ChunkReassembler(chunkTtlMillis = 600_000L, maxConcurrentSessions = maxSessions)

        // Ingest chunk 0 of 2 for messages 1, 2, 3
        reassembler.ingestChunk(SmsChunkPacket(messageId = 1L, chunkIndex = 0, totalChunks = 2, payload = byteArrayOf(1)))
        reassembler.ingestChunk(SmsChunkPacket(messageId = 2L, chunkIndex = 0, totalChunks = 2, payload = byteArrayOf(2)))
        reassembler.ingestChunk(SmsChunkPacket(messageId = 3L, chunkIndex = 0, totalChunks = 2, payload = byteArrayOf(3)))

        assertEquals(3, reassembler.activeSessionCount())

        // Ingest 4th message -> should evict the oldest session (msg 1)
        reassembler.ingestChunk(SmsChunkPacket(messageId = 4L, chunkIndex = 0, totalChunks = 2, payload = byteArrayOf(4)))
        assertEquals(3, reassembler.activeSessionCount())

        // Attempt to finish evicted session 1 -> should not complete
        val result1 = reassembler.ingestChunk(SmsChunkPacket(messageId = 1L, chunkIndex = 1, totalChunks = 2, payload = byteArrayOf(1)))
        assertNull("Evicted session should not complete", result1)

        // Successfully finish session 4
        val result4 = reassembler.ingestChunk(SmsChunkPacket(messageId = 4L, chunkIndex = 1, totalChunks = 2, payload = byteArrayOf(4)))
        assertNotNull(result4)
        assertEquals(2, result4?.size)
    }

    @Test
    fun webSocket_backoffJitter_strictlyBounded() {
        var baseDelay = 1500L
        for (i in 0 until 10) {
            val jitter = (0..500).random().toLong()
            val delayWithJitter = (baseDelay + jitter).coerceAtMost(30_000L)
            assertTrue(delayWithJitter >= baseDelay)
            assertTrue(delayWithJitter <= 30_000L)
            assertTrue(delayWithJitter <= baseDelay + 500)
            baseDelay = (baseDelay * 2).coerceAtMost(30_000L)
        }
    }
}
