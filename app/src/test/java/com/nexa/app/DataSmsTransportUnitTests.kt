package com.nexa.app

import com.nexa.app.core.crypto.ChannelEncryptor
import com.nexa.app.core.proto.EncryptedMessagePayload
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.proto.PacketType
import com.nexa.app.core.sms.ChunkReassembler
import com.nexa.app.core.sms.SmsConstants
import com.nexa.app.core.sms.SmsPayloadCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.Random

class DataSmsTransportUnitTests {

    @Test
    fun smsConstants_portAndLimits_strictlyConformToPduStandard() {
        assertEquals(8901.toShort(), SmsConstants.DATA_SMS_PORT)
        assertEquals(2, SmsConstants.MAGIC_BYTES.size)
        assertEquals(0x4E.toByte(), SmsConstants.MAGIC_BYTES[0]) // 'N'
        assertEquals(0x58.toByte(), SmsConstants.MAGIC_BYTES[1]) // 'X'
        assertEquals(12, SmsConstants.HEADER_SIZE)
        assertEquals(121, SmsConstants.MAX_CHUNK_PAYLOAD_SIZE)
        assertEquals(133, SmsConstants.MAX_PACKET_SIZE)
        assertTrue(SmsConstants.MAX_PACKET_SIZE <= 140) // Below 8-bit binary SMS PDU limit
    }

    @Test
    fun smsPayloadCodec_encodeAndDecode_singleChunk_succeeds() {
        val messageId = 987654321012345678L
        val testData = "NEXA Port 8901 Encrypted Payload".toByteArray(StandardCharsets.UTF_8)

        val encoded = SmsPayloadCodec.encodeChunk(
            messageId = messageId,
            chunkIndex = 0,
            totalChunks = 1,
            chunkData = testData
        )

        assertTrue(encoded.size <= SmsConstants.MAX_PACKET_SIZE)
        assertEquals(SmsConstants.HEADER_SIZE + testData.size, encoded.size)

        val decoded = SmsPayloadCodec.decodeChunk(encoded)
        assertNotNull(decoded)
        assertEquals(messageId, decoded?.messageId)
        assertEquals(0, decoded?.chunkIndex)
        assertEquals(1, decoded?.totalChunks)
        assertTrue(testData.contentEquals(decoded!!.payload))
    }

    @Test
    fun smsPayloadCodec_invalidMagicBytes_returnsNullForSilentDrop() {
        val rawData = ByteArray(50)
        Random().nextBytes(rawData)
        rawData[0] = 0x11 // Invalid magic
        rawData[1] = 0x22

        val decoded = SmsPayloadCodec.decodeChunk(rawData)
        assertNull("Non-NEXA SMS packet must be silently dropped with null", decoded)
    }

    @Test
    fun smsPayloadCodec_multiPartChunkingAndReassembly_handlesOutOfOrderArrival() {
        val messageId = 445566778899L
        // Create a large payload (350 bytes) that exceeds a single SMS (needs 3 chunks)
        val originalPayload = ByteArray(350)
        for (i in originalPayload.indices) {
            originalPayload[i] = (i % 256).toByte()
        }

        val chunks = SmsPayloadCodec.chunkPayload(messageId, originalPayload)
        assertEquals(3, chunks.size) // 121 + 121 + 108 = 350 bytes

        for (chunkBytes in chunks) {
            assertTrue("Chunk size must never exceed 133 bytes", chunkBytes.size <= SmsConstants.MAX_PACKET_SIZE)
        }

        // Simulate out-of-order receipt: Chunk 2, then Chunk 0, then Chunk 1
        val decodedChunk0 = SmsPayloadCodec.decodeChunk(chunks[0])!!
        val decodedChunk1 = SmsPayloadCodec.decodeChunk(chunks[1])!!
        val decodedChunk2 = SmsPayloadCodec.decodeChunk(chunks[2])!!

        val reassembler = ChunkReassembler()

        // Ingest Chunk 2
        val part2Result = reassembler.ingestChunk(decodedChunk2)
        assertNull("Should not be complete with only chunk 2", part2Result)

        // Ingest Chunk 0
        val part0Result = reassembler.ingestChunk(decodedChunk0)
        assertNull("Should not be complete with chunks 0 and 2", part0Result)

        // Ingest Chunk 1 (final piece)
        val finalAssembled = reassembler.ingestChunk(decodedChunk1)
        assertNotNull("Should complete when all chunks arrive", finalAssembled)
        assertEquals(350, finalAssembled?.size)
        assertTrue(originalPayload.contentEquals(finalAssembled!!))
    }

    @Test
    fun channelEncryptor_chacha20Poly1305_encryptAndDecrypt_succeeds() {
        val channelKey = ChannelEncryptor.generateRandomKey()
        val encryptor = ChannelEncryptor(channelKey)

        val plaintext = "Top-Secret Port 8901 Data SMS Wire Message".toByteArray(StandardCharsets.UTF_8)
        val associatedData = "AAD_NEXA_V1".toByteArray(StandardCharsets.UTF_8)

        val ciphertext = encryptor.encrypt(plaintext, associatedData)
        // Ciphertext should contain 12-byte IV + plaintext.length + 16-byte Poly1305 tag
        assertEquals(12 + plaintext.size + 16, ciphertext.size)

        val decrypted = encryptor.decrypt(ciphertext, associatedData)
        assertTrue(plaintext.contentEquals(decrypted))
        assertEquals("Top-Secret Port 8901 Data SMS Wire Message", String(decrypted, StandardCharsets.UTF_8))
    }

    @Test
    fun channelEncryptor_tamperedCiphertext_failsAuthentication() {
        val channelKey = ChannelEncryptor.generateRandomKey()
        val encryptor = ChannelEncryptor(channelKey)

        val plaintext = "Tamper Test Payload".toByteArray(StandardCharsets.UTF_8)
        val ciphertext = encryptor.encrypt(plaintext)

        // Tamper with one byte in the ciphertext payload
        ciphertext[15] = (ciphertext[15].toInt() xor 0xFF).toByte()

        try {
            encryptor.decrypt(ciphertext)
            fail("Decryption of tampered ciphertext must throw GeneralSecurityException")
        } catch (e: GeneralSecurityException) {
            // Expected Poly1305 tag verification failure
        }
    }

    @Test
    fun endToEnd_nexaWirePacket_through_smsChunkingAndReassembly() {
        val channelKey = ChannelEncryptor.generateRandomKey()
        val encryptor = ChannelEncryptor(channelKey)

        // 1. Create original packet
        val originalPacket = NexaWirePacket(
            packetId = "sms_pkt_7788",
            type = PacketType.PACKET_MESSAGE,
            senderNxId = "NX-9842-K7X",
            recipientNxId = "NX-3321-B9Q",
            timestamp = 1730000000000L,
            message = EncryptedMessagePayload(
                conversationSeq = 1L,
                ciphertext = "Offline message sent across cellular airwaves".toByteArray(StandardCharsets.UTF_8),
                nonce = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            )
        )

        // 2. Encode to Protobuf wire bytes
        val wireBytes = originalPacket.encode()

        // 3. Encrypt wire bytes with ChaCha20-Poly1305
        val encryptedWireBytes = encryptor.encrypt(wireBytes)

        // 4. Split into Data SMS packets
        val messageId = 889911223344L
        val smsPackets = SmsPayloadCodec.chunkPayload(messageId, encryptedWireBytes)

        // 5. Simulate reception & decoding of chunks on recipient phone
        val reassembler = ChunkReassembler()
        var assembledBytes: ByteArray? = null
        for (packetBytes in smsPackets) {
            val chunk = SmsPayloadCodec.decodeChunk(packetBytes)
            assertNotNull(chunk)
            val res = reassembler.ingestChunk(chunk!!)
            if (res != null) {
                assembledBytes = res
            }
        }

        assertNotNull("Assembled bytes should not be null", assembledBytes)
        assertTrue(encryptedWireBytes.contentEquals(assembledBytes!!))

        // 6. Decrypt assembled wire bytes
        val decryptedWireBytes = encryptor.decrypt(assembledBytes)
        assertTrue(wireBytes.contentEquals(decryptedWireBytes))

        // 7. Decode back to NexaWirePacket
        val reconstructedPacket = NexaWirePacket.decode(decryptedWireBytes)

        assertEquals(originalPacket.packetId, reconstructedPacket.packetId)
        assertEquals(originalPacket.senderNxId, reconstructedPacket.senderNxId)
        assertEquals(originalPacket.recipientNxId, reconstructedPacket.recipientNxId)
        assertEquals(originalPacket.timestamp, reconstructedPacket.timestamp)
        assertEquals(originalPacket.message?.conversationSeq, reconstructedPacket.message?.conversationSeq)
        assertTrue(originalPacket.message!!.ciphertext.contentEquals(reconstructedPacket.message!!.ciphertext))
    }
}
