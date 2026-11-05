package com.nexa.app

import com.nexa.app.core.media.MediaRoutingCoordinator
import com.nexa.app.core.media.MediaTransferResult
import com.nexa.app.core.media.MediaTransportRoute
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.proto.PacketType
import com.nexa.app.core.proto.SignalingPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaRoutingAndSignalingUnitTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun mediaRouting_ruleD_lightweightMediaPacksInline() {
        // Thumbnail 15 KB -> Inline
        val thumbnailDecision = MediaRoutingCoordinator.evaluateRoute(
            mediaSizeBytes = 15 * 1024L,
            isGroup = false,
            isRecipientOnline = true
        )
        assertEquals(MediaTransportRoute.INLINE_E2EE_WEBSOCKET, thumbnailDecision.selectedRoute)

        // Voice Note 60 KB -> Inline
        val voiceDecision = MediaRoutingCoordinator.evaluateRoute(
            mediaSizeBytes = 60 * 1024L,
            isGroup = true,
            isRecipientOnline = true
        )
        assertEquals(MediaTransportRoute.INLINE_E2EE_WEBSOCKET, voiceDecision.selectedRoute)

        // 64 KB boundary -> Inline
        val boundaryDecision = MediaRoutingCoordinator.evaluateRoute(
            mediaSizeBytes = 64 * 1024L,
            isGroup = false,
            isRecipientOnline = false
        )
        assertEquals(MediaTransportRoute.INLINE_E2EE_WEBSOCKET, boundaryDecision.selectedRoute)
    }

    @Test
    fun mediaRouting_ruleB_groupChatHeavyMediaRoutesDirectlyToCloudflareR2() {
        // Heavy Photo (1.5 MB) in Group Chat -> Cloudflare R2 Blind Fallback (1x upload)
        val groupPhotoDecision = MediaRoutingCoordinator.evaluateRoute(
            mediaSizeBytes = 1_500_000L,
            isGroup = true,
            isRecipientOnline = true
        )
        assertEquals(MediaTransportRoute.CLOUDFLARE_R2_BLIND_FALLBACK, groupPhotoDecision.selectedRoute)
        assertTrue(groupPhotoDecision.reason.contains("1x Cloudflare R2"))

        // Heavy Video (25 MB) in Group Chat -> Cloudflare R2 Blind Fallback
        val groupVideoDecision = MediaRoutingCoordinator.evaluateRoute(
            mediaSizeBytes = 25 * 1024 * 1024L,
            isGroup = true,
            isRecipientOnline = false
        )
        assertEquals(MediaTransportRoute.CLOUDFLARE_R2_BLIND_FALLBACK, groupVideoDecision.selectedRoute)
    }

    @Test
    fun mediaRouting_directChat_onlinePeerRoutesToWebRtcP2P() {
        // Direct 1-to-1 chat with online peer -> Priority 1 WebRTC P2P
        val directOnlineDecision = MediaRoutingCoordinator.evaluateRoute(
            mediaSizeBytes = 10 * 1024 * 1024L,
            isGroup = false,
            isRecipientOnline = true
        )
        assertEquals(MediaTransportRoute.WEBRTC_P2P_DIRECT, directOnlineDecision.selectedRoute)
        assertTrue(directOnlineDecision.reason.contains("Priority 1 WebRTC P2P"))
    }

    @Test
    fun mediaRouting_directChat_offlinePeerRoutesDirectlyToR2Fallback() {
        // Direct 1-to-1 chat with offline peer -> Immediate Cloudflare R2 Fallback
        val directOfflineDecision = MediaRoutingCoordinator.evaluateRoute(
            mediaSizeBytes = 10 * 1024 * 1024L,
            isGroup = false,
            isRecipientOnline = false
        )
        assertEquals(MediaTransportRoute.CLOUDFLARE_R2_BLIND_FALLBACK, directOfflineDecision.selectedRoute)
        assertTrue(directOfflineDecision.reason.contains("Recipient offline"))
    }

    @Test
    fun circuitBreaker_fastP2PConnection_completesSuccessfully() = runBlocking {
        val sampleFile = tempFolder.newFile("sample_video.mp4").apply {
            writeBytes(ByteArray(1024) { 0x33 })
        }

        val result = MediaRoutingCoordinator.executeWithIceCircuitBreaker(
            sourceFile = sampleFile,
            timeoutMs = 1000L,
            onP2PAttempt = {
                delay(50) // Fast 50ms connection
                123456789L
            },
            onR2Fallback = {
                throw AssertionError("Should not trigger fallback when P2P succeeds within timeout gate")
            }
        )

        assertTrue(result is MediaTransferResult.P2PSuccess)
        val success = result as MediaTransferResult.P2PSuccess
        assertEquals(123456789L, success.transferId)
        assertEquals(sampleFile.length(), success.totalBytes)
    }

    @Test
    fun circuitBreaker_symmetricNatTimeout_tripsAndAutoSwitchesToR2Fallback() = runBlocking {
        val sampleFile = tempFolder.newFile("large_cgnat_file.bin").apply {
            writeBytes("Sensitive payload for blind cloud storage".toByteArray())
        }

        var fallbackTriggered = false

        // Simulate 4.5s ICE timeout by specifying a 100ms test timeout gate and 300ms simulated STUN stall
        val result = MediaRoutingCoordinator.executeWithIceCircuitBreaker(
            sourceFile = sampleFile,
            timeoutMs = 100L,
            onP2PAttempt = {
                delay(500) // Carrier Symmetric NAT stalls ICE gathering
                999L
            },
            onR2Fallback = { encResult ->
                fallbackTriggered = true
                MediaTransferResult.BlindR2FallbackSuccess(
                    objectKey = "ephemeral/NX-USER/test.enc",
                    cipherHash = encResult.cipherHash,
                    mediaKey = encResult.mediaKey,
                    iv = encResult.iv,
                    sizeBytes = encResult.sizeBytes
                )
            }
        )

        assertTrue(fallbackTriggered)
        assertTrue(result is MediaTransferResult.BlindR2FallbackSuccess)
        val fallback = result as MediaTransferResult.BlindR2FallbackSuccess
        assertEquals("ephemeral/NX-USER/test.enc", fallback.objectKey)
        assertEquals(32, fallback.mediaKey.size)
        assertEquals(12, fallback.iv.size)
        assertTrue(fallback.cipherHash.isNotBlank())
    }

    @Test
    fun wirePacket_signalingOpcode_roundtripProtobufSerialization() {
        val original = NexaWirePacket(
            packetId = "sig_pkt_88",
            type = PacketType.PACKET_SIGNALING,
            senderNxId = "NX-SUDESH",
            recipientNxId = "NX-RAHUL",
            timestamp = 1731568200000L,
            signaling = SignalingPayload(
                transferId = 9876543210L,
                action = "OFFER",
                data = "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\ns=-\r\n"
            )
        )

        val encoded = original.encode()
        assertTrue(encoded.isNotEmpty())

        val decoded = NexaWirePacket.decode(encoded)
        assertEquals(original.packetId, decoded.packetId)
        assertEquals(PacketType.PACKET_SIGNALING, decoded.type)
        assertEquals(original.senderNxId, decoded.senderNxId)
        assertEquals(original.recipientNxId, decoded.recipientNxId)
        assertEquals(original.timestamp, decoded.timestamp)

        assertNotNull(decoded.signaling)
        val sig = decoded.signaling!!
        assertEquals(9876543210L, sig.transferId)
        assertEquals("OFFER", sig.action)
        assertEquals("v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\ns=-\r\n", sig.data)
    }
}
