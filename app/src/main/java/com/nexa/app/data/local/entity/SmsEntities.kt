package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-contact independent SMS transport session.
 *
 * Only public values and the derived counters are stored. The session root key is NEVER
 * persisted: it is re-derived on demand from the Keystore-wrapped local provisioning key and
 * the peer's provisioning public key.
 *
 * [keyVersion] is bumped whenever the session is (re)provisioned; the version is bound into
 * every packet's AAD and nonce, so a stale version cannot decrypt new packets.
 */
@Entity(
    tableName = "sms_sessions",
    indices = [Index(value = ["expiresAt"])]
)
data class SmsSessionEntity(
    @PrimaryKey val peerNxId: String,
    val peerProvisioningPublicKeyHex: String,
    val localProvisioningPublicKeyHex: String,
    val keyVersion: Int,
    val establishedAt: Long,
    val expiresAt: Long,
    val isRevoked: Boolean = false,
    val sendCounter: Long = 0L,
    val ackSendCounter: Long = 0L,
    val recvHighestCounter: Long = -1L,
    val recvBitmap: Long = 0L,
    val ackRecvHighestCounter: Long = -1L,
    val ackRecvBitmap: Long = 0L,
    val lastUsedAt: Long = 0L
)

/**
 * Local X25519 SMS provisioning keypair. The private key is AES-256-GCM wrapped by the
 * AndroidKeyStore master key ([com.nexa.app.core.security.CryptoKeystoreManager]) before it
 * reaches SQLCipher, so the database alone cannot produce SMS plaintext.
 */
@Entity(tableName = "sms_key_material")
data class SmsKeyMaterialEntity(
    @PrimaryKey val keyId: String,
    val publicKeyHex: String,
    val encryptedPrivateKeyHex: String,
    val ivHex: String,
    val createdAt: Long
)

/**
 * NX1 packet bookkeeping: maps the 96-bit SMS message id to the internal message UUID,
 * tracks part/segment counts and the local handoff/delivery state machine.
 *
 * Delivery states:
 *  QUEUED -> HANDED_OFF -> SENT_UNCONFIRMED -> DELIVERED_APP_ACK
 *                     \-> FAILED / CANCELLED
 */
@Entity(
    tableName = "sms_packets",
    indices = [
        Index(value = ["packetId"]),
        Index(value = ["peerNxId"]),
        Index(value = ["handoffState"]),
        Index(value = ["direction"])
    ]
)
data class SmsPacketEntity(
    @PrimaryKey val messageId: String,
    val packetId: String,
    val peerNxId: String,
    val direction: String,
    val keyVersion: Int,
    val sendCounter: Long,
    val totalParts: Int,
    val segmentCount: Int,
    val handoffState: String,
    val createdAt: Long,
    val updatedAt: Long,
    val failureKind: String? = null,
    val deliveryChannel: String = CHANNEL_COMPOSER
) {
    companion object {
        const val DIRECTION_OUTBOUND = "OUTBOUND"
        const val DIRECTION_INBOUND = "INBOUND"
        const val CHANNEL_COMPOSER = "EXTERNAL_SMS_COMPOSER"
        const val CHANNEL_NONE = "LOCAL_ONLY"
    }
}

/** Persistent bounded multipart reassembly buffer (survives process death). */
@Entity(
    tableName = "sms_inbound_parts",
    indices = [
        Index(value = ["messageId", "senderNxId", "partIndex"], unique = true),
        Index(value = ["receivedAt"])
    ]
)
data class SmsInboundPartEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val messageId: String,
    val senderNxId: String,
    val partIndex: Int,
    val totalParts: Int,
    val payloadBase64: String,
    val receivedAt: Long
)
