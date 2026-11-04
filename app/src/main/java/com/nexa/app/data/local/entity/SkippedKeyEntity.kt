package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "skipped_message_keys",
    indices = [Index(value = ["contactNxId", "sequenceNumber"], unique = true)]
)
data class SkippedKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactNxId: String,
    val sequenceNumber: Int,
    val derivedKeyHex: String,
    val createdAtTimestamp: Long = System.currentTimeMillis()
)
