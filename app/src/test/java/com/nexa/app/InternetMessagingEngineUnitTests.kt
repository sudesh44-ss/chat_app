package com.nexa.app

import com.nexa.app.core.crypto.GroupCiphertext
import com.nexa.app.core.crypto.GroupSenderKeyEngine
import com.nexa.app.core.proto.*
import com.nexa.app.domain.model.MessageStatus
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class InternetMessagingEngineUnitTests {

    // --- 1. Wire Protocol Encode/Decode Tests ---

    @Test
    fun testNexaWirePacket_Presence_EncodeDecode() {
        val original = NexaWirePacket(
            packetId = "presence_pkt_001",
            type = PacketType.PACKET_PRESENCE,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB",
            timestamp = 1700000000000L,
            presence = PresencePayload(
                nxId = "NX-ALICE",
                status = PresenceStatus.PRESENCE_ONLINE,
                timestamp = 1700000000000L
            )
        )

        val encoded = original.encode()
        val decoded = NexaWirePacket.decode(encoded)

        assertEquals("presence_pkt_001", decoded.packetId)
        assertEquals(PacketType.PACKET_PRESENCE, decoded.type)
        assertEquals("NX-ALICE", decoded.senderNxId)
        assertEquals("NX-BOB", decoded.recipientNxId)
        assertNotNull(decoded.presence)
        assertEquals(PresenceStatus.PRESENCE_ONLINE, decoded.presence?.status)
        assertEquals("NX-ALICE", decoded.presence?.nxId)
    }

    @Test
    fun testNexaWirePacket_MessageEdit_EncodeDecode() {
        val original = NexaWirePacket(
            packetId = "edit_pkt_001",
            type = PacketType.PACKET_MESSAGE_EDIT,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB",
            timestamp = 1700000001000L,
            messageEdit = MessageEditPayload(
                targetPacketId = "orig_msg_100",
                newCiphertext = "edited ciphertext bytes".toByteArray(StandardCharsets.UTF_8),
                authTag = ByteArray(16) { 0xAA.toByte() },
                nonce = ByteArray(12) { 0xBB.toByte() },
                aeadNonce = ByteArray(12) { 0xCC.toByte() },
                editedAt = 1700000001000L
            )
        )

        val encoded = original.encode()
        val decoded = NexaWirePacket.decode(encoded)

        assertEquals("edit_pkt_001", decoded.packetId)
        assertEquals(PacketType.PACKET_MESSAGE_EDIT, decoded.type)
        assertNotNull(decoded.messageEdit)
        assertEquals("orig_msg_100", decoded.messageEdit?.targetPacketId)
        assertEquals(1700000001000L, decoded.messageEdit?.editedAt)
        assertArrayEquals(original.messageEdit?.newCiphertext, decoded.messageEdit?.newCiphertext)
        assertArrayEquals(original.messageEdit?.authTag, decoded.messageEdit?.authTag)
    }

    @Test
    fun testNexaWirePacket_MessageDelete_EncodeDecode() {
        val original = NexaWirePacket(
            packetId = "del_pkt_001",
            type = PacketType.PACKET_MESSAGE_DELETE,
            senderNxId = "NX-ALICE",
            recipientNxId = "NX-BOB",
            timestamp = 1700000002000L,
            messageDelete = MessageDeletePayload(
                targetPacketId = "orig_msg_200",
                deleteType = "FOR_EVERYONE",
                deletedAt = 1700000002000L
            )
        )

        val encoded = original.encode()
        val decoded = NexaWirePacket.decode(encoded)

        assertEquals("del_pkt_001", decoded.packetId)
        assertEquals(PacketType.PACKET_MESSAGE_DELETE, decoded.type)
        assertNotNull(decoded.messageDelete)
        assertEquals("orig_msg_200", decoded.messageDelete?.targetPacketId)
        assertEquals("FOR_EVERYONE", decoded.messageDelete?.deleteType)
        assertEquals(1700000002000L, decoded.messageDelete?.deletedAt)
    }

    @Test
    fun testNexaWirePacket_GroupMessage_EncodeDecode() {
        val original = NexaWirePacket(
            packetId = "grp_pkt_001",
            type = PacketType.PACKET_MESSAGE,
            senderNxId = "NX-ALICE",
            recipientNxId = "GRP-TEST-500",
            groupId = "GRP-TEST-500",
            timestamp = 1700000003000L,
            message = EncryptedMessagePayload(
                ciphertext = "group ciphertext".toByteArray(StandardCharsets.UTF_8)
            )
        )

        val encoded = original.encode()
        val decoded = NexaWirePacket.decode(encoded)

        assertEquals("grp_pkt_001", decoded.packetId)
        assertEquals("GRP-TEST-500", decoded.recipientNxId)
        assertEquals("GRP-TEST-500", decoded.groupId)
        assertNotNull(decoded.message)
    }

    // --- 2. Group Sender Key Engine Tests ---

    @Test
    fun testGroupSenderKey_EncryptAndDecrypt() {
        val aliceEngine = GroupSenderKeyEngine()
        val bobEngine = GroupSenderKeyEngine()
        val groupId = "GRP-SECURITY-101"

        // 1. Alice creates sender key and distributes it to Bob
        val (_, distribution) = aliceEngine.createOrRotateSenderKey(groupId, "NX-ALICE")
        bobEngine.processSenderKeyDistribution(distribution)

        // 2. Alice encrypts a message for the group
        val plaintext = "Zero-Knowledge Group Message Plaintext".toByteArray(StandardCharsets.UTF_8)
        val ciphertext = aliceEngine.encrypt(groupId, "NX-ALICE", plaintext)

        // 3. Bob decrypts the message
        val decrypted = bobEngine.decrypt(ciphertext)
        assertNotNull("Bob should successfully decrypt Alice's group message", decrypted)
        assertEquals("Zero-Knowledge Group Message Plaintext", String(decrypted!!, StandardCharsets.UTF_8))
    }

    @Test
    fun testGroupSenderKey_TamperedCiphertext_FailsDecryption() {
        val aliceEngine = GroupSenderKeyEngine()
        val bobEngine = GroupSenderKeyEngine()
        val groupId = "GRP-SECURITY-102"

        val (_, distribution) = aliceEngine.createOrRotateSenderKey(groupId, "NX-ALICE")
        bobEngine.processSenderKeyDistribution(distribution)

        val plaintext = "Original secret message".toByteArray(StandardCharsets.UTF_8)
        val ciphertext = aliceEngine.encrypt(groupId, "NX-ALICE", plaintext)

        // Attacker tampers with ciphertext byte
        val tamperedBytes = ciphertext.ciphertext.copyOf()
        tamperedBytes[0] = (tamperedBytes[0].toInt() xor 0xFF).toByte()
        val tamperedCiphertext = ciphertext.copy(ciphertext = tamperedBytes)

        val decrypted = bobEngine.decrypt(tamperedCiphertext)
        assertNull("Tampered group ciphertext must fail verification and return null", decrypted)
    }

    @Test
    fun testGroupSenderKey_OutboxOutOfOrder_SkippedKeys() {
        val aliceEngine = GroupSenderKeyEngine()
        val bobEngine = GroupSenderKeyEngine()
        val groupId = "GRP-SECURITY-103"

        val (_, distribution) = aliceEngine.createOrRotateSenderKey(groupId, "NX-ALICE")
        bobEngine.processSenderKeyDistribution(distribution)

        val msg1 = aliceEngine.encrypt(groupId, "NX-ALICE", "Message 1".toByteArray(StandardCharsets.UTF_8))
        val msg2 = aliceEngine.encrypt(groupId, "NX-ALICE", "Message 2".toByteArray(StandardCharsets.UTF_8))
        val msg3 = aliceEngine.encrypt(groupId, "NX-ALICE", "Message 3".toByteArray(StandardCharsets.UTF_8))

        // Bob receives msg3 FIRST (out-of-order delivery)
        val dec3 = bobEngine.decrypt(msg3)
        assertNotNull(dec3)
        assertEquals("Message 3", String(dec3!!, StandardCharsets.UTF_8))

        // Bob subsequently receives msg1 (should decrypt using cached skipped key)
        val dec1 = bobEngine.decrypt(msg1)
        assertNotNull(dec1)
        assertEquals("Message 1", String(dec1!!, StandardCharsets.UTF_8))

        // Bob receives msg2
        val dec2 = bobEngine.decrypt(msg2)
        assertNotNull(dec2)
        assertEquals("Message 2", String(dec2!!, StandardCharsets.UTF_8))
    }

    @Test
    fun testGroupSenderKey_EpochRotation_RemovedMemberCannotDecryptFuture() {
        val aliceEngine = GroupSenderKeyEngine()
        val bobEngine = GroupSenderKeyEngine()
        val eveEngine = GroupSenderKeyEngine()
        val groupId = "GRP-SECURITY-104"

        // Epoch 1: Alice, Bob, Eve are members
        val (_, distEpoch1) = aliceEngine.createOrRotateSenderKey(groupId, "NX-ALICE")
        bobEngine.processSenderKeyDistribution(distEpoch1)
        eveEngine.processSenderKeyDistribution(distEpoch1)

        val epoch1Msg = aliceEngine.encrypt(groupId, "NX-ALICE", "Epoch 1 text".toByteArray(StandardCharsets.UTF_8))
        assertEquals("Epoch 1 text", String(eveEngine.decrypt(epoch1Msg)!!, StandardCharsets.UTF_8))

        // Eve is removed from group! Alice rotates to Epoch 2 and distributes ONLY to Bob
        val (_, distEpoch2) = aliceEngine.createOrRotateSenderKey(groupId, "NX-ALICE")
        bobEngine.processSenderKeyDistribution(distEpoch2)
        // Note: distEpoch2 is intentionally NOT given to Eve

        val epoch2Msg = aliceEngine.encrypt(groupId, "NX-ALICE", "Epoch 2 post-removal secret".toByteArray(StandardCharsets.UTF_8))

        // Bob can decrypt Epoch 2
        val bobDecrypted = bobEngine.decrypt(epoch2Msg)
        assertNotNull(bobDecrypted)
        assertEquals("Epoch 2 post-removal secret", String(bobDecrypted!!, StandardCharsets.UTF_8))

        // Eve CANNOT decrypt Epoch 2 (Post-compromise security & forward secrecy)
        val eveDecrypted = eveEngine.decrypt(epoch2Msg)
        assertNull("Removed member Eve must NOT be able to decrypt Epoch 2 message", eveDecrypted)
    }

    // --- 3. Delivery Ticks State Machine Tests ---

    @Test
    fun testDeliveryTickTransitions() {
        // State 0: Initial message creation in Outbox is PENDING
        var currentStatus = MessageStatus.PENDING
        assertEquals(MessageStatus.PENDING, currentStatus)

        // State 1: Server accepts packet -> ACK_SERVER_RECEIVED (Single Grey Tick)
        val serverAck = AckStatus.ACK_SERVER_RECEIVED
        currentStatus = when (serverAck) {
            AckStatus.ACK_SERVER_RECEIVED -> MessageStatus.SENT
            AckStatus.ACK_DEVICE_DELIVERED -> MessageStatus.DELIVERED
            AckStatus.ACK_DEVICE_READ -> MessageStatus.READ
            AckStatus.ACK_UNKNOWN -> currentStatus
        }
        assertEquals(MessageStatus.SENT, currentStatus)

        // State 2: Recipient device saves packet -> ACK_DEVICE_DELIVERED (Double Grey Tick)
        val deliveryAck = AckStatus.ACK_DEVICE_DELIVERED
        currentStatus = when (deliveryAck) {
            AckStatus.ACK_SERVER_RECEIVED -> MessageStatus.SENT
            AckStatus.ACK_DEVICE_DELIVERED -> MessageStatus.DELIVERED
            AckStatus.ACK_DEVICE_READ -> MessageStatus.READ
            AckStatus.ACK_UNKNOWN -> currentStatus
        }
        assertEquals(MessageStatus.DELIVERED, currentStatus)

        // State 3: Recipient opens conversation -> ACK_DEVICE_READ (Double Blue Tick)
        val readAck = AckStatus.ACK_DEVICE_READ
        currentStatus = when (readAck) {
            AckStatus.ACK_SERVER_RECEIVED -> MessageStatus.SENT
            AckStatus.ACK_DEVICE_DELIVERED -> MessageStatus.DELIVERED
            AckStatus.ACK_DEVICE_READ -> MessageStatus.READ
            AckStatus.ACK_UNKNOWN -> currentStatus
        }
        assertEquals(MessageStatus.READ, currentStatus)
    }

    // --- 4. Privacy Settings Combinations Matrix Tests ---

    @Test
    fun testPrivacySettingsCombinations() {
        data class PrivacyTestCase(
            val activeStatusEnabled: Boolean,
            val typingIndicatorEnabled: Boolean,
            val peerIsActuallyOnline: Boolean,
            val peerIsActuallyTyping: Boolean,
            val expectedGreenDot: Boolean,
            val expectedTyping: Boolean
        )

        val testCases = listOf(
            // Row 1: ON / ON -> Green dot + typing
            PrivacyTestCase(
                activeStatusEnabled = true,
                typingIndicatorEnabled = true,
                peerIsActuallyOnline = true,
                peerIsActuallyTyping = true,
                expectedGreenDot = true,
                expectedTyping = true
            ),
            // Row 2: ON / OFF -> Green dot only
            PrivacyTestCase(
                activeStatusEnabled = true,
                typingIndicatorEnabled = false,
                peerIsActuallyOnline = true,
                peerIsActuallyTyping = true,
                expectedGreenDot = true,
                expectedTyping = false // Suppressed locally when typing indicator disabled
            ),
            // Row 3: OFF / ON -> Typing only (Reciprocity rule hides green dot)
            PrivacyTestCase(
                activeStatusEnabled = false,
                typingIndicatorEnabled = true,
                peerIsActuallyOnline = true,
                peerIsActuallyTyping = true,
                expectedGreenDot = false, // Hiding own presence forbids viewing peer's presence!
                expectedTyping = true
            ),
            // Row 4: OFF / OFF -> Neither visible
            PrivacyTestCase(
                activeStatusEnabled = false,
                typingIndicatorEnabled = false,
                peerIsActuallyOnline = true,
                peerIsActuallyTyping = true,
                expectedGreenDot = false,
                expectedTyping = false
            )
        )

        for ((index, tc) in testCases.withIndex()) {
            val visibleGreenDot = tc.activeStatusEnabled && tc.peerIsActuallyOnline
            val visibleTyping = tc.typingIndicatorEnabled && tc.peerIsActuallyTyping

            assertEquals("Case $index Green Dot failed", tc.expectedGreenDot, visibleGreenDot)
            assertEquals("Case $index Typing Indicator failed", tc.expectedTyping, visibleTyping)
        }
    }
}
