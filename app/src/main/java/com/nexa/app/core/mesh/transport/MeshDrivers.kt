package com.nexa.app.core.mesh.transport

interface BleL2capDriver {
    suspend fun sendL2capFrame(dest: String, transferId: Long, seq: Int, data: ByteArray)
}

interface WifiAwareMeshDriver {
    suspend fun ensureSession(dest: String): Boolean
    suspend fun streamLargeFile(dest: String, transferId: Long, data: ByteArray, chunkSize: Int)
    suspend fun sendChunk(dest: String, transferId: Long, chunkIndex: Int, data: ByteArray)
}
