package com.nexa.app.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

class SecureStorage(context: Context) {

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback for devices with corrupted Keystore entries
            context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun saveAuthToken(token: String) {
        prefs.edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun getAuthToken(): String? {
        return prefs.getString(KEY_AUTH_TOKEN, null)
    }

    fun getSessionToken(): String? {
        return getAuthToken()
    }

    fun saveActiveNxId(nxId: String) {
        prefs.edit().putString(KEY_ACTIVE_NX_ID, nxId).apply()
    }

    fun getActiveNxId(): String? {
        return prefs.getString(KEY_ACTIVE_NX_ID, null)
    }

    fun getOrCreateDatabasePassphrase(): ByteArray {
        val existingHex = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (!existingHex.isNullOrBlank()) {
            return existingHex.hexToByteArray()
        }

        val newPassphrase = ByteArray(32)
        SecureRandom().nextBytes(newPassphrase)
        val hex = newPassphrase.toHex()
        prefs.edit().putString(KEY_DB_PASSPHRASE, hex).apply()
        return newPassphrase
    }

    fun saveRecoveryPassphrase(passphrase: String) {
        prefs.edit().putString(KEY_RECOVERY_PASSPHRASE, passphrase.trim()).apply()
    }

    fun getRecoveryPassphrase(): String? {
        return prefs.getString(KEY_RECOVERY_PASSPHRASE, null)
    }

    fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val newId = "dev_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    fun getDeviceId(): String? {
        return prefs.getString(KEY_DEVICE_ID, null) ?: getOrCreateDeviceId()
    }

    fun saveAvatarUrl(url: String?) {
        if (url == null) {
            prefs.edit().remove(KEY_AVATAR_URL).apply()
        } else {
            prefs.edit().putString(KEY_AVATAR_URL, url).apply()
        }
    }

    fun getAvatarUrl(): String? = prefs.getString(KEY_AVATAR_URL, null)

    fun saveIdentityKeyPair(publicKeyHex: String, encryptedPrivKeyHex: String, ivHex: String) {
        prefs.edit()
            .putString(KEY_IDENTITY_PUBLIC_KEY, publicKeyHex)
            .putString(KEY_IDENTITY_ENCRYPTED_PRIVKEY, encryptedPrivKeyHex)
            .putString(KEY_IDENTITY_IV, ivHex)
            .apply()
    }

    fun getIdentityPublicKey(): String? = prefs.getString(KEY_IDENTITY_PUBLIC_KEY, null)
    fun getIdentityEncryptedPrivKey(): String? = prefs.getString(KEY_IDENTITY_ENCRYPTED_PRIVKEY, null)
    fun getIdentityIv(): String? = prefs.getString(KEY_IDENTITY_IV, null)

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val SECURE_PREFS_NAME = "nexa_secure_preferences"
        private const val FALLBACK_PREFS_NAME = "nexa_fallback_preferences"
        private const val KEY_AUTH_TOKEN = "key_auth_token"
        private const val KEY_ACTIVE_NX_ID = "key_active_nx_id"
        private const val KEY_DB_PASSPHRASE = "key_db_passphrase"
        private const val KEY_RECOVERY_PASSPHRASE = "key_recovery_passphrase"
        private const val KEY_DEVICE_ID = "key_device_id"
        private const val KEY_AVATAR_URL = "key_avatar_url"
        private const val KEY_IDENTITY_PUBLIC_KEY = "key_identity_public_key"
        private const val KEY_IDENTITY_ENCRYPTED_PRIVKEY = "key_identity_encrypted_privkey"
        private const val KEY_IDENTITY_IV = "key_identity_iv"
    }
}
