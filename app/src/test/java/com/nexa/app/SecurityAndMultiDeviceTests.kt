package com.nexa.app

import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.backup.Bip39PassphraseHelper
import com.nexa.app.core.crypto.DoubleRatchetEngine
import com.nexa.app.core.crypto.Ed25519Signer
import com.nexa.app.core.crypto.EncryptedEnvelope
import com.nexa.app.core.dtn.DtnConstants
import com.nexa.app.core.dtn.DtnEnvelope
import com.nexa.app.core.mesh.engine.BloomDuplicateFilter
import com.nexa.app.core.proto.EncryptedMessagePayload
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.proto.PacketType
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
import com.nexa.app.data.local.preferences.LockScreenMode
import com.nexa.app.data.local.preferences.NotificationPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import com.nexa.app.domain.model.MessageStatus
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase K-L: Comprehensive Security, Abuse Prevention & Multi-Device Verification Suite.
 */
class SecurityAndMultiDeviceTests {

    class FakeMessageDedupDao : MessageDedupDao {
        val entries = ConcurrentHashMap<String, MessageDedupEntity>()

        override suspend fun getDedupEntry(packetId: String): MessageDedupEntity? = entries[packetId]

        override suspend fun insertDedupEntry(entry: MessageDedupEntity): Long {
            if (entries.containsKey(entry.packetId)) return -1L
            entries[entry.packetId] = entry
            return 1L
        }

        override suspend fun updateStatus(packetId: String, status: String): Int {
            val existing = entries[packetId] ?: return 0
            entries[packetId] = existing.copy(status = status)
            return 1
        }

        override suspend fun getPacketIdsForConversation(conversationId: String): List<String> =
            entries.values.filter { it.conversationId == conversationId }
                .sortedBy { it.firstObservedAt }
                .map { it.packetId }

        override suspend fun getRecentPacketIds(conversationId: String, sinceTimestamp: Long, limit: Int): List<String> =
            entries.values.filter { it.conversationId == conversationId && it.firstObservedAt >= sinceTimestamp }
                .sortedBy { it.firstObservedAt }
                .take(limit)
                .map { it.packetId }

        override suspend fun hasPacket(packetId: String): Int = if (entries.containsKey(packetId)) 1 else 0
    }

    class FakeMessageTransportDao : MessageTransportDao {
        val observations = mutableListOf<MessageTransportEntity>()

        override suspend fun recordObservation(observation: MessageTransportEntity): Long {
            observations.add(observation)
            return observations.size.toLong()
        }

        override suspend fun getObservationsForMessage(packetId: String): List<MessageTransportEntity> =
            observations.filter { it.packetId == packetId }.sortedBy { it.observedAt }

        override suspend fun getObservedMediums(packetId: String): List<String> =
            observations.filter { it.packetId == packetId }.sortedBy { it.observedAt }.map { it.transportMedium }
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
    }

    class FakeChatDao : ChatDao {
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

        override fun searchContacts(query: String): Flow<List<ContactEntity>> = flowOf(contacts.values.toList())

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
    // 1. MULTI-DEVICE EXACT DEDUPLICATION ACROSS TRANSPORTS
    // =========================================================================

    @Test
    fun multiDeviceExactDeduplication_suppressesDuplicateArrivalAcrossTransports() = runTest {
        val packetId = "pkt_multi_transport_dedup_001"
        val envelope = aliceDoubleRatchet.ratchetEncrypt("NX-BOB", "Cross-transport test payload".toByteArray(StandardCharsets.UTF_8), bobPub)

        val wirePacket = NexaWirePacket(
            packetId = packetId,
            type = PacketType.PACKET_MESSAGE,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB",
            timestamp = System.currentTimeMillis(),
            message = envelope.toProtobufPayload()
        )

        // 1. First arrival via WebSocket
        val firstResult = ingest.ingestWirePacket(wirePacket, TransportMedium.INTERNET_WS)
        assertTrue("First arrival via WebSocket must succeed", firstResult)
        assertEquals("Database must contain exactly 1 message", 1, messageDao.messages.size)
        assertEquals("Dedup table must contain 1 entry", 1, messageDedupDao.entries.size)

        // 2. Second arrival of identical packet via Offline Mesh
        val secondResult = ingest.ingestWirePacket(wirePacket, TransportMedium.OFFLINE_MESH)
        assertTrue("Second arrival must return true (suppressed duplicate)", secondResult)

        // 3. Database message count MUST remain 1 (no duplicate insertion)
        assertEquals("Database must still contain exactly 1 message", 1, messageDao.messages.size)

        // 4. Transport table must record both observations for multi-transport telemetry
        val observations = messageTransportDao.getObservationsForMessage(packetId)
        assertEquals("Transport table must record exactly 2 arrivals", 2, observations.size)
        val mediums = messageTransportDao.getObservedMediums(packetId)
        assertEquals(listOf("INTERNET_WS", "OFFLINE_MESH"), mediums)
    }

    // =========================================================================
    // 2. CIPHERTEXT TAMPERING & AEAD VERIFICATION FAILURE
    // =========================================================================

    @Test
    fun doubleRatchet_tamperedCiphertext_aeadFailureRejection() = runTest {
        val plaintext = "Top Secret Zero-Trust Content".toByteArray(StandardCharsets.UTF_8)
        val envelope = aliceDoubleRatchet.ratchetEncrypt("NX-BOB", plaintext, bobPub)

        // Tamper with ciphertext by flipping 1 bit
        val tamperedBytes = envelope.ciphertext.copyOf()
        tamperedBytes[0] = (tamperedBytes[0].toInt() xor 0x01).toByte()
        val tamperedEnvelope = envelope.copy(ciphertext = tamperedBytes)

        // 1. Direct Double Ratchet decryption MUST return null (AEAD Tag failure)
        val directDecrypted = bobDoubleRatchet.ratchetDecrypt("NX-ALICE", tamperedEnvelope)
        assertNull("Double Ratchet ratchetDecrypt must return null on AEAD failure", directDecrypted)

        // 2. Ingestion pipeline must flag corrupted packet as FAILED
        dispatchedAcks.clear()
        val tamperedPacket = NexaWirePacket(
            packetId = "pkt_tampered_aead_001",
            type = PacketType.PACKET_MESSAGE,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB",
            timestamp = System.currentTimeMillis(),
            message = tamperedEnvelope.toProtobufPayload()
        )

        ingest.ingestWirePacket(tamperedPacket, TransportMedium.INTERNET_WS)

        val savedMessage = messageDao.getMessageByPacketId("pkt_tampered_aead_001")
        assertNotNull("Record should be stored for UI tracking", savedMessage)
        assertEquals("Tampered message status must be FAILED", MessageStatus.FAILED.name, savedMessage?.status)
        assertEquals("[Encrypted Message - Verification Failed]", savedMessage?.plainTextContent)

        // 3. Authenticated Delivery ACK must NEVER be dispatched for corrupted/failed messages
        assertTrue("No Delivery ACK must be dispatched when decryption fails", dispatchedAcks.none { it.first.ack?.status == com.nexa.app.core.proto.AckStatus.ACK_DEVICE_DELIVERED })
    }

    // =========================================================================
    // 3. NXDT ENVELOPE TAMPERING & SIGNATURE FAILURE
    // =========================================================================

    @Test
    fun nxdtEnvelope_tamperedPayloadOrHeader_signatureVerificationFails() {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pubKey = Ed25519Signer.derivePublicKey(seed)
        val blindToken = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val payload = "Authentic Unmodified DTN Payload".toByteArray(StandardCharsets.UTF_8)

        val env = DtnEnvelope.createAuthenticated(
            blindToken = blindToken,
            encryptedPayload = payload,
            senderPrivateKey = seed,
            senderPublicKey = pubKey,
            envelopeType = DtnConstants.TYPE_STANDARD_DATA,
            hopLimit = 7,
            ttlSeconds = 3600
        )

        // 1. Valid envelope passes verification
        assertTrue("Authentic envelope must verify", env.verifySignature())

        // 2. Tamper payload: signature verification MUST fail
        val tamperedPayload = payload.copyOf()
        tamperedPayload[0] = (tamperedPayload[0].toInt() xor 0xFF).toByte()

        val tamperedEnv = env.copy(encryptedPayload = tamperedPayload)
        assertFalse("Envelope with tampered payload must fail signature verification", tamperedEnv.verifySignature())

        // 3. Tamper blind token: signature verification MUST fail
        val tamperedToken = blindToken.copyOf()
        tamperedToken[0] = (tamperedToken[0].toInt() xor 0xFF).toByte()

        val tamperedTokenEnv = env.copy(blindToken = tamperedToken)
        assertFalse("Envelope with tampered routing token must fail signature verification", tamperedTokenEnv.verifySignature())
    }

    // =========================================================================
    // 4. EXPIRED DTN ENVELOPE IDENTIFICATION
    // =========================================================================

    @Test
    fun nxdtEnvelope_expiredTtl_identifiedForDrop() {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val blindToken = ByteArray(16)
        val payload = "Expired packet".toByteArray(StandardCharsets.UTF_8)

        val nowEpochSec = System.currentTimeMillis() / 1000L
        val pastEpochSec = nowEpochSec - 3600 // 1 hour in the past

        val expiredEnvelope = DtnEnvelope.createAuthenticated(
            blindToken = blindToken,
            encryptedPayload = payload,
            senderPrivateKey = seed,
            ttlSeconds = 60,
            createdAtEpochSec = pastEpochSec
        )

        val isExpired = expiredEnvelope.expiresAtEpochSec <= nowEpochSec
        assertTrue("Envelope created in the past with short TTL must be expired", isExpired)
    }

    // =========================================================================
    // 5. BIP-39 BACKUP ENCRYPTION & TAMPER RESISTANCE
    // =========================================================================

    @Test
    fun backupCrypto_passphraseDerivationAndTamperedVaultRejection() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val normalized = Bip39PassphraseHelper.normalize(mnemonic)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

        // 1. Key derivation (PBKDF2-HMAC-SHA256, 65,536 iterations)
        val spec = PBEKeySpec(normalized.toCharArray(), salt, 65536, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val plaintextData = "NEXA Database Snapshot & Identity Keys".toByteArray(StandardCharsets.UTF_8)

        // 2. Encrypt with AES-256-GCM
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val ciphertext = encryptCipher.doFinal(plaintextData)

        // Compute authentic payload SHA-256
        val authenticSha256 = MessageDigest.getInstance("SHA-256").digest(ciphertext)

        // 3. Decrypt with correct key & authentic ciphertext: MUST succeed
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val decrypted = decryptCipher.doFinal(ciphertext)
        assertEquals("Decrypted plaintext must match original", String(plaintextData), String(decrypted))

        // 4. Tamper with ciphertext -> Pre-decryption SHA-256 checksum mismatch
        val tamperedCiphertext = ciphertext.copyOf()
        tamperedCiphertext[tamperedCiphertext.size - 1] = (tamperedCiphertext[tamperedCiphertext.size - 1].toInt() xor 0xFF).toByte()

        val tamperedSha256 = MessageDigest.getInstance("SHA-256").digest(tamperedCiphertext)
        assertFalse("Tampered ciphertext must not match authentic SHA-256 digest", authenticSha256.contentEquals(tamperedSha256))

        // 5. Tamper with ciphertext -> AES-GCM AEAD Tag verification failure
        try {
            val tamperedCipher = Cipher.getInstance("AES/GCM/NoPadding")
            tamperedCipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            tamperedCipher.doFinal(tamperedCiphertext)
            fail("CRITICAL SECURITY FLAW: Tampered backup ciphertext passed AEAD tag verification!")
        } catch (e: Exception) {
            assertTrue("Expected AEAD authentication tag failure", e is GeneralSecurityException)
        }
    }

    // =========================================================================
    // 6. LOCK SCREEN NOTIFICATION PRIVACY
    // =========================================================================

    @Test
    fun notificationPrivacy_lockScreenModes_strictlyEnforceRedaction() {
        val hideContentPrefs = NotificationPreferences(lockScreenVisibility = LockScreenMode.HIDE_CONTENT)
        assertEquals(LockScreenMode.HIDE_CONTENT, hideContentPrefs.lockScreenVisibility)

        val redactedPlaceholderTitle = "NEXA"
        val redactedPlaceholderText = "1 new encrypted message"

        assertFalse("Placeholder must not contain sender NX-ID", redactedPlaceholderTitle.contains("NX-"))
        assertFalse("Placeholder must not contain plaintext message body", redactedPlaceholderText.contains("Secret"))
        assertEquals("NEXA", redactedPlaceholderTitle)
        assertEquals("1 new encrypted message", redactedPlaceholderText)

        val dontShowPrefs = NotificationPreferences(lockScreenVisibility = LockScreenMode.DONT_SHOW)
        assertEquals(LockScreenMode.DONT_SHOW, dontShowPrefs.lockScreenVisibility)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
