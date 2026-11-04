package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TransferSessionStatus {
    PENDING_PEER_ONLINE, // Scenario 1: Peer offline, waiting for FCM wake
    ACTIVE,
    PAUSED_OFFLINE,      // Scenario 2 & 5: Network dropped / reconnecting
    COMPLETED,
    FAILED,
    ABORTED              // Scenario 9: Source file unreadable or deleted
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
    val lastActiveTimestamp: Long = System.currentTimeMillis()
)
