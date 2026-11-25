package com.nexa.app.data.repository.sms

import android.util.Log
import com.google.crypto.tink.subtle.X25519
import com.nexa.app.core.security.CryptoKeystoreManager
import com.nexa.app.core.security.toHex
import com.nexa.app.core.security.hexToByteArray
import com.nexa.app.data.local.dao.SmsKeyMaterialDao
import com.nexa.app.data.local.entity.SmsKeyMaterialEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Arrays

/**
 * Provides the local X25519 "SMS provisioning" keypair.
 *
 * The private key never exists at rest in plaintext: it is AES-256-GCM wrapped by the
 * AndroidKeyStore master key before being written to SQLCipher. The public half is the only
 * value that ever leaves the device (inside an E2EE Internet control packet).
 */
interface SmsKeyVault {
    suspend fun localPublicKeyHex(): String
    suspend fun localPrivateKey(): ByteArray
}

class KeystoreSmsKeyVault(
    private val keyMaterialDao: SmsKeyMaterialDao,
    private val keystoreManager: CryptoKeystoreManager,
    private val clock: () -> Long = System::currentTimeMillis
) : SmsKeyVault {

    /**
     * Serialises creation. Without this, two concurrent first-use calls (e.g. provisioning and
     * sending at the same moment) could each generate a keypair, persist one, and leave a peer
     * session bound to the discarded public key — permanently breaking the session.
     */
    private val keyMaterialMutex = Mutex()

    override suspend fun localPublicKeyHex(): String = ensureKeyMaterial().publicKeyHex

    override suspend fun localPrivateKey(): ByteArray {
        val material = ensureKeyMaterial()
        return keystoreManager.decryptWithMasterKey(
            material.ivHex.hexToByteArray(),
            material.encryptedPrivateKeyHex.hexToByteArray()
        )
    }

    private suspend fun ensureKeyMaterial(): SmsKeyMaterialEntity {
        keyMaterialDao.get(KEY_ID)?.let { return it }
        return keyMaterialMutex.withLock {
            // Double-checked inside the lock: another coroutine may have created it.
            keyMaterialDao.get(KEY_ID)?.let { return@withLock it }
            createKeyMaterial()
        }
    }

    private suspend fun createKeyMaterial(): SmsKeyMaterialEntity {
        val privateKey = X25519.generatePrivateKey()
        try {
            val publicKey = X25519.publicFromPrivate(privateKey)
            val (iv, encrypted) = keystoreManager.encryptWithMasterKey(privateKey)
            val material = SmsKeyMaterialEntity(
                keyId = KEY_ID,
                publicKeyHex = publicKey.toHex(),
                encryptedPrivateKeyHex = encrypted.toHex(),
                ivHex = iv.toHex(),
                createdAt = clock()
            )
            keyMaterialDao.upsert(material)
            Log.i(TAG, "Generated local SMS provisioning keypair (public key length 64 hex chars)")
            return material
        } finally {
            Arrays.fill(privateKey, 0.toByte())
        }
    }

    companion object {
        private const val TAG = "KeystoreSmsKeyVault"
        private const val KEY_ID = "sms_provisioning_v1"
    }
}
