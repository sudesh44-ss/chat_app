package com.nexa.app.core.keystore

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

enum class HardwareSecurityLevel {
    STRONGBOX,
    TEE,
    SOFTWARE,
    UNKNOWN
}

/**
 * Hardware-backed device authentication keypair manager.
 * Generates an asymmetric EC P-256 keypair inside the Android Keystore (TEE / StrongBox).
 * The private key is non-exportable and never enters RAM.
 *
 * Dedicated strictly to device identity and challenge-response authentication.
 * E2EE messaging keys (Ed25519/X25519) and AES master wrapping keys remain separate.
 */
class NexaHardwareSecurity(private val context: Context? = null) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    /**
     * Retrieves the existing device public key (X.509 DER encoded) or generates a new EC P-256 keypair.
     * Automatically attempts StrongBox if hardware feature is present, with fallback to standard TEE.
     */
    @Synchronized
    fun getOrCreateDevicePublicKeyDer(): ByteArray {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            generateDeviceKeyPair()
        }
        val certificate = keyStore.getCertificate(KEY_ALIAS)
            ?: throw IllegalStateException("Keystore certificate missing for alias $KEY_ALIAS")
        return certificate.publicKey.encoded
    }

    /**
     * Hex-encoded representation of the X.509 DER public key.
     */
    fun getOrCreateDevicePublicKeyHex(): String {
        return getOrCreateDevicePublicKeyDer().joinToString("") { "%02x".format(it) }
    }

    private fun generateDeviceKeyPair() {
        val hasStrongBox = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && context != null) {
            try {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            } catch (e: Throwable) {
                false
            }
        } else {
            false
        }

        var success = false
        if (hasStrongBox) {
            try {
                initKeyPairGenerator(useStrongBox = true).generateKeyPair()
                Log.i(TAG, "Successfully generated StrongBox-backed EC P-256 device identity keypair")
                success = true
            } catch (e: Exception) {
                Log.w(TAG, "StrongBox keypair generation failed; falling back to standard TEE: ${e.message}")
            }
        }

        if (!success) {
            initKeyPairGenerator(useStrongBox = false).generateKeyPair()
            Log.i(TAG, "Successfully generated standard TEE-backed EC P-256 device identity keypair")
        }
    }

    private fun initKeyPairGenerator(useStrongBox: Boolean): KeyPairGenerator {
        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )

        val specBuilder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)

        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            specBuilder.setIsStrongBoxBacked(true)
        }

        kpg.initialize(specBuilder.build())
        return kpg
    }

    /**
     * Inspects the underlying cryptographic boundary using KeyInfo.
     * Reports whether the private key is residing in StrongBox, TEE, or Software.
     */
    fun getHardwareSecurityLevel(): HardwareSecurityLevel {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            return HardwareSecurityLevel.UNKNOWN
        }

        return try {
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
                ?: return HardwareSecurityLevel.UNKNOWN
            val factory = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(privateKey, KeyInfo::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (keyInfo.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> HardwareSecurityLevel.STRONGBOX
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> HardwareSecurityLevel.TEE
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> HardwareSecurityLevel.SOFTWARE
                    else -> HardwareSecurityLevel.UNKNOWN
                }
            } else {
                @Suppress("DEPRECATION")
                if (keyInfo.isInsideSecureHardware) {
                    HardwareSecurityLevel.TEE
                } else {
                    HardwareSecurityLevel.SOFTWARE
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query KeyInfo for $KEY_ALIAS: ${e.message}")
            HardwareSecurityLevel.UNKNOWN
        }
    }

    /**
     * Signs a challenge nonce inside the hardware enclave using the non-exportable private key.
     * Returns an ASN.1 DER encoded ECDSA signature.
     */
    fun signChallenge(challengeNonce: ByteArray): ByteArray {
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("Private key not found for alias $KEY_ALIAS")

        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(challengeNonce)
        return signer.sign()
    }

    /**
     * Verifies an ECDSA signature against the device's public key (self-verification/test helper).
     */
    fun verifySignature(challengeNonce: ByteArray, signatureBytes: ByteArray): Boolean {
        val certificate = keyStore.getCertificate(KEY_ALIAS) ?: return false
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(certificate.publicKey)
        verifier.update(challengeNonce)
        return verifier.verify(signatureBytes)
    }

    /**
     * Safely deletes the device identity keypair (for testing or device reset).
     */
    @Synchronized
    fun deleteDeviceKeyPair() {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
            Log.i(TAG, "Deleted device identity keypair: $KEY_ALIAS")
        }
    }

    companion object {
        const val KEY_ALIAS = "nexa_device_identity_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val TAG = "NexaHardwareSecurity"
    }
}
