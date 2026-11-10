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

    /**
     * Imports an existing database passphrase (e.g. during cross-device restoration).
     * Protected under the local hardware Android KeyStore MasterKey.
     */
    fun saveDatabasePassphrase(passphrase: ByteArray) {
        prefs.edit().putString(KEY_DB_PASSPHRASE, passphrase.toHex()).apply()
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

    fun saveDhIdentityKeyPair(publicKeyHex: String, encryptedPrivKeyHex: String, ivHex: String) {
        prefs.edit()
            .putString(KEY_DH_IDENTITY_PUBLIC_KEY, publicKeyHex)
            .putString(KEY_DH_IDENTITY_ENCRYPTED_PRIVKEY, encryptedPrivKeyHex)
            .putString(KEY_DH_IDENTITY_IV, ivHex)
            .apply()
    }

    fun getDhIdentityPublicKey(): String? = prefs.getString(KEY_DH_IDENTITY_PUBLIC_KEY, null)
    fun getDhIdentityEncryptedPrivKey(): String? = prefs.getString(KEY_DH_IDENTITY_ENCRYPTED_PRIVKEY, null)
    fun getDhIdentityIv(): String? = prefs.getString(KEY_DH_IDENTITY_IV, null)

    fun saveAccountStatus(status: String) {
        prefs.edit().putString(KEY_ACCOUNT_STATUS, status).apply()
    }

    fun getAccountStatus(): String {
        return prefs.getString(KEY_ACCOUNT_STATUS, "ACTIVE") ?: "ACTIVE"
    }

    fun saveActiveUploadSession(
        sessionUri: String,
        versionEpoch: Long,
        vaultFilePath: String,
        expectedFileSize: Long,
        lastConfirmedOffset: Long,
        existingFileId: String? = null
    ) {
        prefs.edit()
            .putString(KEY_UPLOAD_SESSION_URI, sessionUri)
            .putLong(KEY_UPLOAD_VERSION_EPOCH, versionEpoch)
            .putString(KEY_UPLOAD_VAULT_PATH, vaultFilePath)
            .putLong(KEY_UPLOAD_EXPECTED_SIZE, expectedFileSize)
            .putLong(KEY_UPLOAD_CONFIRMED_OFFSET, lastConfirmedOffset)
            .putLong(KEY_UPLOAD_UPDATED_AT, System.currentTimeMillis())
            .putString(KEY_UPLOAD_EXISTING_FILE_ID, existingFileId)
            .apply()
    }

    fun getActiveUploadSession(): com.nexa.app.data.drive.UploadSessionState? {
        val uri = prefs.getString(KEY_UPLOAD_SESSION_URI, null) ?: return null
        val epoch = prefs.getLong(KEY_UPLOAD_VERSION_EPOCH, 0L)
        val path = prefs.getString(KEY_UPLOAD_VAULT_PATH, null) ?: return null
        val size = prefs.getLong(KEY_UPLOAD_EXPECTED_SIZE, 0L)
        val offset = prefs.getLong(KEY_UPLOAD_CONFIRMED_OFFSET, 0L)
        val updated = prefs.getLong(KEY_UPLOAD_UPDATED_AT, 0L)
        val fileId = prefs.getString(KEY_UPLOAD_EXISTING_FILE_ID, null)

        val session = com.nexa.app.data.drive.UploadSessionState(
            sessionUri = uri,
            versionEpoch = epoch,
            vaultFilePath = path,
            expectedFileSize = size,
            lastConfirmedOffset = offset,
            updatedAt = updated,
            existingFileId = fileId
        )
        return if (session.isValid()) session else null
    }

    fun updateUploadSessionOffset(confirmedOffset: Long) {
        prefs.edit()
            .putLong(KEY_UPLOAD_CONFIRMED_OFFSET, confirmedOffset)
            .putLong(KEY_UPLOAD_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearActiveUploadSession() {
        prefs.edit()
            .remove(KEY_UPLOAD_SESSION_URI)
            .remove(KEY_UPLOAD_VERSION_EPOCH)
            .remove(KEY_UPLOAD_VAULT_PATH)
            .remove(KEY_UPLOAD_EXPECTED_SIZE)
            .remove(KEY_UPLOAD_CONFIRMED_OFFSET)
            .remove(KEY_UPLOAD_UPDATED_AT)
            .remove(KEY_UPLOAD_EXISTING_FILE_ID)
            .apply()
    }

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
        private const val KEY_DH_IDENTITY_PUBLIC_KEY = "key_dh_identity_public_key"
        private const val KEY_DH_IDENTITY_ENCRYPTED_PRIVKEY = "key_dh_identity_encrypted_privkey"
        private const val KEY_DH_IDENTITY_IV = "key_dh_identity_iv"
        private const val KEY_ACCOUNT_STATUS = "key_account_status"
        private const val KEY_UPLOAD_SESSION_URI = "key_upload_session_uri"
        private const val KEY_UPLOAD_VERSION_EPOCH = "key_upload_version_epoch"
        private const val KEY_UPLOAD_VAULT_PATH = "key_upload_vault_path"
        private const val KEY_UPLOAD_EXPECTED_SIZE = "key_upload_expected_size"
        private const val KEY_UPLOAD_CONFIRMED_OFFSET = "key_upload_confirmed_offset"
        private const val KEY_UPLOAD_UPDATED_AT = "key_upload_updated_at"
        private const val KEY_UPLOAD_EXISTING_FILE_ID = "key_upload_existing_file_id"
    }
}
