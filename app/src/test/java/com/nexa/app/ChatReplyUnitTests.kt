package com.nexa.app

import com.nexa.app.core.network.ConnectionState
import com.nexa.app.core.util.SafeMessageParser
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.data.local.entity.toDomain
import com.nexa.app.data.local.entity.toEntity
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.TransportType
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.presentation.chat.ChatUiEvent
import com.nexa.app.presentation.chat.ChatViewModel
import com.nexa.app.presentation.chat.components.QuotedContentType
import com.nexa.app.presentation.chat.components.QuotedMessageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ChatReplyUnitTests {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. Entity and Domain Model Mapping
    // =========================================================================

    @Test
    fun messageEntity_toDomainAndBack_preservesReplyFields() {
        val entity = MessageEntity(
            packetId = "pkt_reply_100",
            conversationId = "conv_group_1",
            senderNxId = "NX-BOB-999",
            recipientNxId = "NX-ALICE-111",
            plainTextContent = "Meeting is at 10 AM",
            status = "DELIVERED",
            timestamp = 1700000000000L,
            isOutgoing = false,
            transportType = "INTERNET",
            isPinned = false,
            replyToMessageId = "pkt_orig_050",
            replyToSenderNxId = "NX-ALICE-111",
            replyToSnippet = "What time is the team meeting?"
        )

        val domain = entity.toDomain()
        assertEquals("pkt_reply_100", domain.packetId)
        assertEquals("pkt_orig_050", domain.replyToMessageId)
        assertEquals("NX-ALICE-111", domain.replyToSenderNxId)
        assertEquals("What time is the team meeting?", domain.replyToSnippet)

        val mappedBack = domain.toEntity()
        assertEquals(entity.packetId, mappedBack.packetId)
        assertEquals(entity.replyToMessageId, mappedBack.replyToMessageId)
        assertEquals(entity.replyToSenderNxId, mappedBack.replyToSenderNxId)
        assertEquals(entity.replyToSnippet, mappedBack.replyToSnippet)
    }

    @Test
    fun messageEntity_withNullReplyFields_defaultsCorrectly() {
        val entity = MessageEntity(
            packetId = "pkt_normal_200",
            conversationId = "conv_1",
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB",
            plainTextContent = "Standard message without reply",
            status = "SENT",
            timestamp = 1700000001000L,
            isOutgoing = true
        )

        assertNull(entity.replyToMessageId)
        assertNull(entity.replyToSenderNxId)
        assertNull(entity.replyToSnippet)

        val domain = entity.toDomain()
        assertNull(domain.replyToMessageId)
        assertNull(domain.replyToSenderNxId)
        assertNull(domain.replyToSnippet)
    }

    // =========================================================================
    // 2. Safe Payload Encoding & Decoding
    // =========================================================================

    @Test
    fun safeMessageParser_formatOutgoingPayload_plainTextWhenNoReply() {
        val rawText = "Hello world, no reply"
        val formatted = SafeMessageParser.formatOutgoingPayload(text = rawText, replyToMessageId = null)
        assertEquals(rawText, formatted)
    }

    @Test
    fun safeMessageParser_formatOutgoingPayload_embedsReplyMetadataInJson() {
        val text = "Sure, I agree with this plan."
        val replyId = "orig-msg-123"
        val sender = "NX-ALICE-001"
        val snippet = "Should we deploy to staging first?"

        val formatted = SafeMessageParser.formatOutgoingPayload(
            text = text,
            replyToMessageId = replyId,
            replyToSenderNxId = sender,
            replyToSnippet = snippet
        )

        assertTrue(formatted.startsWith("{") && formatted.contains("\"type\":\"CHAT_MESSAGE\""))

        val parsed = SafeMessageParser.parseIncomingPayload(formatted)
        assertEquals(text, parsed.plainText)
        assertEquals(replyId, parsed.replyToMessageId)
        assertEquals(sender, parsed.replyToSenderNxId)
        assertEquals(snippet, parsed.replyToSnippet)
    }

    @Test
    fun safeMessageParser_parseIncomingPayload_handlesStandardPlainTextGracefully() {
        val plain = "Regular incoming text message from older client"
        val parsed = SafeMessageParser.parseIncomingPayload(plain)

        assertEquals(plain, parsed.plainText)
        assertNull(parsed.replyToMessageId)
        assertNull(parsed.replyToSenderNxId)
        assertNull(parsed.replyToSnippet)
    }

    @Test
    fun safeMessageParser_parseIncomingPayload_handlesMalformedJsonGracefully() {
        val malformed = "{\"type\":\"CHAT_MESSAGE\",\"text\":\"incomplete"
        val parsed = SafeMessageParser.parseIncomingPayload(malformed)

        assertEquals(malformed, parsed.plainText)
        assertNull(parsed.replyToMessageId)
    }

    // =========================================================================
    // 3. QuotedMessageHelper Display Resolution
    // =========================================================================

    @Test
    fun quotedHelper_resolvesOutgoingMessageAsYou() {
        val origMsg = Message(
            packetId = "orig_001",
            conversationId = "conv_1",
            senderNxId = "NX-MY-ID",
            recipientNxId = "NX-PEER-ID",
            plainTextContent = "Did you check the code?",
            status = MessageStatus.SENT,
            timestamp = 1700000000000L,
            isOutgoing = true
        )

        val displayInfo = QuotedMessageHelper.resolveDisplayInfo(
            originalMessage = origMsg,
            fallbackSenderName = "Peer Name",
            fallbackSnippet = null,
            currentUserId = "NX-MY-ID"
        )

        assertEquals("You", displayInfo.senderName)
        assertEquals("Did you check the code?", displayInfo.previewText)
        assertEquals(QuotedContentType.TEXT, displayInfo.contentType)
        assertFalse(displayInfo.isDeletedOrUnavailable)
    }

    @Test
    fun quotedHelper_resolvesIncomingPeerMessageWithName() {
        val origMsg = Message(
            packetId = "orig_002",
            conversationId = "conv_1",
            senderNxId = "NX-RAHUL-777",
            recipientNxId = "NX-MY-ID",
            plainTextContent = "Let's review at 4 PM",
            status = MessageStatus.DELIVERED,
            timestamp = 1700000000000L,
            isOutgoing = false
        )

        val displayInfo = QuotedMessageHelper.resolveDisplayInfo(
            originalMessage = origMsg,
            fallbackSenderName = "Rahul",
            fallbackSnippet = null,
            currentUserId = "NX-MY-ID"
        )

        assertEquals("Rahul", displayInfo.senderName)
        assertEquals("Let's review at 4 PM", displayInfo.previewText)
        assertEquals(QuotedContentType.TEXT, displayInfo.contentType)
        assertFalse(displayInfo.isDeletedOrUnavailable)
    }

    @Test
    fun quotedHelper_detectsAttachmentTypesInPreview() {
        // Image attachment
        val imgMsg = Message(
            packetId = "img_001",
            conversationId = "c",
            senderNxId = "s",
            recipientNxId = "r",
            plainTextContent = "[Image] diagram_architecture.png",
            status = MessageStatus.DELIVERED,
            timestamp = 1L,
            isOutgoing = false
        )
        val imgInfo = QuotedMessageHelper.resolveDisplayInfo(imgMsg, "Alice", null)
        assertEquals(QuotedContentType.IMAGE, imgInfo.contentType)
        assertEquals("diagram_architecture.png", imgInfo.previewText)
        assertNotNull(imgInfo.iconRes)

        // Video attachment
        val vidMsg = imgMsg.copy(plainTextContent = "[Video] demo_recording.mp4")
        val vidInfo = QuotedMessageHelper.resolveDisplayInfo(vidMsg, "Alice", null)
        assertEquals(QuotedContentType.VIDEO, vidInfo.contentType)
        assertEquals("demo_recording.mp4", vidInfo.previewText)

        // Audio attachment
        val audioMsg = imgMsg.copy(plainTextContent = "[Voice] 0:45")
        val audioInfo = QuotedMessageHelper.resolveDisplayInfo(audioMsg, "Alice", null)
        assertEquals(QuotedContentType.AUDIO, audioInfo.contentType)
        assertEquals("Voice note", audioInfo.previewText)

        // Document attachment
        val docMsg = imgMsg.copy(plainTextContent = "[Document] report.pdf")
        val docInfo = QuotedMessageHelper.resolveDisplayInfo(docMsg, "Alice", null)
        assertEquals(QuotedContentType.DOCUMENT, docInfo.contentType)
        assertEquals("report.pdf", docInfo.previewText)
    }

    @Test
    fun quotedHelper_handlesDeletedOrUnavailableOriginalMessage() {
        // Scenario A: Original message deleted, but safe snapshot snippet was preserved
        val infoWithSnapshot = QuotedMessageHelper.resolveDisplayInfo(
            originalMessage = null,
            fallbackSenderName = "Rahul",
            fallbackSnippet = "Kal meeting kitne baje hai?"
        )
        assertEquals("Rahul", infoWithSnapshot.senderName)
        assertEquals("Kal meeting kitne baje hai?", infoWithSnapshot.previewText)
        assertFalse(infoWithSnapshot.isDeletedOrUnavailable)

        // Scenario B: Original message deleted, no snapshot snippet available
        val infoNoSnapshot = QuotedMessageHelper.resolveDisplayInfo(
            originalMessage = null,
            fallbackSenderName = null,
            fallbackSnippet = null
        )
        assertEquals("Unknown", infoNoSnapshot.senderName)
        assertEquals("Original message was deleted", infoNoSnapshot.previewText)
        assertEquals(QuotedContentType.DELETED_FALLBACK, infoNoSnapshot.contentType)
        assertTrue(infoNoSnapshot.isDeletedOrUnavailable)
    }

    // =========================================================================
    // 4. ViewModel Reply State & Dispatch
    // =========================================================================

    private class FakeChatRepository : ChatRepository {
        var lastSentText: String? = null
        var lastSentReplyId: String? = null
        var lastSentTransport: TransportType? = null

        override fun getMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())
        override fun getConversations(): Flow<List<ChatConversation>> = flowOf(emptyList())

        override suspend fun sendMessage(
            recipientNxId: String,
            text: String,
            transport: TransportType,
            destinationPhone: String?
        ): Result<Message> = sendMessage(recipientNxId, text, transport, destinationPhone, null)

        override suspend fun sendMessage(
            recipientNxId: String,
            text: String,
            transport: TransportType,
            destinationPhone: String?,
            replyToMessageId: String?
        ): Result<Message> {
            lastSentText = text
            lastSentTransport = transport
            lastSentReplyId = replyToMessageId
            val fakeMsg = Message(
                packetId = UUID.randomUUID().toString(),
                conversationId = recipientNxId,
                senderNxId = "NX-TEST-USER",
                recipientNxId = recipientNxId,
                plainTextContent = text,
                status = MessageStatus.SENT,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true,
                transportType = transport,
                replyToMessageId = replyToMessageId
            )
            return Result.success(fakeMsg)
        }

        override suspend fun markConversationRead(conversationId: String) {}
        override suspend fun createOrGetConversation(participantNxId: String, participantName: String?): ChatConversation {
            return ChatConversation("conv_1", participantNxId, participantName ?: participantNxId, "", 0L)
        }
        override fun startConnection() {}
        override fun stopConnection() {}
        override fun getConnectionState(): StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)
        override suspend fun handleIncomingDataSmsPacket(packet: com.nexa.app.core.proto.NexaWirePacket) {}
        override fun sendTypingIndicator(recipientNxId: String, conversationId: String, isTyping: Boolean) {}
        override fun getTypingEvents(): Flow<Pair<String, Boolean>> = flowOf()
    }

    @Test
    fun viewModel_setAndCancelReply_updatesUiStateProperly() = runTest {
        val fakeRepo = FakeChatRepository()
        val viewModel = ChatViewModel(fakeRepo)

        val targetMessage = Message(
            packetId = "target_999",
            conversationId = "conv_1",
            senderNxId = "NX-PEER",
            recipientNxId = "NX-USER",
            plainTextContent = "Target for reply",
            status = MessageStatus.DELIVERED,
            timestamp = 1000L,
            isOutgoing = false
        )

        // 1. Trigger reply
        viewModel.onEvent(ChatUiEvent.SetReplyingMessage(targetMessage))
        assertEquals(targetMessage, viewModel.uiState.value.replyingToMessage)

        // 2. Cancel reply
        viewModel.onEvent(ChatUiEvent.CancelReply)
        assertNull(viewModel.uiState.value.replyingToMessage)
    }

    @Test
    fun viewModel_sendMessage_dispatchesReplyToMessageIdAndClearsReplyState() = runTest {
        val fakeRepo = FakeChatRepository()
        val viewModel = ChatViewModel(fakeRepo)

        viewModel.onEvent(
            ChatUiEvent.Init(
                conversationId = "conv_100",
                participantNxId = "NX-RECIPIENT-555",
                participantName = "Recipient"
            )
        )

        val targetMessage = Message(
            packetId = "target_packet_abc",
            conversationId = "conv_100",
            senderNxId = "NX-RECIPIENT-555",
            recipientNxId = "NX-TEST-USER",
            plainTextContent = "Original question?",
            status = MessageStatus.DELIVERED,
            timestamp = 1000L,
            isOutgoing = false
        )

        viewModel.onEvent(ChatUiEvent.SetReplyingMessage(targetMessage))
        viewModel.onEvent(ChatUiEvent.OnInputTextChanged("This is my answer."))
        viewModel.onEvent(ChatUiEvent.SendMessage)

        advanceUntilIdle()

        // Verifications
        assertEquals("This is my answer.", fakeRepo.lastSentText)
        assertEquals("target_packet_abc", fakeRepo.lastSentReplyId)
        assertNull("Replying message must be reset after sending", viewModel.uiState.value.replyingToMessage)
        assertEquals("", viewModel.uiState.value.inputText)
    }

    @Test
    fun viewModel_highlightMessage_autoDismissesAfterTimeout() = runTest {
        val fakeRepo = FakeChatRepository()
        val viewModel = ChatViewModel(fakeRepo)

        viewModel.onEvent(ChatUiEvent.HighlightMessage("msg_to_highlight"))
        assertEquals("msg_to_highlight", viewModel.uiState.value.highlightedMessageId)

        // Fast-forward time past 1500ms
        advanceTimeBy(1600)

        assertNull("Highlight must be automatically cleared after timeout", viewModel.uiState.value.highlightedMessageId)
    }
}
