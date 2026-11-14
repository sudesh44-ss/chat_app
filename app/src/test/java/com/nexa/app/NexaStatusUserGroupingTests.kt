package com.nexa.app

import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.dao.StatusUpdateDao
import com.nexa.app.data.local.dao.StatusViewDao
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.data.local.entity.StatusUpdateEntity
import com.nexa.app.data.local.entity.StatusViewEntity
import com.nexa.app.data.local.entity.UserEntity
import com.nexa.app.data.repository.StatusRepositoryImpl
import com.nexa.app.domain.model.StatusItem
import com.nexa.app.domain.model.UserStatusGroup
import com.nexa.app.presentation.status.StatusViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NexaStatusUserGroupingTests {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeStatusUpdateDao: FakeStatusUpdateDao
    private lateinit var fakeStatusViewDao: FakeStatusViewDao
    private lateinit var fakeUserDao: FakeUserDao
    private lateinit var fakeContactDao: FakeContactDao
    private lateinit var repository: StatusRepositoryImpl
    private lateinit var viewModel: StatusViewModel

    private val currentMyNxId = "NX-MY-ID-1234"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeStatusUpdateDao = FakeStatusUpdateDao()
        fakeStatusViewDao = FakeStatusViewDao()
        fakeUserDao = FakeUserDao(
            UserEntity(
                nxId = currentMyNxId,
                fullName = "Current User",
                phoneNumber = "+1234567890",
                avatarUrl = "https://nexa.local/my_avatar.png",
                age = 28,
                gender = "O",
                bio = "NEXA Privacy First",
                publicKeyHex = "aa".repeat(32),
                isVerified = true
            )
        )
        fakeContactDao = FakeContactDao()

        // Seed peer contacts
        fakeContactDao.contacts.value = listOf(
            ContactEntity(
                nxId = "NX-USER-A",
                displayName = "Alice Wonderland",
                publicKeyHex = "bb".repeat(32),
                customNickname = "Alice",
                status = ContactStatus.MUTUAL_FRIEND
            ),
            ContactEntity(
                nxId = "NX-USER-B",
                displayName = "Bob Builder",
                publicKeyHex = "cc".repeat(32),
                customNickname = "Bob",
                status = ContactStatus.MUTUAL_FRIEND
            ),
            ContactEntity(
                nxId = "NX-USER-BLOCKED",
                displayName = "Spammer Spammer",
                publicKeyHex = "dd".repeat(32),
                status = ContactStatus.BLOCKED
            )
        )

        repository = StatusRepositoryImpl(
            statusUpdateDao = fakeStatusUpdateDao,
            statusViewDao = fakeStatusViewDao,
            userDao = fakeUserDao,
            contactDao = fakeContactDao,
            ioDispatcher = testDispatcher
        )

        viewModel = StatusViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==========================================
    // 1. User-Wise Grouping Tests (Core Invariant)
    // ==========================================

    @Test
    fun userUploadsOneStatus_producesExactlyOneGroup() = testScope.runTest {
        val now = System.currentTimeMillis()
        fakeStatusUpdateDao.insertStatus(
            StatusUpdateEntity(
                statusId = "s_a_1",
                userNxId = "NX-USER-A",
                caption = "Status 1 from Alice",
                createdAt = now - 1000L,
                expiresAt = now + 86400000L
            )
        )

        val groups = repository.getUserStatusGroupsFlow().first()
        assertEquals("Should produce exactly 1 group for User A", 1, groups.size)
        assertEquals("NX-USER-A", groups[0].userNxId)
        assertEquals("Alice", groups[0].displayName)
        assertEquals(1, groups[0].statuses.size)
        assertEquals("Status 1 from Alice", groups[0].statuses[0].caption)
    }

    @Test
    fun userUploadsThreeStatuses_producesExactlyOneGroupWithThreeStatuses() = testScope.runTest {
        val now = System.currentTimeMillis()
        fakeStatusUpdateDao.insertStatus(
            StatusUpdateEntity("s_a_1", "NX-USER-A", caption = "First", createdAt = now - 3000L, expiresAt = now + 86400000L)
        )
        fakeStatusUpdateDao.insertStatus(
            StatusUpdateEntity("s_a_2", "NX-USER-A", caption = "Second", createdAt = now - 2000L, expiresAt = now + 86400000L)
        )
        fakeStatusUpdateDao.insertStatus(
            StatusUpdateEntity("s_a_3", "NX-USER-A", caption = "Third", createdAt = now - 1000L, expiresAt = now + 86400000L)
        )

        val groups = repository.getUserStatusGroupsFlow().first()
        assertEquals("Must display exactly ONE profile entry for User A regardless of status count", 1, groups.size)
        val aliceGroup = groups[0]
        assertEquals("NX-USER-A", aliceGroup.userNxId)
        assertEquals(3, aliceGroup.statuses.size)

        // Chronological sequence: Oldest first for sequential story viewer
        assertEquals("First", aliceGroup.statuses[0].caption)
        assertEquals("Second", aliceGroup.statuses[1].caption)
        assertEquals("Third", aliceGroup.statuses[2].caption)
    }

    @Test
    fun userUploadsTwentyStatuses_producesExactlyOneGroup() = testScope.runTest {
        val now = System.currentTimeMillis()
        for (i in 1..20) {
            fakeStatusUpdateDao.insertStatus(
                StatusUpdateEntity(
                    statusId = "s_a_$i",
                    userNxId = "NX-USER-A",
                    caption = "Alice Status #$i",
                    createdAt = now - (20 - i) * 1000L,
                    expiresAt = now + 86400000L
                )
            )
        }

        val groups = repository.getUserStatusGroupsFlow().first()
        assertEquals("Even with 20 statuses, User A must produce exactly ONE group", 1, groups.size)
        assertEquals(20, groups[0].statuses.size)
    }

    @Test
    fun multipleUsersWithMultipleStatuses_producesOneGroupPerUser() = testScope.runTest {
        val now = System.currentTimeMillis()
        // Alice: 3 statuses
        fakeStatusUpdateDao.insertStatus(StatusUpdateEntity("a1", "NX-USER-A", caption = "A1", createdAt = now - 3000L, expiresAt = now + 86400000L))
        fakeStatusUpdateDao.insertStatus(StatusUpdateEntity("a2", "NX-USER-A", caption = "A2", createdAt = now - 2000L, expiresAt = now + 86400000L))
        fakeStatusUpdateDao.insertStatus(StatusUpdateEntity("a3", "NX-USER-A", caption = "A3", createdAt = now - 1000L, expiresAt = now + 86400000L))

        // Bob: 2 statuses
        fakeStatusUpdateDao.insertStatus(StatusUpdateEntity("b1", "NX-USER-B", caption = "B1", createdAt = now - 1500L, expiresAt = now + 86400000L))
        fakeStatusUpdateDao.insertStatus(StatusUpdateEntity("b2", "NX-USER-B", caption = "B2", createdAt = now - 500L, expiresAt = now + 86400000L))

        val groups = repository.getUserStatusGroupsFlow().first()
        assertEquals("Expected exactly 2 groups (1 for Alice, 1 for Bob)", 2, groups.size)

        val userIds = groups.map { it.userNxId }.toSet()
        assertTrue(userIds.contains("NX-USER-A"))
        assertTrue(userIds.contains("NX-USER-B"))

        val alice = groups.first { it.userNxId == "NX-USER-A" }
        val bob = groups.first { it.userNxId == "NX-USER-B" }
        assertEquals(3, alice.statuses.size)
        assertEquals(2, bob.statuses.size)
    }

    // ==========================================
    // 2. My Status (Current User) Tests
    // ==========================================

    @Test
    fun myStatus_noActiveStatus_returnsNull() = testScope.runTest {
        val myGroup = repository.getMyStatusGroupFlow().first()
        assertNull("When current user has no active statuses, My Status group must be null", myGroup)
    }

    @Test
    fun myStatus_multipleUploads_producesExactlyOneGroup() = testScope.runTest {
        val now = System.currentTimeMillis()
        repository.createTextStatus("My First Status")
        repository.createTextStatus("My Second Status")

        val myGroup = repository.getMyStatusGroupFlow().first()
        assertNotNull("My Status should be non-null after posting", myGroup)
        assertEquals(currentMyNxId, myGroup!!.userNxId)
        assertTrue(myGroup.isCurrentUser)
        assertEquals(2, myGroup.statuses.size)
        assertEquals("My First Status", myGroup.statuses[0].caption)
        assertEquals("My Second Status", myGroup.statuses[1].caption)

        // Ensure My Status does NOT leak into peer groups
        val peerGroups = repository.getUserStatusGroupsFlow().first()
        assertFalse("Peer status list must never include current user's statuses", peerGroups.any { it.userNxId == currentMyNxId })
    }

    // ==========================================
    // 3. Expiration & Deletion Tests
    // ==========================================

    @Test
    fun oneStatusExpires_remainingActiveStatusesPersistInGroup() = testScope.runTest {
        val now = System.currentTimeMillis()
        // Status 1: EXPIRED (expiresAt in the past)
        fakeStatusUpdateDao.insertStatus(
            StatusUpdateEntity("exp_1", "NX-USER-A", caption = "Old", createdAt = now - 90000000L, expiresAt = now - 1000L)
        )
        // Status 2: ACTIVE
        fakeStatusUpdateDao.insertStatus(
            StatusUpdateEntity("act_1", "NX-USER-A", caption = "Active", createdAt = now - 1000L, expiresAt = now + 86400000L)
        )

        val groups = repository.getUserStatusGroupsFlow().first()
        assertEquals(1, groups.size)
        val alice = groups[0]
        assertEquals(1, alice.statuses.size)
        assertEquals("Active", alice.statuses[0].caption)
    }

    @Test
    fun allStatusesExpire_userGroupDisappearsFromFeed() = testScope.runTest {
        val now = System.currentTimeMillis()
        // Both statuses expired
        fakeStatusUpdateDao.insertStatus(
            StatusUpdateEntity("exp_1", "NX-USER-A", caption = "Old 1", createdAt = now - 90000000L, expiresAt = now - 2000L)
        )
        fakeStatusUpdateDao.insertStatus(
            StatusUpdateEntity("exp_2", "NX-USER-A", caption = "Old 2", createdAt = now - 90000000L, expiresAt = now - 1000L)
        )

        val groups = repository.getUserStatusGroupsFlow().first()
        assertEquals("User with 0 active statuses must completely disappear from feed", 0, groups.size)
    }

    @Test
    fun deleteSingleStatusFromGroup_removesOnlyTargetStatus() = testScope.runTest {
        val now = System.currentTimeMillis()
        fakeStatusUpdateDao.insertStatus(StatusUpdateEntity("s1", "NX-USER-A", caption = "Keep", createdAt = now - 2000L, expiresAt = now + 86400000L))
        fakeStatusUpdateDao.insertStatus(StatusUpdateEntity("s2", "NX-USER-A", caption = "DeleteMe", createdAt = now - 1000L, expiresAt = now + 86400000L))

        repository.deleteStatus("s2")

        val groups = repository.getUserStatusGroupsFlow().first()
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].statuses.size)
        assertEquals("Keep", groups[0].statuses[0].caption)
    }

    // ==========================================
    // 4. Privacy & Blocked Contacts Tests
    // ==========================================

    @Test
    fun blockedUserStatus_strictlySuppressedFromFeed() = testScope.runTest {
        val now = System.currentTimeMillis()
        fakeStatusUpdateDao.insertStatus(
            StatusUpdateEntity("block_1", "NX-USER-BLOCKED", caption = "Spam Story", createdAt = now - 500L, expiresAt = now + 86400000L)
        )

        val groups = repository.getUserStatusGroupsFlow().first()
        assertFalse("Statuses from blocked contacts must never appear in the feed", groups.any { it.userNxId == "NX-USER-BLOCKED" })
    }

    // ==========================================
    // 5. Viewed vs Unviewed Tracking Tests
    // ==========================================

    @Test
    fun viewingOneStatus_marksOnlyThatStatusViewed_groupRemainsUnviewedIfMoreExist() = testScope.runTest {
        val now = System.currentTimeMillis()
        fakeStatusUpdateDao.insertStatus(
            StatusUpdateEntity("s1", "NX-USER-A", caption = "First", createdAt = now - 2000L, expiresAt = now + 86400000L, isViewed = false)
        )
        fakeStatusUpdateDao.insertStatus(
            StatusUpdateEntity("s2", "NX-USER-A", caption = "Second", createdAt = now - 1000L, expiresAt = now + 86400000L, isViewed = false)
        )

        // View first status
        repository.markStatusViewed("s1")

        val groups = repository.getUserStatusGroupsFlow().first()
        val alice = groups[0]
        val status1 = alice.statuses.first { it.statusId == "s1" }
        val status2 = alice.statuses.first { it.statusId == "s2" }

        assertTrue("Status 1 must be marked viewed", status1.isViewed)
        assertFalse("Status 2 must remain unviewed until actually watched", status2.isViewed)
        assertTrue("Group must still indicate unviewed statuses", alice.hasUnviewedStatus)

        // View second status
        repository.markStatusViewed("s2")
        val updatedGroups = repository.getUserStatusGroupsFlow().first()
        assertFalse("When all statuses in group are viewed, hasUnviewedStatus must be false", updatedGroups[0].hasUnviewedStatus)
    }

    // ==========================================
    // 6. ViewModel Sequential Viewer Tests
    // ==========================================

    @Test
    fun viewModel_sequentialViewerNavigation() = testScope.runTest {
        val statuses = listOf(
            StatusItem("1", "NX-USER-A", caption = "First", createdAt = 1000L, expiresAt = 90000L),
            StatusItem("2", "NX-USER-A", caption = "Second", createdAt = 2000L, expiresAt = 90000L)
        )
        val group = UserStatusGroup("NX-USER-A", "Alice", statuses = statuses)

        viewModel.openStatusGroup(group, 0)
        assertEquals(group, viewModel.selectedStatusGroup.value)
        assertEquals(0, viewModel.currentStatusIndex.value)

        viewModel.nextStatus()
        assertEquals(1, viewModel.currentStatusIndex.value)

        // Advancing past last status closes viewer
        viewModel.nextStatus()
        assertNull(viewModel.selectedStatusGroup.value)
    }

    // ==========================================
    // Test Fakes
    // ==========================================

    class FakeStatusUpdateDao : StatusUpdateDao {
        val statusList = MutableStateFlow<List<StatusUpdateEntity>>(emptyList())

        override fun getActiveStatusesFlow(currentTime: Long): Flow<List<StatusUpdateEntity>> {
            return statusList.map { list -> list.filter { it.expiresAt > currentTime } }
        }

        override suspend fun getActiveStatuses(currentTime: Long): List<StatusUpdateEntity> {
            return statusList.value.filter { it.expiresAt > currentTime }
        }

        override fun getAllStatusesFlow(): Flow<List<StatusUpdateEntity>> = statusList

        override suspend fun getAllStatuses(): List<StatusUpdateEntity> = statusList.value

        override suspend fun getUserActiveStatuses(userNxId: String, currentTime: Long): List<StatusUpdateEntity> {
            return statusList.value.filter { it.userNxId == userNxId && it.expiresAt > currentTime }
        }

        override fun getUserActiveStatusesFlow(userNxId: String, currentTime: Long): Flow<List<StatusUpdateEntity>> {
            return statusList.map { list -> list.filter { it.userNxId == userNxId && it.expiresAt > currentTime } }
        }

        override suspend fun getStatus(statusId: String): StatusUpdateEntity? {
            return statusList.value.find { it.statusId == statusId }
        }

        override suspend fun insertStatus(status: StatusUpdateEntity): Long {
            val current = statusList.value.toMutableList()
            current.removeAll { it.statusId == status.statusId }
            current.add(status)
            statusList.value = current
            return 1L
        }

        override suspend fun markStatusViewed(statusId: String, viewedAt: Long): Int {
            val current = statusList.value.toMutableList()
            val index = current.indexOfFirst { it.statusId == statusId }
            if (index >= 0) {
                current[index] = current[index].copy(isViewed = true, viewedAt = viewedAt)
                statusList.value = current
                return 1
            }
            return 0
        }

        override suspend fun incrementViewCount(statusId: String): Int {
            val current = statusList.value.toMutableList()
            val index = current.indexOfFirst { it.statusId == statusId }
            if (index >= 0) {
                current[index] = current[index].copy(viewCount = current[index].viewCount + 1)
                statusList.value = current
                return 1
            }
            return 0
        }

        override suspend fun deleteExpiredStatuses(currentTime: Long): Int {
            val initial = statusList.value.size
            val filtered = statusList.value.filter { it.expiresAt > currentTime }
            statusList.value = filtered
            return initial - filtered.size
        }

        override suspend fun deleteStatus(statusId: String): Int {
            val initial = statusList.value.size
            val filtered = statusList.value.filter { it.statusId != statusId }
            statusList.value = filtered
            return initial - filtered.size
        }

        override suspend fun deleteUserStatuses(userNxId: String): Int {
            val initial = statusList.value.size
            val filtered = statusList.value.filter { it.userNxId != userNxId }
            statusList.value = filtered
            return initial - filtered.size
        }
    }

    class FakeStatusViewDao : StatusViewDao {
        val views = mutableListOf<StatusViewEntity>()

        override suspend fun recordView(view: StatusViewEntity): Long {
            if (views.any { it.statusId == view.statusId && it.viewerNxId == view.viewerNxId }) {
                return 0L
            }
            views.add(view)
            return views.size.toLong()
        }

        override suspend fun getViewersForStatus(statusId: String): List<StatusViewEntity> {
            return views.filter { it.statusId == statusId }
        }

        override fun getViewersForStatusFlow(statusId: String): Flow<List<StatusViewEntity>> {
            return MutableStateFlow(views.filter { it.statusId == statusId })
        }

        override suspend fun getViewCountForStatus(statusId: String): Int {
            return views.count { it.statusId == statusId }
        }

        override suspend fun hasUserViewed(statusId: String, viewerNxId: String): Boolean {
            return views.any { it.statusId == statusId && it.viewerNxId == viewerNxId }
        }

        override suspend fun deleteViewsForStatus(statusId: String): Int {
            val initial = views.size
            views.removeAll { it.statusId == statusId }
            return initial - views.size
        }
    }

    class FakeUserDao(private val localUser: UserEntity?) : UserDao {
        override suspend fun saveUserProfile(user: UserEntity): Long = 1L
        override suspend fun getLocalProfile(): UserEntity? = localUser
        override fun getActiveUserFlow(): Flow<UserEntity?> = MutableStateFlow(localUser)
        override suspend fun getActiveNxId(): String? = localUser?.nxId
        override suspend fun countVerifiedUsers(): Int = if (localUser?.isVerified == true) 1 else 0
        override suspend fun updateFullName(nxId: String, fullName: String): Int = 1
        override suspend fun updatePhoneNumber(nxId: String, phoneNumber: String): Int = 1
        override suspend fun clearProfile(): Int = 1
    }

    class FakeContactDao : ContactDao {
        val contacts = MutableStateFlow<List<ContactEntity>>(emptyList())

        override suspend fun upsertContact(contact: ContactEntity): Long = 1L
        override fun getContactsByStatus(status: ContactStatus): Flow<List<ContactEntity>> =
            contacts.map { list -> list.filter { it.status == status } }
        override suspend fun getContact(nxId: String): ContactEntity? =
            contacts.value.find { it.nxId == nxId }
        override suspend fun updateStatus(nxId: String, newStatus: ContactStatus): Int = 1
        override suspend fun updateStatusMuted(nxId: String, isMuted: Boolean): Int {
            val current = contacts.value.toMutableList()
            val index = current.indexOfFirst { it.nxId == nxId }
            if (index < 0) return 0
            current[index] = current[index].copy(isStatusMuted = isMuted)
            contacts.value = current
            return 1
        }
        override suspend fun deleteContact(nxId: String): Int = 1
        override fun getPendingReceivedCount(): Flow<Int> = MutableStateFlow(0)
        override fun getAllContacts(): Flow<List<ContactEntity>> = contacts
        override suspend fun updateNickname(nxId: String, nickname: String?): Int = 1
        override suspend fun updateDeviceContactName(nxId: String, deviceContactName: String?): Int = 1
        override fun searchContacts(query: String): Flow<List<ContactEntity>> = contacts
        override suspend fun updatePeerPhoneSharing(nxId: String, isShared: Boolean, phone: String?): Int = 1
        override suspend fun updateAvatarUrl(nxId: String, avatarUrl: String?): Int = 1
        override suspend fun updateDeviceContactPhotoUri(nxId: String, photoUri: String?): Int = 1
    }
}
