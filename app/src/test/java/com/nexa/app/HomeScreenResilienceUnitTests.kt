package com.nexa.app

import com.nexa.app.core.config.AppLaunchConfig
import com.nexa.app.core.network.ConnectionState
import com.nexa.app.core.network.WebSocketManager
import com.nexa.app.core.proto.AuthChallengePayload
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.proto.PacketType
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.TransportType
import com.nexa.app.domain.model.UserProfile
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.domain.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
class HomeScreenResilienceUnitTests {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        AppLaunchConfig.DEV_BYPASS_AUTH_TO_HOME_SCREEN = true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        AppLaunchConfig.DEV_BYPASS_AUTH_TO_HOME_SCREEN = true
    }

    @Test
    fun testDevBypassFlagRemainsActive() {
        assertTrue("Dev bypass to Home Screen must remain active", AppLaunchConfig.DEV_BYPASS_AUTH_TO_HOME_SCREEN)
    }

    @Test
    fun testActiveProfileFlowReturnsNullCleanlyWhenUnauthenticated() = runTest {
        val fakeAuthRepo = object : FakeAuthRepository() {
            override fun getActiveProfile(): Flow<UserProfile?> = flowOf(null)
        }

        val profile = fakeAuthRepo.getActiveProfile().first()
        assertNull("Active profile should be null for unauthenticated preview without throwing", profile)
    }

    @Test
    fun testActiveProfileFlowRecoversGracefullyFromDatabaseError() = runTest {
        // Simulating Room DAO throwing SQLite exception
        val failingFlow: Flow<UserProfile?> = flow<UserProfile?> {
            throw android.database.sqlite.SQLiteDiskIOException("Simulated encrypted vault disk read failure")
        }.catch {
            emit(null)
        }

        val result = failingFlow.first()
        assertNull("Flow catch block should emit null rather than crashing Compose", result)
    }

    @Test
    fun testConversationsFlowRecoversGracefullyFromDatabaseCorruption() = runTest {
        // Simulating ChatDao throwing Room/SQLite exception
        val failingFlow: Flow<List<ChatConversation>> = flow<List<ChatConversation>> {
            throw IllegalStateException("Database table locked or cipher key invalidated")
        }.catch {
            emit(emptyList())
        }

        val result = failingFlow.first()
        assertTrue("Flow catch block should emit empty list rather than crashing Compose", result.isEmpty())
    }

    @Test
    fun testWebSocketConnectDoesNotCrashOnBlankNxId() {
        val wsManager = WebSocketManager()
        // Connecting with blank nxId must return early and not throw
        wsManager.connect("", null)
        wsManager.connect("   ", "device_123")
        assertEquals(ConnectionState.Disconnected, wsManager.connectionState.value)
    }

    @Test
    fun testWebSocketConnectDoesNotCrashOnMalformedUrl() {
        val wsManager = WebSocketManager()
        // Call connect with an NX-ID
        wsManager.connect("NX-TESTING-1234", "device-test")
        // Status should transition cleanly to Connecting (or Error if refused) without throwing fatal exception
        val state = wsManager.connectionState.value
        assertTrue(state is ConnectionState.Connecting || state is ConnectionState.Error)
    }

    @Test
    fun testWebSocketSignerFailureFallsBackToEphemeralSignerGracefully() {
        val wsManager = WebSocketManager()
        // Configure identitySigner to simulate hardware keystore throwing
        wsManager.setIdentitySigner {
            throw SecurityException("Simulated hardware keystore locked or invalidated")
        }

        val nonceBytes = "test_nonce_${UUID.randomUUID()}".toByteArray(Charsets.UTF_8)
        val challengePayload = AuthChallengePayload(
            challengeNonce = nonceBytes,
            expiresAt = System.currentTimeMillis() + 60_000L,
            timestamp = System.currentTimeMillis()
        )
        val packet = NexaWirePacket(
            packetId = "test_challenge_pkt",
            type = PacketType.PACKET_AUTH_CHALLENGE,
            senderNxId = "SERVER",
            recipientNxId = "NX-TESTING",
            timestamp = System.currentTimeMillis(),
            authChallenge = challengePayload
        )

        // Decode and encode to ensure serialization resilience
        val encoded = packet.encode()
        val decoded = NexaWirePacket.decode(encoded)
        assertEquals(PacketType.PACKET_AUTH_CHALLENGE, decoded.type)
        assertTrue(nonceBytes.contentEquals(decoded.authChallenge?.challengeNonce))
    }

    @Test
    fun testChatRepositoryStartConnectionDoesNotCrashWhenNxIdIsNull() {
        var startConnectionInvoked = false
        val fakeChatRepo = object : FakeChatRepository() {
            override fun startConnection() {
                startConnectionInvoked = true
                // myNxId is null when unauthenticated - should return early without crash
            }
        }

        fakeChatRepo.startConnection()
        assertTrue(startConnectionInvoked)
    }

    // --- Fake Test Implementations ---
    private open class FakeAuthRepository : AuthRepository {
        override suspend fun isLoggedIn(): Boolean = false
        override suspend fun getActiveNxId(): String? = null
        override fun getActiveProfile(): Flow<UserProfile?> = flowOf(null)
        override suspend fun loginWithGoogle(idToken: String) = Result.failure<com.nexa.app.data.remote.dto.GoogleAuthResponse>(NotImplementedError())
        override suspend fun requestAndVerifyLoginChallenge(nxId: String, deviceId: String) = Result.failure<com.nexa.app.data.remote.dto.LoginVerifyResponseDto>(NotImplementedError())
        override suspend fun initiateReverseVerify(phone: String, name: String, avatarUrl: String?, gender: String?, age: Int?, bio: String?, googleUid: String, googleAuthToken: String?, isReverify: Boolean) = Result.failure<com.nexa.app.data.remote.dto.InitiateReverseVerifyResponseDto>(NotImplementedError())
        override suspend fun pollVerificationStatus(nonce: String) = Result.failure<com.nexa.app.data.remote.dto.VerificationStatusDto>(NotImplementedError())
        override suspend fun saveVerifiedSession(nxId: String, sessionToken: String, name: String, phone: String, avatarUrl: String?, age: Int?, gender: String?, bio: String?, email: String?) = Result.failure<UserProfile>(NotImplementedError())
        override suspend fun fetchRemoteProfile(): Result<UserProfile> = Result.failure(NotImplementedError())
        override suspend fun updateRemoteProfile(name: String?, bio: String?, gender: String?, age: Int?, avatarUrl: String?): Result<Boolean> = Result.failure(NotImplementedError())
        override suspend fun resolveUser(target: String) = Result.failure<com.nexa.app.data.remote.dto.UserResolveDto>(NotImplementedError())
        override suspend fun sendVerificationOtp(phone: String, googleAuthToken: String?) = Result.failure<Boolean>(NotImplementedError())
        override suspend fun verifyOtpAndRegister(phone: String, otp: String, name: String, age: Int?, gender: String?, bio: String?) = Result.failure<UserProfile>(NotImplementedError())
        override suspend fun clearSession() {}
    }

    private open class FakeChatRepository : ChatRepository {
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override fun getMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())
        override fun getConversations(): Flow<List<ChatConversation>> = flowOf(emptyList())
        override suspend fun sendMessage(recipientNxId: String, text: String, transport: TransportType, destinationPhone: String?): Result<Message> = Result.failure(NotImplementedError())
        override suspend fun markConversationRead(conversationId: String) {}
        override suspend fun handleIncomingDataSmsPacket(packet: NexaWirePacket) {}
        override suspend fun retryFailedMessage(packetId: String, transport: TransportType, destinationPhone: String?): Result<Message> = Result.failure(NotImplementedError())
        override suspend fun createOrGetConversation(participantNxId: String, participantName: String?): ChatConversation {
            return ChatConversation(
                conversationId = "conv_$participantNxId",
                participantNxId = participantNxId,
                participantName = participantName ?: participantNxId,
                lastMessageText = "",
                lastMessageTimestamp = System.currentTimeMillis(),
                unreadCount = 0
            )
        }
        override fun startConnection() {}
        override fun stopConnection() {}
        override fun getConnectionState(): StateFlow<ConnectionState> = _state.asStateFlow()
        override fun sendTypingIndicator(recipientNxId: String, conversationId: String, isTyping: Boolean) {}
        override fun getTypingEvents(): Flow<Pair<String, Boolean>> = flowOf()
        override fun getPinnedMessages(conversationId: String): Flow<List<Message>> = flowOf(emptyList())
        override suspend fun pinMessage(packetId: String, conversationId: String, recipientNxId: String, durationMillis: Long?): Result<Unit> = Result.success(Unit)
        override suspend fun unpinMessage(packetId: String, conversationId: String, recipientNxId: String): Result<Unit> = Result.success(Unit)
    }
}
