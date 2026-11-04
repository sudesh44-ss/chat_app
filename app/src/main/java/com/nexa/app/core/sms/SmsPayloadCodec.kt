package com.nexa.app.core.sms

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

data class SmsChunkPacket(
    val messageId: Long,
    val chunkIndex: Int,
    val totalChunks: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SmsChunkPacket
        if (messageId != other.messageId) return false
        if (chunkIndex != other.chunkIndex) return false
        if (totalChunks != other.totalChunks) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + totalChunks
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Binary Codec for chunking and packing Data SMS packets over Port 8901.
 * Supports:
 * 1. Standard 12-byte header: [Magic 2B][Message ID 8B][Chunk Index 1B][Total Chunks 1B]
 * 2. High-density 5-byte header: [MsgID: 2B][Seq: 1B][Total: 1B][CRC8: 1B]
 * Strictly ensures packets do not exceed 133 bytes so they never fragment at the telecom layer.
 */
object SmsPayloadCodec {

    const val COMPACT_HEADER_SIZE = 5 // [MsgId: 2B][Seq: 1B][Total: 1B][CRC8: 1B]
    const val MAX_COMPACT_PAYLOAD_SIZE = 128 // 133 - 5 = 128 bytes

    /**
     * Computes 8-bit Cyclic Redundancy Check (CRC-8 SMBus polynomial: 0x07).
     */
    fun computeCrc8(data: ByteArray, offset: Int = 0, length: Int = data.size): Byte {
        var crc = 0x00
        for (i in offset until (offset + length)) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if ((crc and 0x80) != 0) {
                    ((crc shl 1) xor 0x07) and 0xFF
                } else {
                    (crc shl 1) and 0xFF
                }
            }
        }
        return (crc and 0xFF).toByte()
    }

    /**
     * Serializes a single chunk into wire binary format:
     * [Magic 2B][Message ID 8B][Chunk Index 1B][Total Chunks 1B][Chunk Payload NB]
     */
    fun encodeChunk(
        messageId: Long,
        chunkIndex: Int,
        totalChunks: Int,
        chunkData: ByteArray
    ): ByteArray {
        require(chunkData.size <= SmsConstants.MAX_CHUNK_PAYLOAD_SIZE) {
            "Chunk data exceeds maximum allowable chunk payload size: ${chunkData.size} > ${SmsConstants.MAX_CHUNK_PAYLOAD_SIZE}"
        }
        require(chunkIndex in 0 until totalChunks) {
            "Invalid chunk index $chunkIndex for totalChunks $totalChunks"
        }
        require(totalChunks in 1..255) {
            "Total chunks must be between 1 and 255"
        }

        val buffer = ByteBuffer.allocate(SmsConstants.HEADER_SIZE + chunkData.size)
        buffer.put(SmsConstants.MAGIC_BYTES)
        buffer.putLong(messageId)
        buffer.put(chunkIndex.toByte())
        buffer.put(totalChunks.toByte())
        buffer.put(chunkData)
        return buffer.array()
    }

    /**
     * Serializes a single chunk into high-density 5-byte header format:
     * [MsgId: 2B][Seq: 1B][Total: 1B][CRC8: 1B][Chunk Payload NB]
     */
    fun encodeCompactChunk(
        messageId: Short,
        chunkIndex: Int,
        totalChunks: Int,
        chunkData: ByteArray
    ): ByteArray {
        require(chunkData.size <= MAX_COMPACT_PAYLOAD_SIZE) {
            "Compact chunk data exceeds maximum allowable payload size: ${chunkData.size} > $MAX_COMPACT_PAYLOAD_SIZE"
        }
        require(chunkIndex in 0 until totalChunks) {
            "Invalid chunk index $chunkIndex for totalChunks $totalChunks"
        }
        require(totalChunks in 1..255) {
            "Total chunks must be between 1 and 255"
        }

        val headerWithoutCrc = ByteBuffer.allocate(4)
        headerWithoutCrc.putShort(messageId)
        headerWithoutCrc.put(chunkIndex.toByte())
        headerWithoutCrc.put(totalChunks.toByte())
        val headerBytes = headerWithoutCrc.array()

        val fullDataForCrc = ByteArray(4 + chunkData.size)
        System.arraycopy(headerBytes, 0, fullDataForCrc, 0, 4)
        System.arraycopy(chunkData, 0, fullDataForCrc, 4, chunkData.size)
        val crc = computeCrc8(fullDataForCrc)

        val packet = ByteBuffer.allocate(COMPACT_HEADER_SIZE + chunkData.size)
        packet.put(headerBytes)
        packet.put(crc)
        packet.put(chunkData)
        return packet.array()
    }

    /**
     * Deserializes a binary payload from an incoming SMS PDU.
     * Supports both 12-byte standard format and 5-byte compact header format.
     * Returns null if header is malformed or CRC/magic check fails (silent drop).
     */
    fun decodeChunk(rawBytes: ByteArray): SmsChunkPacket? {
        if (rawBytes.size < COMPACT_HEADER_SIZE) return null

        // Format 1: Standard 12-byte header with magic bytes [0x4E, 0x58]
        if (rawBytes.size >= SmsConstants.HEADER_SIZE &&
            rawBytes[0] == SmsConstants.MAGIC_BYTES[0] &&
            rawBytes[1] == SmsConstants.MAGIC_BYTES[1]
        ) {
            return try {
                val buffer = ByteBuffer.wrap(rawBytes)
                buffer.position(2) // Skip magic bytes

                val messageId = buffer.long
                val chunkIndex = buffer.get().toInt() and 0xFF
                val totalChunks = buffer.get().toInt() and 0xFF

                if (totalChunks <= 0 || chunkIndex >= totalChunks) return null

                val payloadLength = buffer.remaining()
                val payload = ByteArray(payloadLength)
                buffer.get(payload)

                SmsChunkPacket(
                    messageId = messageId,
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                    payload = payload
                )
            } catch (e: Exception) {
                null
            }
        }

        // Format 2: Compact 5-byte header with CRC8
        return decodeCompactChunk(rawBytes)
    }

    /**
     * Deserializes a 5-byte header compact chunk.
     */
    fun decodeCompactChunk(rawBytes: ByteArray): SmsChunkPacket? {
        if (rawBytes.size < COMPACT_HEADER_SIZE) return null

        val expectedCrc = rawBytes[4]
        val dataForCrc = ByteArray(rawBytes.size - 1)
        System.arraycopy(rawBytes, 0, dataForCrc, 0, 4)
        System.arraycopy(rawBytes, 5, dataForCrc, 4, rawBytes.size - 5)
        val actualCrc = computeCrc8(dataForCrc)
        if (expectedCrc != actualCrc) {
            return null // Corrupted packet or unverified CRC
        }

        return try {
            val buffer = ByteBuffer.wrap(rawBytes)
            val msgId = buffer.short.toLong() and 0xFFFFL
            val seq = buffer.get().toInt() and 0xFF
            val total = buffer.get().toInt() and 0xFF
            buffer.get() // Skip CRC byte

            if (total <= 0 || seq >= total) return null

            val payload = ByteArray(buffer.remaining())
            buffer.get(payload)

            SmsChunkPacket(
                messageId = msgId,
                chunkIndex = seq,
                totalChunks = total,
                payload = payload
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Splits a full binary message into multiple chunks of at most 121 bytes,
     * packing each into a wire packet with its 12-byte header.
     */
    fun chunkPayload(messageId: Long, fullPayload: ByteArray): List<ByteArray> {
        if (fullPayload.isEmpty()) {
            return listOf(encodeChunk(messageId, 0, 1, ByteArray(0)))
        }

        val totalChunks = ((fullPayload.size + SmsConstants.MAX_CHUNK_PAYLOAD_SIZE - 1) / SmsConstants.MAX_CHUNK_PAYLOAD_SIZE)
        require(totalChunks <= 255) { "Payload too large for SMS transport: requires $totalChunks chunks (max 255)" }

        val packets = ArrayList<ByteArray>(totalChunks)
        for (i in 0 until totalChunks) {
            val start = i * SmsConstants.MAX_CHUNK_PAYLOAD_SIZE
            val end = minOf(fullPayload.size, start + SmsConstants.MAX_CHUNK_PAYLOAD_SIZE)
            val chunkData = fullPayload.copyOfRange(start, end)
            packets.add(encodeChunk(messageId, i, totalChunks, chunkData))
        }

        return packets
    }

    /**
     * Splits a full binary message into multiple chunks of at most 128 bytes,
     * packing each into a wire packet with its 5-byte header.
     */
    fun chunkCompactPayload(messageId: Short, fullPayload: ByteArray): List<ByteArray> {
        if (fullPayload.isEmpty()) {
            return listOf(encodeCompactChunk(messageId, 0, 1, ByteArray(0)))
        }

        val totalChunks = ((fullPayload.size + MAX_COMPACT_PAYLOAD_SIZE - 1) / MAX_COMPACT_PAYLOAD_SIZE)
        require(totalChunks <= 255) { "Payload too large for compact SMS transport: requires $totalChunks chunks (max 255)" }

        val packets = ArrayList<ByteArray>(totalChunks)
        for (i in 0 until totalChunks) {
            val start = i * MAX_COMPACT_PAYLOAD_SIZE
            val end = minOf(fullPayload.size, start + MAX_COMPACT_PAYLOAD_SIZE)
            val chunkData = fullPayload.copyOfRange(start, end)
            packets.add(encodeCompactChunk(messageId, i, totalChunks, chunkData))
        }

        return packets
    }
}

/**
 * Thread-safe Bounded In-Memory Buffer for reassembling multi-part Data SMS chunks.
 * Enforces a strict 10-minute expiration TTL and a maximum bound of 100 concurrent reassemblies.
 */
class ChunkReassembler(
    private val chunkTtlMillis: Long = 600_000L, // 10 minutes TTL
    private val maxConcurrentSessions: Int = 100 // Bounded memory cap
) {
    private data class MessageSession(
        val totalChunks: Int,
        val createdAt: Long,
        val chunks: MutableMap<Int, ByteArray> = HashMap()
    )

    private val sessions = ConcurrentHashMap<Long, MessageSession>()

    /**
     * Ingests an incoming chunk. If all chunks for this messageId have arrived,
     * concatenates them in order and returns the complete reconstructed ByteArray.
     * Otherwise returns null (awaiting remaining chunks).
     */
    @Synchronized
    fun ingestChunk(chunk: SmsChunkPacket): ByteArray? {
        cleanExpiredSessions()

        // Enforce memory bounds: if cap reached, evict oldest session
        if (sessions.size >= maxConcurrentSessions && !sessions.containsKey(chunk.messageId)) {
            evictOldestSession()
        }

        val session = sessions.getOrPut(chunk.messageId) {
            MessageSession(
                totalChunks = chunk.totalChunks,
                createdAt = System.currentTimeMillis()
            )
        }

        session.chunks[chunk.chunkIndex] = chunk.payload

        if (session.chunks.size == session.totalChunks) {
            // All chunks received -> assemble in order
            var totalLength = 0
            for (i in 0 until session.totalChunks) {
                val part = session.chunks[i] ?: return null
                totalLength += part.size
            }

            val assembled = ByteBuffer.allocate(totalLength)
            for (i in 0 until session.totalChunks) {
                assembled.put(session.chunks[i]!!)
            }

            sessions.remove(chunk.messageId)
            return assembled.array()
        }

        return null
    }

    private fun cleanExpiredSessions() {
        val now = System.currentTimeMillis()
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.createdAt > chunkTtlMillis) {
                iterator.remove()
            }
        }
    }

    private fun evictOldestSession() {
        var oldestId: Long? = null
        var oldestTime = Long.MAX_VALUE
        for ((id, session) in sessions) {
            if (session.createdAt < oldestTime) {
                oldestTime = session.createdAt
                oldestId = id
            }
        }
        if (oldestId != null) {
            sessions.remove(oldestId)
        }
    }

    fun activeSessionCount(): Int = sessions.size

    fun clear() {
        sessions.clear()
    }
}
