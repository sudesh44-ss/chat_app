package com.nexa.app.core.transport

import kotlinx.coroutines.flow.Flow

/**
 * Common contract representing an outbound and inbound message transport adapter.
 */
interface TransportAdapter {
    val medium: TransportMedium
    val isAvailable: Boolean

    /**
     * Transmit raw binary wire bytes to an optional target endpoint or destination identifier.
     */
    suspend fun transmit(rawBytes: ByteArray, destination: String? = null): Boolean

    /**
     * Optional incoming wire packet stream emitted by this transport.
     */
    fun incomingPackets(): Flow<ByteArray>? = null
}
