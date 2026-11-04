package com.nexa.app.core.hardware

import java.util.concurrent.ArrayBlockingQueue

/**
 * Shared pre-allocated byte array pool for high-throughput WebRTC streaming (Scenario 13).
 * Eliminates runtime heap allocations, GC pauses, and Out-Of-Memory (OOM) crashes
 * by reusing 64 KB byte arrays across chunking cycles.
 */
class MemoryBufferPool(
    private val poolSize: Int = DEFAULT_POOL_CAPACITY,
    private val bufferSizeBytes: Int = DEFAULT_BUFFER_SIZE_BYTES
) {
    private val pool = ArrayBlockingQueue<ByteArray>(poolSize)

    init {
        for (i in 0 until poolSize) {
            pool.offer(ByteArray(bufferSizeBytes))
        }
    }

    fun acquire(): ByteArray {
        return pool.poll() ?: ByteArray(bufferSizeBytes)
    }

    fun release(buffer: ByteArray) {
        if (buffer.size == bufferSizeBytes) {
            pool.offer(buffer)
        }
    }

    val availableCount: Int
        get() = pool.size

    companion object {
        const val DEFAULT_POOL_CAPACITY = 16
        const val DEFAULT_BUFFER_SIZE_BYTES = 64 * 1024 // 64 KB
    }
}
