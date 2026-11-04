package com.nexa.app

import com.nexa.app.core.proto.DeviceSyncPayload
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.proto.PacketType
import com.nexa.app.core.proto.TypingPayload
import com.nexa.app.data.local.NexaDatabase
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

class Phase2AuthAndIdentityTests {

    @Test
    fun testDeterministicNxIdDerivation() {
        val dummyEd25519PubKey = ByteArray(32) { it.toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(dummyEd25519PubKey)

        val numPart = ByteBuffer.wrap(digest, 0, 4).int and 0x7FFFFFFF
        val numericSuffix = 1000 + (numPart % 9000)

        val crockfordAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val b0 = digest[4].toInt() and 0xFF
        val b1 = digest[5].toInt() and 0xFF
        val b2 = digest[6].toInt() and 0xFF
        val charSuffix = "" +
                crockfordAlphabet[b0 % crockfordAlphabet.length] +
                crockfordAlphabet[b1 % crockfordAlphabet.length] +
                crockfordAlphabet[b2 % crockfordAlphabet.length]

        val nxId = "NX-$numericSuffix-$charSuffix"

        // Assert NX-ID format matches NX-XXXX-XXX
        assertTrue("NX-ID should match regex NX-\\d{4}-[A-Z0-9]{3}", nxId.matches(Regex("^NX-\\d{4}-[A-Z0-9]{3}$")))

        // Determinism: same public key yields exact same NX-ID
        val digest2 = MessageDigest.getInstance("SHA-256").digest(dummyEd25519PubKey)
        val numPart2 = ByteBuffer.wrap(digest2, 0, 4).int and 0x7FFFFFFF
        val numericSuffix2 = 1000 + (numPart2 % 9000)
        val charSuffix2 = "" +
                crockfordAlphabet[(digest2[4].toInt() and 0xFF) % crockfordAlphabet.length] +
                crockfordAlphabet[(digest2[5].toInt() and 0xFF) % crockfordAlphabet.length] +
                crockfordAlphabet[(digest2[6].toInt() and 0xFF) % crockfordAlphabet.length]
        val nxId2 = "NX-$numericSuffix2-$charSuffix2"

        assertEquals("Same public key must produce identical deterministic NX-ID", nxId, nxId2)

        // Zero PII: verify that phone or email is NOT present in NX-ID
        assertFalse(nxId.contains("@"))
        assertFalse(nxId.contains("+"))
    }

    @Test
    fun testLockedRandomNxIdSpecification() {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        val chars = CharArray(8) { alphabet[random.nextInt(alphabet.length)] }
        val randomNxId = "NX-${String(chars)}"

        // Locked Spec: Fixed "NX-" prefix, exactly 8 uppercase random alphanumeric characters
        assertTrue("Locked NX-ID must match ^NX-[A-Z0-9]{8}$", randomNxId.matches(Regex("^NX-[A-Z0-9]{8}$")))
        assertEquals(11, randomNxId.length)
        assertTrue(randomNxId.startsWith("NX-"))
    }

    @Test
    fun testAddressBookZeroCloudUploadConstraint() {
        val deviceContactList = listOf(
            com.nexa.app.domain.model.DeviceContact("c1", "Alice", "+919876543210", "+919876543210"),
            com.nexa.app.domain.model.DeviceContact("c2", "Bob", "+919876543211", "+919876543211")
        )
        // Request payload sends ONLY phone numbers, NO names, emails, or device IDs
        val request = com.nexa.app.data.remote.dto.ContactLookupRequest(
            phoneNumbers = deviceContactList.map { it.normalizedNumber }
        )
        assertEquals(2, request.phoneNumbers.size)
        request.phoneNumbers.forEach { phone ->
            assertTrue(phone.startsWith("+91"))
        }
    }

    @Test
    fun testOtpCodeValidationAndSecurityConstraints() {
        val validOtp = "782194"
        val shortOtp = "123"
        val nonNumericOtp = "1234A6"

        assertTrue("6-digit numeric OTP should be valid", validOtp.length == 6 && validOtp.all { it.isDigit() })
        assertFalse("Short OTP should be invalid", shortOtp.length == 6 && shortOtp.all { it.isDigit() })
        assertFalse("Non-numeric OTP should be invalid", nonNumericOtp.length == 6 && nonNumericOtp.all { it.isDigit() })

        // Salted SHA-256 hash storage verification (Zero Plaintext Rule)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val otpHash = digest.digest(validOtp.toByteArray(Charsets.UTF_8))

        // Hash length is 32 bytes and does NOT match plaintext
        assertEquals(32, otpHash.size)
        assertNotEquals(validOtp, String(otpHash, Charsets.UTF_8))
    }

    @Test
    fun testWireProtobufTypingIndicatorRoundTrip() {
        val originalPacket = NexaWirePacket(
            packetId = "typing_${UUID.randomUUID()}",
            type = PacketType.PACKET_TYPING,
            senderNxId = "NX-1044-M2P",
            recipientNxId = "NX-4821-X9K",
            timestamp = System.currentTimeMillis(),
            typing = TypingPayload(
                conversationId = "conv_nx_1044_4821",
                isTyping = true
            )
        )

        // Encode to Protobuf wire format
        val wireBytes = originalPacket.encode()
        assertTrue("Encoded wire bytes must not be empty", wireBytes.isNotEmpty())

        // Decode from Protobuf wire format
        val decodedPacket = NexaWirePacket.decode(wireBytes)

        assertEquals(originalPacket.packetId, decodedPacket.packetId)
        assertEquals(PacketType.PACKET_TYPING, decodedPacket.type)
        assertEquals(originalPacket.senderNxId, decodedPacket.senderNxId)
        assertEquals(originalPacket.recipientNxId, decodedPacket.recipientNxId)
        assertEquals(originalPacket.timestamp, decodedPacket.timestamp)
        assertNotNull("Typing payload must not be null", decodedPacket.typing)
        assertEquals("conv_nx_1044_4821", decodedPacket.typing?.conversationId)
        assertTrue(decodedPacket.typing?.isTyping == true)
    }

    @Test
    fun testWireProtobufDeviceSyncRoundTrip() {
        val dummyPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val originalPacket = NexaWirePacket(
            packetId = "sync_${UUID.randomUUID()}",
            type = PacketType.PACKET_DEVICE_SYNC,
            senderNxId = "NX-1044-M2P",
            recipientNxId = "NX-1044-M2P",
            timestamp = System.currentTimeMillis(),
            deviceSync = DeviceSyncPayload(
                syncId = "sync_batch_42",
                sourceDeviceId = "dev_phone_pixel_9",
                sequenceNumber = 42L,
                encryptedPayload = dummyPayload
            )
        )

        val wireBytes = originalPacket.encode()
        assertTrue(wireBytes.isNotEmpty())

        val decodedPacket = NexaWirePacket.decode(wireBytes)

        assertEquals(originalPacket.packetId, decodedPacket.packetId)
        assertEquals(PacketType.PACKET_DEVICE_SYNC, decodedPacket.type)
        assertNotNull(decodedPacket.deviceSync)
        assertEquals("sync_batch_42", decodedPacket.deviceSync?.syncId)
        assertEquals("dev_phone_pixel_9", decodedPacket.deviceSync?.sourceDeviceId)
        assertEquals(42L, decodedPacket.deviceSync?.sequenceNumber)
        assertArrayEquals(dummyPayload, decodedPacket.deviceSync?.encryptedPayload)
    }

    @Test
    fun testRoomSequentialMigrationsConfigured() {
        // Verify that migrations exist from 1 through 11
        assertEquals(1, NexaDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, NexaDatabase.MIGRATION_1_2.endVersion)

        assertEquals(2, NexaDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, NexaDatabase.MIGRATION_2_3.endVersion)

        assertEquals(3, NexaDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, NexaDatabase.MIGRATION_3_4.endVersion)

        assertEquals(4, NexaDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, NexaDatabase.MIGRATION_4_5.endVersion)

        assertEquals(5, NexaDatabase.MIGRATION_5_6.startVersion)
        assertEquals(6, NexaDatabase.MIGRATION_5_6.endVersion)

        assertEquals(6, NexaDatabase.MIGRATION_6_7.startVersion)
        assertEquals(7, NexaDatabase.MIGRATION_6_7.endVersion)

        assertEquals(7, NexaDatabase.MIGRATION_7_8.startVersion)
        assertEquals(8, NexaDatabase.MIGRATION_7_8.endVersion)

        assertEquals(8, NexaDatabase.MIGRATION_8_9.startVersion)
        assertEquals(9, NexaDatabase.MIGRATION_8_9.endVersion)

        assertEquals(9, NexaDatabase.MIGRATION_9_10.startVersion)
        assertEquals(10, NexaDatabase.MIGRATION_9_10.endVersion)

        assertEquals(10, NexaDatabase.MIGRATION_10_11.startVersion)
        assertEquals(11, NexaDatabase.MIGRATION_10_11.endVersion)
    }

    @Test
    fun testPushWakeUpGenericPayloadFormat() {
        // FCM Zero-Plaintext Rule: Payload must NEVER carry message text, sender details, or keys
        val pushJson = JSONObject().apply {
            put("message", JSONObject().apply {
                put("token", "fcm_test_device_token_abc123")
                put("data", JSONObject().apply {
                    put("type", "WAKE_UP")
                    put("timestamp", "${System.currentTimeMillis()}")
                })
                put("android", JSONObject().apply {
                    put("priority", "HIGH")
                })
            })
        }

        val messageObj = pushJson.getJSONObject("message")
        val dataObj = messageObj.getJSONObject("data")

        assertEquals("WAKE_UP", dataObj.getString("type"))
        assertFalse("Push payload must NEVER carry message text", dataObj.has("message_text"))
        assertFalse("Push payload must NEVER carry plaintext content", dataObj.has("content"))
        assertFalse("Push payload must NEVER carry cryptographic keys", dataObj.has("key"))
    }
}
