package com.nexa.app

import com.nexa.app.core.network.ConnectionState
import com.nexa.app.data.local.dao.ChatDao
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.entity.ChatEntity
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.data.remote.dto.BlockedUserItemDto
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.MessageType
import com.nexa.app.domain.model.TransportType
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.domain.repository.ContactRepository
import com.nexa.app.presentation.chat.ChatUiEvent
import com.nexa.app.presentation.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class BlockedContactsAndEnforcementUnitTests {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==========================================
    // 1. ContactDao & Repository Block/Unblock Persistence Tests
    // ==========================================

    @Test
    fun testContactDao_blockUser_persistsLocallyAndReflectsInBlockedList() = runTest {
        val fakeContactDao = TestFakeContactDao()
        val targetNxId = "NX-BOB-999"

        assertFalse(fakeContactDao.isUserBlocked(targetNxId))

        // Upsert contact as BLOCKED
        fakeContactDao.upsertContact(
            ContactEntity(
                nxId = targetNxId,
                displayName = "Bob",
                publicKeyHex = "01020304",
                status = ContactStatus.BLOCKED
            )
        )

        // Contact should now exist in local DB with BLOCKED status
        assertTrue(fakeContactDao.isUserBlocked(targetNxId))
        assertTrue(fakeContactDao.isUserBlockedSync(targetNxId))
        val contact = fakeContactDao.getContact(targetNxId)
        assertNotNull(contact)
        assertEquals(ContactStatus.BLOCKED, contact?.status)
        assertEquals(targetNxId, contact?.nxId)

        // Verify blocked list reflects user
        val blockedList = fakeContactDao.getBlockedContactsList()
        assertEquals(1, blockedList.size)
        assertEquals(targetNxId, blockedList.first().nxId)
    }

    @Test
    fun testContactDao_unblockUser_restoresMutualFriendStatusAndNeverDeletesContact() = runTest {
        val fakeContactDao = TestFakeContactDao()
        val blockedContact = ContactEntity(
            nxId = "NX-CHARLIE-456",
            displayName = "Charlie",
            publicKeyHex = "05060708",
            status = ContactStatus.BLOCKED
        )
        fakeContactDao.upsertContact(blockedContact)

        assertTrue(fakeContactDao.isUserBlocked("NX-CHARLIE-456"))

        // Unblock Charlie: restore MUTUAL_FRIEND
        fakeContactDao.updateStatus("NX-CHARLIE-456", ContactStatus.MUTUAL_FRIEND)

        // Status is restored to MUTUAL_FRIEND, row still exists!
        val restored = fakeContactDao.getContact("NX-CHARLIE-456")
        assertNotNull(restored)
        assertEquals(ContactStatus.MUTUAL_FRIEND, restored?.status)
        assertFalse(fakeContactDao.isUserBlocked("NX-CHARLIE-456"))

        // Blocked list should now be empty
        val blockedList = fakeContactDao.getBlockedContactsList()
        assertTrue(blockedList.isEmpty())
    }

    @Test
    fun testContactRepository_getBlockedUsers_offlineResilienceReturnsLocalDb() = runTest {
        val fakeContactRepo = TestFakeContactRepository()
        fakeContactRepo.blockUser("NX-USER-1")
        fakeContactRepo.blockUser("NX-USER-2")

        // Offline call should successfully return local blocked users without crashing
        val result = fakeContactRepo.getBlockedUsers()
        assertTrue(result.isSuccess)
        val list = result.getOrNull()
        assertNotNull(list)
        assertEquals(2, list?.size)
        assertTrue(list!!.any { it.nxId == "NX-USER-1" })
        assertTrue(list.any { it.nxId == "NX-USER-2" })
    }

    // ==========================================
    // 2. Chat Deletion & History Retention Independence Tests
    // ==========================================

    @Test
    fun testBlockWithoutChatDeletion_preservesConversationAndMessages() = runTest {
        val fakeChatRepo = TestFakeChatRepository()
        val fakeContactRepo = TestFakeContactRepository()

        val convId = "conv-101"
        val peerNxId = "NX-BOB-101"

        fakeChatRepo.createOrGetConversation(peerNxId, "Bob")
        fakeChatRepo.addMessage(convId, "Hello Bob", isOutgoing = true)
        fakeChatRepo.addMessage(convId, "Hey there", isOutgoing = false)

        assertEquals(2, fakeChatRepo.getMessagesDirect(convId).size)

        // Block WITHOUT chat deletion
        fakeContactRepo.blockUser(peerNxId)
        assertTrue(fakeContactRepo.isUserBlocked(peerNxId))

        // Conversation history remains completely intact
        assertEquals(2, fakeChatRepo.getMessagesDirect(convId).size)
    }

    @Test
    fun testBlockWithChatDeletion_clearsConversationWhilePreservingBlockRecord() = runTest {
        val fakeChatRepo = TestFakeChatRepository()
        val fakeContactRepo = TestFakeContactRepository()

        val convId = "conv-202"
        val peerNxId = "NX-EVE-202"

        fakeChatRepo.createOrGetConversation(peerNxId, "Eve")
        fakeChatRepo.addMessage(convId, "Suspicious message", isOutgoing = false)

        assertEquals(1, fakeChatRepo.getMessagesDirect(convId).size)

        // Block WITH chat deletion
        fakeContactRepo.blockUser(peerNxId)
        fakeChatRepo.deleteChat(convId)

        // 1. Block record is intact
        assertTrue(fakeContactRepo.isUserBlocked(peerNxId))

        // 2. Conversation messages are cleared
        assertEquals(0, fakeChatRepo.getMessagesDirect(convId).size)

        // 3. Unrelated conversation remains intact
        fakeChatRepo.createOrGetConversation("NX-ALICE-303", "Alice")
        fakeChatRepo.addMessage("conv-NX-ALICE-303", "Alice message", isOutgoing = false)
        assertEquals(1, fakeChatRepo.getMessagesDirect("conv-NX-ALICE-303").size)
    }

    // ==========================================
    // 3. ChatViewModel & UI State Blocking Tests
    // ==========================================

    @Test
    fun testChatViewModel_dispatchesAndObservesBlockStatus() = runTest {
        val fakeChatRepo = TestFakeChatRepository()
        val fakeContactRepo = TestFakeContactRepository()

        val viewModel = ChatViewModel(
            chatRepository = fakeChatRepo,
            contactRepository = fakeContactRepo
        )

        val peerNxId = "NX-TEST-USER"
        val convId = "conv-test"

        viewModel.onEvent(
            ChatUiEvent.Init(
                conversationId = convId,
                participantNxId = peerNxId,
                participantName = "Test User"
            )
        )
        advanceUntilIdle()

        // Initially not blocked
        assertFalse(viewModel.uiState.value.isContactBlocked)

        // Block contact without deleting chat
        viewModel.onEvent(ChatUiEvent.BlockContact(alsoReport = false, deleteChat = false))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isContactBlocked)
        assertTrue(fakeContactRepo.isUserBlocked(peerNxId))

        // Attempting to send a message while blocked should be rejected
        viewModel.onEvent(ChatUiEvent.OnInputTextChanged("Hello blocked user"))
        viewModel.onEvent(ChatUiEvent.SendMessage)
        advanceUntilIdle()

        // Error message set, message was NOT dispatched
        assertEquals("Cannot send message to a blocked contact.", viewModel.uiState.value.errorMessage)
        assertEquals(0, fakeChatRepo.getMessagesDirect(convId).size)

        // Unblock contact
        viewModel.onEvent(ChatUiEvent.UnblockContact)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isContactBlocked)
        assertFalse(fakeContactRepo.isUserBlocked(peerNxId))
    }

    @Test
    fun testChatViewModel_blockWithDeleteChat_clearsMessagesState() = runTest {
        val fakeChatRepo = TestFakeChatRepository()
        val fakeContactRepo = TestFakeContactRepository()

        val convId = "conv-clear-test"
        val peerNxId = "NX-CLEAR-USER"

        fakeChatRepo.createOrGetConversation(peerNxId, "Clear User")
        fakeChatRepo.addMessage(convId, "Msg 1", isOutgoing = true)
        fakeChatRepo.addMessage(convId, "Msg 2", isOutgoing = false)

        val viewModel = ChatViewModel(
            chatRepository = fakeChatRepo,
            contactRepository = fakeContactRepo
        )

        viewModel.onEvent(
            ChatUiEvent.Init(
                conversationId = convId,
                participantNxId = peerNxId,
                participantName = "Clear User"
            )
        )
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.messages.size)

        // Block WITH deleteChat = true
        viewModel.onEvent(ChatUiEvent.BlockContact(alsoReport = true, deleteChat = true))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isContactBlocked)
        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertEquals(0, fakeChatRepo.getMessagesDirect(convId).size)
    }

    // ==========================================
    // 4. Test Fakes
    // ==========================================

    private class TestFakeContactDao : ContactDao {
        val contacts = mutableMapOf<String, ContactEntity>()

        override suspend fun isUserBlocked(nxId: String): Boolean {
            return contacts[nxId]?.status == ContactStatus.BLOCKED
        }

        override fun isUserBlockedSync(nxId: String): Boolean {
            return contacts[nxId]?.status == ContactStatus.BLOCKED
        }

        override suspend fun getBlockedContactsList(): List<ContactEntity> {
            return contacts.values.filter { it.status == ContactStatus.BLOCKED }
        }

        override suspend fun upsertContact(contact: ContactEntity): Long {
            contacts[contact.nxId] = contact
            return 1L
        }

        override suspend fun getContact(nxId: String): ContactEntity? = contacts[nxId]

        override fun getAllContacts(): Flow<List<ContactEntity>> = flowOf(contacts.values.toList())
        override fun getContactsByStatus(status: ContactStatus): Flow<List<ContactEntity>> =
            flowOf(contacts.values.filter { it.status == status })

        override fun searchContacts(query: String): Flow<List<ContactEntity>> =
            flowOf(contacts.values.filter { it.displayName.contains(query, ignoreCase = true) })

        override fun getPendingReceivedCount(): Flow<Int> = flowOf(0)

        override suspend fun updateStatus(nxId: String, newStatus: ContactStatus): Int {
            val existing = contacts[nxId] ?: return 0
            contacts[nxId] = existing.copy(status = newStatus)
            return 1
        }

        override suspend fun updateStatusMuted(nxId: String, isMuted: Boolean): Int {
            val existing = contacts[nxId] ?: return 0
            contacts[nxId] = existing.copy(isStatusMuted = isMuted)
            return 1
        }

        override suspend fun deleteContact(nxId: String): Int =
            if (contacts.remove(nxId) != null) 1 else 0

        override suspend fun updateNickname(nxId: String, nickname: String?): Int {
            val existing = contacts[nxId] ?: return 0
            contacts[nxId] = existing.copy(customNickname = nickname)
            return 1
        }

        override suspend fun updateDeviceContactName(nxId: String, deviceContactName: String?): Int {
            val existing = contacts[nxId] ?: return 0
            contacts[nxId] = existing.copy(deviceContactName = deviceContactName)
            return 1
        }

        override suspend fun updatePeerPhoneSharing(nxId: String, isShared: Boolean, phone: String?): Int = 1

        override suspend fun updateAvatarUrl(nxId: String, avatarUrl: String?): Int {
            val existing = contacts[nxId] ?: return 0
            contacts[nxId] = existing.copy(nexaAvatarUrl = avatarUrl)
            return 1
        }

        override suspend fun updateDeviceContactPhotoUri(nxId: String, photoUri: String?): Int = 1
    }

    private class TestFakeChatDao : ChatDao {
        val chats = mutableMapOf<String, ChatEntity>()

        override suspend fun getChatByParticipant(nxId: String): ChatEntity? =
            chats.values.firstOrNull { it.participantNxId == nxId }

        override suspend fun getChatById(conversationId: String): ChatEntity? = chats[conversationId]
        override fun getChatsFlow(): Flow<List<ChatEntity>> = flowOf(chats.values.toList())
        override fun getArchivedChatsFlow(): Flow<List<ChatEntity>> = flowOf(emptyList())
        override fun getChatsByTypeFlow(chatType: String): Flow<List<ChatEntity>> = flowOf(emptyList())
        override suspend fun upsertChat(chat: ChatEntity): Long {
            chats[chat.conversationId] = chat
            return 1L
        }
        override suspend fun deleteChat(conversationId: String): Int =
            if (chats.remove(conversationId) != null) 1 else 0

        override suspend fun resetUnreadCount(conversationId: String): Int = 1
        override suspend fun markConversationUnread(conversationId: String, updatedAt: Long): Int = 1
        override suspend fun updateParticipantName(nxId: String, newName: String): Int = 1
        override suspend fun clearLastMessageText(conversationId: String): Int = 1
        override suspend fun updatePinStatus(conversationId: String, isPinned: Boolean, pinPosition: Int, pinnedAt: Long?, updatedAt: Long): Int = 1
        override suspend fun updateArchiveStatus(conversationId: String, isArchived: Boolean, archivedAt: Long?, updatedAt: Long): Int = 1
        override suspend fun updateMuteStatus(conversationId: String, isMuted: Boolean, mutedUntil: Long, updatedAt: Long): Int = 1
        override suspend fun updateLastMessage(conversationId: String, text: String, timestamp: Long, lastMessageId: String?, lastSenderNxId: String?): Int = 1
        override suspend fun incrementUnread(conversationId: String): Int = 1
        override suspend fun countActiveChats(): Int = chats.size

        override suspend fun setPriorityBackup(conversationId: String, isPriority: Boolean, updatedAt: Long): Int {
            val existing = chats[conversationId] ?: return 0
            chats[conversationId] = existing.copy(isPriorityBackup = isPriority, updatedAt = updatedAt)
            return 1
        }
        override fun getPriorityChatsFlow(): Flow<List<ChatEntity>> = flowOf(chats.values.filter { it.isPriorityBackup })
        override fun getPriorityPersonalChatsFlow(): Flow<List<ChatEntity>> = flowOf(chats.values.filter { it.isPriorityBackup && it.chatType != "GROUP" })
        override fun getPriorityGroupChatsFlow(): Flow<List<ChatEntity>> = flowOf(chats.values.filter { it.isPriorityBackup && it.chatType == "GROUP" })
        override suspend fun getPriorityChatIds(): List<String> = chats.values.filter { it.isPriorityBackup }.map { it.conversationId }
        override fun getPriorityChatCountFlow(): Flow<Int> = flowOf(chats.values.count { it.isPriorityBackup })
        override fun getPriorityPersonalChatCountFlow(): Flow<Int> = flowOf(chats.values.count { it.isPriorityBackup && it.chatType != "GROUP" })
        override fun getPriorityGroupChatCountFlow(): Flow<Int> = flowOf(chats.values.count { it.isPriorityBackup && it.chatType == "GROUP" })
        override suspend fun clearAllPriorityBackups(updatedAt: Long): Int {
            chats.values.filter { it.isPriorityBackup }.forEach { chats[it.conversationId] = it.copy(isPriorityBackup = false, updatedAt = updatedAt) }
            return 1
        }
        override suspend fun setConversationBackupPolicy(conversationId: String, policy: String, customCategoriesJson: String?, isPriority: Boolean, updatedAt: Long): Int {
            val existing = chats[conversationId] ?: return 0
            chats[conversationId] = existing.copy(backupPolicy = policy, customBackupCategories = customCategoriesJson, isPriorityBackup = isPriority, updatedAt = updatedAt)
            return 1
        }
        override fun getAllPersonalChatsFlow(): Flow<List<ChatEntity>> = flowOf(chats.values.filter { it.chatType != "GROUP" })
        override fun getAllGroupChatsFlow(): Flow<List<ChatEntity>> = flowOf(chats.values.filter { it.chatType == "GROUP" })
        override suspend fun getAllChatsForBackup(): List<ChatEntity> = chats.values.toList()
        override fun getExcludedChatCountFlow(): Flow<Int> = flowOf(chats.values.count { it.backupPolicy == "EXCLUDED" })
        override fun getCustomPolicyChatCountFlow(): Flow<Int> = flowOf(chats.values.count { it.backupPolicy == "CUSTOM" })
    }

    private class TestFakeContactRepository : ContactRepository {
        val blockedUsers = mutableSetOf<String>()
        private val blockedFlow = MutableStateFlow<List<ContactEntity>>(emptyList())

        override suspend fun blockUser(targetNxId: String): Result<Unit> {
            blockedUsers.add(targetNxId)
            blockedFlow.value = blockedUsers.map {
                ContactEntity(nxId = it, displayName = it, publicKeyHex = "001122", status = ContactStatus.BLOCKED)
            }
            return Result.success(Unit)
        }

        override suspend fun unblockUser(targetNxId: String): Result<Unit> {
            blockedUsers.remove(targetNxId)
            blockedFlow.value = blockedUsers.map {
                ContactEntity(nxId = it, displayName = it, publicKeyHex = "001122", status = ContactStatus.BLOCKED)
            }
            return Result.success(Unit)
        }

        override suspend fun isUserBlocked(targetNxId: String): Boolean = blockedUsers.contains(targetNxId)

        override fun getBlockedContactsFlow(): Flow<List<ContactEntity>> = blockedFlow.asStateFlow()

        override suspend fun getBlockedUsers(): Result<List<BlockedUserItemDto>> {
            return Result.success(blockedUsers.map { BlockedUserItemDto(nxId = it, displayName = it, publicKeyHex = "001122") })
        }

        override suspend fun loadDeviceContacts(): Result<List<com.nexa.app.domain.model.DeviceContact>> = Result.success(emptyList())
        override suspend fun syncPendingRequests(): Int = 0
        override suspend fun lookupSinglePhone(phoneNumber: String): Result<com.nexa.app.domain.model.DeviceContact?> = Result.success(null)
        override suspend fun sendFriendRequest(targetNxId: String, introMessage: String?): Result<Unit> = Result.success(Unit)
        override suspend fun acceptFriendRequest(contact: ContactEntity): Result<Unit> = Result.success(Unit)
        override suspend fun rejectOrBlockContact(nxId: String, block: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun setContactNickname(nxId: String, customNickname: String?): Result<Unit> = Result.success(Unit)
        override suspend fun getContact(nxId: String): ContactEntity? = null
        override fun getContactsByStatus(status: ContactStatus): Flow<List<ContactEntity>> = flowOf(emptyList())
        override fun getPendingCount(): Flow<Int> = flowOf(0)
        override fun getAllContacts(): Flow<List<ContactEntity>> = flowOf(emptyList())
        override fun searchContacts(query: String): Flow<List<ContactEntity>> = flowOf(emptyList())
        override suspend fun discoverUser(nxId: String): Result<com.nexa.app.data.remote.dto.DiscoveredUserDto> = Result.failure(Exception())
        override suspend fun getIncomingConnectionRequests(): Result<List<com.nexa.app.data.remote.dto.ConnectionRequestItemDto>> = Result.success(emptyList())
        override suspend fun getOutgoingConnectionRequests(): Result<List<com.nexa.app.data.remote.dto.ConnectionRequestItemDto>> = Result.success(emptyList())
    }

    private class TestFakeChatRepository : ChatRepository {
        val conversations = mutableMapOf<String, ChatConversation>()
        val messagesMap = mutableMapOf<String, MutableList<Message>>()
        private val messagesFlowMap = mutableMapOf<String, MutableStateFlow<List<Message>>>()
        private val connStateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Connected)

        fun getMessagesDirect(convId: String): List<Message> = messagesMap[convId].orEmpty()

        fun addMessage(convId: String, text: String, isOutgoing: Boolean) {
            val list = messagesMap.getOrPut(convId) { mutableListOf() }
            val msg = Message(
                packetId = "pkt-${System.currentTimeMillis()}-${list.size}",
                conversationId = convId,
                senderNxId = if (isOutgoing) "NX-ME" else "NX-PEER",
                recipientNxId = if (isOutgoing) "NX-PEER" else "NX-ME",
                plainTextContent = text,
                status = MessageStatus.DELIVERED,
                timestamp = System.currentTimeMillis(),
                isOutgoing = isOutgoing
            )
            list.add(msg)
            messagesFlowMap.getOrPut(convId) { MutableStateFlow(emptyList()) }.value = list.toList()
        }

        override suspend fun createOrGetConversation(participantNxId: String, participantName: String?): ChatConversation {
            val conv = ChatConversation(
                conversationId = "conv-$participantNxId",
                participantNxId = participantNxId,
                participantName = participantName ?: "Unknown",
                lastMessageText = "",
                lastMessageTimestamp = System.currentTimeMillis()
            )
            conversations[conv.conversationId] = conv
            return conv
        }

        override suspend fun deleteChat(conversationId: String): Result<Unit> {
            conversations.remove(conversationId)
            messagesMap.remove(conversationId)
            messagesFlowMap[conversationId]?.value = emptyList()
            return Result.success(Unit)
        }

        override suspend fun clearChatMessages(conversationId: String): Result<Unit> {
            messagesMap[conversationId]?.clear()
            messagesFlowMap[conversationId]?.value = emptyList()
            return Result.success(Unit)
        }

        override fun getMessages(conversationId: String): Flow<List<Message>> {
            return messagesFlowMap.getOrPut(conversationId) { MutableStateFlow(messagesMap[conversationId].orEmpty()) }
        }

        override suspend fun sendMessage(
            recipientNxId: String,
            text: String,
            transport: TransportType,
            destinationPhone: String?
        ): Result<Message> {
            val convId = "conv-$recipientNxId"
            addMessage(convId, text, isOutgoing = true)
            return Result.success(messagesMap[convId]!!.last())
        }

        override fun getConversations(): Flow<List<ChatConversation>> =
            flowOf(conversations.values.toList())

        override suspend fun isUserBlocked(targetNxId: String): Boolean = false
        override fun startConnection() {}
        override fun stopConnection() {}
        override fun getConnectionState(): StateFlow<ConnectionState> = connStateFlow.asStateFlow()
        override suspend fun handleIncomingDataSmsPacket(packet: com.nexa.app.core.proto.NexaWirePacket) {}
        override fun sendTypingIndicator(recipientNxId: String, conversationId: String, isTyping: Boolean) {}
        override fun getTypingEvents(): Flow<Pair<String, Boolean>> = flowOf()
        override suspend fun markConversationRead(conversationId: String) {}
    }
}
