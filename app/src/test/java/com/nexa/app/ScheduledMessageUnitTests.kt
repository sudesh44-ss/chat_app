package com.nexa.app

import com.nexa.app.core.network.ConnectionState
import com.nexa.app.data.local.dao.ScheduledMessageDao
import com.nexa.app.data.local.entity.ScheduledMessageEntity
import com.nexa.app.data.local.entity.toDomain
import com.nexa.app.data.local.entity.toEntity
import com.nexa.app.data.manager.ScheduledMessageManagerImpl
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.ScheduledMessage
import com.nexa.app.domain.model.ScheduledMessageStatus
import com.nexa.app.domain.model.TransportType
import com.nexa.app.domain.repository.ChatRepository
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
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledMessageUnitTests {

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
    fun scheduledMessageEntity_toDomainAndBack_preservesAllFields() {
        val entity = ScheduledMessageEntity(
            id = "sched-001",
            messageId = "msg-001",
            chatId = "conv-123",
            senderId = "NX-ALICE",
            recipientId = "NX-BOB",
            messageType = "TEXT",
            content = "Hey Bob, reminder for the sync tomorrow!",
            attachmentReference = null,
            scheduledAt = 1770000000000L,
            createdAt = 1769990000000L,
            status = ScheduledMessageStatus.SCHEDULED.name,
            sentAt = null,
            cancelledAt = null,
            replyToMessageId = "rep-99",
            replyToSenderNxId = "NX-BOB",
            replyToSnippet = "See you soon",
            transportType = TransportType.INTERNET.name,
            destinationPhone = null,
            failureReason = null
        )

        val domain = entity.toDomain()
        assertEquals("sched-001", domain.id)
        assertEquals("msg-001", domain.messageId)
        assertEquals("conv-123", domain.chatId)
        assertEquals("NX-ALICE", domain.senderId)
        assertEquals("NX-BOB", domain.recipientId)
        assertEquals("Hey Bob, reminder for the sync tomorrow!", domain.content)
        assertEquals(ScheduledMessageStatus.SCHEDULED, domain.status)
        assertEquals("rep-99", domain.replyToMessageId)
        assertEquals(TransportType.INTERNET, domain.transportType)

        val backToEntity = domain.toEntity()
        assertEquals(entity, backToEntity)
    }

    // =========================================================================
    // 2. Future-Time Validation
    // =========================================================================

    @Test
    fun scheduleMessage_inThePast_rejectedWithIllegalArgumentException() = runTest {
        val dao = FakeScheduledMessageDao()
        val chatRepo = FakeChatRepository()
        val manager = ScheduledMessageManagerImpl(scheduledMessageDao = dao, chatRepository = chatRepo, ioDispatcher = testDispatcher)

        val pastTimestamp = System.currentTimeMillis() - 10000L
        val result = manager.scheduleMessage(
            chatId = "conv-1",
            recipientId = "NX-BOB",
            content = "This is from the past",
            scheduledAtUtcMillis = pastTimestamp
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, dao.entities.size)
    }

    @Test
    fun scheduleMessage_withBlankContent_rejectedWithIllegalArgumentException() = runTest {
        val dao = FakeScheduledMessageDao()
        val chatRepo = FakeChatRepository()
        val manager = ScheduledMessageManagerImpl(scheduledMessageDao = dao, chatRepository = chatRepo, ioDispatcher = testDispatcher)

        val futureTimestamp = System.currentTimeMillis() + 3600000L
        val result = manager.scheduleMessage(
            chatId = "conv-1",
            recipientId = "NX-BOB",
            content = "   ",
            scheduledAtUtcMillis = futureTimestamp
        )

        assertTrue(result.isFailure)
        assertEquals(0, dao.entities.size)
    }

    @Test
    fun scheduleMessage_inFuture_succeedsAndPersistsScheduledState() = runTest {
        val dao = FakeScheduledMessageDao()
        val chatRepo = FakeChatRepository()
        val manager = ScheduledMessageManagerImpl(scheduledMessageDao = dao, chatRepository = chatRepo, ioDispatcher = testDispatcher)

        val futureTimestamp = System.currentTimeMillis() + 7200000L
        val result = manager.scheduleMessage(
            chatId = "conv-1",
            recipientId = "NX-BOB",
            content = "Meeting at 5pm",
            scheduledAtUtcMillis = futureTimestamp,
            replyToMessageId = "orig-123"
        )

        assertTrue(result.isSuccess)
        val scheduled = result.getOrNull()
        assertNotNull(scheduled)
        assertEquals(ScheduledMessageStatus.SCHEDULED, scheduled!!.status)
        assertEquals("conv-1", scheduled.chatId)
        assertEquals("NX-ME", scheduled.senderId)
        assertEquals("orig-123", scheduled.replyToMessageId)
        assertEquals(1, dao.entities.size)
    }

    // =========================================================================
    // 3. Critical Rule: Scheduled Message Must Remain the Last Message
    // =========================================================================

    @Test
    fun cancelPendingSchedulesForChat_cancelsOnlyTargetChat() = runTest {
        val dao = FakeScheduledMessageDao()
        val chatRepo = FakeChatRepository()
        val manager = ScheduledMessageManagerImpl(scheduledMessageDao = dao, chatRepository = chatRepo, ioDispatcher = testDispatcher)

        val future = System.currentTimeMillis() + 3600000L
        manager.scheduleMessage("chat-A", "NX-BOB", "Msg 1 in Chat A", future)
        manager.scheduleMessage("chat-A", "NX-BOB", "Msg 2 in Chat A", future + 1000L)
        manager.scheduleMessage("chat-B", "NX-CHARLIE", "Msg 1 in Chat B", future)

        assertEquals(3, dao.entities.size)

        // Normal outgoing message committed in chat-A -> cancel chat-A schedules
        val cancelledCount = manager.cancelPendingSchedulesForChat("chat-A")
        assertEquals(2, cancelledCount)

        val chatASchedules = dao.entities.filter { it.chatId == "chat-A" }
        assertTrue(chatASchedules.all { it.status == ScheduledMessageStatus.CANCELLED.name })
        assertTrue(chatASchedules.all { it.cancelledAt != null })

        // Chat B must NOT be cancelled
        val chatBSchedules = dao.entities.filter { it.chatId == "chat-B" }
        assertEquals(1, chatBSchedules.size)
        assertEquals(ScheduledMessageStatus.SCHEDULED.name, chatBSchedules[0].status)
    }

    // =========================================================================
    // 4. Two-Tier Concurrency & Race Condition Defense
    // =========================================================================

    @Test
    fun executeScheduledMessage_abortsIfNewerNormalMessageWasSent() = runTest {
        val dao = FakeScheduledMessageDao()
        val chatRepo = FakeChatRepository()
        val manager = ScheduledMessageManagerImpl(scheduledMessageDao = dao, chatRepository = chatRepo, ioDispatcher = testDispatcher)

        val pastInstant = System.currentTimeMillis() - 50L
        // Insert a schedule whose instant has arrived
        val scheduleId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis() - 2000L
        dao.insert(
            ScheduledMessageEntity(
                id = scheduleId,
                messageId = UUID.randomUUID().toString(),
                chatId = "conv-race",
                senderId = "NX-ALICE",
                recipientId = "NX-BOB",
                content = "Scheduled text",
                scheduledAt = pastInstant,
                createdAt = createdAt,
                status = ScheduledMessageStatus.SCHEDULED.name
            )
        )

        // Simulate: User sent a normal message after the schedule was created
        dao.simulatedNewerNormalCount = 1

        val execResult = manager.executeScheduledMessage(scheduleId)
        assertTrue(execResult.isSuccess)
        // Returns false indicating transmission was aborted without sending
        assertFalse(execResult.getOrNull()!!)

        // Schedule must now be marked CANCELLED in DB
        val updated = dao.getById(scheduleId)
        assertNotNull(updated)
        assertEquals(ScheduledMessageStatus.CANCELLED.name, updated!!.status)
        assertNotNull(updated.cancelledAt)
        // No message should have been sent through the repository
        assertEquals(0, chatRepo.sentMessages.size)
    }

    @Test
    fun executeScheduledMessage_succeedsWhenNoNewerNormalMessage() = runTest {
        val dao = FakeScheduledMessageDao()
        val chatRepo = FakeChatRepository()
        val manager = ScheduledMessageManagerImpl(scheduledMessageDao = dao, chatRepository = chatRepo, ioDispatcher = testDispatcher)

        val pastInstant = System.currentTimeMillis() - 50L
        val scheduleId = UUID.randomUUID().toString()
        dao.insert(
            ScheduledMessageEntity(
                id = scheduleId,
                messageId = UUID.randomUUID().toString(),
                chatId = "conv-valid",
                senderId = "NX-ALICE",
                recipientId = "NX-BOB",
                content = "On-time scheduled delivery",
                scheduledAt = pastInstant,
                createdAt = System.currentTimeMillis() - 1000L,
                status = ScheduledMessageStatus.SCHEDULED.name
            )
        )

        dao.simulatedNewerNormalCount = 0

        val execResult = manager.executeScheduledMessage(scheduleId)
        assertTrue(execResult.isSuccess)
        assertTrue(execResult.getOrNull()!!)

        // Status updated to SENT
        val updated = dao.getById(scheduleId)
        assertNotNull(updated)
        assertEquals(ScheduledMessageStatus.SENT.name, updated!!.status)
        assertNotNull(updated.sentAt)

        // Verified message passed to chatRepository
        assertEquals(1, chatRepo.sentMessages.size)
        assertEquals("On-time scheduled delivery", chatRepo.sentMessages[0].second)
    }

    @Test
    fun executeScheduledMessage_atomicClaimPreventsDuplicateExecution() = runTest {
        val dao = FakeScheduledMessageDao()
        val chatRepo = FakeChatRepository()
        val manager = ScheduledMessageManagerImpl(scheduledMessageDao = dao, chatRepository = chatRepo, ioDispatcher = testDispatcher)

        val scheduleId = UUID.randomUUID().toString()
        dao.insert(
            ScheduledMessageEntity(
                id = scheduleId,
                messageId = UUID.randomUUID().toString(),
                chatId = "conv-dup",
                senderId = "NX-ALICE",
                recipientId = "NX-BOB",
                content = "Only send once",
                scheduledAt = System.currentTimeMillis() - 100L,
                createdAt = System.currentTimeMillis() - 1000L,
                status = ScheduledMessageStatus.SCHEDULED.name
            )
        )

        // Worker 1 runs and claims message
        val res1 = manager.executeScheduledMessage(scheduleId)
        assertTrue(res1.isSuccess)
        assertTrue(res1.getOrNull()!!)
        assertEquals(1, chatRepo.sentMessages.size)

        // Worker 2 runs concurrently on the same schedule
        val res2 = manager.executeScheduledMessage(scheduleId)
        assertTrue(res2.isSuccess)
        // Already sent, skips transmission
        assertEquals(1, chatRepo.sentMessages.size)
    }

    // =========================================================================
    // 5. Offline & Network Failure Handling
    // =========================================================================

    @Test
    fun executeScheduledMessage_onNetworkFailure_marksPendingForRetry() = runTest {
        val dao = FakeScheduledMessageDao()
        val chatRepo = FakeChatRepository().apply { failNextSend = true }
        val manager = ScheduledMessageManagerImpl(scheduledMessageDao = dao, chatRepository = chatRepo, ioDispatcher = testDispatcher)

        val scheduleId = UUID.randomUUID().toString()
        dao.insert(
            ScheduledMessageEntity(
                id = scheduleId,
                messageId = UUID.randomUUID().toString(),
                chatId = "conv-offline",
                senderId = "NX-ALICE",
                recipientId = "NX-BOB",
                content = "Offline scheduled text",
                scheduledAt = System.currentTimeMillis() - 50L,
                createdAt = System.currentTimeMillis() - 1000L,
                status = ScheduledMessageStatus.SCHEDULED.name
            )
        )

        val execResult = manager.executeScheduledMessage(scheduleId)
        assertTrue(execResult.isFailure)

        // Preserved as PENDING with failure reason
        val updated = dao.getById(scheduleId)
        assertNotNull(updated)
        assertEquals(ScheduledMessageStatus.PENDING.name, updated!!.status)
        assertEquals("Simulated network timeout", updated.failureReason)
    }

    @Test
    fun reconcilePendingSchedules_invalidatesSupersededSchedules() = runTest {
        val dao = FakeScheduledMessageDao()
        val chatRepo = FakeChatRepository()
        val manager = ScheduledMessageManagerImpl(scheduledMessageDao = dao, chatRepository = chatRepo, ioDispatcher = testDispatcher)

        val s1 = UUID.randomUUID().toString()
        val s2 = UUID.randomUUID().toString()
        dao.insert(
            ScheduledMessageEntity(
                id = s1,
                messageId = UUID.randomUUID().toString(),
                chatId = "chat-1",
                senderId = "NX-ALICE",
                recipientId = "NX-BOB",
                content = "Superceded msg",
                scheduledAt = System.currentTimeMillis() + 50000L,
                createdAt = System.currentTimeMillis() - 2000L,
                status = ScheduledMessageStatus.SCHEDULED.name
            )
        )
        dao.insert(
            ScheduledMessageEntity(
                id = s2,
                messageId = UUID.randomUUID().toString(),
                chatId = "chat-2",
                senderId = "NX-ALICE",
                recipientId = "NX-CHARLIE",
                content = "Active msg",
                scheduledAt = System.currentTimeMillis() + 60000L,
                createdAt = System.currentTimeMillis() - 1000L,
                status = ScheduledMessageStatus.SCHEDULED.name
            )
        )

        // Chat-1 has newer normal message
        dao.chatSpecificNewerCounts["chat-1"] = 1
        dao.chatSpecificNewerCounts["chat-2"] = 0

        manager.reconcilePendingSchedules()

        assertEquals(ScheduledMessageStatus.CANCELLED.name, dao.getById(s1)!!.status)
        assertEquals(ScheduledMessageStatus.SCHEDULED.name, dao.getById(s2)!!.status)
    }

    // =========================================================================
    // 6. User Cancellation
    // =========================================================================

    @Test
    fun cancelScheduledMessage_byUser_updatesStatusToCancelled() = runTest {
        val dao = FakeScheduledMessageDao()
        val chatRepo = FakeChatRepository()
        val manager = ScheduledMessageManagerImpl(scheduledMessageDao = dao, chatRepository = chatRepo, ioDispatcher = testDispatcher)

        val future = System.currentTimeMillis() + 3600000L
        val res = manager.scheduleMessage("conv-c", "NX-BOB", "To be cancelled", future)
        val id = res.getOrNull()!!.id

        val cancelled = manager.cancelScheduledMessage(id)
        assertTrue(cancelled)

        val updated = dao.getById(id)
        assertNotNull(updated)
        assertEquals(ScheduledMessageStatus.CANCELLED.name, updated!!.status)
        assertEquals("Cancelled by user", updated.failureReason)
    }

    // =========================================================================
    // 7. ViewModel Integration Tests
    // =========================================================================

    @Test
    fun chatViewModel_scheduleCurrentMessage_dispatchesScheduleAndClearsInput() = runTest {
        val dao = FakeScheduledMessageDao()
        val chatRepo = FakeChatRepository()
        val manager = ScheduledMessageManagerImpl(scheduledMessageDao = dao, chatRepository = chatRepo, ioDispatcher = testDispatcher)

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            scheduledMessageManager = manager
        )

        viewModel.onEvent(
            ChatUiEvent.Init(
                conversationId = "conv-vm",
                participantNxId = "NX-PEER",
                participantName = "Peer"
            )
        )
        advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.OnInputTextChanged("Draft scheduled message"))
        assertEquals("Draft scheduled message", viewModel.uiState.value.inputText)

        val future = System.currentTimeMillis() + 3600000L
        viewModel.onEvent(ChatUiEvent.ScheduleCurrentMessage(future))
        advanceUntilIdle()

        // Input should be cleared and schedule created
        assertEquals("", viewModel.uiState.value.inputText)
        assertEquals(1, dao.entities.size)
        assertEquals("Draft scheduled message", dao.entities[0].content)
        assertEquals("conv-vm", dao.entities[0].chatId)
    }

    @Test
    fun chatViewModel_cancelScheduledMessage_invokesManager() = runTest {
        val dao = FakeScheduledMessageDao()
        val chatRepo = FakeChatRepository()
        val manager = ScheduledMessageManagerImpl(scheduledMessageDao = dao, chatRepository = chatRepo, ioDispatcher = testDispatcher)

        val future = System.currentTimeMillis() + 3600000L
        val res = manager.scheduleMessage("conv-vm", "NX-PEER", "To cancel", future)
        val id = res.getOrNull()!!.id

        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            scheduledMessageManager = manager
        )

        viewModel.onEvent(ChatUiEvent.CancelScheduledMessage(id))
        advanceUntilIdle()

        val updated = dao.getById(id)
        assertNotNull(updated)
        assertEquals(ScheduledMessageStatus.CANCELLED.name, updated!!.status)
    }

    // =========================================================================
    // Test Fakes
    // =========================================================================

    private class FakeScheduledMessageDao : ScheduledMessageDao {
        val entities = mutableListOf<ScheduledMessageEntity>()
        var simulatedNewerNormalCount: Int = 0
        val chatSpecificNewerCounts = mutableMapOf<String, Int>()

        override suspend fun insert(entity: ScheduledMessageEntity): Long {
            entities.removeAll { it.id == entity.id || it.messageId == entity.messageId }
            entities.add(entity)
            return 1L
        }

        override suspend fun update(entity: ScheduledMessageEntity): Int {
            val idx = entities.indexOfFirst { it.id == entity.id }
            return if (idx >= 0) {
                entities[idx] = entity
                1
            } else 0
        }

        override suspend fun getById(id: String): ScheduledMessageEntity? {
            return entities.firstOrNull { it.id == id }
        }

        override suspend fun getByMessageId(messageId: String): ScheduledMessageEntity? {
            return entities.firstOrNull { it.messageId == messageId }
        }

        override fun observeActiveSchedulesForChat(chatId: String): Flow<List<ScheduledMessageEntity>> {
            return flowOf(entities.filter { it.chatId == chatId && it.status in listOf("SCHEDULED", "PENDING") })
        }

        override fun observeAllSchedulesForChat(chatId: String): Flow<List<ScheduledMessageEntity>> {
            return flowOf(entities.filter { it.chatId == chatId })
        }

        override suspend fun getActiveSchedulesForChat(chatId: String): List<ScheduledMessageEntity> {
            return entities.filter { it.chatId == chatId && it.status in listOf("SCHEDULED", "PENDING") }
        }

        override suspend fun getAllActiveSchedules(): List<ScheduledMessageEntity> {
            return entities.filter { it.status in listOf("SCHEDULED", "PENDING") }
        }

        override suspend fun cancelActiveSchedulesForChat(chatId: String, cancelledAt: Long, reason: String): Int {
            var count = 0
            val updated = entities.map {
                if (it.chatId == chatId && it.status in listOf("SCHEDULED", "PENDING")) {
                    count++
                    it.copy(status = ScheduledMessageStatus.CANCELLED.name, cancelledAt = cancelledAt, failureReason = reason)
                } else it
            }
            entities.clear()
            entities.addAll(updated)
            return count
        }

        override suspend fun cancelScheduleById(id: String, cancelledAt: Long, reason: String): Int {
            val idx = entities.indexOfFirst { it.id == id && it.status in listOf("SCHEDULED", "PENDING") }
            return if (idx >= 0) {
                entities[idx] = entities[idx].copy(
                    status = ScheduledMessageStatus.CANCELLED.name,
                    cancelledAt = cancelledAt,
                    failureReason = reason
                )
                1
            } else 0
        }

        override suspend fun claimForSending(id: String, now: Long): Int {
            val idx = entities.indexOfFirst { it.id == id && it.status in listOf("SCHEDULED", "PENDING") && it.scheduledAt <= now }
            return if (idx >= 0) {
                entities[idx] = entities[idx].copy(status = ScheduledMessageStatus.SENDING.name)
                1
            } else 0
        }

        override suspend fun markSent(id: String, sentAt: Long): Int {
            val idx = entities.indexOfFirst { it.id == id }
            return if (idx >= 0) {
                entities[idx] = entities[idx].copy(status = ScheduledMessageStatus.SENT.name, sentAt = sentAt)
                1
            } else 0
        }

        override suspend fun markPending(id: String, reason: String?): Int {
            val idx = entities.indexOfFirst { it.id == id }
            return if (idx >= 0) {
                entities[idx] = entities[idx].copy(status = ScheduledMessageStatus.PENDING.name, failureReason = reason)
                1
            } else 0
        }

        override suspend fun markFailed(id: String, reason: String?): Int {
            val idx = entities.indexOfFirst { it.id == id }
            return if (idx >= 0) {
                entities[idx] = entities[idx].copy(status = ScheduledMessageStatus.FAILED.name, failureReason = reason)
                1
            } else 0
        }

        override suspend fun countNewerNormalOutgoingMessages(chatId: String, createdAt: Long): Int {
            return chatSpecificNewerCounts[chatId] ?: simulatedNewerNormalCount
        }

        override suspend fun deleteById(id: String): Int {
            val removed = entities.removeAll { it.id == id }
            return if (removed) 1 else 0
        }
    }

    private class FakeChatRepository : ChatRepository {
        val sentMessages = mutableListOf<Pair<String, String>>()
        var failNextSend = false

        override fun getMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())
        override fun getConversations(): Flow<List<ChatConversation>> = flowOf(emptyList())

        override suspend fun sendMessage(
            recipientNxId: String,
            text: String,
            transport: TransportType,
            destinationPhone: String?
        ): Result<Message> = sendMessage(recipientNxId, text, transport, destinationPhone, null, false)

        override suspend fun sendMessage(
            recipientNxId: String,
            text: String,
            transport: TransportType,
            destinationPhone: String?,
            replyToMessageId: String?
        ): Result<Message> = sendMessage(recipientNxId, text, transport, destinationPhone, replyToMessageId, false)

        override suspend fun sendMessage(
            recipientNxId: String,
            text: String,
            transport: TransportType,
            destinationPhone: String?,
            replyToMessageId: String?,
            isScheduledDispatch: Boolean
        ): Result<Message> {
            if (failNextSend) {
                return Result.failure(Exception("Simulated network timeout"))
            }
            sentMessages.add(Pair(recipientNxId, text))
            val msg = Message(
                packetId = UUID.randomUUID().toString(),
                conversationId = "conv-$recipientNxId",
                senderNxId = "NX-ALICE",
                recipientNxId = recipientNxId,
                plainTextContent = text,
                status = MessageStatus.SENT,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true,
                replyToMessageId = replyToMessageId
            )
            return Result.success(msg)
        }

        override suspend fun markConversationRead(conversationId: String) {}
        override suspend fun createOrGetConversation(participantNxId: String, participantName: String?): ChatConversation {
            return ChatConversation("conv-$participantNxId", participantNxId, participantName ?: participantNxId, "", 0L, 0)
        }
        override fun startConnection() {}
        override fun stopConnection() {}
        override fun getConnectionState(): StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected).asStateFlow()
        override suspend fun handleIncomingDataSmsPacket(packet: com.nexa.app.core.proto.NexaWirePacket) {}
        override fun sendTypingIndicator(recipientNxId: String, conversationId: String, isTyping: Boolean) {}
        override fun getTypingEvents(): Flow<Pair<String, Boolean>> = flowOf()
    }
}
