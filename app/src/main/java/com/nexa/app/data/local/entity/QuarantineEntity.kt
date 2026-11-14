package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class QuarantineStatus {
    ISOLATED,
    SHREDDED,
    OVERRIDDEN_SAFE,
    VERIFIED_CLEAN,
    INCONCLUSIVE,
    RELEASED
}

/**
 * Audit log entity tracking quarantined, analyzed, shredded, or overridden P2P payloads.
 */
@Entity(tableName = "quarantine_logs")
data class QuarantineEntity(
    @PrimaryKey val fileHash: String,
    val fileName: String,
    val threatReason: String?,
    val status: QuarantineStatus,
    val quarantinedFilePath: String,
    val detectedTimestamp: Long = System.currentTimeMillis()
)
