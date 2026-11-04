package com.nexa.app.core.qr

import com.nexa.app.core.crypto.Ed25519Signer

sealed interface QrValidationResult {
    data object Valid : QrValidationResult
    data object Expired : QrValidationResult
    data object SignatureMismatch : QrValidationResult
    data object MalformedData : QrValidationResult
}

/**
 * Validates cryptographic signatures, expiry timestamps, and parameter integrity of scanned QR codes.
 */
object QrSecurityValidator {

    fun validate(
        payload: QrPeerPayload,
        currentEpochSec: Long = System.currentTimeMillis() / 1000
    ): QrValidationResult {
        // 1. Mandatory Parameter & Key Length Validation
        if (payload.nxId.isBlank() || payload.displayName.isBlank() || payload.blePeerId.isBlank()) {
            return QrValidationResult.MalformedData
        }

        // Public key must be exactly 32 bytes (64 hex characters)
        if (payload.publicKeyHex.length != 64) {
            return QrValidationResult.MalformedData
        }

        // Signature must be exactly 64 bytes (128 hex characters)
        val sigHex = payload.signatureHex
        if (sigHex.isNullOrBlank() || sigHex.length != 128) {
            return QrValidationResult.MalformedData
        }

        // 2. Ephemeral Expiry Check (Anti-Stalking Window)
        payload.expiresAtEpochSec?.let { expiry ->
            if (currentEpochSec > expiry) {
                return QrValidationResult.Expired
            }
        }

        // 3. Cryptographic Anti-Tampering Signature Verification
        return try {
            val canonicalData = payload.toCanonicalString().toByteArray(Charsets.UTF_8)
            val signatureBytes = fromHex(sigHex)
            val publicKeyBytes = fromHex(payload.publicKeyHex)

            val isSignatureValid = Ed25519Signer.verify(canonicalData, signatureBytes, publicKeyBytes)
            if (isSignatureValid) {
                QrValidationResult.Valid
            } else {
                QrValidationResult.SignatureMismatch
            }
        } catch (e: Exception) {
            QrValidationResult.MalformedData
        }
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
