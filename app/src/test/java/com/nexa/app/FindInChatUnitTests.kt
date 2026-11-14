package com.nexa.app

import com.nexa.app.core.network.ConnectionState
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.data.local.dao.MessageDao
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.data.local.entity.toDomain
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.TransportType
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.presentation.chat.ChatUiEvent
import com.nexa.app.presentation.chat.ChatViewModel
import com.nexa.app.presentation.chat.components.highlightSearchQuery
import com.nexa.app.presentation.chat.components.isSameCalendarDay
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class FindInChatUnitTests {

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
    // 1. Text Search: Scoped, Partial, Case-Insensitive, Unicode
    // =========================================================================

    @Test
    fun searchMessages_exactAndPartialMatch_returnsExpectedMessages() = runTest {
        val repo = FakeChatRepository()
        val cId = "chat-1"

        repo.addMessage(createMessage("m1", cId, "Hello Alice, how are you?", 1000L))
        repo.addMessage(createMessage("m2", cId, "Meeting rescheduled to 5pm", 2000L))
        repo.addMessage(createMessage("m3", cId, "alice let's review the PR", 3000L))

        // Search "Alice" (case-insensitive partial match)
        val results = repo.searchMessagesInConversation(cId, "alice")
        assertEquals(2, results.size)
        // Ordered by timestamp DESC
        assertEquals("m3", results[0].packetId)
        assertEquals("m1", results[1].packetId)
    }

    @Test
    fun searchMessages_conversationIsolation_onlySearchesActiveChat() = runTest {
        val repo = FakeChatRepository()

        repo.addMessage(createMessage("m1", "chat-A", "Secret project plan", 1000L))
        repo.addMessage(createMessage("m2", "chat-B", "Secret project plan in Chat B", 2000L))

        val resultsA = repo.searchMessagesInConversation("chat-A", "Secret")
        assertEquals(1, resultsA.size)
        assertEquals("m1", resultsA[0].packetId)

        val resultsB = repo.searchMessagesInConversation("chat-B", "Secret")
        assertEquals(1, resultsB.size)
        assertEquals("m2", resultsB[0].packetId)
    }

    @Test
    fun searchMessages_incomingAndOutgoing_returnsBoth() = runTest {
        val repo = FakeChatRepository()
        val cId = "chat-group"

        repo.addMessage(createMessage("m1", cId, "Incoming budget report", 1000L, isOutgoing = false))
        repo.addMessage(createMessage("m2", cId, "Outgoing budget approval", 2000L, isOutgoing = true))

        val results = repo.searchMessagesInConversation(cId, "budget")
        assertEquals(2, results.size)
    }

    @Test
    fun searchMessages_unicodeAndMultilingual_returnsMatches() = runTest {
        val repo = FakeChatRepository()
        val cId = "chat-multi"

        repo.addMessage(createMessage("m1", cId, "नमस्ते, आप कैसे हैं?", 1000L))
        repo.addMessage(createMessage("m2", cId, "Meeting at the Café de Paris", 2000L))
        repo.addMessage(createMessage("m3", cId, "こんにちは世界", 3000L))
        repo.addMessage(createMessage("m4", cId, "E2EE security key 🔐 verified", 4000L))

        // Hindi test
        val hindiResults = repo.searchMessagesInConversation(cId, "नमस्ते")
        assertEquals(1, hindiResults.size)
        assertEquals("m1", hindiResults[0].packetId)

        // Accent test
        val accentResults = repo.searchMessagesInConversation(cId, "café")
        assertEquals(1, accentResults.size)
        assertEquals("m2", accentResults[0].packetId)

        // Japanese test
        val japaneseResults = repo.searchMessagesInConversation(cId, "こんにちは")
        assertEquals(1, japaneseResults.size)
        assertEquals("m3", japaneseResults[0].packetId)

        // Emoji test
        val emojiResults = repo.searchMessagesInConversation(cId, "🔐")
        assertEquals(1, emojiResults.size)
        assertEquals("m4", emojiResults[0].packetId)
    }

    @Test
    fun searchMessages_emptyQuery_returnsEmptyList() = runTest {
        val repo = FakeChatRepository()
        repo.addMessage(createMessage("m1", "chat-1", "Any message", 1000L))

        val emptyResults = repo.searchMessagesInConversation("chat-1", "")
        assertTrue(emptyResults.isEmpty())

        val whitespaceResults = repo.searchMessagesInConversation("chat-1", "   ")
        assertTrue(whitespaceResults.isEmpty())
    }

    @Test
    fun searchMessages_noMatch_returnsEmptyList() = runTest {
        val repo = FakeChatRepository()
        repo.addMessage(createMessage("m1", "chat-1", "Hello World", 1000L))

        val results = repo.searchMessagesInConversation("chat-1", "NonExistentTerm")
        assertTrue(results.isEmpty())
    }

    // =========================================================================
    // 2. Date-Based Navigation & Timestamp Range Calculation
    // =========================================================================

    @Test
    fun getFirstMessageOnDate_dateWithMessages_returnsFirstMessageChronologically() = runTest {
        val repo = FakeChatRepository()
        val cId = "chat-date"
        val zoneId = ZoneId.systemDefault()

        val date = LocalDate.of(2026, 9, 10)
        val startOfDay = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endOfDay = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        // Message earlier on that day
        repo.addMessage(createMessage("m1", cId, "Morning message on 10 Sep", startOfDay + 3600000L))
        // Message later on that day
        repo.addMessage(createMessage("m2", cId, "Evening message on 10 Sep", startOfDay + 43200000L))
        // Message on next day
        repo.addMessage(createMessage("m3", cId, "Message on 11 Sep", endOfDay + 1000L))

        val first = repo.getFirstMessageOnDate(cId, startOfDay, endOfDay)
        assertNotNull(first)
        assertEquals("m1", first!!.packetId)
    }

    @Test
    fun getFirstMessageOnDate_dateWithNoMessages_returnsNull() = runTest {
        val repo = FakeChatRepository()
        val cId = "chat-date"
        val zoneId = ZoneId.systemDefault()

        val date = LocalDate.of(2026, 9, 10)
        val startOfDay = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endOfDay = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        // Message exists on day before
        repo.addMessage(createMessage("m0", cId, "9 Sep message", startOfDay - 1000L))
        // Message exists on day after
        repo.addMessage(createMessage("m2", cId, "11 Sep message", endOfDay + 1000L))

        val first = repo.getFirstMessageOnDate(cId, startOfDay, endOfDay)
        assertNull(first)
    }

    @Test
    fun isSameCalendarDay_correctlyDifferentiatesDays() {
        val zoneId = ZoneId.systemDefault()
        val date1 = LocalDate.of(2026, 9, 10)
        val morning = date1.atTime(9, 0).atZone(zoneId).toInstant().toEpochMilli()
        val night = date1.atTime(23, 59).atZone(zoneId).toInstant().toEpochMilli()
        val nextDayMidnight = date1.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        assertTrue(isSameCalendarDay(morning, night))
        assertFalse(isSameCalendarDay(night, nextDayMidnight))
    }

    // =========================================================================
    // 3. ViewModel Integration: Query Debounce, Navigation, and Highlighting
    // =========================================================================

    @Test
    fun chatViewModel_onSearchQueryChanged_debouncesAndUpdatesResults() = runTest {
        val repo = FakeChatRepository()
        val cId = "conv-search"
        repo.addMessage(createMessage("m1", cId, "Alpha report", 1000L))
        repo.addMessage(createMessage("m2", cId, "Beta alpha review", 2000L))

        val viewModel = ChatViewModel(chatRepository = repo)
        viewModel.onEvent(ChatUiEvent.Init(cId, "NX-BOB", "Bob"))
        advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.SetSearchActive(true))
        assertTrue(viewModel.uiState.value.isSearchActive)

        // Type search query
        viewModel.onEvent(ChatUiEvent.OnSearchQueryChanged("alpha"))
        assertEquals("alpha", viewModel.uiState.value.searchQuery)

        // Advance debounce time (250ms)
        advanceTimeBy(300L)
        advanceUntilIdle()

        val results = viewModel.uiState.value.searchResults
        assertEquals(2, results.size)
        assertEquals("m2", results[0].packetId)
        assertEquals("m1", results[1].packetId)
    }

    @Test
    fun chatViewModel_searchMatchCycling_updatesMatchIndexAndEmitsTarget() = runTest {
        val repo = FakeChatRepository()
        val cId = "conv-cycle"
        repo.addMessage(createMessage("m1", cId, "Target keyword 1", 1000L))
        repo.addMessage(createMessage("m2", cId, "Target keyword 2", 2000L))
        repo.addMessage(createMessage("m3", cId, "Target keyword 3", 3000L))

        val viewModel = ChatViewModel(chatRepository = repo)
        viewModel.onEvent(ChatUiEvent.Init(cId, "NX-BOB", "Bob"))
        advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.SetSearchActive(true))
        viewModel.onEvent(ChatUiEvent.OnSearchQueryChanged("Target"))
        advanceTimeBy(300L)
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.searchResults.size)
        assertEquals(0, viewModel.uiState.value.searchMatchIndex)

        // Next match
        viewModel.onEvent(ChatUiEvent.NextSearchMatch)
        assertEquals(1, viewModel.uiState.value.searchMatchIndex)

        viewModel.onEvent(ChatUiEvent.NextSearchMatch)
        assertEquals(2, viewModel.uiState.value.searchMatchIndex)

        // Wraps around to 0
        viewModel.onEvent(ChatUiEvent.NextSearchMatch)
        assertEquals(0, viewModel.uiState.value.searchMatchIndex)

        // Previous match wraps back to 2
        viewModel.onEvent(ChatUiEvent.PreviousSearchMatch)
        assertEquals(2, viewModel.uiState.value.searchMatchIndex)
    }

    @Test
    fun chatViewModel_selectSearchResult_closesSearchAndNavigates() = runTest {
        val repo = FakeChatRepository()
        val cId = "conv-select"
        val targetMsg = createMessage("m1", cId, "Navigate to this message", 1000L)
        repo.addMessage(targetMsg)

        val viewModel = ChatViewModel(chatRepository = repo)
        viewModel.onEvent(ChatUiEvent.Init(cId, "NX-BOB", "Bob"))
        advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.SetSearchActive(true))
        assertTrue(viewModel.uiState.value.isSearchActive)

        var navigatedPacketId: String? = null
        val collectJob = launch {
            viewModel.navigateToMessageFlow.collect { navigatedPacketId = it }
        }

        viewModel.onEvent(ChatUiEvent.SelectSearchResult(targetMsg))
        advanceTimeBy(50L)

        assertFalse(viewModel.uiState.value.isSearchActive)
        assertEquals("m1", navigatedPacketId)
        assertEquals("m1", viewModel.uiState.value.highlightedMessageId)

        // Advance past 1500ms to verify highlight is cleared
        advanceTimeBy(1600L)
        assertNull(viewModel.uiState.value.highlightedMessageId)

        collectJob.cancel()
    }

    @Test
    fun chatViewModel_selectDate_whenFound_navigatesAndClosesSearch() = runTest {
        val repo = FakeChatRepository()
        val cId = "conv-date"
        val zoneId = ZoneId.systemDefault()
        val date = LocalDate.of(2026, 9, 10)
        val epochMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli()

        val dayMsg = createMessage("m10", cId, "First message on Sep 10", epochMillis + 1800000L)
        repo.addMessage(dayMsg)

        val viewModel = ChatViewModel(chatRepository = repo)
        viewModel.onEvent(ChatUiEvent.Init(cId, "NX-BOB", "Bob"))
        advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.SetSearchActive(true))
        viewModel.onEvent(ChatUiEvent.SetDatePickerVisible(true))

        var navigatedPacketId: String? = null
        val collectJob = launch {
            viewModel.navigateToMessageFlow.collect { navigatedPacketId = it }
        }

        viewModel.onEvent(ChatUiEvent.SelectDate(epochMillis))
        advanceTimeBy(50L)

        assertFalse(viewModel.uiState.value.isSearchActive)
        assertFalse(viewModel.uiState.value.showDatePickerDialog)
        assertEquals("m10", navigatedPacketId)
        assertEquals("m10", viewModel.uiState.value.highlightedMessageId)

        // Advance past 1500ms to verify highlight is cleared
        advanceTimeBy(1600L)
        assertNull(viewModel.uiState.value.highlightedMessageId)

        collectJob.cancel()
    }

    @Test
    fun chatViewModel_selectDate_whenNoMessages_setsErrorMessage() = runTest {
        val repo = FakeChatRepository()
        val cId = "conv-date-empty"
        val zoneId = ZoneId.systemDefault()
        val date = LocalDate.of(2026, 1, 1)
        val epochMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli()

        val viewModel = ChatViewModel(chatRepository = repo)
        viewModel.onEvent(ChatUiEvent.Init(cId, "NX-BOB", "Bob"))
        advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.SelectDate(epochMillis))
        advanceUntilIdle()

        assertEquals("No messages found on this date.", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.showDatePickerDialog)
    }

    // =========================================================================
    // 4. Highlight Search Query Function
    // =========================================================================

    @Test
    fun highlightSearchQuery_caseInsensitive_highlightsAllOccurrences() {
        val text = "The Quick brown fox jumps over the quick dog"
        val query = "quick"
        val color = Color.Cyan

        val annotated = highlightSearchQuery(text, query, color)
        assertEquals(text, annotated.text)

        // Verify span styles applied
        val spans = annotated.spanStyles
        assertEquals(2, spans.size)
        assertEquals(4, spans[0].start)
        assertEquals(9, spans[0].end)
        assertEquals(color, spans[0].item.color)

        assertEquals(35, spans[1].start)
        assertEquals(40, spans[1].end)
        assertEquals(color, spans[1].item.color)
    }

    // =========================================================================
    // Test Helpers & Fakes
    // =========================================================================

    private fun createMessage(
        packetId: String,
        conversationId: String,
        text: String,
        timestamp: Long,
        isOutgoing: Boolean = true
    ): Message {
        return Message(
            packetId = packetId,
            conversationId = conversationId,
            senderNxId = if (isOutgoing) "NX-ME" else "NX-PEER",
            recipientNxId = if (isOutgoing) "NX-PEER" else "NX-ME",
            plainTextContent = text,
            status = MessageStatus.SENT,
            timestamp = timestamp,
            isOutgoing = isOutgoing,
            transportType = TransportType.INTERNET
        )
    }

    private inner class FakeChatRepository : ChatRepository {
        val messages = mutableListOf<Message>()
        private val _connState = MutableStateFlow<ConnectionState>(ConnectionState.Connected)

        fun addMessage(msg: Message) {
            messages.removeAll { it.packetId == msg.packetId }
            messages.add(msg)
        }

        override fun getMessages(conversationId: String): Flow<List<Message>> {
            return flowOf(messages.filter { it.conversationId == conversationId }.sortedBy { it.timestamp })
        }

        override fun getConversations(): Flow<List<ChatConversation>> = flowOf(emptyList())

        override suspend fun sendMessage(
            recipientNxId: String,
            text: String,
            transport: TransportType,
            destinationPhone: String?
        ): Result<Message> {
            val msg = createMessage("gen-${System.currentTimeMillis()}", "chat", text, System.currentTimeMillis())
            addMessage(msg)
            return Result.success(msg)
        }

        override suspend fun markConversationRead(conversationId: String) {}

        override suspend fun createOrGetConversation(
            participantNxId: String,
            participantName: String?
        ): ChatConversation {
            return ChatConversation("chat", participantNxId, participantName ?: "", "", 0L)
        }

        override fun startConnection() {}
        override fun stopConnection() {}
        override fun getConnectionState(): StateFlow<ConnectionState> = _connState.asStateFlow()
        override suspend fun handleIncomingDataSmsPacket(packet: NexaWirePacket) {}
        override fun sendTypingIndicator(recipientNxId: String, conversationId: String, isTyping: Boolean) {}
        override fun getTypingEvents(): Flow<Pair<String, Boolean>> = flowOf()

        override suspend fun getMessageById(packetId: String): Message? {
            return messages.firstOrNull { it.packetId == packetId }
        }

        override suspend fun searchMessagesInConversation(conversationId: String, query: String): List<Message> {
            val trimmed = query.trim()
            if (trimmed.isBlank()) return emptyList()
            return messages
                .filter { it.conversationId == conversationId && it.plainTextContent.contains(trimmed, ignoreCase = true) }
                .sortedByDescending { it.timestamp }
        }

        override suspend fun getFirstMessageOnDate(
            conversationId: String,
            startOfDay: Long,
            endOfDay: Long
        ): Message? {
            return messages
                .filter { it.conversationId == conversationId && it.timestamp in startOfDay until endOfDay }
                .minByOrNull { it.timestamp }
        }

        override suspend fun getMessagesOnDate(
            conversationId: String,
            startOfDay: Long,
            endOfDay: Long
        ): List<Message> {
            return messages
                .filter { it.conversationId == conversationId && it.timestamp in startOfDay until endOfDay }
                .sortedBy { it.timestamp }
        }
    }
}
