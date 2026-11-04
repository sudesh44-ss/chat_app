package com.nexa.app.core.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer as BCEd25519Signer

/**
 * High-performance Ed25519 digital signature generator and verifier.
 * Guarantees cryptographic anti-impersonation across all handshake envelopes.
 */
object Ed25519Signer {

    /**
     * Signs data using a 32-byte Ed25519 private key seed.
     * Returns a 64-byte cryptographic signature.
     */
    fun sign(data: ByteArray, privateKeyBytes: ByteArray): ByteArray {
        require(privateKeyBytes.size == 32) { "Ed25519 private key must be 32 bytes, got ${privateKeyBytes.size}" }
        val privateKeyParams = Ed25519PrivateKeyParameters(privateKeyBytes, 0)
        val signer = BCEd25519Signer()
        signer.init(true, privateKeyParams)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    /**
     * Verifies a 64-byte Ed25519 signature against data and the sender's 32-byte public key.
     */
    fun verify(data: ByteArray, signature: ByteArray, publicKeyBytes: ByteArray): Boolean {
        if (signature.size != 64 || publicKeyBytes.size != 32) {
            return false
        }
        return try {
            val publicKeyParams = Ed25519PublicKeyParameters(publicKeyBytes, 0)
            val verifier = BCEd25519Signer()
            verifier.init(false, publicKeyParams)
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Derives a 32-byte Ed25519 public key from a 32-byte private key seed.
     */
    fun derivePublicKey(privateKeyBytes: ByteArray): ByteArray {
        require(privateKeyBytes.size == 32) { "Ed25519 private key must be 32 bytes" }
        val privateKeyParams = Ed25519PrivateKeyParameters(privateKeyBytes, 0)
        return privateKeyParams.generatePublicKey().encoded
    }
}
