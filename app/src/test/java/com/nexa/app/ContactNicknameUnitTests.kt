package com.nexa.app

import com.nexa.app.core.network.ConnectionState
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.model.DeviceContact
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.TransportType
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.domain.repository.ContactRepository
import com.nexa.app.presentation.chat.ChatUiEvent
import com.nexa.app.presentation.chat.ChatViewModel
import com.nexa.app.presentation.contacts.ContactViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactNicknameUnitTests {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun contactEntity_effectiveDisplayName_prioritizesCustomNickname() {
        val original = ContactEntity(
            nxId = "NX-9842-K7X",
            displayName = "Rahul Sharma",
            publicKeyHex = "aabbcc112233",
            introMessage = "Hello",
            status = ContactStatus.MUTUAL_FRIEND,
            customNickname = null
        )

        // Case 1: No nickname set -> returns original display name
        assertEquals("Rahul Sharma", original.effectiveDisplayName)

        // Case 2: Custom nickname set -> returns custom nickname
        val withNickname = original.copy(customNickname = "Rahul Bhai")
        assertEquals("Rahul Bhai", withNickname.effectiveDisplayName)
        assertEquals("Rahul Sharma", withNickname.displayName)

        // Case 3: Empty or whitespace nickname -> falls back to original name
        val withBlankNickname = original.copy(customNickname = "   ")
        assertEquals("Rahul Sharma", withBlankNickname.effectiveDisplayName)
    }

    @Test
    fun chatViewModel_nicknameDialogAndSave_updatesStateAndPersists() = runTest {
        var savedNxId: String? = null
        var savedNickname: String? = null

        val fakeContact = ContactEntity(
            nxId = "NX-1044-M2P",
            displayName = "Rahul Sharma",
            publicKeyHex = "11223344",
            introMessage = null,
            status = ContactStatus.MUTUAL_FRIEND,
            customNickname = null
        )

        val fakeContactRepo = object : ContactRepository {
            override suspend fun sendFriendRequest(targetNxId: String, introMessage: String?): Result<Unit> = Result.success(Unit)
            override suspend fun acceptFriendRequest(contact: ContactEntity): Result<Unit> = Result.success(Unit)
            override suspend fun rejectOrBlockContact(nxId: String, block: Boolean): Result<Unit> = Result.success(Unit)
            override suspend fun syncPendingRequests(): Int = 0
            override fun getContactsByStatus(status: ContactStatus): Flow<List<ContactEntity>> = flowOf(emptyList())
            override fun getPendingCount(): Flow<Int> = flowOf(0)
            override fun getAllContacts(): Flow<List<ContactEntity>> = flowOf(emptyList())
            override suspend fun getContact(nxId: String): ContactEntity? = if (nxId == fakeContact.nxId) fakeContact else null
            override suspend fun setContactNickname(nxId: String, customNickname: String?): Result<Unit> {
                savedNxId = nxId
                savedNickname = customNickname
                return Result.success(Unit)
            }
            override fun searchContacts(query: String): Flow<List<ContactEntity>> = flowOf(emptyList())
            override suspend fun loadDeviceContacts(): Result<List<DeviceContact>> = Result.success(emptyList())
            override suspend fun lookupSinglePhone(phoneNumber: String): Result<DeviceContact?> = Result.success(null)
        }

        val fakeChatRepo = object : ChatRepository {
            override fun getMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())
            override fun getConversations(): Flow<List<ChatConversation>> = flowOf(emptyList())
            override suspend fun sendMessage(
                recipientNxId: String,
                text: String,
                transport: TransportType,
                destinationPhone: String?
            ): Result<Message> = Result.failure(UnsupportedOperationException())
            override suspend fun markConversationRead(conversationId: String) {}
            override suspend fun createOrGetConversation(participantNxId: String, participantName: String?): ChatConversation {
                return ChatConversation("c1", participantNxId, participantName ?: participantNxId, "", 0L, 0)
            }
            override fun startConnection() {}
            override fun stopConnection() {}
            override fun getConnectionState(): StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)
            override suspend fun handleIncomingDataSmsPacket(packet: NexaWirePacket) {}
            override fun sendTypingIndicator(recipientNxId: String, conversationId: String, isTyping: Boolean) {}
            override fun getTypingEvents(): Flow<Pair<String, Boolean>> = flowOf()
        }

        val viewModel = ChatViewModel(
            chatRepository = fakeChatRepo,
            contactRepository = fakeContactRepo
        )

        // Initialize conversation
        viewModel.onEvent(
            ChatUiEvent.Init(
                conversationId = "c1",
                participantNxId = "NX-1044-M2P",
                participantName = "Rahul Sharma"
            )
        )
        advanceUntilIdle()

        assertEquals("Rahul Sharma", viewModel.uiState.value.participantName)
        assertEquals("Rahul Sharma", viewModel.uiState.value.originalProfileName)
        assertNull(viewModel.uiState.value.customNickname)

        // Open nickname dialog
        viewModel.onEvent(ChatUiEvent.SetNicknameDialogVisible(true))
        assertTrue(viewModel.uiState.value.showNicknameDialog)

        // Save new nickname "Rahul Bhai"
        viewModel.onEvent(ChatUiEvent.SaveNickname("Rahul Bhai"))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showNicknameDialog)
        assertEquals("Rahul Bhai", viewModel.uiState.value.participantName)
        assertEquals("Rahul Sharma", viewModel.uiState.value.originalProfileName)
        assertEquals("Rahul Bhai", viewModel.uiState.value.customNickname)
        assertEquals("NX-1044-M2P", savedNxId)
        assertEquals("Rahul Bhai", savedNickname)

        // Remove / Clear nickname
        viewModel.onEvent(ChatUiEvent.SaveNickname(null))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.customNickname)
        assertEquals("Rahul Sharma", viewModel.uiState.value.participantName)
        assertNull(savedNickname)
    }

    @Test
    fun contactViewModel_searchFilter_matchesBothNicknameAndOriginalName() = runTest {
        val contact1 = ContactEntity(
            nxId = "NX-1044-M2P",
            displayName = "Rahul Sharma",
            publicKeyHex = "1122",
            introMessage = null,
            status = ContactStatus.MUTUAL_FRIEND,
            customNickname = "Rahul Bhai"
        )
        val contact2 = ContactEntity(
            nxId = "NX-8833-J9Q",
            displayName = "Vikram Aditya",
            publicKeyHex = "3344",
            introMessage = null,
            status = ContactStatus.MUTUAL_FRIEND,
            customNickname = "Aditya Bro"
        )

        val fakeContactRepo = object : ContactRepository {
            override suspend fun sendFriendRequest(targetNxId: String, introMessage: String?): Result<Unit> = Result.success(Unit)
            override suspend fun acceptFriendRequest(contact: ContactEntity): Result<Unit> = Result.success(Unit)
            override suspend fun rejectOrBlockContact(nxId: String, block: Boolean): Result<Unit> = Result.success(Unit)
            override suspend fun syncPendingRequests(): Int = 0
            override fun getContactsByStatus(status: ContactStatus): Flow<List<ContactEntity>> = flowOf(listOf(contact1, contact2))
            override fun getPendingCount(): Flow<Int> = flowOf(0)
            override fun getAllContacts(): Flow<List<ContactEntity>> = flowOf(listOf(contact1, contact2))
            override suspend fun getContact(nxId: String): ContactEntity? = null
            override suspend fun setContactNickname(nxId: String, customNickname: String?): Result<Unit> = Result.success(Unit)
            override fun searchContacts(query: String): Flow<List<ContactEntity>> = flowOf(emptyList())
            override suspend fun loadDeviceContacts(): Result<List<DeviceContact>> = Result.success(emptyList())
            override suspend fun lookupSinglePhone(phoneNumber: String): Result<DeviceContact?> = Result.success(null)
        }

        val viewModel = ContactViewModel(contactRepository = fakeContactRepo)
        val collectJob = launch {
            viewModel.uiState.collect { }
        }
        advanceUntilIdle()

        // 1. Search by custom nickname "Bhai" -> matches contact1
        viewModel.onSearchQueryChanged("Bhai")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.mutualFriends.size)
        assertEquals("Rahul Bhai", viewModel.uiState.value.mutualFriends[0].effectiveDisplayName)

        // 2. Search by original profile name "Sharma" -> matches contact1
        viewModel.onSearchQueryChanged("Sharma")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.mutualFriends.size)
        assertEquals("Rahul Sharma", viewModel.uiState.value.mutualFriends[0].displayName)

        // 3. Search by partial NX-ID "8833" -> matches contact2
        viewModel.onSearchQueryChanged("8833")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.mutualFriends.size)
        assertEquals("Aditya Bro", viewModel.uiState.value.mutualFriends[0].effectiveDisplayName)

        // 4. Empty search returns all contacts
        viewModel.onSearchQueryChanged("")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.mutualFriends.size)

        collectJob.cancel()
    }
}
