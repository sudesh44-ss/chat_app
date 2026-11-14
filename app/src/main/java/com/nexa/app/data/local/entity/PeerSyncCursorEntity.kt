package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persists peer-to-peer anti-entropy synchronization cursors.
 * Tracks reconciliation progress per conversation/peer across network disconnects.
 */
@Entity(
    tableName = "peer_sync_cursors",
    indices = [
        Index(value = ["peerNxId"]),
        Index(value = ["lastSyncTimestamp"])
    ]
)
data class PeerSyncCursorEntity(
    @PrimaryKey
    val conversationId: String,
    val peerNxId: String,
    val lastSyncTimestamp: Long = 0L,
    val lastSyncSequence: Long = 0L,
    val lastSyncedPacketId: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
