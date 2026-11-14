package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Authoritative persistent deduplication index for exact message tracking.
 * Survives app restarts, device reboots, and cross-transport redundant deliveries.
 */
@Entity(
    tableName = "message_dedup_index",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["senderNxId"]),
        Index(value = ["firstObservedAt"])
    ]
)
data class MessageDedupEntity(
    @PrimaryKey
    val packetId: String,
    val senderNxId: String,
    val recipientNxId: String,
    val conversationId: String,
    val payloadHashHex: String,
    val firstObservedAt: Long,
    val firstTransport: String,
    val status: String = STATUS_PROCESSED // RESERVED, PROCESSED, CONFLICT
) {
    companion object {
        const val STATUS_RESERVED = "RESERVED"
        const val STATUS_PROCESSED = "PROCESSED"
        const val STATUS_CONFLICT = "CONFLICT"
    }
}
