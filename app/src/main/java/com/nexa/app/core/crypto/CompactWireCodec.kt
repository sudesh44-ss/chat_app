package com.nexa.app.core.crypto

/**
 * High-performance binary codec for the 49-byte overhead universal wire envelope.
 * Fully decoupled from transport protocol (Internet WS, Data SMS, BLE DTN).
 */
object CompactWireCodec {

    fun serialize(envelope: EncryptedEnvelope): ByteArray {
        return envelope.serialize()
    }

    fun deserialize(rawBytes: ByteArray): EncryptedEnvelope? {
        return EncryptedEnvelope.deserialize(rawBytes)
    }

    fun getFixedOverheadSize(): Int {
        return EncryptedEnvelope.HEADER_OVERHEAD_SIZE
    }
}
