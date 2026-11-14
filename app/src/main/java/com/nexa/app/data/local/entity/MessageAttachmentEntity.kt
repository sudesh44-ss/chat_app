package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores attachment and media metadata separately from message content.
 * Links to messages.packetId and tracks format detection, media dimensions,
 * transfer progress, and 3-checkpoint security verification states.
 */
@Entity(
    tableName = "message_attachments",
    indices = [
        Index(value = ["messageId"]),
        Index(value = ["mimeType"]),
        Index(value = ["securityScanState"])
    ]
)
data class MessageAttachmentEntity(
    @PrimaryKey
    val attachmentId: String,
    val messageId: String,
    val fileName: String,
    val mimeType: String,
    val detectedFileType: String,
    val fileSize: Long,
    val localPathReference: String? = null,
    val thumbnailReference: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val transferState: String = "COMPLETED", // PENDING, TRANSFERRING, COMPLETED, FAILED
    val integrityState: String = "VERIFIED", // PENDING, VERIFIED, CORRUPTED
    val securityScanState: String = "SAFE",   // SCANNING, SAFE, MALICIOUS, QUARANTINED
    val createdAt: Long = System.currentTimeMillis()
)
