package com.nexa.app.core.webrtc

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.delay
import org.webrtc.DataChannel
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Backpressure-Aware WebRTC DataChannel Streaming Bridge.
 * Streams 64 KB slices through FileChannel with resume capability,
 * strictly throttling dispatch whenever internal RTCDataChannel buffer exceeds 1 MB.
 */
class FileDataChannelBridge(
    private val dataChannel: DataChannel,
    private val maxBufferLimitBytes: Long = MAX_BUFFER_CEILING
) {

    /**
     * Streams a file from a ParcelFileDescriptor (obtained via Android Storage Access Framework).
     */
    suspend fun streamFromParcelFileDescriptor(
        pfd: ParcelFileDescriptor,
        transferId: Long,
        totalBytes: Long,
        startChunkIndex: Int = 0,
        onProgress: (sentBytes: Long, totalBytes: Long, chunkIndex: Int) -> Unit
    ) {
        val inputStream = FileInputStream(pfd.fileDescriptor)
        val channel = inputStream.channel

        val startOffset = startChunkIndex.toLong() * FileChunk.DEFAULT_CHUNK_SIZE
        if (startOffset < totalBytes) {
            channel.position(startOffset)
        }

        var bytesSent = startOffset
        var currentChunkIndex = startChunkIndex
        val buffer = ByteArray(FileChunk.DEFAULT_CHUNK_SIZE)

        try {
            while (true) {
                // BACKPRESSURE GUARD: Prevent buffer exhaustion & OOM on budget hardware
                while (dataChannel.bufferedAmount() > maxBufferLimitBytes) {
                    delay(BACKOFF_DELAY_MS)
                }

                if (dataChannel.state() != DataChannel.State.OPEN) {
                    throw IllegalStateException("DataChannel closed unexpectedly in state: ${dataChannel.state()}")
                }

                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) {
                    // Send EOF marker
                    val eofChunk = FileChunk(
                        transferId = transferId,
                        chunkIndex = currentChunkIndex,
                        isEof = true,
                        data = ByteArray(0)
                    )
                    dataChannel.send(DataChannel.Buffer(eofChunk.serialize(), true))
                    break
                }

                val isLast = (bytesSent + bytesRead >= totalBytes)
                val payload = if (bytesRead == FileChunk.DEFAULT_CHUNK_SIZE) {
                    buffer
                } else {
                    buffer.copyOf(bytesRead)
                }

                val chunk = FileChunk(
                    transferId = transferId,
                    chunkIndex = currentChunkIndex,
                    isEof = isLast,
                    data = payload
                )

                val byteBuffer = chunk.serialize()
                val dataBuffer = DataChannel.Buffer(byteBuffer, true) // Binary mode
                val sent = dataChannel.send(dataBuffer)

                if (!sent) {
                    Log.w(TAG, "DataChannel.send returned false for chunk $currentChunkIndex; backing off...")
                    delay(20)
                    continue
                }

                bytesSent += bytesRead
                currentChunkIndex++
                onProgress(bytesSent, totalBytes, currentChunkIndex)

                if (isLast) {
                    break
                }
            }
        } finally {
            try {
                channel.close()
                inputStream.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing stream", e)
            }
        }
    }

    /**
     * Streams directly from a standard File object.
     */
    suspend fun streamFromFile(
        file: File,
        transferId: Long,
        startChunkIndex: Int = 0,
        onProgress: (sentBytes: Long, totalBytes: Long, chunkIndex: Int) -> Unit
    ) {
        val inputStream = FileInputStream(file)
        val channel = inputStream.channel
        val totalBytes = file.length()

        val startOffset = startChunkIndex.toLong() * FileChunk.DEFAULT_CHUNK_SIZE
        if (startOffset < totalBytes) {
            channel.position(startOffset)
        }

        var bytesSent = startOffset
        var currentChunkIndex = startChunkIndex
        val buffer = ByteArray(FileChunk.DEFAULT_CHUNK_SIZE)

        try {
            while (true) {
                while (dataChannel.bufferedAmount() > maxBufferLimitBytes) {
                    delay(BACKOFF_DELAY_MS)
                }

                if (dataChannel.state() != DataChannel.State.OPEN) {
                    throw IllegalStateException("DataChannel closed in state: ${dataChannel.state()}")
                }

                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) {
                    val eofChunk = FileChunk(
                        transferId = transferId,
                        chunkIndex = currentChunkIndex,
                        isEof = true,
                        data = ByteArray(0)
                    )
                    dataChannel.send(DataChannel.Buffer(eofChunk.serialize(), true))
                    break
                }

                val isLast = (bytesSent + bytesRead >= totalBytes)
                val payload = if (bytesRead == FileChunk.DEFAULT_CHUNK_SIZE) {
                    buffer
                } else {
                    buffer.copyOf(bytesRead)
                }

                val chunk = FileChunk(
                    transferId = transferId,
                    chunkIndex = currentChunkIndex,
                    isEof = isLast,
                    data = payload
                )

                dataChannel.send(DataChannel.Buffer(chunk.serialize(), true))

                bytesSent += bytesRead
                currentChunkIndex++
                onProgress(bytesSent, totalBytes, currentChunkIndex)

                if (isLast) break
            }
        } finally {
            channel.close()
            inputStream.close()
        }
    }

    companion object {
        private const val TAG = "FileDataChannelBridge"
        const val MAX_BUFFER_CEILING = 1024L * 1024L // 1 MB buffer ceiling
        const val BACKOFF_DELAY_MS = 15L // 15ms throttling backoff
    }
}
