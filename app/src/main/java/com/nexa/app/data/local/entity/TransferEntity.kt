package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "file_transfers",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["status"])
    ]
)
data class TransferEntity(
    @PrimaryKey
    val transferId: Long,
    val conversationId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val localUri: String,
    val fileHash: String,            // Pre-shared Blake3 / SHA-256 integrity hash
    val chunksCompleted: Int,
    val totalChunks: Int,
    val status: String,              // PENDING, ACTIVE, PAUSED, COMPLETED, FAILED
    val isIncoming: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
