package com.nexa.app.domain.repository

import com.nexa.app.data.local.entity.StatusViewEntity
import com.nexa.app.domain.model.StatusItem
import com.nexa.app.domain.model.UserStatusGroup
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface governing 24-hour status stories, user-wise grouping,
 * viewing receipts, and expiration lifecycles.
 */
interface StatusRepository {

    /**
     * Emits a reactive list of other users' status groups.
     * Invariant: Exactly ONE entry per peer user with active visible statuses.
     */
    fun getUserStatusGroupsFlow(): Flow<List<UserStatusGroup>>

    fun getMutedUserStatusGroupsFlow(): Flow<List<UserStatusGroup>> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Emits the current user's dedicated My Status group, or null if the user has 0 active statuses.
     */
    fun getMyStatusGroupFlow(): Flow<UserStatusGroup?>

    /**
     * Retrieves all active statuses for a specific user, sorted chronologically (oldest first).
     */
    suspend fun getStatusesForUser(userNxId: String): List<StatusItem>

    suspend fun refreshAvailability() {}

    fun getStatusesForUserFlow(userNxId: String): Flow<List<StatusItem>> =
        kotlinx.coroutines.flow.flow { emit(getStatusesForUser(userNxId)) }

    suspend fun getStatusGroup(userNxId: String): UserStatusGroup? =
        getStatusesForUser(userNxId).takeIf { it.isNotEmpty() }?.let {
            UserStatusGroup(userNxId = userNxId, displayName = userNxId, statuses = it)
        }

    suspend fun setStatusMuted(userNxId: String, isMuted: Boolean) {}

    /**
     * Creates a text-only status update.
     */
    suspend fun createTextStatus(caption: String, privacy: String = "CONTACTS"): StatusItem

    /**
     * Creates a media status update (image/video).
     */
    suspend fun createMediaStatus(
        mediaUri: String,
        caption: String = "",
        statusType: String = "IMAGE",
        privacy: String = "CONTACTS"
    ): StatusItem

    /**
     * Marks an individual status as viewed by the local user.
     */
    suspend fun markStatusViewed(statusId: String)

    /**
     * Records a view from a peer on the current user's status.
     */
    suspend fun recordPeerStatusView(statusId: String, viewerNxId: String)

    /**
     * Deletes an individual status record and associated view receipts.
     */
    suspend fun deleteStatus(statusId: String)

    /**
     * Purges all expired statuses (where expiresAt <= currentTime).
     */
    suspend fun purgeExpiredStatuses(): Int

    /**
     * Retrieves the list of viewers for a specific status owned by current user.
     */
    suspend fun getStatusViewers(statusId: String): List<StatusViewEntity>
}
