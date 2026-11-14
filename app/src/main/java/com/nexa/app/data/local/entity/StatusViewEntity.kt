package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persists view receipts from peers for the current user's statuses.
 */
@Entity(
    tableName = "status_views",
    indices = [
        Index(value = ["statusId", "viewerNxId"], unique = true),
        Index(value = ["statusId"])
    ]
)
data class StatusViewEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val statusId: String,
    val viewerNxId: String,
    val viewedAt: Long = System.currentTimeMillis()
)
