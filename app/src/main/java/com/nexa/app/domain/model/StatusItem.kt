package com.nexa.app.domain.model

/**
 * Domain model representing an individual 24-hour status update.
 */
data class StatusItem(
    val statusId: String,
    val userNxId: String,
    val mediaReference: String? = null,
    val caption: String = "",
    val createdAt: Long,
    val expiresAt: Long,
    val privacy: String = "CONTACTS",
    val statusType: String = "TEXT",
    val isViewed: Boolean = false,
    val viewedAt: Long? = null,
    val viewCount: Int = 0
) {
    val isExpired: Boolean get() = System.currentTimeMillis() >= expiresAt
}

/**
 * Domain model representing a user-wise status group.
 * Invariant: One user (userNxId) = At most one UserStatusGroup entry in the UI.
 */
data class UserStatusGroup(
    val userNxId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val isCurrentUser: Boolean = false,
    val statuses: List<StatusItem>, // Sorted chronologically (oldest first for viewer sequence)
    val latestTimestamp: Long = statuses.maxOfOrNull { it.createdAt } ?: 0L,
    val hasUnviewedStatus: Boolean = statuses.any { !it.isViewed }
)
