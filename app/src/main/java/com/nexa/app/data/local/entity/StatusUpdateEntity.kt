package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nexa.app.domain.model.StatusItem

/**
 * Persists 24-hour ephemeral status/story updates.
 */
@Entity(
    tableName = "status_updates",
    indices = [
        Index(value = ["userNxId"]),
        Index(value = ["expiresAt"]),
        Index(value = ["isViewed"])
    ]
)
data class StatusUpdateEntity(
    @PrimaryKey
    val statusId: String,
    val userNxId: String,
    val mediaReference: String? = null,
    val caption: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 86400000L, // 24 hours
    val privacy: String = "CONTACTS", // PUBLIC, CONTACTS, EXCLUDED
    val statusType: String = "TEXT",   // TEXT, IMAGE, VIDEO
    val isViewed: Boolean = false,
    val viewedAt: Long? = null,
    val viewCount: Int = 0
)

fun StatusUpdateEntity.toDomain(): StatusItem = StatusItem(
    statusId = statusId,
    userNxId = userNxId,
    mediaReference = mediaReference,
    caption = caption,
    createdAt = createdAt,
    expiresAt = expiresAt,
    privacy = privacy,
    statusType = statusType,
    isViewed = isViewed,
    viewedAt = viewedAt,
    viewCount = viewCount
)

fun StatusItem.toEntity(): StatusUpdateEntity = StatusUpdateEntity(
    statusId = statusId,
    userNxId = userNxId,
    mediaReference = mediaReference,
    caption = caption,
    createdAt = createdAt,
    expiresAt = expiresAt,
    privacy = privacy,
    statusType = statusType,
    isViewed = isViewed,
    viewedAt = viewedAt,
    viewCount = viewCount
)

internal fun StatusUpdateEntity.isActiveAt(now: Long): Boolean =
    createdAt <= now && expiresAt > now
