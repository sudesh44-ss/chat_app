package com.nexa.app.data.repository

import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.dao.StatusUpdateDao
import com.nexa.app.data.local.dao.StatusViewDao
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.data.local.entity.StatusUpdateEntity
import com.nexa.app.data.local.entity.StatusViewEntity
import com.nexa.app.data.local.entity.isActiveAt
import com.nexa.app.data.local.entity.toDomain
import com.nexa.app.domain.model.StatusItem
import com.nexa.app.domain.model.UserStatusGroup
import com.nexa.app.domain.repository.StatusRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID

class StatusRepositoryImpl(
    private val statusUpdateDao: StatusUpdateDao,
    private val statusViewDao: StatusViewDao,
    private val userDao: UserDao,
    private val contactDao: ContactDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : StatusRepository {

    private val refreshVersion = MutableStateFlow(0L)
    private val activeStatusSnapshots get() = activeStatusesFlow()

    override fun getUserStatusGroupsFlow(): Flow<List<UserStatusGroup>> =
        getStatusGroupsFlow(muted = false)

    override fun getMutedUserStatusGroupsFlow(): Flow<List<UserStatusGroup>> =
        getStatusGroupsFlow(muted = true)

    private fun getStatusGroupsFlow(muted: Boolean): Flow<List<UserStatusGroup>> =
        combine(
            activeStatusSnapshots,
            userDao.getActiveUserFlow(),
            contactDao.getAllContacts()
        ) { activeEntities, activeUser, contacts ->
            val myNxId = activeUser?.nxId ?: ""
            val contactsById = contacts.associateBy { it.nxId }
            val visibleStatuses = activeEntities.filter { status ->
                status.userNxId != myNxId &&
                    isVisibleToCurrentUser(status, myNxId, contactsById) &&
                    (contactsById[status.userNxId]?.isStatusMuted == true) == muted
            }

            visibleStatuses.groupBy { it.userNxId }
                .map { (userNxId, entities) ->
                    val contact = contactsById[userNxId]
                    val statuses = entities.map { it.toDomain() }.sortedBy { it.createdAt }
                    UserStatusGroup(
                        userNxId = userNxId,
                        displayName = contact?.resolvedIdentity?.primaryDisplayName ?: userNxId,
                        avatarUrl = contact?.effectiveAvatarUrl,
                        statuses = statuses,
                        latestTimestamp = statuses.maxOfOrNull { it.createdAt } ?: 0L,
                        hasUnviewedStatus = statuses.any { !it.isViewed }
                    )
                }
                .sortedWith(
                    compareByDescending<UserStatusGroup> { it.hasUnviewedStatus }
                        .thenByDescending { it.latestTimestamp }
                        .thenBy { it.userNxId }
                )
        }.flowOn(ioDispatcher)

    override fun getMyStatusGroupFlow(): Flow<UserStatusGroup?> =
        combine(activeStatusSnapshots, userDao.getActiveUserFlow()) { statuses, activeUser ->
            val myNxId = activeUser?.nxId ?: return@combine null
            val myStatuses = statuses
                .filter { it.userNxId == myNxId }
                .map { it.toDomain() }
                .sortedBy { it.createdAt }
            myStatuses.takeIf { it.isNotEmpty() }?.let {
                UserStatusGroup(
                    userNxId = myNxId,
                    displayName = "My Status",
                    avatarUrl = activeUser.avatarUrl,
                    isCurrentUser = true,
                    statuses = it,
                    latestTimestamp = it.maxOfOrNull { status -> status.createdAt } ?: 0L,
                    hasUnviewedStatus = false
                )
            }
        }.flowOn(ioDispatcher)

    override fun getStatusesForUserFlow(userNxId: String): Flow<List<StatusItem>> =
        combine(
            activeStatusSnapshots,
            userDao.getActiveUserFlow(),
            contactDao.getAllContacts()
        ) { statuses, activeUser, contacts ->
            val myNxId = activeUser?.nxId ?: ""
            val contactsById = contacts.associateBy { it.nxId }
            statuses.asSequence()
                .filter { it.userNxId == userNxId && isVisibleToCurrentUser(it, myNxId, contactsById) }
                .map { it.toDomain() }
                .sortedBy { it.createdAt }
                .toList()
        }.flowOn(ioDispatcher)

    override suspend fun getStatusesForUser(userNxId: String): List<StatusItem> = withContext(ioDispatcher) {
        val statuses = statusUpdateDao.getAllStatuses()
        val activeUser = userDao.getActiveUserFlow().first()
        val contacts = contactDao.getAllContacts().first()
        val now = System.currentTimeMillis()
        val myNxId = activeUser?.nxId ?: ""
        val contactsById = contacts.associateBy { it.nxId }
        statuses.asSequence()
            .filter { it.userNxId == userNxId && it.isActiveAt(now) && isVisibleToCurrentUser(it, myNxId, contactsById) }
            .map { it.toDomain() }
            .sortedBy { it.createdAt }
            .toList()
    }

    override suspend fun refreshAvailability() {
        refreshVersion.update { it + 1L }
    }

    override suspend fun getStatusGroup(userNxId: String): UserStatusGroup? = withContext(ioDispatcher) {
        val statuses = getStatusesForUser(userNxId)
        if (statuses.isEmpty()) return@withContext null
        val contact = contactDao.getContact(userNxId)
        UserStatusGroup(
            userNxId = userNxId,
            displayName = contact?.resolvedIdentity?.primaryDisplayName ?: userNxId,
            avatarUrl = contact?.effectiveAvatarUrl,
            statuses = statuses,
            latestTimestamp = statuses.maxOfOrNull { it.createdAt } ?: 0L,
            hasUnviewedStatus = statuses.any { !it.isViewed }
        )
    }

    override suspend fun setStatusMuted(userNxId: String, isMuted: Boolean): Unit = withContext(ioDispatcher) {
        check(contactDao.updateStatusMuted(userNxId, isMuted) == 1) {
            "Contact is no longer available"
        }
    }

    override suspend fun createTextStatus(caption: String, privacy: String): StatusItem = withContext(ioDispatcher) {
        val myNxId = userDao.getActiveNxId() ?: "NX-DEV-USER"
        val now = System.currentTimeMillis()
        val entity = StatusUpdateEntity(
            statusId = UUID.randomUUID().toString(),
            userNxId = myNxId,
            mediaReference = null,
            caption = caption,
            createdAt = now,
            expiresAt = now + STATUS_LIFETIME_MS,
            privacy = privacy,
            statusType = "TEXT",
            isViewed = true,
            viewedAt = now,
            viewCount = 0
        )
        statusUpdateDao.insertStatus(entity)
        entity.toDomain()
    }

    override suspend fun createMediaStatus(
        mediaUri: String,
        caption: String,
        statusType: String,
        privacy: String
    ): StatusItem = withContext(ioDispatcher) {
        val myNxId = userDao.getActiveNxId() ?: "NX-DEV-USER"
        val now = System.currentTimeMillis()
        val entity = StatusUpdateEntity(
            statusId = UUID.randomUUID().toString(),
            userNxId = myNxId,
            mediaReference = mediaUri,
            caption = caption,
            createdAt = now,
            expiresAt = now + STATUS_LIFETIME_MS,
            privacy = privacy,
            statusType = statusType,
            isViewed = true,
            viewedAt = now,
            viewCount = 0
        )
        statusUpdateDao.insertStatus(entity)
        entity.toDomain()
    }

    override suspend fun markStatusViewed(statusId: String): Unit = withContext(ioDispatcher) {
        statusUpdateDao.markStatusViewed(statusId, System.currentTimeMillis())
    }

    override suspend fun recordPeerStatusView(statusId: String, viewerNxId: String): Unit = withContext(ioDispatcher) {
        val rowId = statusViewDao.recordView(
            StatusViewEntity(statusId = statusId, viewerNxId = viewerNxId, viewedAt = System.currentTimeMillis())
        )
        if (rowId > 0) statusUpdateDao.incrementViewCount(statusId)
    }

    override suspend fun deleteStatus(statusId: String): Unit = withContext(ioDispatcher) {
        statusUpdateDao.deleteStatus(statusId)
        statusViewDao.deleteViewsForStatus(statusId)
    }

    override suspend fun purgeExpiredStatuses(): Int = withContext(ioDispatcher) {
        statusUpdateDao.deleteExpiredStatuses(System.currentTimeMillis())
    }

    override suspend fun getStatusViewers(statusId: String): List<StatusViewEntity> = withContext(ioDispatcher) {
        statusViewDao.getViewersForStatus(statusId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun activeStatusesFlow(): Flow<List<StatusUpdateEntity>> =
        combine(statusUpdateDao.getAllStatusesFlow(), refreshVersion) { entities, _ -> entities }
            .flatMapLatest { entities ->
                flow {
                    while (true) {
                        val now = System.currentTimeMillis()
                        emit(entities.filter { it.isActiveAt(now) })
                        val nextTransition = entities.asSequence()
                            .filter { it.expiresAt > now }
                            .map { status ->
                                if (status.createdAt > now) status.createdAt else status.expiresAt
                            }
                            .minOrNull()
                        if (nextTransition == null) {
                            awaitCancellation()
                        } else {
                            delay((nextTransition - now).coerceAtLeast(1L))
                        }
                    }
                }
            }

    private fun isVisibleToCurrentUser(
        status: StatusUpdateEntity,
        myNxId: String,
        contactsById: Map<String, ContactEntity>
    ): Boolean {
        if (status.userNxId == myNxId) return true
        val contact = contactsById[status.userNxId] ?: return status.privacy.equals("PUBLIC", ignoreCase = true)
        if (contact.status == ContactStatus.BLOCKED) return false

        return when (status.privacy.uppercase()) {
            "PUBLIC" -> true
            "CONTACTS" -> contact.status == ContactStatus.MUTUAL_FRIEND ||
                contact.status == ContactStatus.CONTACT_VERIFIED
            "EXCLUDED" -> false
            else -> false
        }
    }

    companion object {
        private const val STATUS_LIFETIME_MS = 24 * 60 * 60 * 1000L
    }
}
