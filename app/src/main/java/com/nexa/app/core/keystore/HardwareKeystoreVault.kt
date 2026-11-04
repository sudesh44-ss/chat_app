package com.nexa.app.core.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore hardware-backed vault (TEE / StrongBox).
 * Protects root identity keys and database passphrases using an AES-256-GCM Master Key.
 * Compatible down to Android 7.0 (API 24).
 */
class HardwareKeystoreVault {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private fun getOrCreateMasterKey(): SecretKey {
        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
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
        return keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey
    }

    fun wrap(rawBytes: ByteArray): ByteArray {
        val masterKey = getOrCreateMasterKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv // 12-byte GCM IV
        val ciphertext = cipher.doFinal(rawBytes)

        val buffer = ByteBuffer.allocate(1 + iv.size + ciphertext.size)
        buffer.put(iv.size.toByte())
        buffer.put(iv)
        buffer.put(ciphertext)
        return buffer.array()
    }

    fun unwrap(wrappedBytes: ByteArray): ByteArray {
        val masterKey = getOrCreateMasterKey()
        val buffer = ByteBuffer.wrap(wrappedBytes)
        val ivSize = buffer.get().toInt() and 0xFF
        val iv = ByteArray(ivSize).also { buffer.get(it) }
        val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "nexa_hardware_master_key"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
