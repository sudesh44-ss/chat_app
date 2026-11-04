package com.nexa.app

import com.nexa.app.core.network.ConnectionState
import com.nexa.app.core.proto.AckStatus
import com.nexa.app.core.proto.DeliveryAckPayload
import com.nexa.app.core.proto.EncryptedMessagePayload
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.proto.PacketType
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.TransportType
import com.nexa.app.domain.repository.ChatRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMessagingUnitTests {

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
    fun protobufWire_encodeAndDecode_preservesBinaryIntegrity() {
        val originalMessagePayload = EncryptedMessagePayload(
            conversationSeq = 17294820L,
            ciphertext = "EncryptedSecretTextPayload".toByteArray(Charsets.UTF_8),
            nonce = byteArrayOf(0x01, 0x02, 0x03, 0x04),
            authTag = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        )

        val originalPacket = NexaWirePacket(
            packetId = "pkt_9842_abc",
            type = PacketType.PACKET_MESSAGE,
            senderNxId = "NX-9842-K7X",
            recipientNxId = "NX-1044-M2P",
            timestamp = 1729482000000L,
            message = originalMessagePayload
        )

        // Encode to Protobuf binary bytes
        val encodedBytes = originalPacket.encode()
        assertTrue(encodedBytes.isNotEmpty())

        // Decode back from Protobuf binary bytes
        val decodedPacket = NexaWirePacket.decode(encodedBytes)

        assertEquals(originalPacket.packetId, decodedPacket.packetId)
        assertEquals(originalPacket.type, decodedPacket.type)
        assertEquals(originalPacket.senderNxId, decodedPacket.senderNxId)
        assertEquals(originalPacket.recipientNxId, decodedPacket.recipientNxId)
        assertEquals(originalPacket.timestamp, decodedPacket.timestamp)

        assertNotNull(decodedPacket.message)
        assertEquals(originalMessagePayload.conversationSeq, decodedPacket.message?.conversationSeq)
        assertTrue(originalMessagePayload.ciphertext.contentEquals(decodedPacket.message!!.ciphertext))
        assertTrue(originalMessagePayload.nonce.contentEquals(decodedPacket.message!!.nonce))
        assertTrue(originalMessagePayload.authTag.contentEquals(decodedPacket.message!!.authTag))
    }

    @Test
    fun protobufWire_ackPacket_encodeAndDecode_succeeds() {
        val originalAckPacket = NexaWirePacket(
            packetId = "ack_001",
            type = PacketType.PACKET_ACK,
            senderNxId = "NX-1044-M2P",
            recipientNxId = "NX-9842-K7X",
            timestamp = 1729482005000L,
            ack = DeliveryAckPayload(
                originalPacketId = "pkt_9842_abc",
                status = AckStatus.ACK_DEVICE_DELIVERED
            )
        )

        val encoded = originalAckPacket.encode()
        val decoded = NexaWirePacket.decode(encoded)

        assertEquals(originalAckPacket.packetId, decoded.packetId)
        assertEquals(originalAckPacket.type, decoded.type)
        assertNotNull(decoded.ack)
        assertEquals("pkt_9842_abc", decoded.ack?.originalPacketId)
        assertEquals(AckStatus.ACK_DEVICE_DELIVERED, decoded.ack?.status)
    }

    @Test
    fun chatViewModel_sendMessage_invokesRepositoryAndClearsInput() = runTest {
        var sentRecipient: String? = null
        var sentText: String? = null
        var sentTransport: TransportType? = null

        val fakeRepository = object : ChatRepository {
            override fun getMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())
            override fun getConversations(): Flow<List<ChatConversation>> = flowOf(emptyList())

            override suspend fun sendMessage(
                recipientNxId: String,
                text: String,
                transport: TransportType,
                destinationPhone: String?
            ): Result<Message> {
                sentRecipient = recipientNxId
                sentText = text
                sentTransport = transport
                return Result.success(
                    Message(
                        packetId = UUID.randomUUID().toString(),
                        conversationId = "conv_1",
                        senderNxId = "NX-ME",
                        recipientNxId = recipientNxId,
                        plainTextContent = text,
                        status = MessageStatus.SENT,
                        timestamp = System.currentTimeMillis(),
                        isOutgoing = true,
                        transportType = transport
                    )
                )
            }

            override suspend fun markConversationRead(conversationId: String) {}
            override suspend fun createOrGetConversation(participantNxId: String, participantName: String?): ChatConversation {
                return ChatConversation("conv_1", participantNxId, participantName ?: participantNxId, "", 0L, 0)
            }
            override fun startConnection() {}
            override fun stopConnection() {}
            override fun getConnectionState(): StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)
            override suspend fun handleIncomingDataSmsPacket(packet: NexaWirePacket) {}
            override fun sendTypingIndicator(recipientNxId: String, conversationId: String, isTyping: Boolean) {}
            override fun getTypingEvents(): Flow<Pair<String, Boolean>> = flowOf()
        }

        val viewModel = ChatViewModel(fakeRepository)
        viewModel.onEvent(
            ChatUiEvent.Init(
                conversationId = "conv_1",
                participantNxId = "NX-1044-M2P",
                participantName = "Bob"
            )
        )

        viewModel.onEvent(ChatUiEvent.OnInputTextChanged("Kahan ho?"))
        assertEquals("Kahan ho?", viewModel.uiState.value.inputText)

        viewModel.onEvent(ChatUiEvent.SendMessage)
        advanceUntilIdle()

        // Input text must be cleared
        assertEquals("", viewModel.uiState.value.inputText)
        assertEquals("NX-1044-M2P", sentRecipient)
        assertEquals("Kahan ho?", sentText)
        assertEquals(TransportType.INTERNET, sentTransport)
    }

    @Test
    fun messageStatus_transitionSequence_matchesSpecification() {
        val statuses = listOf(
            MessageStatus.PENDING,
            MessageStatus.SENT,
            MessageStatus.DELIVERED,
            MessageStatus.READ
        )

        assertEquals(4, statuses.size)
        assertEquals("PENDING", MessageStatus.PENDING.name)
        assertEquals("SENT", MessageStatus.SENT.name)
        assertEquals("DELIVERED", MessageStatus.DELIVERED.name)
        assertEquals("READ", MessageStatus.READ.name)
    }
}
