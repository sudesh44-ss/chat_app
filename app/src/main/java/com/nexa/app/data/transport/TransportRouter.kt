package com.nexa.app.data.transport

import com.nexa.app.core.crypto.EncryptedEnvelope

enum class OutboundRoute { INTERNET, DATA_SMS, BLE_MESH }

/**
 * Universal Multi-Transport Router.
 * Implements "Encrypt Once, Route Anywhere" by routing the exact same
 * 49-byte overhead binary envelope across WebSocket, Port 8901 Data SMS, or BLE DTN Mesh.
 */
class TransportRouter(
    private val internetTransport: InternetTransport,
    private val smsTransport: SmsTransport,
    private val meshTransport: MeshTransport
) {
    suspend fun routeEncryptedEnvelope(
        envelope: EncryptedEnvelope,
        recipientPhone: String?,
        blindTokenHex: String,
        targetRoute: OutboundRoute
    ): Boolean {
        val rawWireBytes = envelope.serialize()

        return when (targetRoute) {
            OutboundRoute.INTERNET -> {
                internetTransport.sendBinaryPacket(rawWireBytes)
            }

            OutboundRoute.DATA_SMS -> {
                requireNotNull(recipientPhone) { "Phone number required for SMS mode" }
                smsTransport.sendDataSms(recipientPhone, rawWireBytes)
            }

            OutboundRoute.BLE_MESH -> {
                meshTransport.broadcastDtnEnvelope(blindTokenHex, rawWireBytes)
            }
        }
    }
}
