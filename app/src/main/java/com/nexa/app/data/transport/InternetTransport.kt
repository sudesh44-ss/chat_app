package com.nexa.app.data.transport

import com.nexa.app.core.network.WebSocketManager

class InternetTransport(
    private val webSocketManager: WebSocketManager
) {
    suspend fun sendBinaryPacket(rawWireBytes: ByteArray): Boolean {
        return webSocketManager.sendBinary(rawWireBytes)
    }
}
