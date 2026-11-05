package com.nexa.app

import com.nexa.app.core.identity.PeerIdentityResolver
import com.nexa.app.core.identity.ResolvedPeerIdentity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * Unit test suite verifying the 3-Tier Identity Hierarchy System:
 * - Anti-Impersonation preservation
 * - Strict priority resolution (Tier 1 > Tier 2 > Tier 3)
 * - Zero-Knowledge client-side state handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerIdentityHierarchyUnitTests {

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
    fun resolveIdentity_whenBothNicknameAndDeviceContactPresent_resolvesTier1AndTier2() {
        val resolved = PeerIdentityResolver.resolveIdentityDisplay(
            customNickname = "Rahul Bhai",
            deviceContactName = "Rahul Office",
            originalRegisteredName = "Rahul Sharma",
            nxId = "NX-8F29-K7P4"
        )

        assertEquals("Rahul Bhai", resolved.primaryDisplayName)
        assertEquals("Rahul Office", resolved.secondarySubLabel)
        assertEquals("Rahul Sharma", resolved.originalRegisteredName)
        assertEquals("NX-8F29-K7P4", resolved.nxId)
        assertTrue(resolved.hasCustomAlias)
        assertEquals("~ Rahul Sharma", resolved.formattedOriginalName)
        assertEquals("Rahul Office • ~ Rahul Sharma", resolved.topBarSubtitle)
    }

    @Test
    fun resolveIdentity_whenOnlyNicknameSet_resolvesTier1WithNullSubLabel() {
        val resolved = PeerIdentityResolver.resolveIdentityDisplay(
            customNickname = "Rahul Bhai",
            deviceContactName = null,
            originalRegisteredName = "Rahul Sharma",
            nxId = "NX-8F29-K7P4"
        )

        assertEquals("Rahul Bhai", resolved.primaryDisplayName)
        assertNull(resolved.secondarySubLabel)
        assertEquals("Rahul Sharma", resolved.originalRegisteredName)
        assertEquals("NX-8F29-K7P4", resolved.nxId)
        assertTrue(resolved.hasCustomAlias)
        assertEquals("~ Rahul Sharma", resolved.formattedOriginalName)
        assertEquals("~ Rahul Sharma", resolved.topBarSubtitle)
    }

    @Test
    fun resolveIdentity_whenOnlyDeviceContactPresent_resolvesTier2WithNullSubLabel() {
        val resolved = PeerIdentityResolver.resolveIdentityDisplay(
            customNickname = null,
            deviceContactName = "Rahul Office",
            originalRegisteredName = "Rahul Sharma",
            nxId = "NX-8F29-K7P4"
        )

        assertEquals("Rahul Office", resolved.primaryDisplayName)
        assertNull(resolved.secondarySubLabel)
        assertEquals("Rahul Sharma", resolved.originalRegisteredName)
        assertEquals("NX-8F29-K7P4", resolved.nxId)
        assertTrue(resolved.hasCustomAlias)
        assertEquals("~ Rahul Sharma", resolved.formattedOriginalName)
        assertEquals("~ Rahul Sharma", resolved.topBarSubtitle)
    }

    @Test
    fun resolveIdentity_whenNeitherPresent_resolvesTier3Unchanged() {
        val resolved = PeerIdentityResolver.resolveIdentityDisplay(
            customNickname = null,
            deviceContactName = null,
            originalRegisteredName = "Rahul Sharma",
            nxId = "NX-8F29-K7P4"
        )

        assertEquals("Rahul Sharma", resolved.primaryDisplayName)
        assertNull(resolved.secondarySubLabel)
        assertEquals("Rahul Sharma", resolved.originalRegisteredName)
        assertEquals("NX-8F29-K7P4", resolved.nxId)
        assertFalse(resolved.hasCustomAlias)
        assertEquals("Rahul Sharma", resolved.formattedOriginalName)
        assertNull(resolved.topBarSubtitle)
    }

    @Test
    fun resolveIdentity_whenOriginalNameBlank_fallsBackToNxId() {
        val resolved = PeerIdentityResolver.resolveIdentityDisplay(
            customNickname = null,
            deviceContactName = null,
            originalRegisteredName = "   ",
            nxId = "NX-8F29-K7P4"
        )

        assertEquals("NX-8F29-K7P4", resolved.primaryDisplayName)
        assertEquals("NX-8F29-K7P4", resolved.originalRegisteredName)
        assertNull(resolved.secondarySubLabel)
        assertFalse(resolved.hasCustomAlias)
    }

    @Test
    fun contactEntity_resolvedIdentity_and_effectiveDisplayName_respectsHierarchy() {
        val baseContact = ContactEntity(
            nxId = "NX-8F29-K7P4",
            displayName = "Rahul Sharma",
            publicKeyHex = "abcd1234ef",
            introMessage = null,
            status = ContactStatus.MUTUAL_FRIEND,
            customNickname = null,
            deviceContactName = null
        )

        // Tier 3 only
        assertEquals("Rahul Sharma", baseContact.effectiveDisplayName)
        assertNull(baseContact.resolvedIdentity.secondarySubLabel)
        assertNull(baseContact.resolvedIdentity.topBarSubtitle)

        // Add Tier 2 (Device Contact)
        val withDevice = baseContact.copy(deviceContactName = "Rahul Office")
        assertEquals("Rahul Office", withDevice.effectiveDisplayName)
        assertEquals("~ Rahul Sharma", withDevice.resolvedIdentity.topBarSubtitle)

        // Add Tier 1 (Custom Nickname) -> Overrides Tier 2 as primary
        val withBoth = withDevice.copy(customNickname = "Rahul Bhai")
        assertEquals("Rahul Bhai", withBoth.effectiveDisplayName)
        assertEquals("Rahul Office", withBoth.resolvedIdentity.secondarySubLabel)
        assertEquals("Rahul Office • ~ Rahul Sharma", withBoth.resolvedIdentity.topBarSubtitle)

        // Clear Tier 1 -> Falls back to Tier 2
        val reverted = withBoth.copy(customNickname = "   ")
        assertEquals("Rahul Office", reverted.effectiveDisplayName)
        assertEquals("~ Rahul Sharma", reverted.resolvedIdentity.topBarSubtitle)
    }

    @Test
    fun chatViewModel_loads3TierIdentity_and_updatesOnNicknameChange() = runTest {
        var savedNickname: String? = null

        val testContact = ContactEntity(
            nxId = "NX-8F29-K7P4",
            displayName = "Rahul Sharma",
            publicKeyHex = "abcd1234ef",
            introMessage = null,
            status = ContactStatus.MUTUAL_FRIEND,
            customNickname = null,
            deviceContactName = "Rahul Office"
        )

        val fakeContactRepo = object : ContactRepository {
            override suspend fun sendFriendRequest(targetNxId: String, introMessage: String?): Result<Unit> = Result.success(Unit)
            override suspend fun acceptFriendRequest(contact: ContactEntity): Result<Unit> = Result.success(Unit)
            override suspend fun rejectOrBlockContact(nxId: String, block: Boolean): Result<Unit> = Result.success(Unit)
            override suspend fun syncPendingRequests(): Int = 0
            override fun getContactsByStatus(status: ContactStatus): Flow<List<ContactEntity>> = flowOf(emptyList())
            override fun getPendingCount(): Flow<Int> = flowOf(0)
            override fun getAllContacts(): Flow<List<ContactEntity>> = flowOf(emptyList())
            override suspend fun getContact(nxId: String): ContactEntity? = if (nxId == testContact.nxId) testContact else null
            override suspend fun setContactNickname(nxId: String, customNickname: String?): Result<Unit> {
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
            override suspend fun sendMessage(recipientNxId: String, text: String, transport: TransportType, destinationPhone: String?): Result<Message> =
                Result.failure(UnsupportedOperationException())
            override suspend fun markConversationRead(conversationId: String) {}
            override suspend fun createOrGetConversation(participantNxId: String, participantName: String?): ChatConversation =
                ChatConversation("conv_1", participantNxId, participantName ?: participantNxId, "", 0L)
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

        // Initialize chat with participant
        viewModel.onEvent(
            ChatUiEvent.Init(
                conversationId = "conv_1",
                participantNxId = "NX-8F29-K7P4",
                participantName = "Rahul Sharma"
            )
        )
        advanceUntilIdle()

        // Before setting custom nickname: Tier 2 (deviceContactName "Rahul Office") is primary
        val stateAfterInit = viewModel.uiState.value
        assertEquals("Rahul Office", stateAfterInit.participantName)
        assertEquals("Rahul Office", stateAfterInit.deviceContactName)
        assertEquals("Rahul Sharma", stateAfterInit.originalProfileName)
        assertNull(stateAfterInit.customNickname)

        // User sets a Tier 1 custom nickname "Rahul Bhai"
        viewModel.onEvent(ChatUiEvent.SaveNickname("Rahul Bhai"))
        advanceUntilIdle()

        val stateAfterNickname = viewModel.uiState.value
        assertEquals("Rahul Bhai", stateAfterNickname.participantName)
        assertEquals("Rahul Bhai", stateAfterNickname.customNickname)
        assertEquals("Rahul Office", stateAfterNickname.deviceContactName)
        assertEquals("Rahul Sharma", stateAfterNickname.originalProfileName)
        assertEquals("Rahul Bhai", savedNickname)

        // User clears custom nickname -> falls back to Tier 2 "Rahul Office"
        viewModel.onEvent(ChatUiEvent.SaveNickname(null))
        advanceUntilIdle()

        val stateAfterClear = viewModel.uiState.value
        assertEquals("Rahul Office", stateAfterClear.participantName)
        assertNull(stateAfterClear.customNickname)
        assertEquals("Rahul Office", stateAfterClear.deviceContactName)
        assertEquals("Rahul Sharma", stateAfterClear.originalProfileName)
    }
}
