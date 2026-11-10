package com.nexa.app.core.transport

import com.nexa.app.domain.model.TransportType

/**
 * Universal Physical/Logical Transport Medium over which packets travel in NEXA:
 * 1. INTERNET_WS: Binary WebSocket frames over TLS/TCP with Ed25519 authentication.
 * 2. CELLULAR_DATA_SMS: Port 8901 binary SMS PDU chunks via Android telephony stack.
 * 3. OFFLINE_MESH: Delay-Tolerant Networking (DTN) store-and-forward mesh over BLE/Wi-Fi Direct.
 */
enum class TransportMedium {
    INTERNET_WS,
    CELLULAR_DATA_SMS,
    OFFLINE_MESH;

    /**
     * Map low-level transport medium to presentation/domain TransportType.
     */
    fun toDomainTransportType(): TransportType = when (this) {
        INTERNET_WS -> TransportType.INTERNET
        CELLULAR_DATA_SMS -> TransportType.DATA_SMS
        OFFLINE_MESH -> TransportType.DTN_MESH
    }

    companion object {
        fun fromDomainTransportType(type: TransportType): TransportMedium = when (type) {
            TransportType.INTERNET -> INTERNET_WS
            TransportType.DATA_SMS -> CELLULAR_DATA_SMS
            TransportType.DTN_MESH -> OFFLINE_MESH
        }
    }
}
