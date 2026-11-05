package com.nexa.app.core.media

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Strategy target for media transmission.
 */
enum class MediaTransportRoute {
    /**
     * Payloads <= 64 KB (Thumbnails, Audio / Voice Notes) embedded directly into
     * primary E2EE WebSocket envelope for near-zero latency UX.
     */
    INLINE_E2EE_WEBSOCKET,

    /**
     * Direct peer-to-peer WebRTC DataChannel (Priority 1 for 1-to-1 chats when peer is ONLINE).
     * Server Bandwidth: 0 MB, Server Storage: 0 Bytes, Hosting Cost: ₹0.
     */
    WEBRTC_P2P_DIRECT,

    /**
     * Cloudflare R2 Blind Ephemeral Fallback:
     * - Group chats (1x upload prevents 10x cellular drain)
     * - Recipient offline (asynchronous delivery)
     * - 4.5-Second ICE / Symmetric NAT circuit breaker timeout
     */
    CLOUDFLARE_R2_BLIND_FALLBACK
}

/**
 * Result of the routing evaluation decision.
 */
data class MediaRoutingDecision(
    val selectedRoute: MediaTransportRoute,
    val mediaSizeBytes: Long,
    val isGroup: Boolean,
    val isRecipientOnline: Boolean,
    val reason: String
)

/**
 * Result of an attempted media transfer.
 */
sealed class MediaTransferResult {
    data class P2PSuccess(val transferId: Long, val totalBytes: Long) : MediaTransferResult()
    data class BlindR2FallbackSuccess(
        val objectKey: String,
        val cipherHash: String,
        val mediaKey: ByteArray,
        val iv: ByteArray,
        val sizeBytes: Long
    ) : MediaTransferResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as BlindR2FallbackSuccess
            if (objectKey != other.objectKey) return false
            if (cipherHash != other.cipherHash) return false
            if (!mediaKey.contentEquals(other.mediaKey)) return false
            if (!iv.contentEquals(other.iv)) return false
            if (sizeBytes != other.sizeBytes) return false
            return true
        }

        override fun hashCode(): Int {
            var result = objectKey.hashCode()
            result = 31 * result + cipherHash.hashCode()
            result = 31 * result + mediaKey.contentHashCode()
            result = 31 * result + iv.contentHashCode()
            result = 31 * result + sizeBytes.hashCode()
            return result
        }
    }
    data class InlineSuccess(val payloadBytes: ByteArray) : MediaTransferResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as InlineSuccess
            return payloadBytes.contentEquals(other.payloadBytes)
        }

        override fun hashCode(): Int = payloadBytes.contentHashCode()
    }
    data class Failed(val error: String, val circuitBreakerTripped: Boolean) : MediaTransferResult()
}

/**
 * NEXA Production Media Routing Coordinator:
 *
 * Implements the 4 Master Architectural Rules:
 * 1. Strict "Symmetric NAT / ICE Failure" 4.5-Second Circuit Breaker (Timeout Gate)
 * 2. Direct 1-to-1 (P2P Priority 1) vs Group Multi-Peer (R2 Priority 1) Split
 * 3. WebRTC Signaling via Ephemeral WebSocket Packets (OP_SIGNALING)
 * 4. Content-Type Media Routing Policy (<= 64 KB Inline, > 64 KB P2P/R2)
 *
 * Enforces the 3 Zero-Knowledge / Anti-Cost Exclusions:
 * - ❌ No PostgreSQL Media Metadata (Stored client-side in SQLCipher, keys in Double Ratchet)
 * - ❌ No Pure TURN Relay Streaming ($0 Egress Cloudflare R2 replaces costly TURN relays)
 * - ❌ No AWS S3 (Strictly Cloudflare R2 to avoid unpredictable egress bills)
 */
object MediaRoutingCoordinator {

    private const val TAG = "MediaRoutingCoordinator"

    /** 64 KB boundary for inline WebSocket E2EE embedding */
    const val INLINE_MAX_BYTES = 64 * 1024L

    /** 4.5-Second hard timeout gate for P2P ICE candidate gathering / DataChannel negotiation */
    const val ICE_GATHERING_TIMEOUT_MS = 4500L

    /** 100 MB ceiling for Cloudflare R2 Blind Cloud Fallback */
    const val MAX_CLOUD_FALLBACK_BYTES = 100 * 1024 * 1024L

    /**
     * Determines the optimal media route based on size, group chat status, and recipient presence.
     */
    fun evaluateRoute(
        mediaSizeBytes: Long,
        isGroup: Boolean,
        isRecipientOnline: Boolean
    ): MediaRoutingDecision {
        // Rule D: Thumbnails / Voice Notes <= 64 KB -> Inline WebSocket E2EE blob
        if (mediaSizeBytes in 1..INLINE_MAX_BYTES) {
            return MediaRoutingDecision(
                selectedRoute = MediaTransportRoute.INLINE_E2EE_WEBSOCKET,
                mediaSizeBytes = mediaSizeBytes,
                isGroup = isGroup,
                isRecipientOnline = isRecipientOnline,
                reason = "Lightweight media (<= 64KB) packs directly into E2EE WebSocket envelope for instant UX."
            )
        }

        // Rule B: Group Chat -> Direct Cloudflare R2 Blind Blob (1x upload prevents sender bandwidth/battery burnout)
        if (isGroup) {
            return MediaRoutingDecision(
                selectedRoute = MediaTransportRoute.CLOUDFLARE_R2_BLIND_FALLBACK,
                mediaSizeBytes = mediaSizeBytes,
                isGroup = true,
                isRecipientOnline = isRecipientOnline,
                reason = "Group chat media uses 1x Cloudflare R2 Blind Blob upload so sender does not upload N-times to each peer."
            )
        }

        // Direct 1-to-1 Chat: Recipient Offline -> Immediate Cloudflare R2 Fallback
        if (!isRecipientOnline) {
            return MediaRoutingDecision(
                selectedRoute = MediaTransportRoute.CLOUDFLARE_R2_BLIND_FALLBACK,
                mediaSizeBytes = mediaSizeBytes,
                isGroup = false,
                isRecipientOnline = false,
                reason = "Recipient offline: P2P cannot establish. Routing directly to Cloudflare R2 Blind Mailbox (7-day TTL)."
            )
        }

        // Direct 1-to-1 Chat: Recipient Online -> Priority 1 WebRTC P2P Swarm with 4.5s Circuit Breaker Gate
        return MediaRoutingDecision(
            selectedRoute = MediaTransportRoute.WEBRTC_P2P_DIRECT,
            mediaSizeBytes = mediaSizeBytes,
            isGroup = false,
            isRecipientOnline = true,
            reason = "Direct 1-to-1 online peer: Priority 1 WebRTC P2P DataChannel with 4.5s ICE Gathering Circuit Breaker."
        )
    }

    /**
     * Executes the P2P connection with a strict 4.5-Second Hard Timeout Gate.
     * If WebRTC fails or times out (e.g. Carrier Symmetric CGNAT), it immediately trips
     * the circuit breaker and switches to Cloudflare R2 Blind Fallback.
     */
    suspend fun executeWithIceCircuitBreaker(
        sourceFile: File,
        timeoutMs: Long = ICE_GATHERING_TIMEOUT_MS,
        onP2PAttempt: suspend () -> Long,
        onR2Fallback: suspend (EncryptedMediaResult) -> MediaTransferResult.BlindR2FallbackSuccess
    ): MediaTransferResult {
        return try {
            Log.d(TAG, "Initiating WebRTC P2P ICE candidate negotiation with ${timeoutMs}ms circuit breaker gate...")
            val transferId = withTimeout(timeoutMs) {
                onP2PAttempt()
            }
            Log.d(TAG, "WebRTC DataChannel connected successfully within timeout gate! Transfer ID: $transferId")
            MediaTransferResult.P2PSuccess(transferId, sourceFile.length())
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Circuit Breaker Tripped: ICE candidate negotiation timed out after ${timeoutMs}ms (Carrier Symmetric NAT). Switching to Cloudflare R2 Blind Fallback.")
            triggerBlindR2Fallback(sourceFile, onR2Fallback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Circuit Breaker Tripped: P2P connection failed (${e.message}). Switching to Cloudflare R2 Blind Fallback.")
            triggerBlindR2Fallback(sourceFile, onR2Fallback)
        }
    }

    private suspend fun triggerBlindR2Fallback(
        sourceFile: File,
        onR2Fallback: suspend (EncryptedMediaResult) -> MediaTransferResult.BlindR2FallbackSuccess
    ): MediaTransferResult {
        return try {
            val tempEncryptedFile = File.createTempFile("nexa_blind_", ".enc")
            try {
                // Client-side AES-256-GCM streaming encryption
                val encResult = BlindMediaVaultEngine.encryptMediaStream(
                    sourceFile = sourceFile,
                    destinationEncryptedFile = tempEncryptedFile
                )
                onR2Fallback(encResult)
            } finally {
                if (tempEncryptedFile.exists()) tempEncryptedFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Blind Cloud Fallback failed: ${e.message}", e)
            MediaTransferResult.Failed(
                error = e.localizedMessage ?: "Blind Cloud Fallback failed",
                circuitBreakerTripped = true
            )
        }
    }
}
