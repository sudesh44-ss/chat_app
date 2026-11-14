package com.nexa.app.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Thrown when the hardware-backed preference store cannot be initialised.
 * Secure storage never falls back to plaintext: callers must surface this as
 * an authentication failure instead of silently downgrading.
 */
class SecureStorageUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class SecureStorage(context: Context) {

    private val prefs: SharedPreferences? by lazy {
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
            // Never fall back to plaintext. Returning null makes every access throw
            // SecureStorageUnavailableException, so auth fails closed.
            Log.e("SecureStorage", "Encrypted storage unavailable; refusing plaintext fallback", e)
            null
        }
    }

    /** True when the encrypted store initialised successfully. Does not throw. */
    fun isAvailable(): Boolean = prefs != null

    private fun requirePrefs(): SharedPreferences =
        prefs ?: throw SecureStorageUnavailableException(
            "Encrypted secure storage is unavailable on this device. Refusing to store credentials."
        )

    fun saveAuthToken(token: String) {
        requirePrefs().edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun getAuthToken(): String? {
        return requirePrefs().getString(KEY_AUTH_TOKEN, null)
    }

    fun getSessionToken(): String? {
        return getAuthToken()
    }

    fun saveActiveNxId(nxId: String) {
        requirePrefs().edit().putString(KEY_ACTIVE_NX_ID, nxId).apply()
    }

    fun getActiveNxId(): String? {
        return requirePrefs().getString(KEY_ACTIVE_NX_ID, null)
    }

    fun getOrCreateDatabasePassphrase(): ByteArray {
        val existingHex = requirePrefs().getString(KEY_DB_PASSPHRASE, null)
        if (!existingHex.isNullOrBlank()) {
            return existingHex.hexToByteArray()
        }

        val newPassphrase = ByteArray(32)
        SecureRandom().nextBytes(newPassphrase)
        val hex = newPassphrase.toHex()
        requirePrefs().edit().putString(KEY_DB_PASSPHRASE, hex).apply()
        return newPassphrase
    }

    /**
     * Imports an existing database passphrase (e.g. during cross-device restoration).
     * Protected under the local hardware Android KeyStore MasterKey.
     */
    fun saveDatabasePassphrase(passphrase: ByteArray) {
        requirePrefs().edit().putString(KEY_DB_PASSPHRASE, passphrase.toHex()).apply()
    }

    fun saveRecoveryPassphrase(passphrase: String) {
        requirePrefs().edit().putString(KEY_RECOVERY_PASSPHRASE, passphrase.trim()).apply()
    }

    fun getRecoveryPassphrase(): String? {
        return requirePrefs().getString(KEY_RECOVERY_PASSPHRASE, null)
    }

    fun getOrCreateDeviceId(): String {
        val existing = requirePrefs().getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val newId = "dev_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        requirePrefs().edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    fun getDeviceId(): String? {
        return requirePrefs().getString(KEY_DEVICE_ID, null) ?: getOrCreateDeviceId()
    }

    /**
     * Persists the verified hardware security level of the device identity key
     * (STRONGBOX, TEE, SOFTWARE, UNKNOWN). The UI must only claim hardware
     * backing from this value, never from device model or feature flags alone.
     */
    fun saveHardwareSecurityLevel(level: String) {
        if (level.isBlank()) return
        requirePrefs().edit().putString(KEY_HARDWARE_SECURITY_LEVEL, level).apply()
    }

    /** Returns the last verified hardware security level, or null if never probed. */
    fun getHardwareSecurityLevel(): String? =
        requirePrefs().getString(KEY_HARDWARE_SECURITY_LEVEL, null)

    /**
     * Persists the server-issued device identifier. The server is authoritative for
     * device identity; a locally generated id must never override it.
     */
    fun saveDeviceId(deviceId: String) {
        if (deviceId.isBlank()) return
        requirePrefs().edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    fun saveAvatarUrl(url: String?) {
        if (url == null) {
            requirePrefs().edit().remove(KEY_AVATAR_URL).apply()
        } else {
            requirePrefs().edit().putString(KEY_AVATAR_URL, url).apply()
        }
    }

    fun getAvatarUrl(): String? = requirePrefs().getString(KEY_AVATAR_URL, null)

    fun saveIdentityKeyPair(publicKeyHex: String, encryptedPrivKeyHex: String, ivHex: String) {
        requirePrefs().edit()
            .putString(KEY_IDENTITY_PUBLIC_KEY, publicKeyHex)
            .putString(KEY_IDENTITY_ENCRYPTED_PRIVKEY, encryptedPrivKeyHex)
            .putString(KEY_IDENTITY_IV, ivHex)
            .apply()
    }

    fun getIdentityPublicKey(): String? = requirePrefs().getString(KEY_IDENTITY_PUBLIC_KEY, null)
    fun getIdentityEncryptedPrivKey(): String? = requirePrefs().getString(KEY_IDENTITY_ENCRYPTED_PRIVKEY, null)
    fun getIdentityIv(): String? = requirePrefs().getString(KEY_IDENTITY_IV, null)

    fun saveDhIdentityKeyPair(publicKeyHex: String, encryptedPrivKeyHex: String, ivHex: String) {
        requirePrefs().edit()
            .putString(KEY_DH_IDENTITY_PUBLIC_KEY, publicKeyHex)
            .putString(KEY_DH_IDENTITY_ENCRYPTED_PRIVKEY, encryptedPrivKeyHex)
            .putString(KEY_DH_IDENTITY_IV, ivHex)
            .apply()
    }

    fun getDhIdentityPublicKey(): String? = requirePrefs().getString(KEY_DH_IDENTITY_PUBLIC_KEY, null)
    fun getDhIdentityEncryptedPrivKey(): String? = requirePrefs().getString(KEY_DH_IDENTITY_ENCRYPTED_PRIVKEY, null)
    fun getDhIdentityIv(): String? = requirePrefs().getString(KEY_DH_IDENTITY_IV, null)

    fun saveAccountStatus(status: String) {
        requirePrefs().edit().putString(KEY_ACCOUNT_STATUS, status).apply()
    }

    fun getAccountStatus(): String {
        return requirePrefs().getString(KEY_ACCOUNT_STATUS, "ACTIVE") ?: "ACTIVE"
    }

    fun saveActiveUploadSession(
        sessionUri: String,
        versionEpoch: Long,
        vaultFilePath: String,
        expectedFileSize: Long,
        lastConfirmedOffset: Long,
        existingFileId: String? = null
    ) {
        requirePrefs().edit()
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
        val uri = requirePrefs().getString(KEY_UPLOAD_SESSION_URI, null) ?: return null
        val epoch = requirePrefs().getLong(KEY_UPLOAD_VERSION_EPOCH, 0L)
        val path = requirePrefs().getString(KEY_UPLOAD_VAULT_PATH, null) ?: return null
        val size = requirePrefs().getLong(KEY_UPLOAD_EXPECTED_SIZE, 0L)
        val offset = requirePrefs().getLong(KEY_UPLOAD_CONFIRMED_OFFSET, 0L)
        val updated = requirePrefs().getLong(KEY_UPLOAD_UPDATED_AT, 0L)
        val fileId = requirePrefs().getString(KEY_UPLOAD_EXISTING_FILE_ID, null)

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
        requirePrefs().edit()
            .putLong(KEY_UPLOAD_CONFIRMED_OFFSET, confirmedOffset)
            .putLong(KEY_UPLOAD_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearActiveUploadSession() {
        requirePrefs().edit()
            .remove(KEY_UPLOAD_SESSION_URI)
            .remove(KEY_UPLOAD_VERSION_EPOCH)
            .remove(KEY_UPLOAD_VAULT_PATH)
            .remove(KEY_UPLOAD_EXPECTED_SIZE)
            .remove(KEY_UPLOAD_CONFIRMED_OFFSET)
            .remove(KEY_UPLOAD_UPDATED_AT)
            .remove(KEY_UPLOAD_EXISTING_FILE_ID)
            .apply()
    }

    // ==========================================
    // Local App Lock (PIN hash + biometric opt-in)
    // ==========================================

    fun isAppLockEnabled(): Boolean = requirePrefs().getBoolean(KEY_APP_LOCK_ENABLED, false)

    fun setAppLockPin(pin: String) {
        val salt = AppLockPinHasher.newSalt()
        val hash = AppLockPinHasher.hash(pin, salt)
        requirePrefs().edit()
            .putBoolean(KEY_APP_LOCK_ENABLED, true)
            .putString(KEY_APP_LOCK_SALT, salt.toHex())
            .putString(KEY_APP_LOCK_HASH, hash.toHex())
            .apply()
    }

    fun verifyAppLockPin(pin: String): Boolean {
        if (!isAppLockEnabled()) return false
        val saltHex = requirePrefs().getString(KEY_APP_LOCK_SALT, null) ?: return false
        val hashHex = requirePrefs().getString(KEY_APP_LOCK_HASH, null) ?: return false
        return try {
            AppLockPinHasher.verify(pin, saltHex.hexToByteArray(), hashHex.hexToByteArray())
        } catch (e: Exception) {
            false
        }
    }

    fun isBiometricUnlockEnabled(): Boolean =
        isAppLockEnabled() && requirePrefs().getBoolean(KEY_APP_LOCK_BIOMETRIC, false)

    fun setBiometricUnlockEnabled(enabled: Boolean) {
        requirePrefs().edit().putBoolean(KEY_APP_LOCK_BIOMETRIC, enabled).apply()
    }

    fun clearAppLock() {
        requirePrefs().edit()
            .remove(KEY_APP_LOCK_ENABLED)
            .remove(KEY_APP_LOCK_SALT)
            .remove(KEY_APP_LOCK_HASH)
            .remove(KEY_APP_LOCK_BIOMETRIC)
            .apply()
    }

    /**
     * Clears only per-session credentials. The database passphrase, device id and
     * identity keys are deliberately preserved so local encrypted history stays
     * readable after logout and on the next sign-in.
     */
    fun clearSessionCredentials() {
        val editor = requirePrefs().edit()
        SESSION_SCOPED_KEYS.forEach { key -> editor.remove(key) }
        editor.apply()
    }

    /**
     * Full identity wipe. Only for explicit permanent account deletion / device wipe,
     * never for a normal logout.
     */
    fun clearAll() {
        requirePrefs().edit().clear().apply()
    }

    companion object {
        private const val SECURE_PREFS_NAME = "nexa_secure_preferences"
        private const val KEY_AUTH_TOKEN = "key_auth_token"
        private const val KEY_ACTIVE_NX_ID = "key_active_nx_id"
        private const val KEY_DB_PASSPHRASE = "key_db_passphrase"
        private const val KEY_RECOVERY_PASSPHRASE = "key_recovery_passphrase"
        private const val KEY_DEVICE_ID = "key_device_id"
        private const val KEY_HARDWARE_SECURITY_LEVEL = "key_hardware_security_level"
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
        private const val KEY_APP_LOCK_ENABLED = "key_app_lock_enabled"
        private const val KEY_APP_LOCK_SALT = "key_app_lock_salt"
        private const val KEY_APP_LOCK_HASH = "key_app_lock_hash"
        private const val KEY_APP_LOCK_BIOMETRIC = "key_app_lock_biometric"

        /**
         * Keys that belong to a single authenticated session and are safe to drop
         * on logout. Must never contain the DB passphrase, device id, or any
         * identity/keystore material.
         */
        internal val SESSION_SCOPED_KEYS: Set<String> = setOf(
            KEY_AUTH_TOKEN,
            KEY_ACTIVE_NX_ID,
            KEY_AVATAR_URL,
            KEY_ACCOUNT_STATUS,
            KEY_UPLOAD_SESSION_URI,
            KEY_UPLOAD_VERSION_EPOCH,
            KEY_UPLOAD_VAULT_PATH,
            KEY_UPLOAD_EXPECTED_SIZE,
            KEY_UPLOAD_CONFIRMED_OFFSET,
            KEY_UPLOAD_UPDATED_AT,
            KEY_UPLOAD_EXISTING_FILE_ID
        )

        /**
         * Non-recoverable local identity material. Surviving logout is what keeps
         * the encrypted database readable; only an explicit account deletion may
         * remove it.
         */
        internal val LOCAL_IDENTITY_KEYS: Set<String> = setOf(
            KEY_DB_PASSPHRASE,
            KEY_DEVICE_ID,
            KEY_IDENTITY_PUBLIC_KEY,
            KEY_IDENTITY_ENCRYPTED_PRIVKEY,
            KEY_IDENTITY_IV,
            KEY_DH_IDENTITY_PUBLIC_KEY,
            KEY_DH_IDENTITY_ENCRYPTED_PRIVKEY,
            KEY_DH_IDENTITY_IV
        )
    }
}
