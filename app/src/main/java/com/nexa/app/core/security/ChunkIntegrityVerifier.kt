package com.nexa.app.core.security

import com.nexa.app.core.webrtc.FileChunk
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

sealed interface ChunkVerificationResult {
    data object Valid : ChunkVerificationResult
    data object DuplicateOrReplayed : ChunkVerificationResult
    data class Rejected(val reason: String, val threatCategory: ThreatCategory) : ChunkVerificationResult
}

sealed interface TransferCompletionResult {
    data class Verified(val totalBytes: Long, val totalChunks: Int) : TransferCompletionResult
    data class Incomplete(val receivedCount: Int, val expectedCount: Int, val missingChunks: List<Int>) : TransferCompletionResult
    data class Corrupted(val reason: String) : TransferCompletionResult
}

/**
 * CHECKPOINT 2: Continuous Transfer-Time Security & Integrity Verifier.
 *
 * Verifies every incoming WebRTC DataChannel chunk in real time:
 * 1. Authenticated context binding (Transfer ID, Sender NxId, Recipient NxId).
 * 2. Sequence boundary validation (chunkIndex in 0 until expectedTotalChunks).
 * 3. Replay and duplicate chunk protection via bitset tracking.
 * 4. Transfer size ceiling & byte explosion defense.
 * 5. Cryptographic chunk MAC tag verification where session keys are bound.
 * 6. EOF reassembly completeness validation (zero missing chunks).
 */
class ChunkIntegrityVerifier {

    private val activeSessions = ConcurrentHashMap<Long, TransferSessionTracker>()

    data class TransferSessionTracker(
        val transferId: Long,
        val senderNxId: String,
        val recipientNxId: String,
        val expectedTotalChunks: Int,
        val expectedTotalBytes: Long,
        val expectedFileHash: String,
        val receivedIndices: BitSet = BitSet(expectedTotalChunks),
        val receivedBytesTotal: AtomicLong = AtomicLong(0L),
        val sessionHmacKey: ByteArray? = null
    )

    fun registerSession(
        transferId: Long,
        senderNxId: String,
        recipientNxId: String,
        expectedTotalChunks: Int,
        expectedTotalBytes: Long,
        expectedFileHash: String,
        sessionHmacKey: ByteArray? = null
    ) {
        activeSessions[transferId] = TransferSessionTracker(
            transferId = transferId,
            senderNxId = senderNxId,
            recipientNxId = recipientNxId,
            expectedTotalChunks = expectedTotalChunks,
            expectedTotalBytes = expectedTotalBytes,
            expectedFileHash = expectedFileHash,
            sessionHmacKey = sessionHmacKey
        )
    }

    fun unregisterSession(transferId: Long) {
        activeSessions.remove(transferId)
    }

    /**
     * Verifies an incoming chunk against session invariants before writing to disk.
     */
    fun verifyChunk(
        chunk: FileChunk,
        senderNxId: String? = null,
        recipientNxId: String? = null,
        chunkMacTag: ByteArray? = null
    ): ChunkVerificationResult {
        val session = activeSessions[chunk.transferId]
            ?: return ChunkVerificationResult.Rejected(
                "Unregistered or expired transfer context: #${chunk.transferId}",
                ThreatCategory.CORRUPTED_STREAM
            )

        // 1. Context Binding Validation
        if (senderNxId != null && senderNxId != session.senderNxId) {
            return ChunkVerificationResult.Rejected(
                "Sender context spoofing: expected ${session.senderNxId}, got $senderNxId",
                ThreatCategory.CORRUPTED_STREAM
            )
        }
        if (recipientNxId != null && recipientNxId != session.recipientNxId) {
            return ChunkVerificationResult.Rejected(
                "Recipient context mismatch: expected ${session.recipientNxId}, got $recipientNxId",
                ThreatCategory.CORRUPTED_STREAM
            )
        }

        // 2. Chunk Sequence Boundary Validation
        if (chunk.chunkIndex < 0 || chunk.chunkIndex >= session.expectedTotalChunks) {
            return ChunkVerificationResult.Rejected(
                "Chunk index ${chunk.chunkIndex} out of valid bounds [0, ${session.expectedTotalChunks - 1}]",
                ThreatCategory.CORRUPTED_STREAM
            )
        }

        // 3. Duplicate and Replay Protection
        synchronized(session.receivedIndices) {
            if (session.receivedIndices.get(chunk.chunkIndex)) {
                return ChunkVerificationResult.DuplicateOrReplayed
            }
        }

        // 4. Expected Size Ceiling Validation (Prevents buffer/disk exhaustion attacks)
        val newTotal = session.receivedBytesTotal.get() + chunk.data.size
        if (newTotal > session.expectedTotalBytes + FileChunk.DEFAULT_CHUNK_SIZE) {
            return ChunkVerificationResult.Rejected(
                "Cumulative transfer size ($newTotal bytes) exceeds expected file size (${session.expectedTotalBytes} bytes)",
                ThreatCategory.FILE_TOO_LARGE
            )
        }

        // 5. Cryptographic Chunk Tag / MAC Verification (if key bound)
        if (session.sessionHmacKey != null && chunkMacTag != null) {
            val expectedTag = computeChunkMac(session.sessionHmacKey, chunk)
            if (!MessageDigest.isEqual(expectedTag, chunkMacTag)) {
                return ChunkVerificationResult.Rejected(
                    "Cryptographic chunk MAC authentication failed for chunk #${chunk.chunkIndex}",
                    ThreatCategory.CORRUPTED_STREAM
                )
            }
        }

        // 6. Record Chunk Reception
        synchronized(session.receivedIndices) {
            session.receivedIndices.set(chunk.chunkIndex)
        }
        session.receivedBytesTotal.addAndGet(chunk.data.size.toLong())

        return ChunkVerificationResult.Valid
    }

    /**
     * Validates that all chunks have been received without gaps upon EOF.
     */
    fun finalizeSession(transferId: Long): TransferCompletionResult {
        val session = activeSessions[transferId]
            ?: return TransferCompletionResult.Corrupted("Session not found for transfer #$transferId")

        synchronized(session.receivedIndices) {
            val receivedCount = session.receivedIndices.cardinality()
            if (receivedCount < session.expectedTotalChunks) {
                val missing = mutableListOf<Int>()
                for (i in 0 until session.expectedTotalChunks) {
                    if (!session.receivedIndices.get(i)) {
                        missing.add(i)
                        if (missing.size >= 50) break // Cap list size
                    }
                }
                return TransferCompletionResult.Incomplete(
                    receivedCount = receivedCount,
                    expectedCount = session.expectedTotalChunks,
                    missingChunks = missing
                )
            }

            val totalBytes = session.receivedBytesTotal.get()
            return TransferCompletionResult.Verified(
                totalBytes = totalBytes,
                totalChunks = session.expectedTotalChunks
            )
        }
    }

    private fun computeChunkMac(hmacKey: ByteArray, chunk: FileChunk): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        mac.update(chunk.transferId.toString().toByteArray())
        mac.update(chunk.chunkIndex.toString().toByteArray())
        mac.update(if (chunk.isEof) 1.toByte() else 0.toByte())
        return mac.doFinal(chunk.data)
    }
}
