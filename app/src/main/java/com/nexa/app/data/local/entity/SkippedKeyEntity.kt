package com.nexa.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "skipped_message_keys",
    indices = [
        Index(value = ["contactNxId", "sequenceNumber"], unique = true),
        Index(value = ["contactNxId", "ratchetPublicKeyHex", "messageIndex"])
    ]
)
data class SkippedKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactNxId: String,
    val sequenceNumber: Int = 0,
    @ColumnInfo(defaultValue = "''")
    val ratchetPublicKeyHex: String = "",
    @ColumnInfo(defaultValue = "0")
    val messageIndex: Int = 0,
    val derivedKeyHex: String,
    val createdAtTimestamp: Long = System.currentTimeMillis()
)
