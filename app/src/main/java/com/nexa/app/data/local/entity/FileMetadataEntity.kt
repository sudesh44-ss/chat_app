package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class FileStatus {
    METADATA_ONLY,       // Visible on chat, not downloaded (0 Bytes used)
    DOWNLOADING,         // Chunks actively streaming from swarm
    IN_QUARANTINE,       // Downloaded into sandbox, running post-scan
    SCAN_PASSED,         // Receiver scan passed; explicit release still required
    SCAN_INCONCLUSIVE,   // Scan incomplete or format unsupported; release prohibited
    VERIFIED_SAFE,       // Passed Gate 2, ready to view / seed
    OVERRIDDEN_RELEASED, // Receiver explicitly accepted a failed scan
    QUARANTINED_THREAT   // Threat detected in quarantine sandbox
}

@Entity(
    tableName = "file_envelopes",
    indices = [Index(value = ["fileHash"], unique = true)]
)
data class FileMetadataEntity(
    @PrimaryKey val fileHash: String, // Blake3 Hex
    val conversationId: String,
    val senderNxId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val totalChunks: Int,
    val blurHash: String? = null,
    val status: FileStatus = FileStatus.METADATA_ONLY,
    val localFilePath: String? = null,
    val lastAccessedTimestamp: Long = System.currentTimeMillis()
)
