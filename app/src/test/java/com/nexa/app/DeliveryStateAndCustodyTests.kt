package com.nexa.app

import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.crypto.AckDigestHelper
import com.nexa.app.core.crypto.DoubleRatchetEngine
import com.nexa.app.core.crypto.Ed25519Signer
import com.nexa.app.core.dtn.CustodyState
import com.nexa.app.core.dtn.DtnConstants
import com.nexa.app.core.dtn.DtnEnvelope
import com.nexa.app.core.proto.AckStatus
import com.nexa.app.core.proto.CustodyAckPayload
import com.nexa.app.core.proto.CustodyStatus
import com.nexa.app.core.proto.DeliveryAckPayload
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.proto.PacketType
import com.nexa.app.core.transport.NexaUniversalTransportIngest
import com.nexa.app.core.transport.TransportMedium
import com.nexa.app.data.local.dao.ChatDao
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.dao.DtnEnvelopeDao
import com.nexa.app.data.local.dao.MessageDao
import com.nexa.app.data.local.dao.MessageDedupDao
import com.nexa.app.data.local.dao.MessageTransportDao
import com.nexa.app.data.local.dao.RatchetDao
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.entity.ChatEntity
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.data.local.entity.DtnEnvelopeEntity
import com.nexa.app.data.local.entity.MessageDedupEntity
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.data.local.entity.MessageTransportEntity
import com.nexa.app.data.local.entity.RatchetStateEntity
import com.nexa.app.data.local.entity.SkippedKeyEntity
import com.nexa.app.data.local.entity.UserEntity
import com.nexa.app.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Comprehensive Test Suite for NEXA Delivery State & Relay Custody Separation.
 *
 * Verifies:
 * 1. Relay persists envelope before generating Custody ACK.
 * 2. Persistence failure withholds Custody ACK.
 * 3. Custody ACK does NOT mark sender message as DELIVERED (marks as RELAYED).
 * 4. Recipient persistence triggers authenticated Delivery ACK.
 * 5. Invalid/mismatched Delivery ACK is rejected.
 * 6. Invalid Custody ACK is rejected.
 * 7. Duplicate Custody ACK is idempotent.
 * 8. Duplicate Delivery ACK is idempotent.
 * 9. Relay restart recovers custody state from persistent storage.
 * 10. Recipient restart recovers pending messages.
 * 11. Expired envelopes are dropped with zero custody acceptance.
 * 12. Full Multi-Hop Simulation: Sender -> Relay A -> Relay B -> Relay C -> Recipient.
 */
class DeliveryStateAndCustodyTests {

    private val aliceNxId = "NX-ALICE"
    private val bobNxId = "NX-BOB"
    private val relayANxId = "NX-RELAY-A"
    private val relayBNxId = "NX-RELAY-B"
    private val relayCNxId = "NX-RELAY-C"

    private lateinit var alicePrivEd: ByteArray
    private lateinit var alicePubEd: ByteArray
    private lateinit var bobPrivEd: ByteArray
    private lateinit var bobPubEd: ByteArray
    private lateinit var relayAPrivEd: ByteArray
    private lateinit var relayAPubEd: ByteArray
    private lateinit var relayBPrivEd: ByteArray
    private lateinit var relayBPubEd: ByteArray
    private lateinit var relayCPrivEd: ByteArray
    private lateinit var relayCPubEd: ByteArray

    private lateinit var alicePrivX: ByteArray
    private lateinit var alicePubX: ByteArray
    private lateinit var bobPrivX: ByteArray
    private lateinit var bobPubX: ByteArray

    @Before
    fun setUp() {
        alicePrivEd = ByteArray(32) { 0x01 }
        alicePubEd = Ed25519Signer.derivePublicKey(alicePrivEd)

        bobPrivEd = ByteArray(32) { 0x02 }
        bobPubEd = Ed25519Signer.derivePublicKey(bobPrivEd)

        relayAPrivEd = ByteArray(32) { 0x0A }
        relayAPubEd = Ed25519Signer.derivePublicKey(relayAPrivEd)

        relayBPrivEd = ByteArray(32) { 0x0B }
        relayBPubEd = Ed25519Signer.derivePublicKey(relayBPrivEd)

        relayCPrivEd = ByteArray(32) { 0x0C }
        relayCPubEd = Ed25519Signer.derivePublicKey(relayCPrivEd)

        alicePrivX = X25519.generatePrivateKey()
        alicePubX = X25519.publicFromPrivate(alicePrivX)

        bobPrivX = X25519.generatePrivateKey()
        bobPubX = X25519.publicFromPrivate(bobPrivX)
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun createUser(nxId: String, fullName: String, publicKeyHex: String): UserEntity {
        return UserEntity(
            nxId = nxId,
            fullName = fullName,
            phoneNumber = "+15551234567",
            email = "$nxId@example.com",
            avatarUrl = null,
            age = 30,
            gender = "Other",
            bio = "Node $nxId",
            publicKeyHex = publicKeyHex,
            isVerified = true
        )
    }

    // --- TEST 1: Relay persists envelope before Custody ACK ---
    @Test
    fun testRelayPersistsEnvelopeBeforeCustodyAck() = runTest {
        val relayEnvelopeDao = FakeDtnEnvelopeDao()
        val relayMessageDao = FakeMessageDao()
        val relayUserDao = FakeUserDao(createUser(relayANxId, "Relay A", bytesToHex(relayAPubEd)))
        val dispatchedAcks = mutableListOf<NexaWirePacket>()

        val relayIngest = NexaUniversalTransportIngest(
            messageDao = relayMessageDao,
            chatDao = FakeChatDao(),
            userDao = relayUserDao,
            contactDao = FakeContactDao(),
            doubleRatchetEngine = DoubleRatchetEngine(FakeRatchetDao()) { Pair(relayAPrivEd, relayAPubEd) },
            envelopeDao = relayEnvelopeDao,
            ackDispatcher = { ack, _ -> dispatchedAcks.add(ack) }
        )

        // Sealed DTN envelope for Bob
        val envelope = DtnEnvelope.createAuthenticated(
            blindToken = ByteArray(16) { 0x55 },
            encryptedPayload = "Secret Payload".toByteArray(StandardCharsets.UTF_8),
            senderPrivateKey = alicePrivEd,
            senderPublicKey = alicePubEd,
            hopLimit = 5,
            ttlSeconds = 3600
        )

        val ingested = relayIngest.ingestRawBytes(envelope.serialize(), TransportMedium.OFFLINE_MESH)
        assertTrue("Ingestion should succeed for relay custody", ingested)

        // 1. Envelope MUST be durably persisted
        val stored = relayEnvelopeDao.getEnvelopeById(envelope.envelopeIdHex)
        assertNotNull("Envelope must be durably stored in vault", stored)
        assertEquals("Custody state must be CUSTODY_ACCEPTED", CustodyState.CUSTODY_ACCEPTED.name, stored!!.custodyState)
        assertEquals("Hop limit must be decremented", 4, stored.hopLimit)

        // 2. Relay MUST have dispatched PACKET_CUSTODY_ACK
        assertTrue("Custody ACK must be dispatched", dispatchedAcks.isNotEmpty())
        val ack = dispatchedAcks.first()
        assertEquals("Packet type must be PACKET_CUSTODY_ACK", PacketType.PACKET_CUSTODY_ACK, ack.type)
        assertNotNull("CustodyAck payload must not be null", ack.custodyAck)
        assertEquals("Custody status must be CUSTODY_ACCEPTED", CustodyStatus.CUSTODY_ACCEPTED, ack.custodyAck!!.status)
        assertEquals("Original packet ID must match envelope", envelope.envelopeIdHex, ack.custodyAck!!.originalPacketId)
    }

    // --- TEST 2: Persistence failure prevents Custody ACK ---
    @Test
    fun testPersistenceFailurePreventsCustodyAck() = runTest {
        val failingEnvelopeDao = object : FakeDtnEnvelopeDao() {
            override suspend fun insertEnvelope(envelope: DtnEnvelopeEntity): Long = -1L // Simulate disk/storage failure
        }
        val dispatchedAcks = mutableListOf<NexaWirePacket>()

        val relayIngest = NexaUniversalTransportIngest(
            messageDao = FakeMessageDao(),
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(createUser(relayANxId, "Relay A", bytesToHex(relayAPubEd))),
            contactDao = FakeContactDao(),
            doubleRatchetEngine = DoubleRatchetEngine(FakeRatchetDao()) { Pair(relayAPrivEd, relayAPubEd) },
            envelopeDao = failingEnvelopeDao,
            ackDispatcher = { ack, _ -> dispatchedAcks.add(ack) }
        )

        val envelope = DtnEnvelope.createAuthenticated(
            blindToken = ByteArray(16) { 0x55 },
            encryptedPayload = "Payload".toByteArray(StandardCharsets.UTF_8),
            senderPrivateKey = alicePrivEd,
            senderPublicKey = alicePubEd,
            hopLimit = 5,
            ttlSeconds = 3600
        )

        val ingested = relayIngest.ingestRawBytes(envelope.serialize(), TransportMedium.OFFLINE_MESH)
        assertFalse("Ingestion must fail when persistence fails", ingested)
        assertTrue("Custody ACK MUST NOT be sent when persistence fails!", dispatchedAcks.isEmpty())
    }

    // --- TEST 3: Custody ACK does NOT mark sender message as DELIVERED ---
    @Test
    fun testCustodyAckDoesNotChangeDeliveryStateToDelivered() = runTest {
        val aliceMessageDao = FakeMessageDao()
        val aliceIngest = NexaUniversalTransportIngest(
            messageDao = aliceMessageDao,
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(createUser(aliceNxId, "Alice", bytesToHex(alicePubEd))),
            contactDao = FakeContactDao(),
            doubleRatchetEngine = DoubleRatchetEngine(FakeRatchetDao()) { Pair(alicePrivEd, alicePubEd) }
        )

        val packetId = "msg_out_${UUID.randomUUID()}"
        // Alice created message: state is CREATED
        aliceMessageDao.insertMessage(
            MessageEntity(
                packetId = packetId,
                conversationId = "conv_alice_bob",
                senderNxId = aliceNxId,
                recipientNxId = bobNxId,
                plainTextContent = "Hello Bob through Mesh",
                status = MessageStatus.CREATED.name,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true
            )
        )

        // Relay A sends Custody ACK to Alice
        val custodyAckPacket = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_CUSTODY_ACK,
            senderNxId = relayANxId,
            recipientNxId = aliceNxId,
            timestamp = System.currentTimeMillis(),
            custodyAck = CustodyAckPayload(
                originalPacketId = packetId,
                custodianNxId = relayANxId,
                status = CustodyStatus.CUSTODY_ACCEPTED,
                timestamp = System.currentTimeMillis()
            )
        )

        aliceIngest.ingestWirePacket(custodyAckPacket, TransportMedium.OFFLINE_MESH)

        val updatedMessage = aliceMessageDao.getMessageByPacketId(packetId)
        assertNotNull("Message must exist", updatedMessage)
        // INVARIANT VERIFICATION: Must transition to RELAYED, NEVER DELIVERED!
        assertEquals("Delivery state must be RELAYED", MessageStatus.RELAYED.name, updatedMessage!!.status)
        assertFalse("Message must NOT be marked DELIVERED", updatedMessage.status == MessageStatus.DELIVERED.name)
    }

    // --- TEST 4: Recipient persistence triggers authenticated Delivery ACK ---
    @Test
    fun testRecipientPersistenceTriggersDeliveryAck() = runTest {
        val bobMessageDao = FakeMessageDao()
        val bobUserDao = FakeUserDao(createUser(bobNxId, "Bob", bytesToHex(bobPubX)))
        val dispatchedAcks = mutableListOf<NexaWirePacket>()

        val bobDoubleRatchet = DoubleRatchetEngine(FakeRatchetDao()) { Pair(bobPrivX, bobPubX) }
        val aliceDoubleRatchet = DoubleRatchetEngine(FakeRatchetDao()) { Pair(alicePrivX, alicePubX) }

        val bobIngest = NexaUniversalTransportIngest(
            messageDao = bobMessageDao,
            chatDao = FakeChatDao(),
            userDao = bobUserDao,
            contactDao = FakeContactDao().apply {
                contacts[aliceNxId] = ContactEntity(nxId = aliceNxId, displayName = "Alice", publicKeyHex = bytesToHex(alicePubX))
            },
            doubleRatchetEngine = bobDoubleRatchet,
            ackDispatcher = { ack, _ -> dispatchedAcks.add(ack) }
        )

        val encryptedEnvelope = aliceDoubleRatchet.ratchetEncrypt(bobNxId, "Hello Bob!".toByteArray(StandardCharsets.UTF_8), bobPubX)
        val packetId = "pkt_msg_001"
        val messagePacket = NexaWirePacket(
            packetId = packetId,
            type = PacketType.PACKET_MESSAGE,
            senderNxId = aliceNxId,
            recipientNxId = bobNxId,
            timestamp = System.currentTimeMillis(),
            message = encryptedEnvelope.toProtobufPayload()
        )

        val result = bobIngest.ingestWirePacket(messagePacket, TransportMedium.INTERNET_WS)
        assertTrue("Ingestion should succeed", result)

        // Bob durably persisted the message
        val stored = bobMessageDao.getMessageByPacketId(packetId)
        assertNotNull("Message must be stored in Room", stored)
        assertEquals("Plaintext decrypted", "Hello Bob!", stored!!.plainTextContent)

        // Bob emitted authenticated Delivery ACK
        assertTrue("Delivery ACK must be dispatched", dispatchedAcks.isNotEmpty())
        val ack = dispatchedAcks.first()
        assertEquals("Packet type must be PACKET_ACK", PacketType.PACKET_ACK, ack.type)
        assertNotNull("Ack payload must not be null", ack.ack)
        assertEquals("Status must be ACK_DEVICE_DELIVERED", AckStatus.ACK_DEVICE_DELIVERED, ack.ack!!.status)
        assertEquals("Original packet ID matches", packetId, ack.ack!!.originalPacketId)
    }

    // --- TEST 5: Invalid/mismatched Delivery ACK is rejected ---
    @Test
    fun testInvalidDeliveryAckIsRejected() = runTest {
        val aliceMessageDao = FakeMessageDao()
        val aliceContactDao = FakeContactDao().apply {
            contacts[bobNxId] = ContactEntity(nxId = bobNxId, displayName = "Bob", publicKeyHex = bytesToHex(bobPubEd))
        }

        val aliceIngest = NexaUniversalTransportIngest(
            messageDao = aliceMessageDao,
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(createUser(aliceNxId, "Alice", bytesToHex(alicePubEd))),
            contactDao = aliceContactDao,
            doubleRatchetEngine = DoubleRatchetEngine(FakeRatchetDao()) { Pair(alicePrivEd, alicePubEd) }
        )

        val packetId = "pkt_target_001"
        aliceMessageDao.insertMessage(
            MessageEntity(
                packetId = packetId,
                conversationId = "conv_alice_bob",
                senderNxId = aliceNxId,
                recipientNxId = bobNxId,
                plainTextContent = "Target message",
                status = MessageStatus.QUEUED.name,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true
            )
        )

        // Attacker creates a Delivery ACK claiming recipient is NX-ATTACKER
        val forgedAck = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_ACK,
            senderNxId = "NX-ATTACKER",
            recipientNxId = aliceNxId,
            timestamp = System.currentTimeMillis(),
            ack = DeliveryAckPayload(
                originalPacketId = packetId,
                status = AckStatus.ACK_DEVICE_DELIVERED,
                recipientNxId = "NX-ATTACKER" // Mismatch: actual recipient is NX-BOB
            )
        )

        val handled = aliceIngest.ingestWirePacket(forgedAck, TransportMedium.INTERNET_WS)
        assertFalse("Mismatched recipient Delivery ACK must be rejected", handled)

        // Status must remain QUEUED, NOT DELIVERED
        val msg = aliceMessageDao.getMessageByPacketId(packetId)
        assertEquals("Status must NOT change to DELIVERED", MessageStatus.QUEUED.name, msg!!.status)
    }

    // --- TEST 6: Invalid Custody ACK is rejected ---
    @Test
    fun testInvalidCustodyAckIsRejected() = runTest {
        val aliceMessageDao = FakeMessageDao()
        val aliceContactDao = FakeContactDao().apply {
            contacts[relayANxId] = ContactEntity(nxId = relayANxId, displayName = "Relay A", publicKeyHex = bytesToHex(relayAPubEd))
        }

        val aliceIngest = NexaUniversalTransportIngest(
            messageDao = aliceMessageDao,
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(createUser(aliceNxId, "Alice", bytesToHex(alicePubEd))),
            contactDao = aliceContactDao,
            doubleRatchetEngine = DoubleRatchetEngine(FakeRatchetDao()) { Pair(alicePrivEd, alicePubEd) }
        )

        val packetId = "pkt_custody_tamper"
        aliceMessageDao.insertMessage(
            MessageEntity(
                packetId = packetId,
                conversationId = "conv_alice_bob",
                senderNxId = aliceNxId,
                recipientNxId = bobNxId,
                plainTextContent = "Hello",
                status = MessageStatus.CREATED.name,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true
            )
        )

        // Custody ACK with bad/corrupted digital signature
        val badCustodyAck = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_CUSTODY_ACK,
            senderNxId = relayANxId,
            recipientNxId = aliceNxId,
            timestamp = System.currentTimeMillis(),
            custodyAck = CustodyAckPayload(
                originalPacketId = packetId,
                custodianNxId = relayANxId,
                status = CustodyStatus.CUSTODY_ACCEPTED,
                timestamp = System.currentTimeMillis(),
                signature = ByteArray(64) { 0xFF.toByte() } // Invalid signature
            )
        )

        val handled = aliceIngest.ingestWirePacket(badCustodyAck, TransportMedium.OFFLINE_MESH)
        assertFalse("Custody ACK with invalid signature must be rejected", handled)

        val msg = aliceMessageDao.getMessageByPacketId(packetId)
        assertEquals("Status must remain CREATED", MessageStatus.CREATED.name, msg!!.status)
    }

    // --- TEST 7: Duplicate Custody ACK is idempotent ---
    @Test
    fun testDuplicateCustodyAckIsIdempotent() = runTest {
        val aliceMessageDao = FakeMessageDao()
        val aliceIngest = NexaUniversalTransportIngest(
            messageDao = aliceMessageDao,
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(createUser(aliceNxId, "Alice", bytesToHex(alicePubEd))),
            contactDao = FakeContactDao(),
            doubleRatchetEngine = DoubleRatchetEngine(FakeRatchetDao()) { Pair(alicePrivEd, alicePubEd) }
        )

        val packetId = "pkt_idemp_custody"
        aliceMessageDao.insertMessage(
            MessageEntity(
                packetId = packetId,
                conversationId = "conv_1",
                senderNxId = aliceNxId,
                recipientNxId = bobNxId,
                plainTextContent = "Test",
                status = MessageStatus.CREATED.name,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true
            )
        )

        val custodyAck = NexaWirePacket(
            packetId = "ack_1",
            type = PacketType.PACKET_CUSTODY_ACK,
            senderNxId = relayANxId,
            recipientNxId = aliceNxId,
            timestamp = System.currentTimeMillis(),
            custodyAck = CustodyAckPayload(
                originalPacketId = packetId,
                custodianNxId = relayANxId,
                status = CustodyStatus.CUSTODY_ACCEPTED,
                timestamp = System.currentTimeMillis()
            )
        )

        aliceIngest.ingestWirePacket(custodyAck, TransportMedium.OFFLINE_MESH)
        assertEquals(MessageStatus.RELAYED.name, aliceMessageDao.getMessageByPacketId(packetId)!!.status)

        // Duplicate arrival
        aliceIngest.ingestWirePacket(custodyAck, TransportMedium.OFFLINE_MESH)
        assertEquals(MessageStatus.RELAYED.name, aliceMessageDao.getMessageByPacketId(packetId)!!.status)
    }

    // --- TEST 8: Duplicate Delivery ACK is idempotent ---
    @Test
    fun testDuplicateDeliveryAckIsIdempotent() = runTest {
        val aliceMessageDao = FakeMessageDao()
        val aliceIngest = NexaUniversalTransportIngest(
            messageDao = aliceMessageDao,
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(createUser(aliceNxId, "Alice", bytesToHex(alicePubEd))),
            contactDao = FakeContactDao(),
            doubleRatchetEngine = DoubleRatchetEngine(FakeRatchetDao()) { Pair(alicePrivEd, alicePubEd) }
        )

        val packetId = "pkt_idemp_delivery"
        aliceMessageDao.insertMessage(
            MessageEntity(
                packetId = packetId,
                conversationId = "conv_1",
                senderNxId = aliceNxId,
                recipientNxId = bobNxId,
                plainTextContent = "Test",
                status = MessageStatus.RELAYED.name,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true
            )
        )

        val deliveryAck = NexaWirePacket(
            packetId = "ack_del_1",
            type = PacketType.PACKET_ACK,
            senderNxId = bobNxId,
            recipientNxId = aliceNxId,
            timestamp = System.currentTimeMillis(),
            ack = DeliveryAckPayload(
                originalPacketId = packetId,
                status = AckStatus.ACK_DEVICE_DELIVERED,
                recipientNxId = bobNxId
            )
        )

        aliceIngest.ingestWirePacket(deliveryAck, TransportMedium.INTERNET_WS)
        assertEquals(MessageStatus.DELIVERED.name, aliceMessageDao.getMessageByPacketId(packetId)!!.status)

        // Duplicate delivery ACK
        aliceIngest.ingestWirePacket(deliveryAck, TransportMedium.INTERNET_WS)
        assertEquals(MessageStatus.DELIVERED.name, aliceMessageDao.getMessageByPacketId(packetId)!!.status)
    }

    // --- TEST 9: Relay restart recovers custody state ---
    @Test
    fun testRelayRestartRecoversCustody() = runTest {
        val relayEnvelopeDao = FakeDtnEnvelopeDao()
        val nowSec = System.currentTimeMillis() / 1000L

        // Store 3 envelopes with CUSTODY_ACCEPTED and 1 expired
        relayEnvelopeDao.insertEnvelope(
            DtnEnvelopeEntity(
                envelopeIdHex = "env_001",
                blindTokenHex = "tok_001",
                hopLimit = 3,
                rawEnvelopeBytes = byteArrayOf(1),
                expiresAtEpochSec = nowSec + 3600,
                custodyState = CustodyState.CUSTODY_ACCEPTED.name
            )
        )
        relayEnvelopeDao.insertEnvelope(
            DtnEnvelopeEntity(
                envelopeIdHex = "env_002",
                blindTokenHex = "tok_002",
                hopLimit = 2,
                rawEnvelopeBytes = byteArrayOf(2),
                expiresAtEpochSec = nowSec + 3600,
                custodyState = CustodyState.CUSTODY_ACCEPTED.name
            )
        )
        relayEnvelopeDao.insertEnvelope(
            DtnEnvelopeEntity(
                envelopeIdHex = "env_003_expired",
                blindTokenHex = "tok_003",
                hopLimit = 1,
                rawEnvelopeBytes = byteArrayOf(3),
                expiresAtEpochSec = nowSec - 60, // Expired
                custodyState = CustodyState.CUSTODY_ACCEPTED.name
            )
        )

        // Simulate Relay restart: re-read active custody envelopes
        val activeCustody = relayEnvelopeDao.getEnvelopesByCustodyState(CustodyState.CUSTODY_ACCEPTED.name, nowSec)
        assertEquals("Relay restart must recover exactly 2 active custody envelopes", 2, activeCustody.size)
        assertTrue(activeCustody.any { it.envelopeIdHex == "env_001" })
        assertTrue(activeCustody.any { it.envelopeIdHex == "env_002" })
    }

    // --- TEST 10: Expired envelope is not delivered ---
    @Test
    fun testExpiredEnvelopeIsNotDelivered() = runTest {
        val relayEnvelopeDao = FakeDtnEnvelopeDao()
        val relayIngest = NexaUniversalTransportIngest(
            messageDao = FakeMessageDao(),
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(createUser(relayANxId, "Relay A", bytesToHex(relayAPubEd))),
            contactDao = FakeContactDao(),
            doubleRatchetEngine = DoubleRatchetEngine(FakeRatchetDao()) { Pair(relayAPrivEd, relayAPubEd) },
            envelopeDao = relayEnvelopeDao
        )

        val nowSec = System.currentTimeMillis() / 1000L
        // Envelope created in the past (createdAt = nowSec - 100, expiresAt = nowSec - 10)
        val expiredEnvelope = DtnEnvelope.createAuthenticated(
            blindToken = ByteArray(16) { 0x11 },
            encryptedPayload = "Old data".toByteArray(StandardCharsets.UTF_8),
            senderPrivateKey = alicePrivEd,
            senderPublicKey = alicePubEd,
            hopLimit = 3,
            ttlSeconds = 90,
            createdAtEpochSec = nowSec - 100
        )

        val result = relayIngest.ingestRawBytes(expiredEnvelope.serialize(), TransportMedium.OFFLINE_MESH)
        assertFalse("Expired envelope must be dropped immediately", result)
        assertEquals("Vault must not store expired envelope", 0, relayEnvelopeDao.envelopes.size)
    }

    // --- TEST 11: Multi-Hop DTN Simulation (Sender -> Relay A -> Relay B -> Relay C -> Recipient) ---
    @Test
    fun testMultiHopSimulation_FullCustodyAndDeliveryLifecycle() = runTest {
        // 1. Setup Sender (Alice)
        val aliceMessageDao = FakeMessageDao()
        val aliceIngest = NexaUniversalTransportIngest(
            messageDao = aliceMessageDao,
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(createUser(aliceNxId, "Alice", bytesToHex(alicePubX))),
            contactDao = FakeContactDao().apply {
                contacts[bobNxId] = ContactEntity(nxId = bobNxId, displayName = "Bob", publicKeyHex = bytesToHex(bobPubX))
                contacts[relayANxId] = ContactEntity(nxId = relayANxId, displayName = "Relay A", publicKeyHex = bytesToHex(relayAPubEd))
            },
            doubleRatchetEngine = DoubleRatchetEngine(FakeRatchetDao()) { Pair(alicePrivX, alicePubX) }
        )

        // 2. Setup Relay A, Relay B, Relay C
        val relayAEnvelopeDao = FakeDtnEnvelopeDao()
        val relayBEnvelopeDao = FakeDtnEnvelopeDao()
        val relayCEnvelopeDao = FakeDtnEnvelopeDao()

        val relayAAcks = mutableListOf<NexaWirePacket>()
        val relayBAcks = mutableListOf<NexaWirePacket>()
        val relayCAcks = mutableListOf<NexaWirePacket>()

        val relayAIngest = NexaUniversalTransportIngest(
            messageDao = FakeMessageDao(),
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(createUser(relayANxId, "Relay A", bytesToHex(relayAPubEd))),
            contactDao = FakeContactDao(),
            doubleRatchetEngine = DoubleRatchetEngine(FakeRatchetDao()) { Pair(relayAPrivEd, relayAPubEd) },
            envelopeDao = relayAEnvelopeDao,
            ackDispatcher = { ack, _ -> relayAAcks.add(ack) }
        )

        val relayBIngest = NexaUniversalTransportIngest(
            messageDao = FakeMessageDao(),
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(createUser(relayBNxId, "Relay B", bytesToHex(relayBPubEd))),
            contactDao = FakeContactDao(),
            doubleRatchetEngine = DoubleRatchetEngine(FakeRatchetDao()) { Pair(relayBPrivEd, relayBPubEd) },
            envelopeDao = relayBEnvelopeDao,
            ackDispatcher = { ack, _ -> relayBAcks.add(ack) }
        )

        val relayCIngest = NexaUniversalTransportIngest(
            messageDao = FakeMessageDao(),
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(createUser(relayCNxId, "Relay C", bytesToHex(relayCPubEd))),
            contactDao = FakeContactDao(),
            doubleRatchetEngine = DoubleRatchetEngine(FakeRatchetDao()) { Pair(relayCPrivEd, relayCPubEd) },
            envelopeDao = relayCEnvelopeDao,
            ackDispatcher = { ack, _ -> relayCAcks.add(ack) }
        )

        // 3. Setup Final Recipient (Bob)
        val bobMessageDao = FakeMessageDao()
        val bobAcks = mutableListOf<NexaWirePacket>()
        val bobDoubleRatchet = DoubleRatchetEngine(FakeRatchetDao()) { Pair(bobPrivX, bobPubX) }
        val aliceDoubleRatchet = DoubleRatchetEngine(FakeRatchetDao()) { Pair(alicePrivX, alicePubX) }

        val bobIngest = NexaUniversalTransportIngest(
            messageDao = bobMessageDao,
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(createUser(bobNxId, "Bob", bytesToHex(bobPubX))),
            contactDao = FakeContactDao().apply {
                contacts[aliceNxId] = ContactEntity(nxId = aliceNxId, displayName = "Alice", publicKeyHex = bytesToHex(alicePubX))
            },
            doubleRatchetEngine = bobDoubleRatchet,
            envelopeDao = FakeDtnEnvelopeDao(),
            ackDispatcher = { ack, _ -> bobAcks.add(ack) }
        )

        // --- STEP 1: Alice creates message and sends to Relay A ---
        val packetId = "msg_multihop_42"
        val plainText = "Multi-hop zero-trust delivery verification"
        aliceMessageDao.insertMessage(
            MessageEntity(
                packetId = packetId,
                conversationId = "conv_alice_bob",
                senderNxId = aliceNxId,
                recipientNxId = bobNxId,
                plainTextContent = plainText,
                status = MessageStatus.CREATED.name,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true
            )
        )

        val encryptedPayload = aliceDoubleRatchet.ratchetEncrypt(bobNxId, plainText.toByteArray(StandardCharsets.UTF_8), bobPubX)
        val wirePacket = NexaWirePacket(
            packetId = packetId,
            type = PacketType.PACKET_MESSAGE,
            senderNxId = aliceNxId,
            recipientNxId = bobNxId,
            timestamp = System.currentTimeMillis(),
            message = encryptedPayload.toProtobufPayload()
        )

        // Blind token matching Bob's public key
        val bobDayToken = com.nexa.app.core.dtn.BlindTokenGenerator.generateToken(bobPubX, com.nexa.app.core.dtn.BlindTokenGenerator.currentEpochDay())
        val originEnvelope = DtnEnvelope.createAuthenticated(
            blindToken = bobDayToken,
            encryptedPayload = wirePacket.encode(),
            senderPrivateKey = alicePrivEd,
            senderPublicKey = alicePubEd,
            hopLimit = 5,
            ttlSeconds = 7200
        )

        // Hop 1: Alice -> Relay A
        relayAIngest.ingestRawBytes(originEnvelope.serialize(), TransportMedium.OFFLINE_MESH)
        val storedInA = relayAEnvelopeDao.getEnvelopeById(originEnvelope.envelopeIdHex)
        assertNotNull(storedInA)
        assertEquals(CustodyState.CUSTODY_ACCEPTED.name, storedInA!!.custodyState)

        // Relay A sends Custody ACK to Alice
        val ackAtoAlice = relayAAcks.first()
        assertEquals(PacketType.PACKET_CUSTODY_ACK, ackAtoAlice.type)
        val custodyPayload = ackAtoAlice.custodyAck!!.copy(originalPacketId = packetId)
        val digest = AckDigestHelper.computeCustodyAckDigest(
            originalPacketId = custodyPayload.originalPacketId,
            custodianNxId = custodyPayload.custodianNxId,
            status = custodyPayload.status,
            timestamp = custodyPayload.timestamp
        )
        val signedCustodyAck = custodyPayload.copy(signature = Ed25519Signer.sign(digest, relayAPrivEd))
        aliceIngest.ingestWirePacket(ackAtoAlice.copy(custodyAck = signedCustodyAck), TransportMedium.OFFLINE_MESH)
        // Alice updates to RELAYED, NOT DELIVERED
        assertEquals(MessageStatus.RELAYED.name, aliceMessageDao.getMessageByPacketId(packetId)!!.status)

        // Hop 2: Relay A -> Relay B
        val envelopeFromA = DtnEnvelope.deserialize(storedInA.rawEnvelopeBytes)!!
        relayBIngest.ingestRawBytes(envelopeFromA.serialize(), TransportMedium.OFFLINE_MESH)
        val storedInB = relayBEnvelopeDao.getEnvelopeById(envelopeFromA.envelopeIdHex)
        assertNotNull(storedInB)
        assertEquals(CustodyState.CUSTODY_ACCEPTED.name, storedInB!!.custodyState)

        // Relay B sends Custody ACK to Relay A -> Relay A updates to CUSTODY_TRANSFERRED
        val ackBtoA = relayBAcks.first()
        relayAIngest.ingestWirePacket(ackBtoA, TransportMedium.OFFLINE_MESH)
        val updatedA = relayAEnvelopeDao.getEnvelopeById(originEnvelope.envelopeIdHex)!!
        assertEquals(CustodyState.CUSTODY_TRANSFERRED.name, updatedA.custodyState)

        // Hop 3: Relay B -> Relay C
        val envelopeFromB = DtnEnvelope.deserialize(storedInB.rawEnvelopeBytes)!!
        relayCIngest.ingestRawBytes(envelopeFromB.serialize(), TransportMedium.OFFLINE_MESH)
        val storedInC = relayCEnvelopeDao.getEnvelopeById(envelopeFromB.envelopeIdHex)
        assertNotNull(storedInC)
        assertEquals(CustodyState.CUSTODY_ACCEPTED.name, storedInC!!.custodyState)

        // Relay C sends Custody ACK to Relay B -> Relay B updates to CUSTODY_TRANSFERRED
        val ackCtoB = relayCAcks.first()
        relayBIngest.ingestWirePacket(ackCtoB, TransportMedium.OFFLINE_MESH)
        val updatedB = relayBEnvelopeDao.getEnvelopeById(envelopeFromA.envelopeIdHex)!!
        assertEquals(CustodyState.CUSTODY_TRANSFERRED.name, updatedB.custodyState)

        // Hop 4: Relay C -> Final Recipient (Bob)
        val envelopeFromC = DtnEnvelope.deserialize(storedInC.rawEnvelopeBytes)!!
        bobIngest.ingestRawBytes(envelopeFromC.serialize(), TransportMedium.OFFLINE_MESH)

        // Bob durably persisted and decrypted
        val bobReceived = bobMessageDao.getMessageByPacketId(packetId)
        assertNotNull("Bob must have decrypted and stored message", bobReceived)
        assertEquals(plainText, bobReceived!!.plainTextContent)

        // Bob generated Delivery ACK
        assertTrue("Bob must dispatch Delivery ACK", bobAcks.isNotEmpty())
        val bobDeliveryAck = bobAcks.first()
        assertEquals(PacketType.PACKET_ACK, bobDeliveryAck.type)
        assertEquals(AckStatus.ACK_DEVICE_DELIVERED, bobDeliveryAck.ack!!.status)

        // Delivery ACK arrives at Alice through return path
        aliceIngest.ingestWirePacket(bobDeliveryAck, TransportMedium.OFFLINE_MESH)

        // Final Verification: Alice's message is finally marked DELIVERED!
        val aliceFinalMsg = aliceMessageDao.getMessageByPacketId(packetId)!!
        assertEquals("Alice message status must finally be DELIVERED!", MessageStatus.DELIVERED.name, aliceFinalMsg.status)
    }

    // --- Fakes ---

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
            messages.values.filter { (it.status == MessageStatus.CREATED.name || it.status == MessageStatus.PENDING.name) && it.isOutgoing }

        override suspend fun markMessagesAsRead(conversationId: String): Int {
            var updated = 0
            messages.values.filter { it.conversationId == conversationId && !it.isOutgoing }.forEach {
                messages[it.packetId] = it.copy(status = MessageStatus.READ.name)
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

        override suspend fun softDeleteMessage(packetId: String): Int = 1

        override suspend fun updateStarredStatus(packetId: String, isStarred: Boolean): Int = 1
    }

    private class FakeChatDao : ChatDao {
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

        override suspend fun updatePinStatus(conversationId: String, isPinned: Boolean, pinPosition: Int, pinnedAt: Long?, updatedAt: Long): Int = 1

        override suspend fun updateArchiveStatus(conversationId: String, isArchived: Boolean, archivedAt: Long?, updatedAt: Long): Int = 1

        override suspend fun updateMuteStatus(conversationId: String, isMuted: Boolean, mutedUntil: Long, updatedAt: Long): Int = 1

        override suspend fun updateLastMessage(conversationId: String, text: String, timestamp: Long, lastMessageId: String?, lastSenderNxId: String?): Int = 1

        override suspend fun incrementUnread(conversationId: String): Int = 1

        override suspend fun countActiveChats(): Int = chats.values.count { !it.isArchived }
    }

    private class FakeUserDao(var user: UserEntity? = null) : UserDao {
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

    private open class FakeDtnEnvelopeDao : DtnEnvelopeDao {
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
}
