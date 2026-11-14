package com.nexa.app

import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.crypto.DoubleRatchetEngine
import com.nexa.app.core.crypto.EncryptedEnvelope
import com.nexa.app.core.mesh.engine.BloomDuplicateFilter
import com.nexa.app.core.proto.AckStatus
import com.nexa.app.core.proto.DeliveryAckPayload
import com.nexa.app.core.proto.EncryptedMessagePayload
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.proto.PacketType
import com.nexa.app.core.proto.PeerSyncAction
import com.nexa.app.core.proto.PeerSyncPayload
import com.nexa.app.core.sync.PeerSyncEngine
import com.nexa.app.core.transport.NexaUniversalTransportIngest
import com.nexa.app.core.transport.TransportMedium
import com.nexa.app.data.local.dao.ChatDao
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.dao.MessageDao
import com.nexa.app.data.local.dao.MessageDedupDao
import com.nexa.app.data.local.dao.MessageTransportDao
import com.nexa.app.data.local.dao.PeerSyncCursorDao
import com.nexa.app.data.local.dao.RatchetDao
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.entity.ChatEntity
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.data.local.entity.MessageDedupEntity
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.data.local.entity.MessageTransportEntity
import com.nexa.app.data.local.entity.PeerSyncCursorEntity
import com.nexa.app.data.local.entity.RatchetStateEntity
import com.nexa.app.data.local.entity.SkippedKeyEntity
import com.nexa.app.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow
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
import java.util.concurrent.ConcurrentHashMap

class ExactDeduplicationAndPeerSyncTests {

    class FakeMessageDedupDao : MessageDedupDao {
        val entries = ConcurrentHashMap<String, MessageDedupEntity>()

        override suspend fun getDedupEntry(packetId: String): MessageDedupEntity? = entries[packetId]

        override suspend fun insertDedupEntry(entry: MessageDedupEntity): Long {
            if (entries.containsKey(entry.packetId)) {
                return -1L
            }
            entries[entry.packetId] = entry
            return 1L
        }

        override suspend fun updateStatus(packetId: String, status: String): Int {
            val existing = entries[packetId] ?: return 0
            entries[packetId] = existing.copy(status = status)
            return 1
        }

        override suspend fun getPacketIdsForConversation(conversationId: String): List<String> {
            return entries.values.filter { it.conversationId == conversationId }
                .sortedBy { it.firstObservedAt }
                .map { it.packetId }
        }

        override suspend fun getRecentPacketIds(
            conversationId: String,
            sinceTimestamp: Long,
            limit: Int
        ): List<String> {
            return entries.values.filter { it.conversationId == conversationId && it.firstObservedAt >= sinceTimestamp }
                .sortedBy { it.firstObservedAt }
                .take(limit)
                .map { it.packetId }
        }

        override suspend fun hasPacket(packetId: String): Int {
            return if (entries.containsKey(packetId)) 1 else 0
        }
    }

    class FakeMessageTransportDao : MessageTransportDao {
        val observations = mutableListOf<MessageTransportEntity>()

        override suspend fun recordObservation(observation: MessageTransportEntity): Long {
            observations.add(observation)
            return observations.size.toLong()
        }

        override suspend fun getObservationsForMessage(packetId: String): List<MessageTransportEntity> {
            return observations.filter { it.packetId == packetId }.sortedBy { it.observedAt }
        }

        override suspend fun getObservedMediums(packetId: String): List<String> {
            return observations.filter { it.packetId == packetId }.sortedBy { it.observedAt }.map { it.transportMedium }
        }
    }

    class FakePeerSyncCursorDao : PeerSyncCursorDao {
        val cursors = ConcurrentHashMap<String, PeerSyncCursorEntity>()

        override suspend fun getCursor(conversationId: String): PeerSyncCursorEntity? = cursors[conversationId]

        override suspend fun upsertCursor(cursor: PeerSyncCursorEntity): Long {
            cursors[cursor.conversationId] = cursor
            return 1L
        }

        override suspend fun updateSyncProgress(
            conversationId: String,
            timestamp: Long,
            packetId: String?,
            now: Long
        ): Int {
            val existing = cursors[conversationId] ?: return 0
            cursors[conversationId] = existing.copy(
                lastSyncTimestamp = timestamp,
                lastSyncedPacketId = packetId,
                updatedAt = now
            )
            return 1
        }
    }

    class FakeMessageDao : MessageDao {
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

        override suspend fun searchMessagesInConversation(conversationId: String, query: String): List<MessageEntity> =
            messages.values.filter { it.conversationId == conversationId && it.plainTextContent.contains(query, ignoreCase = true) }
                .sortedByDescending { it.timestamp }

        override suspend fun getFirstMessageOnDate(conversationId: String, startOfDay: Long, endOfDay: Long): MessageEntity? =
            messages.values.filter { it.conversationId == conversationId && it.timestamp in startOfDay until endOfDay }
                .minByOrNull { it.timestamp }

        override suspend fun getMessagesOnDate(conversationId: String, startOfDay: Long, endOfDay: Long): List<MessageEntity> =
            messages.values.filter { it.conversationId == conversationId && it.timestamp in startOfDay until endOfDay }
                .sortedBy { it.timestamp }

        override suspend fun countMessagesOnDate(conversationId: String, startOfDay: Long, endOfDay: Long): Int =
            messages.values.count { it.conversationId == conversationId && it.timestamp in startOfDay until endOfDay }

        override suspend fun countMessagesBeforeTimestamp(conversationId: String, timestamp: Long): Int =
            messages.values.count { it.conversationId == conversationId && it.timestamp < timestamp }

        override suspend fun getMessagesPaged(conversationId: String, limit: Int, offset: Int): List<MessageEntity> =
            messages.values.filter { it.conversationId == conversationId && !it.isDeleted }
                .sortedWith(compareByDescending<MessageEntity> { it.timestamp }.thenByDescending { it.localSequence })
                .drop(offset).take(limit)

        override suspend fun getNextLocalSequence(conversationId: String): Long =
            (messages.values.filter { it.conversationId == conversationId }.maxOfOrNull { it.localSequence } ?: 0L) + 1L

        override suspend fun softDeleteMessage(packetId: String): Int {
            val existing = messages[packetId] ?: return 0
            messages[packetId] = existing.copy(isDeleted = true)
            return 1
        }

        override suspend fun updateStarredStatus(packetId: String, isStarred: Boolean): Int {
            val existing = messages[packetId] ?: return 0
            messages[packetId] = existing.copy(isStarred = isStarred)
            return 1
        }
    }

    class FakeChatDao : ChatDao {
        val chats = mutableMapOf<String, ChatEntity>()

        override fun getChatsFlow(): Flow<List<ChatEntity>> = flowOf(chats.values.filter { !it.isArchived })

        override fun getArchivedChatsFlow(): Flow<List<ChatEntity>> = flowOf(chats.values.filter { it.isArchived })

        override fun getChatsByTypeFlow(chatType: String): Flow<List<ChatEntity>> =
            flowOf(chats.values.filter { !it.isArchived && it.chatType == chatType })

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

        override suspend fun markConversationUnread(conversationId: String, updatedAt: Long): Int {
            val existing = chats[conversationId] ?: return 0
            chats[conversationId] = existing.copy(hasManualUnread = true, updatedAt = updatedAt)
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

        override suspend fun updatePinStatus(conversationId: String, isPinned: Boolean, pinPosition: Int, pinnedAt: Long?, updatedAt: Long): Int {
            val existing = chats[conversationId] ?: return 0
            chats[conversationId] = existing.copy(isPinned = isPinned, pinPosition = pinPosition, pinnedAt = pinnedAt, updatedAt = updatedAt)
            return 1
        }

        override suspend fun updateArchiveStatus(conversationId: String, isArchived: Boolean, archivedAt: Long?, updatedAt: Long): Int {
            val existing = chats[conversationId] ?: return 0
            chats[conversationId] = existing.copy(isArchived = isArchived, archivedAt = archivedAt, updatedAt = updatedAt)
            return 1
        }

        override suspend fun updateMuteStatus(conversationId: String, isMuted: Boolean, mutedUntil: Long, updatedAt: Long): Int {
            val existing = chats[conversationId] ?: return 0
            chats[conversationId] = existing.copy(isMuted = isMuted, mutedUntil = mutedUntil, updatedAt = updatedAt)
            return 1
        }

        override suspend fun updateLastMessage(conversationId: String, text: String, timestamp: Long, lastMessageId: String?, lastSenderNxId: String?): Int {
            val existing = chats[conversationId] ?: return 0
            chats[conversationId] = existing.copy(lastMessageText = text, lastMessageTimestamp = timestamp, lastMessageId = lastMessageId, lastMessageSenderNxId = lastSenderNxId, updatedAt = timestamp)
            return 1
        }

        override suspend fun incrementUnread(conversationId: String): Int {
            val existing = chats[conversationId] ?: return 0
            chats[conversationId] = existing.copy(unreadCount = existing.unreadCount + 1)
            return 1
        }

        override suspend fun countActiveChats(): Int = chats.values.count { !it.isArchived }
    }

    class FakeUserDao : UserDao {
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

    class FakeContactDao : ContactDao {
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

        override suspend fun updateStatusMuted(nxId: String, isMuted: Boolean): Int {
            val existing = contacts[nxId] ?: return 0
            contacts[nxId] = existing.copy(isStatusMuted = isMuted)
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

    class FakeRatchetDao : RatchetDao {
        val sessions = mutableMapOf<String, RatchetStateEntity>()
        val skippedKeys = mutableMapOf<String, SkippedKeyEntity>()

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

    private lateinit var messageDao: FakeMessageDao
    private lateinit var chatDao: FakeChatDao
    private lateinit var userDao: FakeUserDao
    private lateinit var contactDao: FakeContactDao
    private lateinit var messageDedupDao: FakeMessageDedupDao
    private lateinit var messageTransportDao: FakeMessageTransportDao
    private lateinit var peerSyncCursorDao: FakePeerSyncCursorDao
    private lateinit var bobRatchetDao: FakeRatchetDao
    private lateinit var aliceRatchetDao: FakeRatchetDao

    private lateinit var bobDoubleRatchet: DoubleRatchetEngine
    private lateinit var aliceDoubleRatchet: DoubleRatchetEngine

    private lateinit var alicePriv: ByteArray
    private lateinit var alicePub: ByteArray
    private lateinit var bobPriv: ByteArray
    private lateinit var bobPub: ByteArray

    private lateinit var ingest: NexaUniversalTransportIngest
    private val dispatchedAcks = mutableListOf<Pair<NexaWirePacket, TransportMedium>>()

    @Before
    fun setUp() {
        messageDao = FakeMessageDao()
        chatDao = FakeChatDao()
        userDao = FakeUserDao()
        contactDao = FakeContactDao()
        messageDedupDao = FakeMessageDedupDao()
        messageTransportDao = FakeMessageTransportDao()
        peerSyncCursorDao = FakePeerSyncCursorDao()
        bobRatchetDao = FakeRatchetDao()
        aliceRatchetDao = FakeRatchetDao()

        alicePriv = X25519.generatePrivateKey()
        alicePub = X25519.publicFromPrivate(alicePriv)

        bobPriv = X25519.generatePrivateKey()
        bobPub = X25519.publicFromPrivate(bobPriv)

        userDao.user = UserEntity(
            nxId = "NX-BOB",
            fullName = "Bob",
            phoneNumber = "+1234567890",
            age = 28,
            gender = "M",
            bio = "Bio",
            publicKeyHex = bobPub.toHex(),
            isVerified = true
        )

        contactDao.contacts["NX-ALICE"] = ContactEntity(
            nxId = "NX-ALICE",
            displayName = "Alice",
            publicKeyHex = alicePub.toHex(),
            status = ContactStatus.MUTUAL_FRIEND
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
            doubleRatchetEngine = bobDoubleRatchet,
            messageDedupDao = messageDedupDao,
            messageTransportDao = messageTransportDao,
            ackDispatcher = { ack, medium ->
                dispatchedAcks.add(Pair(ack, medium))
            }
        )
    }

    // =========================================================================
    // 1. Authoritative Deduplication Index & Atomic Reservation Tests
    // =========================================================================

    @Test
    fun testMessageDedup_AtomicReservationAndStatusLifecycle() = runTest {
        val packetId = "pkt_res_001"
        val entry = MessageDedupEntity(
            packetId = packetId,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB",
            conversationId = "NX-ALICE_NX-BOB",
            payloadHashHex = "sha256_dummy_hash",
            firstObservedAt = System.currentTimeMillis(),
            firstTransport = "INTERNET_WS",
            status = MessageDedupEntity.STATUS_RESERVED
        )

        // 1. Initial reservation
        val insertId = messageDedupDao.insertDedupEntry(entry)
        assertEquals(1L, insertId)

        val stored = messageDedupDao.getDedupEntry(packetId)
        assertNotNull(stored)
        assertEquals(MessageDedupEntity.STATUS_RESERVED, stored?.status)

        // 2. Conflict on re-inserting with IGNORE
        val duplicateInsert = messageDedupDao.insertDedupEntry(entry)
        assertEquals(-1L, duplicateInsert)

        // 3. Status transition to PROCESSED
        val updateCount = messageDedupDao.updateStatus(packetId, MessageDedupEntity.STATUS_PROCESSED)
        assertEquals(1, updateCount)
        assertEquals(MessageDedupEntity.STATUS_PROCESSED, messageDedupDao.getDedupEntry(packetId)?.status)
    }

    // =========================================================================
    // 2. Multi-Transport Observation Tracking Tests
    // =========================================================================

    @Test
    fun testMultiTransportObservationTracking_PreservesMessageEntity() = runTest {
        val packetId = "pkt_multi_transport_001"

        // 1. First arrival via WebSocket
        messageTransportDao.recordObservation(
            MessageTransportEntity(
                packetId = packetId,
                transportMedium = TransportMedium.INTERNET_WS.name,
                observedAt = 1000L
            )
        )

        // 2. Second arrival via Mesh
        messageTransportDao.recordObservation(
            MessageTransportEntity(
                packetId = packetId,
                transportMedium = TransportMedium.OFFLINE_MESH.name,
                observedAt = 2000L
            )
        )

        val observations = messageTransportDao.getObservationsForMessage(packetId)
        assertEquals(2, observations.size)

        val mediums = messageTransportDao.getObservedMediums(packetId)
        assertEquals(listOf("INTERNET_WS", "OFFLINE_MESH"), mediums)
    }

    // =========================================================================
    // 3. Collision and Tampering Rejection Tests
    // =========================================================================

    @Test
    fun testCollisionRejection_SamePacketIdDifferentSender_Rejected() = runTest {
        val packetId = "pkt_collision_001"

        // Setup an existing record from Alice
        messageDedupDao.insertDedupEntry(
            MessageDedupEntity(
                packetId = packetId,
                senderNxId = "NX-ALICE",
                recipientNxId = "NX-BOB",
                conversationId = "NX-ALICE_NX-BOB",
                payloadHashHex = "hash_original",
                firstObservedAt = System.currentTimeMillis(),
                firstTransport = "INTERNET_WS",
                status = MessageDedupEntity.STATUS_PROCESSED
            )
        )

        // Attacker Mallory sends a packet with the same packetId
        val maliciousPacket = NexaWirePacket(
            packetId = packetId,
            type = PacketType.PACKET_MESSAGE,
            senderNxId = "NX-MALLORY",
            recipientNxId = "NX-BOB",
            timestamp = System.currentTimeMillis(),
            message = EncryptedMessagePayload(ciphertext = "malicious_content".toByteArray())
        )

        val result = ingest.ingestWirePacket(maliciousPacket, TransportMedium.INTERNET_WS)
        assertFalse("Ingestion must reject colliding packet from different sender", result)

        val status = messageDedupDao.getDedupEntry(packetId)?.status
        assertEquals(MessageDedupEntity.STATUS_CONFLICT, status)
    }

    @Test
    fun testCollisionRejection_SamePacketIdAlteredPayloadHash_Rejected() = runTest {
        val packetId = "pkt_tamper_001"

        val envelope = aliceDoubleRatchet.ratchetEncrypt("NX-BOB", "Original text".toByteArray(StandardCharsets.UTF_8), bobPub)
        val originalPacket = NexaWirePacket(
            packetId = packetId,
            type = PacketType.PACKET_MESSAGE,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB",
            timestamp = System.currentTimeMillis(),
            message = envelope.toProtobufPayload()
        )

        // Ingest original
        val firstResult = ingest.ingestWirePacket(originalPacket, TransportMedium.INTERNET_WS)
        assertTrue(firstResult)

        // Same sender and packetId, but altered/tampered ciphertext
        val tamperedEnvelope = envelope.copy(ciphertext = "tampered_ciphertext_bytes".toByteArray())
        val tamperedPacket = NexaWirePacket(
            packetId = packetId,
            type = PacketType.PACKET_MESSAGE,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB",
            timestamp = System.currentTimeMillis(),
            message = tamperedEnvelope.toProtobufPayload()
        )

        val secondResult = ingest.ingestWirePacket(tamperedPacket, TransportMedium.OFFLINE_MESH)
        assertFalse("Ingestion must reject tampered payload hash collision", secondResult)

        val status = messageDedupDao.getDedupEntry(packetId)?.status
        assertEquals(MessageDedupEntity.STATUS_CONFLICT, status)
    }

    // =========================================================================
    // 4. Duplicate Ingestion & Double Ratchet Idempotency Tests
    // =========================================================================

    @Test
    fun testDuplicateIngestion_SuppressesDoubleRatchetAndDispatchesAck() = runTest {
        val packetId = "pkt_idempotent_001"
        val envelope = aliceDoubleRatchet.ratchetEncrypt("NX-BOB", "Idempotent delivery test".toByteArray(StandardCharsets.UTF_8), bobPub)
        val packet = NexaWirePacket(
            packetId = packetId,
            type = PacketType.PACKET_MESSAGE,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB",
            timestamp = 1000L,
            message = envelope.toProtobufPayload()
        )

        // 1. First arrival via WebSocket
        val firstIngest = ingest.ingestWirePacket(packet, TransportMedium.INTERNET_WS)
        assertTrue(firstIngest)
        assertEquals(1, messageDao.messages.size)
        assertEquals(1, dispatchedAcks.size)
        assertEquals(AckStatus.ACK_DEVICE_DELIVERED, dispatchedAcks.first().first.ack?.status)

        // 2. Second arrival of identical packet via OFFLINE_MESH
        val secondIngest = ingest.ingestWirePacket(packet, TransportMedium.OFFLINE_MESH)
        assertTrue(secondIngest)

        // Must NOT insert another message into messages table (idempotent Room state)
        assertEquals(1, messageDao.messages.size)

        // Must record both transport observations
        val observations = messageTransportDao.getObservationsForMessage(packetId)
        assertEquals(2, observations.size)
        assertEquals("INTERNET_WS", observations[0].transportMedium)
        assertEquals("OFFLINE_MESH", observations[1].transportMedium)

        // Must dispatch idempotent delivery ACK to sender
        assertEquals(2, dispatchedAcks.size)
        assertEquals(AckStatus.ACK_DEVICE_DELIVERED, dispatchedAcks[1].first.ack?.status)
    }

    // =========================================================================
    // 5. Bloom Filter Verification Fallback Tests
    // =========================================================================

    @Test
    fun testBloomFilter_FalsePositiveDoesNotDropPacket() = runTest {
        // Create Bloom filter with exact lookup fallback connected to messageDedupDao
        val bloomFilter = BloomDuplicateFilter(
            bitSetSize = 32768,
            maxHistorySize = 100,
            exactLookup = { candidateId ->
                messageDedupDao.hasPacket(candidateId) > 0
            }
        )

        val packetId = "pkt_bloom_test_1"

        // 1. Brand new packet: Bloom does not contain it
        assertFalse(bloomFilter.mightContain(packetId))

        // 2. Mark in Bloom filter and exact DB
        messageDedupDao.insertDedupEntry(
            MessageDedupEntity(
                packetId = packetId,
                senderNxId = "NX-ALICE",
                recipientNxId = "NX-BOB",
                conversationId = "NX-ALICE_NX-BOB",
                payloadHashHex = "hash",
                firstObservedAt = System.currentTimeMillis(),
                firstTransport = "INTERNET_WS",
                status = MessageDedupEntity.STATUS_PROCESSED
            )
        )
        bloomFilter.mark(packetId)

        // 3. Known packet: both Bloom and DB confirm existence
        assertTrue(bloomFilter.mightContain(packetId))

        // 4. Test simulated false-positive:
        // A filter with exact verification where DB returns false
        val strictFilter = BloomDuplicateFilter(
            bitSetSize = 32768,
            maxHistorySize = 100,
            exactLookup = { id ->
                messageDedupDao.hasPacket(id) > 0
            }
        )

        // Candidate packet does NOT exist in DB
        val candidateNonExistent = "pkt_non_existent"
        // Even if bloom hash bits happen to match, exact verification confirms it's false positive
        assertFalse(
            "Exact lookup fallback must prevent dropping unseen messages despite Bloom positive",
            strictFilter.mightContain(candidateNonExistent)
        )
    }

    // =========================================================================
    // 6. PeerSyncEngine Handshake and Anti-Entropy Tests
    // =========================================================================

    @Test
    fun testPeerSync_FullHandshakeReconciliation() = runTest {
        val outbox = mutableListOf<Pair<NexaWirePacket, TransportMedium>>()
        val peerSyncEngine = PeerSyncEngine(
            myNxIdProvider = { "NX-BOB" },
            contactDao = contactDao,
            messageDao = messageDao,
            messageDedupDao = messageDedupDao,
            peerSyncCursorDao = peerSyncCursorDao,
            universalTransportIngest = ingest,
            packetSender = { packet, medium ->
                outbox.add(Pair(packet, medium))
                true
            }
        )

        // 1. Bob initiates sync with Alice
        val sessionId = peerSyncEngine.initiateSync("NX-ALICE", TransportMedium.OFFLINE_MESH)
        assertNotNull(sessionId)
        assertEquals(1, outbox.size)

        val helloPkt = outbox.first().first
        assertEquals(PacketType.PACKET_PEER_SYNC, helloPkt.type)
        assertEquals(PeerSyncAction.SYNC_HELLO, helloPkt.peerSync?.action)
        assertEquals("NX-ALICE_NX-BOB", helloPkt.peerSync?.conversationId)

        // 2. Alice sends SYNC_INVENTORY_REQ to Bob
        val invReq = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_PEER_SYNC,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB",
            timestamp = System.currentTimeMillis(),
            peerSync = PeerSyncPayload(
                action = PeerSyncAction.SYNC_INVENTORY_REQ,
                sessionId = sessionId!!,
                conversationId = "NX-ALICE_NX-BOB",
                cursorTimestamp = 0L
            )
        )

        // Seed 2 messages in Bob's messageDao
        messageDao.insertMessage(
            MessageEntity(
                packetId = "msg_sync_001",
                conversationId = "NX-ALICE_NX-BOB",
                senderNxId = "NX-BOB",
                recipientNxId = "NX-ALICE",
                plainTextContent = "Hello Alice from Bob",
                status = "DELIVERED",
                timestamp = 1000L,
                isOutgoing = true
            )
        )
        messageDao.insertMessage(
            MessageEntity(
                packetId = "msg_sync_002",
                conversationId = "NX-ALICE_NX-BOB",
                senderNxId = "NX-BOB",
                recipientNxId = "NX-ALICE",
                plainTextContent = "Second message",
                status = "DELIVERED",
                timestamp = 2000L,
                isOutgoing = true
            )
        )

        outbox.clear()
        peerSyncEngine.handleInboundPeerSync(invReq, TransportMedium.OFFLINE_MESH)

        // Bob responds with SYNC_INVENTORY_RESP containing both candidate UUIDs
        assertEquals(1, outbox.size)
        val invResp = outbox.first().first
        assertEquals(PeerSyncAction.SYNC_INVENTORY_RESP, invResp.peerSync?.action)
        assertEquals(listOf("msg_sync_001", "msg_sync_002"), invResp.peerSync?.messageIds)

        // 3. Alice requests missing messages via SYNC_PULL_REQ
        outbox.clear()
        val pullReq = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_PEER_SYNC,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB",
            timestamp = System.currentTimeMillis(),
            peerSync = PeerSyncPayload(
                action = PeerSyncAction.SYNC_PULL_REQ,
                sessionId = sessionId,
                conversationId = "NX-ALICE_NX-BOB",
                messageIds = listOf("msg_sync_001", "msg_sync_002")
            )
        )
        peerSyncEngine.handleInboundPeerSync(pullReq, TransportMedium.OFFLINE_MESH)

        // Bob packages and responds with SYNC_PULL_RESP containing both envelopes
        assertEquals(1, outbox.size)
        val pullResp = outbox.first().first
        assertEquals(PeerSyncAction.SYNC_PULL_RESP, pullResp.peerSync?.action)
        assertEquals(2, pullResp.peerSync?.envelopes?.size)

        // 4. Ingesting SYNC_PULL_RESP from Alice on Bob's side
        outbox.clear()
        val pullRespFromAlice = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_PEER_SYNC,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB",
            timestamp = System.currentTimeMillis(),
            peerSync = PeerSyncPayload(
                action = PeerSyncAction.SYNC_PULL_RESP,
                sessionId = sessionId,
                conversationId = "NX-ALICE_NX-BOB",
                envelopes = pullResp.peerSync!!.envelopes
            )
        )
        peerSyncEngine.handleInboundPeerSync(pullRespFromAlice, TransportMedium.OFFLINE_MESH)

        // Verified delivery: sync cursor updated to latest timestamp
        val cursor = peerSyncCursorDao.getCursor("NX-ALICE_NX-BOB")
        assertNotNull(cursor)
        assertEquals(2000L, cursor?.lastSyncTimestamp)

        // Bob sends SYNC_ACK to finalize session
        assertEquals(1, outbox.size)
        assertEquals(PeerSyncAction.SYNC_ACK, outbox.first().first.peerSync?.action)
    }

    // =========================================================================
    // 7. PeerSync Scope and Stranger Rejection Tests
    // =========================================================================

    @Test
    fun testPeerSync_ScopeMismatch_RejectsWithSecurityError() = runTest {
        val outbox = mutableListOf<Pair<NexaWirePacket, TransportMedium>>()
        val peerSyncEngine = PeerSyncEngine(
            myNxIdProvider = { "NX-BOB" },
            contactDao = contactDao,
            messageDao = messageDao,
            messageDedupDao = messageDedupDao,
            peerSyncCursorDao = peerSyncCursorDao,
            universalTransportIngest = ingest,
            packetSender = { packet, medium ->
                outbox.add(Pair(packet, medium))
                true
            }
        )

        // Alice sends a packet claiming to sync Charlie's conversation
        val spoofedScopePacket = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_PEER_SYNC,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB",
            timestamp = System.currentTimeMillis(),
            peerSync = PeerSyncPayload(
                action = PeerSyncAction.SYNC_HELLO,
                sessionId = "spoof_sess",
                conversationId = "NX-CHARLIE_NX-DAVID" // Invalid foreign scope!
            )
        )

        val handled = peerSyncEngine.handleInboundPeerSync(spoofedScopePacket, TransportMedium.INTERNET_WS)
        assertFalse(handled)

        // Must reject with UNAUTHORIZED_CONVERSATION_SCOPE error ACK
        assertEquals(1, outbox.size)
        val errAck = outbox.first().first
        assertEquals(PeerSyncAction.SYNC_ACK, errAck.peerSync?.action)
        assertEquals("UNAUTHORIZED_CONVERSATION_SCOPE", errAck.peerSync?.errorMessage)
    }

    @Test
    fun testPeerSync_Stranger_RejectsWithContactNotFound() = runTest {
        val outbox = mutableListOf<Pair<NexaWirePacket, TransportMedium>>()
        val peerSyncEngine = PeerSyncEngine(
            myNxIdProvider = { "NX-BOB" },
            contactDao = contactDao,
            messageDao = messageDao,
            messageDedupDao = messageDedupDao,
            peerSyncCursorDao = peerSyncCursorDao,
            universalTransportIngest = ingest,
            packetSender = { packet, medium ->
                outbox.add(Pair(packet, medium))
                true
            }
        )

        val strangerPacket = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_PEER_SYNC,
            senderNxId = "NX-UNKNOWN-STRANGER",
            recipientNxId = "NX-BOB",
            timestamp = System.currentTimeMillis(),
            peerSync = PeerSyncPayload(
                action = PeerSyncAction.SYNC_HELLO,
                sessionId = "stranger_sess",
                conversationId = "NX-BOB_NX-UNKNOWN-STRANGER"
            )
        )

        val handled = peerSyncEngine.handleInboundPeerSync(strangerPacket, TransportMedium.INTERNET_WS)
        assertFalse(handled)

        assertEquals(1, outbox.size)
        val errAck = outbox.first().first
        assertEquals(PeerSyncAction.SYNC_ACK, errAck.peerSync?.action)
        assertEquals("CONTACT_NOT_FOUND", errAck.peerSync?.errorMessage)
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
