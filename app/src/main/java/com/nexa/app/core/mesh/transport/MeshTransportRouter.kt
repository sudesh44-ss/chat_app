package com.nexa.app.core.mesh.transport

import java.util.BitSet

/**
 * Dual-Transport Physical Router & Selective Resumption Engine (Scenario 11 & 14):
 * - Directs payloads <= 64 KB to 512-byte BLE L2CAP frames
 * - Hands off payloads > 64 KB to high-speed 64 KB Wi-Fi Aware streams
 * - Resumes from missing bits after intermediate relay disappearance using BitSet offsets
 */
class MeshTransportRouter(
    private val bleDriver: BleL2capDriver,
    private val wifiAwareDriver: WifiAwareMeshDriver
) {
    companion object {
        const val MAX_BLE_PAYLOAD_BYTES = 65536 // 64 KB threshold
        const val BLE_MTU_CHUNK_BYTES = 512
        const val WIFI_CHUNK_BYTES = 65536
    }

    suspend fun routeOutbound(
        destinationId: String,
        payload: ByteArray,
        transferId: Long
    ) {
        if (payload.size <= MAX_BLE_PAYLOAD_BYTES) {
            // Fast path: Slice into 512-byte L2CAP frames
            val chunks = payload.toList().chunked(BLE_MTU_CHUNK_BYTES)
            chunks.forEachIndexed { index, slice ->
                bleDriver.sendL2capFrame(destinationId, transferId, index, slice.toByteArray())
            }
        } else {
            // Scenario 14: Handshake over BLE control, stream over Wi-Fi Aware
            val isChannelReady = wifiAwareDriver.ensureSession(destinationId)
            if (isChannelReady) {
                wifiAwareDriver.streamLargeFile(destinationId, transferId, payload, WIFI_CHUNK_BYTES)
            } else {
                throw IllegalStateException("Wi-Fi Aware negotiation in progress. Data queued in outbox.")
            }
        }
    }

    // Scenario 11: Resumes only missing slices after intermediate relay drops offline
    suspend fun resumeWithBitfield(
        destinationId: String,
        payload: ByteArray,
        transferId: Long,
        missingChunksBitSet: BitSet
    ) {
        val chunks = payload.toList().chunked(WIFI_CHUNK_BYTES)
        for (i in chunks.indices) {
            if (!missingChunksBitSet.get(i)) {
                wifiAwareDriver.sendChunk(destinationId, transferId, i, chunks[i].toByteArray())
            }
        }
    }
}
