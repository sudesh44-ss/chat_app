package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "device_sync_log",
    indices = [
        Index(value = ["sourceDeviceId"]),
        Index(value = ["isSynced"])
    ]
)
data class DeviceSyncEntity(
    @PrimaryKey
    val syncId: String,
    val sourceDeviceId: String,
    val sequenceNumber: Long,
    val entityType: String, // "MESSAGE", "CONTACT", "READ_RECEIPT"
    val encryptedPayloadHex: String,
    val timestamp: Long,
    val isSynced: Boolean = false
)
