package com.nexa.app.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.crypto.tink.subtle.X25519
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Backward-compatible cryptographic manager supporting Android 7.0 (API 24) to Android 15+ (API 35).
 * Uses AndroidKeyStore with an AES-256 GCM Master Key to protect Ed25519/X25519 raw keys at rest.
 */
class CryptoKeystoreManager(private val context: Context) {

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }

    private val secureRandom = SecureRandom()

    init {
        ensureMasterKeyExists()
    }

    private fun ensureMasterKeyExists() {
        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
            )
            val spec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    /**
     * Encrypts private key bytes with the hardware-backed AES-256 GCM Master Key.
     * Returns Pair(IV, Ciphertext).
     */
    fun encryptWithMasterKey(rawBytes: ByteArray): Pair<ByteArray, ByteArray> {
        val secretKey = keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(rawBytes)
        return Pair(iv, ciphertext)
    }

    /**
     * Decrypts encrypted private key bytes using the hardware-backed AES-256 GCM Master Key.
     */
    fun decryptWithMasterKey(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val secretKey = keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Generates a deterministic or high-entropy 32-byte cryptographic identity keypair.
     * Returns IdentityKeys containing public key hex and master-encrypted private key bytes.
     */
    fun generateIdentityKeyPair(): IdentityKeyPair {
        val rawPrivateKey = ByteArray(32)
        secureRandom.nextBytes(rawPrivateKey)

        // Derive 32-byte public key (for Curve25519/Ed25519) using high-entropy scalar multiplication
        val rawPublicKey = derivePublicKeyBytes(rawPrivateKey)
        val publicKeyHex = rawPublicKey.toHex()

        // Encrypt private key at rest using Master Key
        val (iv, encryptedPrivateKey) = encryptWithMasterKey(rawPrivateKey)

        // Securely wipe raw private key from volatile memory
        Arrays.fill(rawPrivateKey, 0.toByte())

        return IdentityKeyPair(
            publicKeyHex = publicKeyHex,
            encryptedPrivateKey = encryptedPrivateKey,
            iv = iv
        )
    }

    /**
     * Retrieves an existing identity keypair from secure storage, or creates a new one.
     * Automatically performs seamless legacy migration if a previously stored public key was derived
     * using the flawed legacy SHA-256 bit-clamping rather than RFC 8032 Ed25519 point multiplication.
     */
    fun getOrCreateIdentityKeyPair(secureStorage: SecureStorage): IdentityKeyPair {
        val pub = secureStorage.getIdentityPublicKey()
        val enc = secureStorage.getIdentityEncryptedPrivKey()
        val iv = secureStorage.getIdentityIv()

        if (!pub.isNullOrBlank() && !enc.isNullOrBlank() && !iv.isNullOrBlank()) {
            val encBytes = enc.hexToByteArray()
            val ivBytes = iv.hexToByteArray()

            // Validate and migrate flawed legacy public keys while preserving the private key seed
            val truePubKeyHex = try {
                val rawPriv = decryptWithMasterKey(ivBytes, encBytes)
                val derived = derivePublicKeyBytes(rawPriv).toHex()
                Arrays.fill(rawPriv, 0.toByte())
                derived
            } catch (e: Exception) {
                pub
            }

            if (truePubKeyHex != pub) {
                // Update persistent storage with authoritative RFC 8032 Ed25519 public key
                secureStorage.saveIdentityKeyPair(
                    publicKeyHex = truePubKeyHex,
                    encryptedPrivKeyHex = enc,
                    ivHex = iv
                )
                return IdentityKeyPair(
                    publicKeyHex = truePubKeyHex,
                    encryptedPrivateKey = encBytes,
                    iv = ivBytes
                )
            }

            return IdentityKeyPair(
                publicKeyHex = pub,
                encryptedPrivateKey = encBytes,
                iv = ivBytes
            )
        }

        val generated = generateIdentityKeyPair()
        secureStorage.saveIdentityKeyPair(
            publicKeyHex = generated.publicKeyHex,
            encryptedPrivKeyHex = generated.encryptedPrivateKey.toHex(),
            ivHex = generated.iv.toHex()
        )
        return generated
    }

    /**
     * Generates an independent high-entropy 32-byte X25519 identity DH keypair.
     * Encrypts the raw private key at rest using the KeyStore Master Key.
     */
    fun generateDhIdentityKeyPair(): IdentityKeyPair {
        val rawPrivateKey = X25519.generatePrivateKey()
        val rawPublicKey = X25519.publicFromPrivate(rawPrivateKey)
        val publicKeyHex = rawPublicKey.toHex()

        val (iv, encryptedPrivateKey) = encryptWithMasterKey(rawPrivateKey)
        Arrays.fill(rawPrivateKey, 0.toByte())

        return IdentityKeyPair(
            publicKeyHex = publicKeyHex,
            encryptedPrivateKey = encryptedPrivateKey,
            iv = iv
        )
    }

    /**
     * Retrieves an existing X25519 DH identity keypair or generates a new one.
     */
    fun getOrCreateDhIdentityKeyPair(secureStorage: SecureStorage): IdentityKeyPair {
        val pub = secureStorage.getDhIdentityPublicKey()
        val enc = secureStorage.getDhIdentityEncryptedPrivKey()
        val iv = secureStorage.getDhIdentityIv()

        if (!pub.isNullOrBlank() && !enc.isNullOrBlank() && !iv.isNullOrBlank()) {
            return IdentityKeyPair(
                publicKeyHex = pub,
                encryptedPrivateKey = enc.hexToByteArray(),
                iv = iv.hexToByteArray()
            )
        }

        val generated = generateDhIdentityKeyPair()
        secureStorage.saveDhIdentityKeyPair(
            publicKeyHex = generated.publicKeyHex,
            encryptedPrivKeyHex = generated.encryptedPrivateKey.toHex(),
            ivHex = generated.iv.toHex()
        )
        return generated
    }

    /**
     * Executes a cryptographic signing or verification operation with the decrypted Ed25519 private key seed,
     * immediately wiping the plaintext seed from memory upon completion to minimize memory exposure.
     */
    fun <T> withDecryptedPrivateKey(
        identityKeyPair: IdentityKeyPair,
        block: (rawPrivateKey: ByteArray) -> T
    ): T {
        val raw = decryptWithMasterKey(identityKeyPair.iv, identityKeyPair.encryptedPrivateKey)
        return try {
            block(raw)
        } finally {
            Arrays.fill(raw, 0.toByte())
        }
    }

    /**
     * Derives a 32-byte Ed25519 public key from a 32-byte private key seed using standard RFC 8032
     * Edwards curve point multiplication (BouncyCastle Ed25519).
     */
    fun derivePublicKeyBytes(privateSeed: ByteArray): ByteArray {
        return com.nexa.app.core.crypto.Ed25519Signer.derivePublicKey(privateSeed)
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "nexa_master_vault_key"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}

data class IdentityKeyPair(
    val publicKeyHex: String,
    val encryptedPrivateKey: ByteArray,
    val iv: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IdentityKeyPair
        if (publicKeyHex != other.publicKeyHex) return false
        if (!encryptedPrivateKey.contentEquals(other.encryptedPrivateKey)) return false
        if (!iv.contentEquals(other.iv)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = publicKeyHex.hashCode()
        result = 31 * result + encryptedPrivateKey.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        return result
    }
}

fun ByteArray.toHex(): String {
    val result = StringBuilder(size * 2)
    for (b in this) {
        val octet = b.toInt() and 0xFF
        val firstIndex = octet ushr 4
        val secondIndex = octet and 0x0F
        result.append(HEX_CHARS[firstIndex])
        result.append(HEX_CHARS[secondIndex])
    }
    return result.toString()
}

fun String.hexToByteArray(): ByteArray {
    val len = length
    require(len % 2 == 0) { "Hex string must have an even length" }
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(this[i], 16) shl 4) +
                Character.digit(this[i + 1], 16)).toByte()
        i += 2
    }
    return data
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()
