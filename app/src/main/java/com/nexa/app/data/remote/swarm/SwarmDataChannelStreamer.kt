package com.nexa.app.data.remote.swarm

import android.os.ParcelFileDescriptor
import android.util.Log
import com.nexa.app.core.swarm.SwarmConstants
import com.nexa.app.core.webrtc.FileChunk
import kotlinx.coroutines.delay
import org.webrtc.DataChannel
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

class SwarmDataChannelStreamer(
    private val dataChannel: DataChannel,
    private val maxBufferLimitBytes: Long = SwarmConstants.BACKPRESSURE_BUFFER_CEILING
) {

    suspend fun streamChunk(
        raf: RandomAccessFile,
        transferId: Long,
        chunkIndex: Int,
        totalChunks: Int,
        chunkSize: Int = SwarmConstants.CHUNK_SIZE
    ): Boolean {
        // BACKPRESSURE GUARD: Throttle if WebRTC internal queue is full
        while (dataChannel.bufferedAmount() > maxBufferLimitBytes) {
            delay(15)
        }

        if (dataChannel.state() != DataChannel.State.OPEN) {
            return false
        }

        val offset = chunkIndex.toLong() * chunkSize
        raf.seek(offset)
        val buffer = ByteArray(chunkSize)
        val read = raf.read(buffer)
        if (read <= 0) return false

        val isLast = (chunkIndex == totalChunks - 1)
        val payload = if (read == chunkSize) buffer else buffer.copyOf(read)

        val chunk = FileChunk(
            transferId = transferId,
            chunkIndex = chunkIndex,
            isEof = isLast,
            data = payload
        )

        val bufferWrapper = DataChannel.Buffer(chunk.serialize(), true)
        return dataChannel.send(bufferWrapper)
    }

    suspend fun streamAllChunksFromFile(
        file: File,
        transferId: Long,
        totalChunks: Int,
        onProgress: (completedChunks: Int) -> Unit
    ) {
        val raf = RandomAccessFile(file, "r")
        try {
            for (index in 0 until totalChunks) {
                while (dataChannel.bufferedAmount() > maxBufferLimitBytes) {
                    delay(15)
                }

                if (dataChannel.state() != DataChannel.State.OPEN) break

                val offset = index.toLong() * SwarmConstants.CHUNK_SIZE
                raf.seek(offset)
                val buffer = ByteArray(SwarmConstants.CHUNK_SIZE)
                val read = raf.read(buffer)
                if (read <= 0) break

                val isLast = (index == totalChunks - 1)
                val payload = if (read == SwarmConstants.CHUNK_SIZE) buffer else buffer.copyOf(read)

                val chunk = FileChunk(
                    transferId = transferId,
                    chunkIndex = index,
                    isEof = isLast,
                    data = payload
                )

                dataChannel.send(DataChannel.Buffer(chunk.serialize(), true))
                onProgress(index + 1)
            }
        } finally {
            raf.close()
        }
    }
}
