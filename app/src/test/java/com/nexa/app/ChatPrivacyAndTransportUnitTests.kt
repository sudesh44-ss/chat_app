package com.nexa.app

import android.content.SharedPreferences
import com.nexa.app.core.network.ConnectionState
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.data.local.preferences.ChatPrivacyPrefs
import com.nexa.app.data.local.preferences.OnlinePrivacyMode
import com.nexa.app.data.local.preferences.TransportRouteMode
import com.nexa.app.data.local.preferences.TypingPrivacyMode
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPrivacyAndTransportUnitTests {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * In-memory implementation of SharedPreferences for isolated unit testing.
     */
    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val parentMap: MutableMap<String, Any>) : SharedPreferences.Editor {
            private val tempMap = mutableMapOf<String, Any>()
            private var clearCalled = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null && value != null) tempMap[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null && values != null) tempMap[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) tempMap.remove(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearCalled = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearCalled) parentMap.clear()
                parentMap.putAll(tempMap)
            }
        }
    }

    @Test
    fun chatPrivacyPrefs_defaultValues_areSecureAndStandard() {
        val fakePrefs = FakeSharedPreferences()
        val privacyPrefs = ChatPrivacyPrefs(fakePrefs)

        assertTrue("Read receipts must default to true", privacyPrefs.readReceiptsEnabled)
        assertEquals("Typing indicator must default to EVERYONE", TypingPrivacyMode.EVERYONE, privacyPrefs.typingIndicatorMode)
        assertEquals("Online presence must default to EVERYONE", OnlinePrivacyMode.EVERYONE, privacyPrefs.onlinePresenceMode)
        assertTrue("Transport badges must default to true", privacyPrefs.showTransportBadges)
        assertEquals("Default route mode must be AUTO", TransportRouteMode.AUTO, privacyPrefs.preferredTransportRoute)
    }

    @Test
    fun chatPrivacyPrefs_mutations_persistCorrectly() {
        val fakePrefs = FakeSharedPreferences()
        val privacyPrefs = ChatPrivacyPrefs(fakePrefs)

        privacyPrefs.readReceiptsEnabled = false
        assertFalse(privacyPrefs.readReceiptsEnabled)

        privacyPrefs.typingIndicatorMode = TypingPrivacyMode.NOBODY
        assertEquals(TypingPrivacyMode.NOBODY, privacyPrefs.typingIndicatorMode)

        privacyPrefs.onlinePresenceMode = OnlinePrivacyMode.MY_CONTACTS
        assertEquals(OnlinePrivacyMode.MY_CONTACTS, privacyPrefs.onlinePresenceMode)

        privacyPrefs.showTransportBadges = false
        assertFalse(privacyPrefs.showTransportBadges)

        privacyPrefs.preferredTransportRoute = TransportRouteMode.MESH
        assertEquals(TransportRouteMode.MESH, privacyPrefs.preferredTransportRoute)
    }

    @Test
    fun typingGatekeeper_whenDisabled_suppressesTypingIndicatorPulse() = runTest {
        var typingSentCount = 0
        val fakeRepository = object : ChatRepository {
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
            override fun sendTypingIndicator(recipientNxId: String, conversationId: String, isTyping: Boolean) {
                typingSentCount++
            }
            override fun getTypingEvents(): Flow<Pair<String, Boolean>> = flowOf()
        }

        val fakePrefs = FakeSharedPreferences()
        val privacyPrefs = ChatPrivacyPrefs(fakePrefs)
        privacyPrefs.typingIndicatorMode = TypingPrivacyMode.NOBODY

        val viewModel = ChatViewModel(
            chatRepository = fakeRepository,
            chatPrivacyPrefs = privacyPrefs
        )
        viewModel.onEvent(ChatUiEvent.Init("c1", "NX-PEER-1", "Alice"))

        // User types text with typing indicator disabled
        viewModel.onEvent(ChatUiEvent.OnInputTextChanged("Hello World"))
        advanceUntilIdle()

        assertEquals("Typing pulse must be 100% suppressed when mode is NOBODY", 0, typingSentCount)

        // Now enable typing indicator and verify it sends (typing_start followed by debounce typing_stop)
        privacyPrefs.typingIndicatorMode = TypingPrivacyMode.EVERYONE
        viewModel.onEvent(ChatUiEvent.OnInputTextChanged("Hello World again"))
        advanceUntilIdle()

        assertTrue("Typing pulse must be dispatched when mode is EVERYONE", typingSentCount >= 1)
    }

    @Test
    fun multiTransportRouting_dynamicRouteSelection_updatesActiveTransport() = runTest {
        val connectionFlow = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val fakeRepository = object : ChatRepository {
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
            override fun getConnectionState(): StateFlow<ConnectionState> = connectionFlow
            override suspend fun handleIncomingDataSmsPacket(packet: NexaWirePacket) {}
            override fun sendTypingIndicator(recipientNxId: String, conversationId: String, isTyping: Boolean) {}
            override fun getTypingEvents(): Flow<Pair<String, Boolean>> = flowOf()
        }

        val fakePrefs = FakeSharedPreferences()
        val privacyPrefs = ChatPrivacyPrefs(fakePrefs)
        val viewModel = ChatViewModel(
            chatRepository = fakeRepository,
            chatPrivacyPrefs = privacyPrefs
        )

        advanceUntilIdle()
        assertEquals(TransportRouteMode.AUTO, viewModel.uiState.value.selectedRouteMode)
        assertEquals(TransportType.INTERNET, viewModel.uiState.value.selectedTransport)

        // Switch to MESH
        viewModel.onEvent(ChatUiEvent.SetRouteMode(TransportRouteMode.MESH))
        advanceUntilIdle()
        assertEquals(TransportRouteMode.MESH, viewModel.uiState.value.selectedRouteMode)
        assertEquals(TransportType.DTN_MESH, viewModel.uiState.value.selectedTransport)
        assertEquals(TransportRouteMode.MESH, privacyPrefs.preferredTransportRoute)

        // Switch to SMS
        viewModel.onEvent(ChatUiEvent.SetRouteMode(TransportRouteMode.SMS))
        advanceUntilIdle()
        assertEquals(TransportRouteMode.SMS, viewModel.uiState.value.selectedRouteMode)
        assertEquals(TransportType.DATA_SMS, viewModel.uiState.value.selectedTransport)

        // Switch back to AUTO and simulate disconnection -> auto fallbacks to MESH
        viewModel.onEvent(ChatUiEvent.SetRouteMode(TransportRouteMode.AUTO))
        connectionFlow.value = ConnectionState.Disconnected
        advanceUntilIdle()
        assertEquals(TransportRouteMode.AUTO, viewModel.uiState.value.selectedRouteMode)
        assertEquals(TransportType.DTN_MESH, viewModel.uiState.value.selectedTransport)

        // Reconnect -> auto switches to INTERNET
        connectionFlow.value = ConnectionState.Connected
        advanceUntilIdle()
        assertEquals(TransportType.INTERNET, viewModel.uiState.value.selectedTransport)
    }

    @Test
    fun carrierSmsFailure_remediationFlow_allowsRetryingWithAlternateTransport() = runTest {
        var retriedPacketId: String? = null
        var retriedTransport: TransportType? = null

        val fakeRepository = object : ChatRepository {
            override fun getMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())
            override fun getConversations(): Flow<List<ChatConversation>> = flowOf(emptyList())
            override suspend fun sendMessage(
                recipientNxId: String,
                text: String,
                transport: TransportType,
                destinationPhone: String?
            ): Result<Message> = Result.failure(UnsupportedOperationException())
            override suspend fun retryFailedMessage(
                packetId: String,
                transport: TransportType,
                destinationPhone: String?
            ): Result<Message> {
                retriedPacketId = packetId
                retriedTransport = transport
                return Result.success(
                    Message(
                        packetId = packetId,
                        conversationId = "c1",
                        senderNxId = "NX-ME",
                        recipientNxId = "NX-PEER-1",
                        plainTextContent = "Retry text",
                        status = MessageStatus.SENT,
                        timestamp = System.currentTimeMillis(),
                        isOutgoing = true,
                        transportType = transport
                    )
                )
            }
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

        val viewModel = ChatViewModel(chatRepository = fakeRepository)
        val failedMsg = Message(
            packetId = "pkt-failed-123",
            conversationId = "c1",
            senderNxId = "NX-ME",
            recipientNxId = "NX-PEER-1",
            plainTextContent = "Mission critical message",
            status = MessageStatus.FAILED,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            transportType = TransportType.DATA_SMS
        )

        // User taps failed message bubble
        viewModel.onEvent(ChatUiEvent.OnFailedMessageClicked(failedMsg))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showSmsFailureSheet)
        assertEquals(failedMsg, viewModel.uiState.value.failedMessageCandidate)

        // User chooses to switch to Internet & resend
        viewModel.onEvent(ChatUiEvent.RetryFailedMessage(TransportType.INTERNET))
        advanceUntilIdle()

        assertFalse("Sheet must be dismissed after retry", viewModel.uiState.value.showSmsFailureSheet)
        assertNull("Failed message candidate must be cleared", viewModel.uiState.value.failedMessageCandidate)
        assertEquals("pkt-failed-123", retriedPacketId)
        assertEquals(TransportType.INTERNET, retriedTransport)
    }

    @Test
    fun transportBadges_toggleSettings_updatesVisibility() = runTest {
        val fakeRepository = object : ChatRepository {
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

        val fakePrefs = FakeSharedPreferences()
        val privacyPrefs = ChatPrivacyPrefs(fakePrefs)
        val viewModel = ChatViewModel(
            chatRepository = fakeRepository,
            chatPrivacyPrefs = privacyPrefs
        )

        advanceUntilIdle()
        assertTrue("Badges default to visible", viewModel.uiState.value.showTransportBadges)

        // Toggle OFF
        viewModel.onEvent(ChatUiEvent.SetShowTransportBadges(false))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.showTransportBadges)
        assertFalse(privacyPrefs.showTransportBadges)

        // Toggle ON
        viewModel.onEvent(ChatUiEvent.SetShowTransportBadges(true))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showTransportBadges)
        assertTrue(privacyPrefs.showTransportBadges)
    }

    @Test
    fun telemetryRingBuffer_andProtobufSerialization_verifiesBatchingAndPayloadEfficiency() {
        com.nexa.app.core.telemetry.NexaTelemetryEngine.reset()

        // 1. Buffer 19 events -> No disk flush emitted (pure RAM ArrayDeque)
        for (i in 1..19) {
            val flush = com.nexa.app.core.telemetry.NexaTelemetryEngine.recordAudit(
                com.nexa.app.core.telemetry.TelemetryAuditRecord(
                    msgIdHash = "hash_$i",
                    mediaCategory = "NONE",
                    sizeBytes = 100L + i,
                    eventType = "MSG_SENT"
                )
            )
            org.junit.Assert.assertNull("Buffer must not flush before reaching capacity 20", flush)
        }

        // 2. 20th event triggers atomic flush of 20 items
        val batchOf20 = com.nexa.app.core.telemetry.NexaTelemetryEngine.recordAudit(
            com.nexa.app.core.telemetry.TelemetryAuditRecord(
                msgIdHash = "hash_20",
                mediaCategory = "DOC",
                sizeBytes = 500L,
                eventType = "MEDIA_SENT"
            )
        )
        org.junit.Assert.assertNotNull("20th item must trigger atomic flush", batchOf20)
        assertEquals(20, batchOf20!!.size)

        // 3. Encode to Protobuf binary
        val protobufBytes = com.nexa.app.core.telemetry.NexaTelemetryEngine.encodeProtobufBatch(batchOf20)
        org.junit.Assert.assertTrue("Protobuf payload must be non-empty", protobufBytes.isNotEmpty())
        // Highly compact: average ~30-40 bytes per record
        org.junit.Assert.assertTrue("20 Protobuf records must be highly compact (< 1000 bytes)", protobufBytes.size < 1000)

        // 4. Manual flush test
        com.nexa.app.core.telemetry.NexaTelemetryEngine.recordAudit(
            com.nexa.app.core.telemetry.TelemetryAuditRecord(msgIdHash = "manual_1")
        )
        val manualFlush = com.nexa.app.core.telemetry.NexaTelemetryEngine.flushPendingAuditBuffer()
        assertEquals(1, manualFlush.size)
        assertEquals("manual_1", manualFlush[0].msgIdHash)
    }
}
