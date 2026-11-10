package com.nexa.app

import com.nexa.app.core.config.AppLaunchConfig
import com.nexa.app.core.contacts.ContactsProviderHelper
import com.nexa.app.core.demo.DemoDataSeeder
import com.nexa.app.data.local.dao.ChatDao
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.dao.MessageDao
import com.nexa.app.data.local.entity.ChatEntity
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.domain.model.DeviceContact
import com.nexa.app.domain.repository.ContactRepository
import com.nexa.app.presentation.contacts.ContactViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class ContactsAndDemoUnitTests {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        AppLaunchConfig.DEV_BYPASS_AUTH_TO_HOME_SCREEN = true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==========================================
    // 1. ContactsProviderHelper Tests
    // ==========================================

    @Test
    fun testNormalizePhoneNumber_validE164() {
        val result = ContactsProviderHelper.normalizePhoneNumber("+1 (415) 555-2671")
        assertEquals("+14155552671", result)
    }

    @Test
    fun testNormalizePhoneNumber_indianNumberWithPlus91() {
        val result = ContactsProviderHelper.normalizePhoneNumber("+91 98765 43210")
        assertEquals("+919876543210", result)
    }

    @Test
    fun testNormalizePhoneNumber_localTenDigitNumber() {
        val result = ContactsProviderHelper.normalizePhoneNumber("9876543210")
        assertEquals("+919876543210", result)
    }

    @Test
    fun testNormalizePhoneNumber_internationalLeadingZeroZero() {
        val result = ContactsProviderHelper.normalizePhoneNumber("0014155552671")
        assertEquals("+14155552671", result)
    }

    @Test
    fun testNormalizePhoneNumber_blankOrInvalidReturnsNull() {
        assertNull(ContactsProviderHelper.normalizePhoneNumber(""))
        assertNull(ContactsProviderHelper.normalizePhoneNumber("   "))
        assertNull(ContactsProviderHelper.normalizePhoneNumber("12345"))
        assertNull(ContactsProviderHelper.normalizePhoneNumber("abc-defg"))
    }

    // ==========================================
    // 2. ContactViewModel State & Error Tests
    // ==========================================

    @Test
    fun testContactViewModel_initialState() = runTest {
        val mockRepo = FakeContactRepository()
        val viewModel = ContactViewModel(mockRepo)

        val state = viewModel.uiState.value
        assertFalse(state.isPermissionGranted)
        assertFalse(state.isPermissionDeniedPermanently)
        assertFalse(state.permissionChecked)
        assertTrue(state.deviceContacts.isEmpty())
    }

    @Test
    fun testContactViewModel_setPermissionGrantedLoadsContacts() = runTest {
        val mockRepo = FakeContactRepository()
        mockRepo.fakeDeviceContacts = listOf(
            DeviceContact(
                id = "1",
                displayName = "Alice Smith",
                phoneNumber = "+14155551234",
                normalizedNumber = "+14155551234"
            )
        )
        val viewModel = ContactViewModel(mockRepo)

        viewModel.setPermissionState(granted = true, permanentlyDenied = false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isPermissionGranted)
        assertFalse(state.isPermissionDeniedPermanently)
        assertTrue(state.permissionChecked)
        assertEquals(1, state.deviceContacts.size)
        assertEquals("Alice Smith", state.deviceContacts[0].displayName)
    }

    @Test
    fun testContactViewModel_setPermissionDeniedPermanently() = runTest {
        val mockRepo = FakeContactRepository()
        val viewModel = ContactViewModel(mockRepo)

        viewModel.setPermissionState(granted = false, permanentlyDenied = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isPermissionGranted)
        assertTrue(state.isPermissionDeniedPermanently)
        assertTrue(state.permissionChecked)
    }

    @Test
    fun testContactViewModel_searchFilter() = runTest {
        val mockRepo = FakeContactRepository()
        mockRepo.fakeDeviceContacts = listOf(
            DeviceContact(id = "1", displayName = "Alice Smith", phoneNumber = "9876543210", normalizedNumber = "+919876543210"),
            DeviceContact(id = "2", displayName = "Bob Jones", phoneNumber = "1234567890", normalizedNumber = "+11234567890")
        )
        val viewModel = ContactViewModel(mockRepo)
        viewModel.setPermissionState(granted = true)
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("Alice")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.deviceContacts.size)
        assertEquals("Alice Smith", state.deviceContacts[0].displayName)

        viewModel.onSearchQueryChanged("1234")
        advanceUntilIdle()

        val state2 = viewModel.uiState.value
        assertEquals(1, state2.deviceContacts.size)
        assertEquals("Bob Jones", state2.deviceContacts[0].displayName)
    }

    @Test
    fun testContactViewModel_dbErrorCaughtCleanly() = runTest {
        val mockRepo = FakeContactRepository(throwOnFlow = true)
        val viewModel = ContactViewModel(mockRepo)
        advanceUntilIdle()

        // Should not throw or crash; uiState remains safely accessible
        val state = viewModel.uiState.value
        assertNotNull(state)
        assertTrue(state.mutualFriends.isEmpty())
    }

    // ==========================================
    // 3. Demo Data Seeder Tests
    // ==========================================

    @Test
    fun testDemoDataSeeder_seedsBothConversationsIdempotently() = runTest {
        val fakeChatDao = FakeChatDao()
        val fakeMessageDao = FakeMessageDao()
        val fakeContactDao = FakeContactDao()

        // Initial Seed
        DemoDataSeeder.seedDemoConversations(fakeChatDao, fakeMessageDao, fakeContactDao)

        val personalChat = fakeChatDao.getChatById(DemoDataSeeder.DEMO_PERSONAL_CONVERSATION_ID)
        assertNotNull(personalChat)
        assertEquals("Demo Contact", personalChat?.participantName)
        assertEquals("NX-DEMO-CONTACT", personalChat?.participantNxId)

        val groupChat = fakeChatDao.getChatById(DemoDataSeeder.DEMO_GROUP_CONVERSATION_ID)
        assertNotNull(groupChat)
        assertEquals("Demo Group", groupChat?.participantName)
        assertEquals("NX-DEMO-GROUP", groupChat?.participantNxId)

        val demoContact = fakeContactDao.getContact("NX-DEMO-CONTACT")
        assertNotNull(demoContact)
        assertEquals("Demo Contact", demoContact?.displayName)

        val groupMessages = fakeMessageDao.messages.filter { it.conversationId == DemoDataSeeder.DEMO_GROUP_CONVERSATION_ID }
        assertEquals(3, groupMessages.size)

        val personalMessages = fakeMessageDao.messages.filter { it.conversationId == DemoDataSeeder.DEMO_PERSONAL_CONVERSATION_ID }
        assertEquals(3, personalMessages.size)

        // Second Seed should be completely idempotent
        DemoDataSeeder.seedDemoConversations(fakeChatDao, fakeMessageDao, fakeContactDao)
        assertEquals(3, fakeMessageDao.messages.filter { it.conversationId == DemoDataSeeder.DEMO_GROUP_CONVERSATION_ID }.size)
        assertEquals(3, fakeMessageDao.messages.filter { it.conversationId == DemoDataSeeder.DEMO_PERSONAL_CONVERSATION_ID }.size)
    }

    // ==========================================
    // 4. Fake Implementations for Testing
    // ==========================================

    private class FakeContactRepository(private val throwOnFlow: Boolean = false) : ContactRepository {
        var fakeDeviceContacts: List<DeviceContact> = emptyList()

        override suspend fun loadDeviceContacts(): Result<List<DeviceContact>> {
            return Result.success(fakeDeviceContacts)
        }

        override suspend fun syncPendingRequests(): Int = 0
        override suspend fun lookupSinglePhone(phoneNumber: String): Result<DeviceContact?> = Result.success(null)
        override suspend fun sendFriendRequest(targetNxId: String, introMessage: String?): Result<Unit> = Result.success(Unit)
        override suspend fun acceptFriendRequest(contact: ContactEntity): Result<Unit> = Result.success(Unit)
        override suspend fun rejectOrBlockContact(nxId: String, block: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun setContactNickname(nxId: String, customNickname: String?): Result<Unit> = Result.success(Unit)
        override suspend fun getContact(nxId: String): ContactEntity? = null

        override fun getContactsByStatus(status: ContactStatus): Flow<List<ContactEntity>> {
            return if (throwOnFlow) {
                flow { throw RuntimeException("DB Disk read error") }
            } else {
                flowOf(emptyList())
            }
        }

        override fun getPendingCount(): Flow<Int> {
            return if (throwOnFlow) {
                flow { throw RuntimeException("DB Disk count error") }
            } else {
                flowOf(0)
            }
        }

        override fun getAllContacts(): Flow<List<ContactEntity>> = flowOf(emptyList())
        override fun searchContacts(query: String): Flow<List<ContactEntity>> = flowOf(emptyList())
        override suspend fun discoverUser(nxId: String): Result<com.nexa.app.data.remote.dto.DiscoveredUserDto> =
            Result.failure(Exception("Not implemented"))
        override suspend fun getIncomingConnectionRequests(): Result<List<com.nexa.app.data.remote.dto.ConnectionRequestItemDto>> =
            Result.success(emptyList())
        override suspend fun getOutgoingConnectionRequests(): Result<List<com.nexa.app.data.remote.dto.ConnectionRequestItemDto>> =
            Result.success(emptyList())
        override suspend fun acceptConnectionRequestById(requestId: String, contact: ContactEntity): Result<Unit> =
            Result.success(Unit)
        override suspend fun rejectConnectionRequestById(requestId: String, nxId: String): Result<Unit> =
            Result.success(Unit)
        override suspend fun cancelConnectionRequestById(requestId: String): Result<Unit> =
            Result.success(Unit)
        override suspend fun removeConnection(targetNxId: String): Result<Unit> =
            Result.success(Unit)
        override suspend fun blockUser(targetNxId: String): Result<Unit> =
            Result.success(Unit)
        override suspend fun unblockUser(targetNxId: String): Result<Unit> =
            Result.success(Unit)
        override suspend fun getBlockedUsers(): Result<List<com.nexa.app.data.remote.dto.BlockedUserItemDto>> =
            Result.success(emptyList())
        override suspend fun syncConnectionsFromServer(): Result<List<ContactEntity>> =
            Result.success(emptyList())
    }

    private class FakeChatDao : ChatDao {
        val chats = mutableMapOf<String, ChatEntity>()

        override fun getChatsFlow(): Flow<List<ChatEntity>> = flowOf(chats.values.toList())
        override suspend fun upsertChat(chat: ChatEntity): Long {
            chats[chat.conversationId] = chat
            return 1L
        }
        override suspend fun getChatByParticipant(nxId: String): ChatEntity? =
            chats.values.firstOrNull { it.participantNxId == nxId }
        override suspend fun getChatById(conversationId: String): ChatEntity? =
            chats[conversationId]
        override suspend fun resetUnreadCount(conversationId: String): Int = 0
        override suspend fun deleteChat(conversationId: String): Int = 0
        override suspend fun updateParticipantName(nxId: String, newName: String): Int = 0
        override suspend fun clearLastMessageText(conversationId: String): Int = 1
    }

    private class FakeMessageDao : MessageDao {
        val messages = mutableListOf<MessageEntity>()

        override fun getMessagesFlow(conversationId: String): Flow<List<MessageEntity>> =
            flowOf(messages.filter { it.conversationId == conversationId })
        override suspend fun insertMessage(message: MessageEntity): Long {
            messages.add(message)
            return 1L
        }
        override suspend fun insertMessageIfNotExists(message: MessageEntity): Long {
            if (messages.none { it.packetId == message.packetId }) {
                messages.add(message)
            }
            return 1L
        }
        override suspend fun updateMessageStatus(packetId: String, status: String): Int = 1
        override suspend fun getPendingOutgoingMessages(): List<MessageEntity> = emptyList()
        override suspend fun markMessagesAsRead(conversationId: String): Int = 1
        override suspend fun getMessageByPacketId(packetId: String): MessageEntity? =
            messages.firstOrNull { it.packetId == packetId }
        override suspend fun getLatestMessage(conversationId: String): MessageEntity? =
            messages.filter { it.conversationId == conversationId }.lastOrNull()
        override fun getPinnedMessagesFlow(conversationId: String, currentTimeMillis: Long): Flow<List<MessageEntity>> =
            flowOf(emptyList())
        override suspend fun getPinnedMessages(conversationId: String, currentTimeMillis: Long): List<MessageEntity> = emptyList()
        override suspend fun getPinnedMessagesCount(conversationId: String, currentTimeMillis: Long): Int = 0
        override suspend fun updatePinStatus(packetId: String, isPinned: Boolean, pinnedExpiresAt: Long?, pinnedAt: Long?): Int = 1
        override suspend fun unpinMessage(packetId: String): Int = 1
        override suspend fun clearExpiredPins(conversationId: String, currentTimeMillis: Long): Int = 0
        override suspend fun deleteMessagesForConversation(conversationId: String): Int = 1
        override suspend fun getMessageIdsAfterTimestamp(conversationId: String, afterTimestamp: Long, limit: Int): List<String> =
            messages.filter { it.conversationId == conversationId && it.timestamp > afterTimestamp }.take(limit).map { it.packetId }
        override suspend fun getMessagesAfterTimestamp(conversationId: String, afterTimestamp: Long, limit: Int): List<MessageEntity> =
            messages.filter { it.conversationId == conversationId && it.timestamp > afterTimestamp }.take(limit)
    }

    private class FakeContactDao : ContactDao {
        val contacts = mutableMapOf<String, ContactEntity>()

        override suspend fun upsertContact(contact: ContactEntity): Long {
            contacts[contact.nxId] = contact
            return 1L
        }
        override fun getContactsByStatus(status: ContactStatus): Flow<List<ContactEntity>> =
            flowOf(contacts.values.filter { it.status == status })
        override suspend fun getContact(nxId: String): ContactEntity? = contacts[nxId]
        override suspend fun updateStatus(nxId: String, newStatus: ContactStatus): Int = 1
        override suspend fun deleteContact(nxId: String): Int = 1
        override fun getPendingReceivedCount(): Flow<Int> = flowOf(0)
        override fun getAllContacts(): Flow<List<ContactEntity>> = flowOf(contacts.values.toList())
        override suspend fun updateNickname(nxId: String, nickname: String?): Int = 1
        override suspend fun updateDeviceContactName(nxId: String, deviceContactName: String?): Int = 1
        override fun searchContacts(query: String): Flow<List<ContactEntity>> = flowOf(emptyList())
        override suspend fun updatePeerPhoneSharing(nxId: String, isShared: Boolean, phone: String?): Int = 1
        override suspend fun updateAvatarUrl(nxId: String, avatarUrl: String?): Int = 1
        override suspend fun updateDeviceContactPhotoUri(nxId: String, photoUri: String?): Int = 1
    }
}
