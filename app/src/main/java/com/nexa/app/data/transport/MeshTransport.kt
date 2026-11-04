package com.nexa.app.data.transport

import com.nexa.app.core.dtn.DtnEnvelope
import com.nexa.app.domain.repository.DtnRepository
import java.util.Random

class MeshTransport(
    private val dtnRepository: DtnRepository
) {
    suspend fun broadcastDtnEnvelope(blindTokenHex: String, rawWireBytes: ByteArray): Boolean {
        val envId = ByteArray(16).also { Random().nextBytes(it) }
        val tokenBytes = fromHex(blindTokenHex.padEnd(32, '0').take(32))
        val envelope = DtnEnvelope(
            envelopeId = envId,
            blindToken = tokenBytes,
            hopLimit = 7,
            expiresAtEpochSec = (System.currentTimeMillis() / 1000L) + (48 * 3600L),
            encryptedPayload = rawWireBytes
        )
        return dtnRepository.ingestInboundEnvelope(envelope)
    }

    private fun fromHex(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
