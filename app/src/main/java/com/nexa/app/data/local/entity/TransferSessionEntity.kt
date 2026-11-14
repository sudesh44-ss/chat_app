package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TransferSessionStatus {
    CREATED,
    QUEUED,
    CONNECTING,
    TRANSFERRING,
    PAUSED,
    BACKGROUND_RESTRICTED,
    CONNECTION_LOST,
    RETRY_PENDING,
    RESUMING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,

    // Legacy compatibility names retained for existing Room rows and callers.
    PENDING_PEER_ONLINE,
    ACTIVE,
    PAUSED_OFFLINE,
    ABORTED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == ABORTED

    val isRecoverable: Boolean
        get() = !isTerminal

    companion object {
        fun fromLegacyOrNormalized(raw: String?): TransferSessionStatus {
            val normalized = raw?.trim() ?: return CREATED
            return when (normalized.uppercase()) {
                "", "CREATED" -> CREATED
                "QUEUED", "PENDING_PEER_ONLINE" -> QUEUED
                "CONNECTING" -> CONNECTING
                "TRANSFERRING", "ACTIVE" -> TRANSFERRING
                "PAUSED", "PAUSED_OFFLINE" -> PAUSED
                "BACKGROUND_RESTRICTED" -> BACKGROUND_RESTRICTED
                "CONNECTION_LOST" -> CONNECTION_LOST
                "RETRY_PENDING" -> RETRY_PENDING
                "RESUMING" -> RESUMING
                "VERIFYING" -> VERIFYING
                "COMPLETED" -> COMPLETED
                "FAILED" -> FAILED
                "CANCELLED", "ABORTED" -> CANCELLED
                else -> runCatching { valueOf(normalized.uppercase()) }.getOrDefault(CREATED)
            }
        }
    }
}

/**
 * Manages resilient transfer state, BitSet resumption checkpoints, priority ordering,
 * and circuit-breaker telemetry across all 18 scenarios.
 */
@Entity(tableName = "transfer_sessions")
data class TransferSessionEntity(
    @PrimaryKey val transferId: Long,
    val conversationId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val localFilePath: String,
    val fileHash: String,
    val chunksCompleted: Int,
    val totalChunks: Int,
    val bitfieldBytes: ByteArray,
    val status: TransferSessionStatus,
    val priority: Int = 0,               // Scenario 11: 1 for small files < 5MB, 0 for normal
    val isTurnRelayed: Boolean = false,  // Scenario 14: Circuit breaker rate-limited TURN flag
    val lastActiveTimestamp: Long = System.currentTimeMillis(),
    val senderId: String = "",
    val receiverId: String = "",
    val transferDirection: String = "OUTBOUND",
    val transportType: String = "WEBRTC",
    val chunkSize: Int = 64 * 1024,
    val retryCount: Int = 0,
    val lastSuccessfulCheckpoint: Long = 0L,
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val uploadedBytes: Long = 0L,
    val receivedBytes: Long = 0L
) {
    fun normalizedStatus(): TransferSessionStatus = TransferSessionStatus.fromLegacyOrNormalized(status.name)

    fun toCheckpoint(): TransferCheckpoint = TransferCheckpoint(
        transferId = transferId,
        fileName = fileName,
        fileSizeBytes = fileSizeBytes,
        status = normalizedStatus(),
        completedChunks = chunksCompleted,
        totalChunks = totalChunks,
        uploadedBytes = uploadedBytes,
        receivedBytes = receivedBytes,
        retryCount = retryCount,
        lastSuccessfulCheckpoint = lastSuccessfulCheckpoint,
        lastUpdatedAt = lastUpdatedAt
    )
}

data class TransferCheckpoint(
    val transferId: Long,
    val fileName: String,
    val fileSizeBytes: Long,
    val status: TransferSessionStatus,
    val completedChunks: Int,
    val totalChunks: Int,
    val uploadedBytes: Long,
    val receivedBytes: Long,
    val retryCount: Int,
    val lastSuccessfulCheckpoint: Long,
    val lastUpdatedAt: Long
)
