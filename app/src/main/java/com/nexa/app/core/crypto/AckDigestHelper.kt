package com.nexa.app.core.crypto

import com.nexa.app.core.proto.AckStatus
import com.nexa.app.core.proto.CustodyStatus
import java.security.MessageDigest

/**
 * Deterministic cryptographic digest builder for Delivery ACKs and Relay Custody ACKs.
 *
 * Guarantees that digital signatures over ACK payloads bind strictly to the immutable
 * message UUID, the acknowledging identity, device ID, ACK status enum, and timestamp.
 */
object AckDigestHelper {

    fun computeDeliveryAckDigest(
        originalPacketId: String,
        recipientNxId: String,
        deviceId: String,
        status: AckStatus,
        timestamp: Long
    ): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("NEXA_DELIVERY_ACK:".toByteArray(Charsets.UTF_8))
        md.update(originalPacketId.toByteArray(Charsets.UTF_8))
        md.update(":".toByteArray(Charsets.UTF_8))
        md.update(recipientNxId.toByteArray(Charsets.UTF_8))
        md.update(":".toByteArray(Charsets.UTF_8))
        md.update(deviceId.toByteArray(Charsets.UTF_8))
        md.update(":".toByteArray(Charsets.UTF_8))
        md.update(status.value.toString().toByteArray(Charsets.UTF_8))
        md.update(":".toByteArray(Charsets.UTF_8))
        md.update(timestamp.toString().toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    fun computeCustodyAckDigest(
        originalPacketId: String,
        custodianNxId: String,
        status: CustodyStatus,
        timestamp: Long
    ): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("NEXA_CUSTODY_ACK:".toByteArray(Charsets.UTF_8))
        md.update(originalPacketId.toByteArray(Charsets.UTF_8))
        md.update(":".toByteArray(Charsets.UTF_8))
        md.update(custodianNxId.toByteArray(Charsets.UTF_8))
        md.update(":".toByteArray(Charsets.UTF_8))
        md.update(status.value.toString().toByteArray(Charsets.UTF_8))
        md.update(":".toByteArray(Charsets.UTF_8))
        md.update(timestamp.toString().toByteArray(Charsets.UTF_8))
        return md.digest()
    }
}
