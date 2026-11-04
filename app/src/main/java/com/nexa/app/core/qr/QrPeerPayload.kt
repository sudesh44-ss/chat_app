package com.nexa.app.core.qr

/**
 * Self-contained, cryptographically signed peer identity payload encapsulated inside NEXA QR codes.
 */
data class QrPeerPayload(
    val nxId: String,
    val displayName: String,
    val publicKeyHex: String,        // 32-byte public key (64 hex chars)
    val blePeerId: String,          // Bluetooth LE / Mesh address or endpoint ID
    val expiresAtEpochSec: Long? = null, // Ephemeral expiry timestamp (null for permanent QR)
    val signatureHex: String? = null     // 64-byte Ed25519 signature (128 hex chars)
) {
    /**
     * Builds the deterministic canonical string for Ed25519 signature generation and verification.
     */
    fun toCanonicalString(): String {
        return "$nxId|$displayName|$publicKeyHex|$blePeerId|${expiresAtEpochSec ?: 0}"
    }
}
