package com.nexa.app

import com.nexa.app.core.crypto.ChunkEncryptor
import com.nexa.app.core.webrtc.FileChunk
import com.nexa.app.core.webrtc.FileDataChannelBridge
import com.nexa.app.data.local.entity.TransferEntity
import com.nexa.app.data.remote.signaling.SignalingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.Random

class P2PFileTransferUnitTests {

    @Test
    fun fileChunk_constants_conformToSpecification() {
        assertEquals(65536, FileChunk.DEFAULT_CHUNK_SIZE) // 64 KB Slicing
        assertEquals(13, FileChunk.HEADER_SIZE)            // 8B TransferId + 4B ChunkIndex + 1B EOF
    }

    @Test
    fun fileChunk_serializationAndDeserialization_preservesBinaryIntegrity() {
        val transferId = 1730000000123L
        val chunkIndex = 42
        val payload = ByteArray(65536).also { Random(101).nextBytes(it) }

        // Non-EOF chunk
        val chunk = FileChunk(
            transferId = transferId,
            chunkIndex = chunkIndex,
            isEof = false,
            data = payload
        )

        val serialized = chunk.serialize()
        assertEquals(FileChunk.HEADER_SIZE + payload.size, serialized.remaining())

        val deserialized = FileChunk.deserialize(serialized)
        assertEquals(transferId, deserialized.transferId)
        assertEquals(chunkIndex, deserialized.chunkIndex)
        assertFalse(deserialized.isEof)
        assertTrue(payload.contentEquals(deserialized.data))

        // EOF chunk
        val eofPayload = ByteArray(1024).also { Random(102).nextBytes(it) }
        val eofChunk = FileChunk(
            transferId = transferId,
            chunkIndex = 100,
            isEof = true,
            data = eofPayload
        )
        val eofSerialized = eofChunk.serialize()
        val eofDeserialized = FileChunk.deserialize(eofSerialized)
        assertEquals(transferId, eofDeserialized.transferId)
        assertEquals(100, eofDeserialized.chunkIndex)
        assertTrue(eofDeserialized.isEof)
        assertTrue(eofPayload.contentEquals(eofDeserialized.data))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fileChunk_truncatedBytes_throwsIllegalArgumentException() {
        // Less than 13 bytes header
        val truncated = ByteBuffer.wrap(ByteArray(10))
        FileChunk.deserialize(truncated)
    }

    @Test
    fun chunkEncryptor_chacha20Poly1305_encryptAndDecryptRoundtrip() {
        val key = ByteArray(32).also { Random(201).nextBytes(it) }
        val plaintext = ByteArray(65536).also { Random(202).nextBytes(it) }

        val encryptor = ChunkEncryptor(key)
        val ciphertext = encryptor.encryptChunk(plaintext)
        // ChaCha20-Poly1305 adds 12-byte IV + 16-byte Poly1305 MAC tag = 28 bytes
        assertEquals(plaintext.size + 12 + 16, ciphertext.size)

        val decrypted = encryptor.decryptChunk(ciphertext)
        assertTrue(plaintext.contentEquals(decrypted))
    }

    @Test(expected = GeneralSecurityException::class)
    fun chunkEncryptor_tamperedCiphertext_failsDecryption() {
        val key = ByteArray(32).also { Random(301).nextBytes(it) }
        val plaintext = "Sensitive direct P2P binary chunk data".toByteArray()

        val encryptor = ChunkEncryptor(key)
        val ciphertext = encryptor.encryptChunk(plaintext)
        // Corrupt one byte of ciphertext
        ciphertext[ciphertext.size - 5] = (ciphertext[ciphertext.size - 5].toInt() xor 0xFF).toByte()

        encryptor.decryptChunk(ciphertext)
    }

    @Test
    fun signalingMessage_offerTransfer_roundtripJsonSerialization() {
        val offer = SignalingMessage.OfferTransfer(
            transferId = 9876543210L,
            fileName = "ubuntu-24.04-desktop-amd64.iso",
            fileSizeBytes = 5_000_000_000L,
            fileHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            senderNxId = "NX-9842-K7X",
            recipientNxId = "NX-1234-A1B"
        )

        val jsonStr = offer.toJson().toString()
        val parsed = SignalingMessage.fromJson(jsonStr) as? SignalingMessage.OfferTransfer

        assertNotNull(parsed)
        assertEquals(offer.transferId, parsed!!.transferId)
        assertEquals(offer.fileName, parsed.fileName)
        assertEquals(offer.fileSizeBytes, parsed.fileSizeBytes)
        assertEquals(offer.fileHash, parsed.fileHash)
        assertEquals(offer.senderNxId, parsed.senderNxId)
        assertEquals(offer.recipientNxId, parsed.recipientNxId)
        assertEquals(SignalingMessage.ACTION_OFFER, parsed.action)
    }

    @Test
    fun signalingMessage_sdpAndIce_roundtripJsonSerialization() {
        val sdp = SignalingMessage.SignalingSdp(
            transferId = 12345L,
            sdpType = "offer",
            sdpDescription = "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n",
            senderNxId = "NX-AAA-111",
            recipientNxId = "NX-BBB-222"
        )
        val sdpParsed = SignalingMessage.fromJson(sdp.toJson().toString()) as? SignalingMessage.SignalingSdp
        assertNotNull(sdpParsed)
        assertEquals(sdp.sdpType, sdpParsed!!.sdpType)
        assertEquals(sdp.sdpDescription, sdpParsed.sdpDescription)

        val ice = SignalingMessage.SignalingIce(
            transferId = 12345L,
            sdpMid = "data",
            sdpMLineIndex = 0,
            sdpCandidate = "candidate:1 1 UDP 2122252543 192.168.1.100 54321 typ host",
            senderNxId = "NX-AAA-111",
            recipientNxId = "NX-BBB-222"
        )
        val iceParsed = SignalingMessage.fromJson(ice.toJson().toString()) as? SignalingMessage.SignalingIce
        assertNotNull(iceParsed)
        assertEquals(ice.sdpMid, iceParsed!!.sdpMid)
        assertEquals(ice.sdpMLineIndex, iceParsed.sdpMLineIndex)
        assertEquals(ice.sdpCandidate, iceParsed.sdpCandidate)
    }

    @Test
    fun signalingMessage_resumeFromChunk_roundtripJsonSerialization() {
        val resume = SignalingMessage.ResumeFromChunk(
            transferId = 8888L,
            startChunkIndex = 512,
            senderNxId = "NX-AAA-111",
            recipientNxId = "NX-BBB-222"
        )
        val parsed = SignalingMessage.fromJson(resume.toJson().toString()) as? SignalingMessage.ResumeFromChunk
        assertNotNull(parsed)
        assertEquals(8888L, parsed!!.transferId)
        assertEquals(512, parsed.startChunkIndex)
    }

    @Test
    fun signalingMessage_malformedOrUnknownJson_returnsNullSafely() {
        assertNull(SignalingMessage.fromJson("{ \"invalid\": true }"))
        assertNull(SignalingMessage.fromJson("not-json"))
    }

    @Test
    fun transferEntity_chunkCountCalculation_forVariousFileSizes() {
        val chunkSize = FileChunk.DEFAULT_CHUNK_SIZE.toLong()

        // Exact single chunk
        val total1 = ((chunkSize + chunkSize - 1) / chunkSize).toInt()
        assertEquals(1, total1)

        // Single chunk + 1 byte -> 2 chunks
        val total2 = (((chunkSize + 1) + chunkSize - 1) / chunkSize).toInt()
        assertEquals(2, total2)

        // 1.2 GB file = 1,288,490,188 bytes
        val fileSizeBytes = 1_288_490_188L
        val totalChunks = ((fileSizeBytes + chunkSize - 1) / chunkSize).toInt()
        assertEquals(19661, totalChunks)

        val entity = TransferEntity(
            transferId = 1L,
            conversationId = "NX-PEER",
            fileName = "large_video.mp4",
            fileSizeBytes = fileSizeBytes,
            localUri = "content://media/external/file/101",
            fileHash = "mock_hash",
            chunksCompleted = 9830,
            totalChunks = totalChunks,
            status = "TRANSFERRING",
            isIncoming = true
        )

        val percent = (entity.chunksCompleted * 100) / entity.totalChunks
        assertEquals(49, percent)
    }

    @Test
    fun fileDataChannelBridge_backpressureThresholds_conformToSpecification() {
        assertEquals(1024 * 1024L, FileDataChannelBridge.MAX_BUFFER_CEILING) // 1 MB ceiling
        assertEquals(15L, FileDataChannelBridge.BACKOFF_DELAY_MS)           // 15ms throttling
    }
}
