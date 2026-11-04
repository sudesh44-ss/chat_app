package com.nexa.app.core.webrtc

import java.nio.ByteBuffer

/**
 * Binary Chunk Wire Format for WebRTC RTCDataChannel Streaming.
 * Header (13 Bytes):
 * [Transfer ID: 8B Long][Chunk Sequence Index: 4B Int][Is EOF Flag: 1B][Payload: up to 64 KB]
 */
data class FileChunk(
    val transferId: Long,
    val chunkIndex: Int,
    val isEof: Boolean,
    val data: ByteArray
) {
    /**
     * Serializes this chunk into a binary ByteBuffer ready for DataChannel transmission.
     */
    fun serialize(): ByteBuffer {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + data.size)
        buffer.putLong(transferId)
        buffer.putInt(chunkIndex)
        buffer.put(if (isEof) 1.toByte() else 0.toByte())
        buffer.put(data)
        buffer.flip()
        return buffer
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FileChunk
        if (transferId != other.transferId) return false
        if (chunkIndex != other.chunkIndex) return false
        if (isEof != other.isEof) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + isEof.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    companion object {
        const val HEADER_SIZE = 8 + 4 + 1 // 13 bytes
        const val DEFAULT_CHUNK_SIZE = 64 * 1024 // 64 KB sweet spot

        /**
         * Deserializes a binary ByteBuffer received from an incoming RTCDataChannel frame.
         */
        fun deserialize(buffer: ByteBuffer): FileChunk {
            require(buffer.remaining() >= HEADER_SIZE) {
                "Malformed FileChunk: buffer remaining ${buffer.remaining()} is less than header size $HEADER_SIZE"
            }
            val transferId = buffer.long
            val chunkIndex = buffer.int
            val isEof = buffer.get() == 1.toByte()
            val payload = ByteArray(buffer.remaining())
            buffer.get(payload)
            return FileChunk(transferId, chunkIndex, isEof, payload)
        }
    }
}
