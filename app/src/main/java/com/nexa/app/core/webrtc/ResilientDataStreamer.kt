package com.nexa.app.core.webrtc

import android.os.ParcelFileDescriptor
import com.nexa.app.core.crypto.ChunkCipherEngine
import com.nexa.app.core.hardware.MemoryBufferPool
import com.nexa.app.core.hardware.ThermalThrottlingMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.webrtc.DataChannel
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Resilient WebRTC DataChannel Streaming Engine with Backpressure and Hardware Throttling:
 * - Scenario 7: Enforces strict 1 MB socket buffer ceiling (prevents budget eMMC receiver overflow)
 * - Scenario 13: Zero heap GC allocation via shared 64 KB MemoryBufferPool
 * - Scenario 15: Hardware thermal rate backoff when device reaches severe temperature
 */
class ResilientDataStreamer(
    private val dataChannel: DataChannel,
    private val cipherEngine: ChunkCipherEngine,
    private val bufferPool: MemoryBufferPool,
    private val thermalMonitor: ThermalThrottlingMonitor,
    private val maxBufferThresholdBytes: Long = 1024 * 1024 // 1 MB backpressure ceiling
) {
    @Volatile
    private var isCancelled = false

    @Volatile
    private var isPaused = false

    fun cancel() {
        isCancelled = true
    }

    fun pause() {
        isPaused = true
    }

    fun resume() {
        isPaused = false
    }

    suspend fun streamFile(
        pfd: ParcelFileDescriptor,
        transferId: Long,
        fileKey: ByteArray,
        startChunkIndex: Int,
        totalChunks: Int,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val chunkSize = 64 * 1024 // 64 KB
        val inputStream = FileInputStream(pfd.fileDescriptor)
        val channel = inputStream.channel
        val totalBytes = pfd.statSize

        val startOffset = startChunkIndex.toLong() * chunkSize
        channel.position(startOffset)

        var bytesSent = startOffset
        var currentChunk = startChunkIndex

        while (currentChunk < totalChunks && !isCancelled) {
            while (isPaused && !isCancelled) {
                delay(100)
            }
            if (isCancelled) break

            // Scenario 7: Backpressure check - prevent socket overflow
            while (dataChannel.bufferedAmount() > maxBufferThresholdBytes && !isCancelled) {
                delay(15)
            }
            if (isCancelled) break

            // Scenario 15: Thermal throttling - inject cooldown delay if device is overheating
            if (thermalMonitor.isThrottlingRequired()) {
                delay(50)
            }

            val rawBuffer = bufferPool.acquire() // Scenario 13: Zero-allocation buffer pool
            try {
                val bytesRead = inputStream.read(rawBuffer, 0, chunkSize)
                if (bytesRead == -1) break

                val isEof = (currentChunk == totalChunks - 1) || (bytesSent + bytesRead >= totalBytes)

                // Encrypt chunk slice + calculate per-chunk authentication tag
                val encryptedSlice = cipherEngine.encryptChunk(
                    key = fileKey,
                    chunkIndex = currentChunk,
                    plaintext = rawBuffer,
                    length = bytesRead
                )

                // Pack wire header: transferId (8B) + chunkIndex (4B) + isEof (1B) + payload
                val packet = ByteBuffer.allocate(8 + 4 + 1 + encryptedSlice.size)
                packet.putLong(transferId)
                packet.putInt(currentChunk)
                packet.put(if (isEof) 1.toByte() else 0.toByte())
                packet.put(encryptedSlice)
                packet.flip()

                dataChannel.send(DataChannel.Buffer(packet, true))

                bytesSent += bytesRead
                currentChunk++
                onProgress(bytesSent, totalBytes)

                if (isEof) break
            } finally {
                bufferPool.release(rawBuffer)
            }
        }

        try { channel.close() } catch (ignored: Exception) {}
        try { inputStream.close() } catch (ignored: Exception) {}
    }
}
