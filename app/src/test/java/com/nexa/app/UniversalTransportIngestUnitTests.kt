package com.nexa.app

import com.nexa.app.core.crypto.DoubleRatchetEngine
import com.nexa.app.core.crypto.EncryptedEnvelope
import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.dtn.BlindTokenGenerator
import com.nexa.app.core.dtn.DtnConstants
import com.nexa.app.core.dtn.DtnEnvelope
import com.nexa.app.core.proto.AckStatus
import com.nexa.app.core.proto.DeliveryAckPayload
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.proto.PacketType
import com.nexa.app.core.proto.TypingPayload
import com.nexa.app.core.transport.NexaUniversalTransportIngest
import com.nexa.app.core.transport.TransportMedium
import com.nexa.app.data.local.dao.ChatDao
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.dao.DtnEnvelopeDao
import com.nexa.app.data.local.dao.MessageDao
import com.nexa.app.data.local.dao.RatchetDao
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.entity.ChatEntity
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.data.local.entity.DtnEnvelopeEntity
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.data.local.entity.RatchetStateEntity
import com.nexa.app.data.local.entity.SkippedKeyEntity
import com.nexa.app.data.local.entity.UserEntity
import com.nexa.app.domain.model.MessageStatus
import com.nexa.app.domain.model.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.UUID

class UniversalTransportIngestUnitTests {

    private lateinit var messageDao: FakeMessageDao
    private lateinit var chatDao: FakeChatDao
    private lateinit var userDao: FakeUserDao
    private lateinit var contactDao: FakeContactDao
    private lateinit var dtnEnvelopeDao: FakeDtnEnvelopeDao
    private lateinit var bobRatchetDao: FakeRatchetDao
    private lateinit var aliceRatchetDao: FakeRatchetDao

    private lateinit var bobDoubleRatchet: DoubleRatchetEngine
    private lateinit var aliceDoubleRatchet: DoubleRatchetEngine

    private lateinit var ingest: NexaUniversalTransportIngest
    private val dispatchedAcks = mutableListOf<Pair<NexaWirePacket, TransportMedium>>()

    private val aliceNxId = "NX-ALICE-1111"
    private val bobNxId = "NX-BOB-2222"
    private val charlieNxId = "NX-CHARLIE-3333"

    private lateinit var alicePriv: ByteArray
    private lateinit var alicePub: ByteArray
    private lateinit var bobPriv: ByteArray
    private lateinit var bobPub: ByteArray

    @Before
    fun setUp() {
        messageDao = FakeMessageDao()
        chatDao = FakeChatDao()
        userDao = FakeUserDao()
        contactDao = FakeContactDao()
        dtnEnvelopeDao = FakeDtnEnvelopeDao()
        bobRatchetDao = FakeRatchetDao()
        aliceRatchetDao = FakeRatchetDao()

        alicePriv = X25519.generatePrivateKey()
        alicePub = X25519.publicFromPrivate(alicePriv)

        bobPriv = X25519.generatePrivateKey()
        bobPub = X25519.publicFromPrivate(bobPriv)

        // Configure Bob's user profile with Bob's public key
        val bobUser = UserEntity(
            nxId = bobNxId,
            fullName = "Bob Tester",
            phoneNumber = "+15551234567",
            email = "bob@example.com",
            avatarUrl = null,
            age = 28,
            gender = "Male",
            bio = "NEXA Node",
            publicKeyHex = bobPub.toHex(),
            isVerified = true
        )
        userDao.user = bobUser

        // Alice knows Bob's contact info
        contactDao.contacts[aliceNxId] = ContactEntity(
            nxId = aliceNxId,
            displayName = "Alice Tester",
            publicKeyHex = alicePub.toHex()
        )

        bobDoubleRatchet = DoubleRatchetEngine(
            ratchetDao = bobRatchetDao,
            identityKeyPairProvider = { Pair(bobPriv, bobPub) }
        )

        aliceDoubleRatchet = DoubleRatchetEngine(
            ratchetDao = aliceRatchetDao,
            identityKeyPairProvider = { Pair(alicePriv, alicePub) }
        )

        dispatchedAcks.clear()

        ingest = NexaUniversalTransportIngest(
            messageDao = messageDao,
            chatDao = chatDao,
            userDao = userDao,
            contactDao = contactDao,
            secureStorage = null,
            doubleRatchetEngine = bobDoubleRatchet,
            envelopeDao = dtnEnvelopeDao,
            notificationDispatcher = null,
            ackDispatcher = { ackPacket, medium ->
                dispatchedAcks.add(Pair(ackPacket, medium))
            }
        )
    }

    @Test
    fun testWebSocketMessageIngestion_deliversAndRecordsInternetTransport() = runTest {
        val plainText = "Hello Bob via Internet WebSocket!"
        val envelope = aliceDoubleRatchet.ratchetEncrypt(bobNxId, plainText.toByteArray(StandardCharsets.UTF_8), bobPub)

        val packetId = "ws_pkt_${UUID.randomUUID()}"
        val packet = NexaWirePacket(
            packetId = packetId,
            type = PacketType.PACKET_MESSAGE,
            senderNxId = aliceNxId,
            recipientNxId = bobNxId,
            timestamp = System.currentTimeMillis(),
            message = envelope.toProtobufPayload()
        )

        val result = ingest.ingestWirePacket(packet, TransportMedium.INTERNET_WS)
        assertTrue("Ingest must return true on valid message", result)

        val stored = messageDao.getMessageByPacketId(packetId)
        assertNotNull("Message must be stored in MessageDao", stored)
        assertEquals(packetId, stored!!.packetId)
        assertEquals(plainText, stored.plainTextContent)
        assertEquals(TransportType.INTERNET.name, stored.transportType)
        assertEquals(MessageStatus.DELIVERED.name, stored.status)
        assertFalse(stored.isOutgoing)

        // Chat conversation should be upserted
        val chat = chatDao.getChatById(stored.conversationId)
        assertNotNull("Chat conversation must be upserted", chat)
        assertEquals(1, chat!!.unreadCount)
        assertEquals(plainText, chat.lastMessageText)

        // Delivery ACK must be dispatched
        assertEquals(1, dispatchedAcks.size)
        val (ackPacket, medium) = dispatchedAcks[0]
        assertEquals(TransportMedium.INTERNET_WS, medium)
        assertEquals(PacketType.PACKET_ACK, ackPacket.type)
        assertEquals(packetId, ackPacket.ack?.originalPacketId)
        assertEquals(AckStatus.ACK_DEVICE_DELIVERED, ackPacket.ack?.status)
    }

    @Test
    fun testCellularDataSmsIngestion_recordsDataSmsTransport() = runTest {
        val plainText = "Urgent SMS fallback message"
        val envelope = aliceDoubleRatchet.ratchetEncrypt(bobNxId, plainText.toByteArray(StandardCharsets.UTF_8), bobPub)

        val packetId = "sms_pkt_${UUID.randomUUID()}"
        val packet = NexaWirePacket(
            packetId = packetId,
            type = PacketType.PACKET_MESSAGE,
            senderNxId = aliceNxId,
            recipientNxId = bobNxId,
            timestamp = System.currentTimeMillis(),
            message = envelope.toProtobufPayload()
        )

        val result = ingest.ingestWirePacket(packet, TransportMedium.CELLULAR_DATA_SMS)
        assertTrue(result)

        val stored = messageDao.getMessageByPacketId(packetId)
        assertNotNull(stored)
        assertEquals(TransportType.DATA_SMS.name, stored!!.transportType)
        assertEquals(plainText, stored.plainTextContent)
    }

    @Test
    fun testOfflineDtnMeshRecipientNode_unblindsConsumesAndRecordsMeshTransport() = runTest {
        val plainText = "P2P Mesh message across mountain valley"
        val envelope = aliceDoubleRatchet.ratchetEncrypt(bobNxId, plainText.toByteArray(StandardCharsets.UTF_8), bobPub)

        val packetId = "mesh_pkt_${UUID.randomUUID()}"
        val wirePacket = NexaWirePacket(
            packetId = packetId,
            type = PacketType.PACKET_MESSAGE,
            senderNxId = aliceNxId,
            recipientNxId = bobNxId,
            timestamp = System.currentTimeMillis(),
            message = envelope.toProtobufPayload()
        )
        val wireBytes = wirePacket.encode()

        // Alice computes Bob's blind token
        val blindToken = BlindTokenGenerator.generateToken(bobPub, BlindTokenGenerator.currentEpochDay())

        val dtnEnvelope = DtnEnvelope.createNew(
            blindToken = blindToken,
            encryptedPayload = wireBytes,
            hopLimit = 7
        )

        // Bob ingests the raw DTN envelope bytes over OFFLINE_MESH
        val result = ingest.ingestRawBytes(dtnEnvelope.serialize(), TransportMedium.OFFLINE_MESH)
        assertTrue("Recipient node must successfully unblind and ingest DTN envelope", result)

        val stored = messageDao.getMessageByPacketId(packetId)
        assertNotNull("Message must be decrypted and stored in MessageDao", stored)
        assertEquals(plainText, stored!!.plainTextContent)
        assertEquals(TransportType.DTN_MESH.name, stored.transportType)

        // Envelope must NOT remain in dtnEnvelopeDao for Bob since it was consumed
        assertNull(dtnEnvelopeDao.envelopes[dtnEnvelope.envelopeIdHex])
    }

    @Test
    fun testOfflineDtnMeshRelayNodeMulingCustody_vaultsEnvelopeWithoutDecryption() = runTest {
        // Alice sends message to Charlie (Bob is strictly a data mule)
        val charlieKey = ByteArray(32) { 0x55.toByte() }
        val charlieBlindToken = BlindTokenGenerator.generateToken(charlieKey, BlindTokenGenerator.currentEpochDay())

        val secretPayload = "Secret for Charlie only".toByteArray(StandardCharsets.UTF_8)
        val dtnEnvelope = DtnEnvelope.createNew(
            blindToken = charlieBlindToken,
            encryptedPayload = secretPayload,
            hopLimit = 7
        )

        val result = ingest.ingestRawBytes(dtnEnvelope.serialize(), TransportMedium.OFFLINE_MESH)
        assertTrue("Mule ingestion must succeed for store-and-forward custody", result)

        // Bob must NOT have decrypted or stored any message in messageDao
        assertEquals(0, messageDao.messages.size)
        assertEquals(0, chatDao.chats.size)

        // Bob MUST have vaulted the envelope in dtnEnvelopeDao with hopLimit decremented (7 -> 6)
        val vaulted = dtnEnvelopeDao.envelopes[dtnEnvelope.envelopeIdHex]
        assertNotNull("Envelope must be held in DTN vault", vaulted)
        assertEquals(6, vaulted!!.hopLimit)
        assertEquals(dtnEnvelope.blindTokenHex, vaulted.blindTokenHex)
    }

    @Test
    fun testCrossTransportDeduplication_InternetFirstThenMesh_preservesOriginalTransport() = runTest {
        val plainText = "Cross-transport redundant message"
        val envelope = aliceDoubleRatchet.ratchetEncrypt(bobNxId, plainText.toByteArray(StandardCharsets.UTF_8), bobPub)

        val packetId = "shared_packet_id_101"
        val wirePacket = NexaWirePacket(
            packetId = packetId,
            type = PacketType.PACKET_MESSAGE,
            senderNxId = aliceNxId,
            recipientNxId = bobNxId,
            timestamp = System.currentTimeMillis(),
            message = envelope.toProtobufPayload()
        )

        // 1. Packet arrives via INTERNET_WS first
        val wsResult = ingest.ingestWirePacket(wirePacket, TransportMedium.INTERNET_WS)
        assertTrue(wsResult)
        assertEquals(1, messageDao.messages.size)
        val firstRecord = messageDao.getMessageByPacketId(packetId)
        assertEquals(TransportType.INTERNET.name, firstRecord?.transportType)

        // 2. Exact same packet arrives later via OFFLINE_MESH
        val meshResult = ingest.ingestWirePacket(wirePacket, TransportMedium.OFFLINE_MESH)
        assertTrue("Deduplication gatekeeper must silently absorb duplicate delivery", meshResult)

        // Database must still contain only 1 message, retaining the original INTERNET transport badge
        assertEquals(1, messageDao.messages.size)
        val finalRecord = messageDao.getMessageByPacketId(packetId)
        assertEquals(TransportType.INTERNET.name, finalRecord?.transportType)

        // Chat unread count must remain 1
        val chat = chatDao.getChatById(finalRecord!!.conversationId)
        assertEquals(1, chat?.unreadCount)
    }

    @Test
    fun testCrossTransportDeduplication_MeshFirstThenInternet_preservesMeshBadge() = runTest {
        val plainText = "Mesh first then cloud sync"
        val envelope = aliceDoubleRatchet.ratchetEncrypt(bobNxId, plainText.toByteArray(StandardCharsets.UTF_8), bobPub)

        val packetId = "shared_packet_id_202"
        val wirePacket = NexaWirePacket(
            packetId = packetId,
            type = PacketType.PACKET_MESSAGE,
            senderNxId = aliceNxId,
            recipientNxId = bobNxId,
            timestamp = System.currentTimeMillis(),
            message = envelope.toProtobufPayload()
        )

        // 1. Packet arrives via OFFLINE_MESH first
        val meshResult = ingest.ingestWirePacket(wirePacket, TransportMedium.OFFLINE_MESH)
        assertTrue(meshResult)
        val firstRecord = messageDao.getMessageByPacketId(packetId)
        assertEquals(TransportType.DTN_MESH.name, firstRecord?.transportType)

        // 2. Later, cloud bridge dumps the same packet via INTERNET_WS
        val wsResult = ingest.ingestWirePacket(wirePacket, TransportMedium.INTERNET_WS)
        assertTrue(wsResult)

        // Database must retain the original DTN_MESH badge!
        assertEquals(1, messageDao.messages.size)
        val finalRecord = messageDao.getMessageByPacketId(packetId)
        assertEquals(TransportType.DTN_MESH.name, finalRecord?.transportType)
    }

    @Test
    fun testAckPacketUpdatesMessageStatus() = runTest {
        val targetPacketId = "outgoing_msg_999"
        messageDao.insertMessage(
            MessageEntity(
                packetId = targetPacketId,
                conversationId = "conv_1",
                senderNxId = bobNxId,
                recipientNxId = aliceNxId,
                plainTextContent = "Are you there?",
                status = MessageStatus.SENT.name,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true,
                transportType = TransportType.INTERNET.name
            )
        )

        val ackPacket = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_ACK,
            senderNxId = aliceNxId,
            recipientNxId = bobNxId,
            timestamp = System.currentTimeMillis(),
            ack = DeliveryAckPayload(
                originalPacketId = targetPacketId,
                status = AckStatus.ACK_DEVICE_DELIVERED
            )
        )

        val result = ingest.ingestWirePacket(ackPacket, TransportMedium.INTERNET_WS)
        assertTrue(result)

        val updated = messageDao.getMessageByPacketId(targetPacketId)
        assertEquals(MessageStatus.DELIVERED.name, updated?.status)
    }

    @Test
    fun testTypingIndicatorEmitted() = runTest {
        val typingPacket = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_TYPING,
            senderNxId = aliceNxId,
            recipientNxId = bobNxId,
            timestamp = System.currentTimeMillis(),
            typing = TypingPayload(
                conversationId = "conv_123",
                isTyping = true
            )
        )

        val result = ingest.ingestWirePacket(typingPacket, TransportMedium.INTERNET_WS)
        assertTrue(result)

        val event = ingest.typingEvents.first()
        assertEquals("conv_123", event.first)
        assertTrue(event.second)
    }

    @Test
    fun testMalformedAndExpiredPacketsDropped() = runTest {
        // Blank packet ID
        val blankPacket = NexaWirePacket(
            packetId = "",
            type = PacketType.PACKET_MESSAGE,
            senderNxId = aliceNxId,
            recipientNxId = bobNxId,
            timestamp = System.currentTimeMillis()
        )
        assertFalse(ingest.ingestWirePacket(blankPacket, TransportMedium.INTERNET_WS))

        // Expired DTN envelope
        val expiredEnvelope = DtnEnvelope(
            envelopeId = ByteArray(16) { 1 },
            blindToken = ByteArray(16) { 2 },
            hopLimit = 5,
            expiresAtEpochSec = (System.currentTimeMillis() / 1000L) - 100, // in the past
            encryptedPayload = ByteArray(20)
        )
        assertFalse(ingest.ingestRawBytes(expiredEnvelope.serialize(), TransportMedium.OFFLINE_MESH))

        // Zero hop limit
        val zeroHopEnvelope = DtnEnvelope(
            envelopeId = ByteArray(16) { 1 },
            blindToken = ByteArray(16) { 2 },
            hopLimit = 0,
            expiresAtEpochSec = (System.currentTimeMillis() / 1000L) + 1000,
            encryptedPayload = ByteArray(20)
        )
        assertFalse(ingest.ingestRawBytes(zeroHopEnvelope.serialize(), TransportMedium.OFFLINE_MESH))
    }

    // --- Fake Test Double Implementations ---

    private class FakeMessageDao : MessageDao {
        val messages = mutableMapOf<String, MessageEntity>()

        override fun getMessagesFlow(conversationId: String): Flow<List<MessageEntity>> =
            flowOf(messages.values.filter { it.conversationId == conversationId })

        override suspend fun insertMessage(message: MessageEntity): Long {
            messages[message.packetId] = message
            return 1L
        }

        override suspend fun insertMessageIfNotExists(message: MessageEntity): Long {
            return if (!messages.containsKey(message.packetId)) {
                messages[message.packetId] = message
                1L
            } else {
                -1L
            }
        }

        override suspend fun updateMessageStatus(packetId: String, status: String): Int {
            val existing = messages[packetId] ?: return 0
            messages[packetId] = existing.copy(status = status)
            return 1
        }

        override suspend fun getPendingOutgoingMessages(): List<MessageEntity> =
            messages.values.filter { it.status == "PENDING" && it.isOutgoing }

        override suspend fun markMessagesAsRead(conversationId: String): Int {
            var updated = 0
            messages.values.filter { it.conversationId == conversationId && !it.isOutgoing }.forEach {
                messages[it.packetId] = it.copy(status = "READ")
                updated++
            }
            return updated
        }

        override suspend fun getMessageByPacketId(packetId: String): MessageEntity? = messages[packetId]

        override suspend fun getLatestMessage(conversationId: String): MessageEntity? =
            messages.values.filter { it.conversationId == conversationId }.maxByOrNull { it.timestamp }

        override fun getPinnedMessagesFlow(conversationId: String, currentTimeMillis: Long): Flow<List<MessageEntity>> =
            flowOf(emptyList())

        override suspend fun getPinnedMessages(conversationId: String, currentTimeMillis: Long): List<MessageEntity> =
            emptyList()

        override suspend fun getPinnedMessagesCount(conversationId: String, currentTimeMillis: Long): Int = 0

        override suspend fun clearExpiredPins(conversationId: String, currentTimeMillis: Long): Int = 0

        override suspend fun updatePinStatus(packetId: String, isPinned: Boolean, pinnedExpiresAt: Long?, pinnedAt: Long?): Int {
            val existing = messages[packetId] ?: return 0
            messages[packetId] = existing.copy(isPinned = isPinned, pinnedExpiresAt = pinnedExpiresAt, pinnedAt = pinnedAt)
            return 1
        }

        override suspend fun unpinMessage(packetId: String): Int {
            val existing = messages[packetId] ?: return 0
            messages[packetId] = existing.copy(isPinned = false, pinnedExpiresAt = null, pinnedAt = null)
            return 1
        }

        override suspend fun deleteMessagesForConversation(conversationId: String): Int {
            val toRemove = messages.values.filter { it.conversationId == conversationId }.map { it.packetId }
            toRemove.forEach { messages.remove(it) }
            return toRemove.size
        }

        override suspend fun getMessageIdsAfterTimestamp(conversationId: String, afterTimestamp: Long, limit: Int): List<String> =
            messages.values.filter { it.conversationId == conversationId && it.timestamp > afterTimestamp }
                .sortedBy { it.timestamp }.take(limit).map { it.packetId }

        override suspend fun getMessagesAfterTimestamp(conversationId: String, afterTimestamp: Long, limit: Int): List<MessageEntity> =
            messages.values.filter { it.conversationId == conversationId && it.timestamp > afterTimestamp }
                .sortedBy { it.timestamp }.take(limit)
    }

    private class FakeChatDao : ChatDao {
        val chats = mutableMapOf<String, ChatEntity>()

        override fun getChatsFlow(): Flow<List<ChatEntity>> = flowOf(chats.values.toList())

        override suspend fun upsertChat(chat: ChatEntity): Long {
            chats[chat.conversationId] = chat
            return 1L
        }

        override suspend fun getChatByParticipant(nxId: String): ChatEntity? =
            chats.values.find { it.participantNxId == nxId }

        override suspend fun getChatById(conversationId: String): ChatEntity? = chats[conversationId]

        override suspend fun resetUnreadCount(conversationId: String): Int {
            val existing = chats[conversationId] ?: return 0
            chats[conversationId] = existing.copy(unreadCount = 0)
            return 1
        }

        override suspend fun deleteChat(conversationId: String): Int =
            if (chats.remove(conversationId) != null) 1 else 0

        override suspend fun updateParticipantName(nxId: String, newName: String): Int {
            val chat = getChatByParticipant(nxId) ?: return 0
            chats[chat.conversationId] = chat.copy(participantName = newName)
            return 1
        }

        override suspend fun clearLastMessageText(conversationId: String): Int {
            val existing = chats[conversationId] ?: return 0
            chats[conversationId] = existing.copy(lastMessageText = "", unreadCount = 0)
            return 1
        }
    }

    private class FakeUserDao : UserDao {
        var user: UserEntity? = null

        override suspend fun saveUserProfile(user: UserEntity): Long {
            this.user = user
            return 1L
        }

        override suspend fun getLocalProfile(): UserEntity? = user
        override fun getActiveUserFlow(): Flow<UserEntity?> = flowOf(user)
        override suspend fun getActiveNxId(): String? = user?.nxId
        override suspend fun countVerifiedUsers(): Int = if (user?.isVerified == true) 1 else 0
        override suspend fun updateFullName(nxId: String, fullName: String): Int = 1
        override suspend fun updatePhoneNumber(nxId: String, phoneNumber: String): Int = 1
        override suspend fun clearProfile(): Int {
            user = null
            return 1
        }
    }

    private class FakeContactDao : ContactDao {
        val contacts = mutableMapOf<String, ContactEntity>()

        override fun getContactsByStatus(status: ContactStatus): Flow<List<ContactEntity>> =
            flowOf(contacts.values.filter { it.status == status })

        override suspend fun getContact(nxId: String): ContactEntity? = contacts[nxId]

        override suspend fun upsertContact(contact: ContactEntity): Long {
            contacts[contact.nxId] = contact
            return 1L
        }

        override suspend fun updateStatus(nxId: String, newStatus: ContactStatus): Int {
            val existing = contacts[nxId] ?: return 0
            contacts[nxId] = existing.copy(status = newStatus)
            return 1
        }

        override suspend fun deleteContact(nxId: String): Int =
            if (contacts.remove(nxId) != null) 1 else 0

        override fun getPendingReceivedCount(): Flow<Int> = flowOf(0)

        override fun getAllContacts(): Flow<List<ContactEntity>> = flowOf(contacts.values.toList())

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

        override fun searchContacts(query: String): Flow<List<ContactEntity>> =
            flowOf(contacts.values.toList())

        override suspend fun updatePeerPhoneSharing(nxId: String, isShared: Boolean, phone: String?): Int = 1

        override suspend fun updateAvatarUrl(nxId: String, avatarUrl: String?): Int {
            val existing = contacts[nxId] ?: return 0
            contacts[nxId] = existing.copy(nexaAvatarUrl = avatarUrl)
            return 1
        }

        override suspend fun updateDeviceContactPhotoUri(nxId: String, photoUri: String?): Int = 1
    }

    private class FakeDtnEnvelopeDao : DtnEnvelopeDao {
        val envelopes = mutableMapOf<String, DtnEnvelopeEntity>()

        override suspend fun insertEnvelope(envelope: DtnEnvelopeEntity): Long {
            envelopes[envelope.envelopeIdHex] = envelope
            return 1L
        }

        override suspend fun getActiveEnvelopesForSync(currentEpochSec: Long): List<DtnEnvelopeEntity> =
            envelopes.values.filter { it.expiresAtEpochSec > currentEpochSec }

        override suspend fun findMatchingEnvelopes(tokenHex: String): List<ByteArray> =
            envelopes.values.filter { it.blindTokenHex == tokenHex }.map { it.rawEnvelopeBytes }

        override suspend fun findMatchingEnvelopesBatch(tokenHexes: List<String>): List<ByteArray> =
            envelopes.values.filter { it.blindTokenHex in tokenHexes }.map { it.rawEnvelopeBytes }

        override suspend fun purgeExpiredEnvelopes(currentEpochSec: Long): Int {
            val toRemove = envelopes.values.filter { it.expiresAtEpochSec <= currentEpochSec }.map { it.envelopeIdHex }
            toRemove.forEach { envelopes.remove(it) }
            return toRemove.size
        }

        override suspend fun pruneOldest(count: Int): Int = 0

        override suspend fun deleteEnvelope(envelopeIdHex: String): Int =
            if (envelopes.remove(envelopeIdHex) != null) 1 else 0

        override suspend fun getTotalCachedCount(): Int = envelopes.size
        override fun getCachedCountFlow(): Flow<Int> = flowOf(envelopes.size)

        override suspend fun updateCustodyState(envelopeIdHex: String, custodyState: String): Int {
            val env = envelopes[envelopeIdHex] ?: return 0
            envelopes[envelopeIdHex] = env.copy(custodyState = custodyState)
            return 1
        }

        override suspend fun markCustodyTransferred(envelopeIdHex: String, custodyState: String, nextHopNodeId: String, transferredAt: Long): Int {
            val env = envelopes[envelopeIdHex] ?: return 0
            envelopes[envelopeIdHex] = env.copy(
                custodyState = custodyState,
                nextHopNodeId = nextHopNodeId,
                transferredAtTimestamp = transferredAt
            )
            return 1
        }

        override suspend fun getEnvelopesByCustodyState(custodyState: String, currentEpochSec: Long): List<DtnEnvelopeEntity> =
            envelopes.values.filter { it.custodyState == custodyState && it.expiresAtEpochSec > currentEpochSec }

        override suspend fun getEnvelopeById(envelopeIdHex: String): DtnEnvelopeEntity? =
            envelopes[envelopeIdHex]
    }

    private class FakeRatchetDao : RatchetDao {
        private val sessions = mutableMapOf<String, RatchetStateEntity>()
        private val skippedKeys = mutableMapOf<String, SkippedKeyEntity>()

        override suspend fun upsertSession(session: RatchetStateEntity): Long {
            sessions[session.contactNxId] = session
            return 1L
        }

        override suspend fun getSession(contactNxId: String): RatchetStateEntity? = sessions[contactNxId]
        override suspend fun getSessionById(sessionId: String): RatchetStateEntity? =
            sessions.values.find { it.sessionId == sessionId }
        override suspend fun deleteSession(contactNxId: String): Int =
            if (sessions.remove(contactNxId) != null) 1 else 0

        override suspend fun insertSkippedKey(key: SkippedKeyEntity): Long {
            skippedKeys["${key.contactNxId}_${key.sequenceNumber}"] = key
            if (key.ratchetPublicKeyHex.isNotBlank()) {
                skippedKeys["${key.contactNxId}_${key.ratchetPublicKeyHex}_${key.messageIndex}"] = key
            }
            return 1L
        }

        override suspend fun getSkippedKey(contactNxId: String, seq: Int): SkippedKeyEntity? =
            skippedKeys["${contactNxId}_${seq}"]

        override suspend fun getSkippedKeyByHeader(contactNxId: String, ratchetPubHex: String, msgIdx: Int): SkippedKeyEntity? =
            skippedKeys["${contactNxId}_${ratchetPubHex}_${msgIdx}"] ?: skippedKeys["${contactNxId}_${msgIdx}"]

        override suspend fun deleteSkippedKey(contactNxId: String, seq: Int): Int =
            if (skippedKeys.remove("${contactNxId}_${seq}") != null) 1 else 0

        override suspend fun deleteSkippedKeyByHeader(contactNxId: String, ratchetPubHex: String, msgIdx: Int): Int {
            val k1 = skippedKeys.remove("${contactNxId}_${ratchetPubHex}_${msgIdx}")
            val k2 = skippedKeys.remove("${contactNxId}_${msgIdx}")
            return if (k1 != null || k2 != null) 1 else 0
        }

        override suspend fun purgeOldSkippedKeys(thresholdTimestamp: Long): Int = 0

        override suspend fun countSkippedKeys(contactNxId: String): Int =
            skippedKeys.values.count { it.contactNxId == contactNxId }
    }

    private fun ByteArray.toHex(): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(size * 2)
        for (b in this) {
            val i = b.toInt() and 0xFF
            result.append(hexChars[i ushr 4])
            result.append(hexChars[i and 0x0F])
        }
        return result.toString()
    }
}
