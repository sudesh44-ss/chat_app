package com.nexa.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BackupPreferencesState(
    val autoBackupEnabled: Boolean = true,
    val backupOverWifiOnly: Boolean = true,
    val backupWhenCharging: Boolean = true,
    val lastBackupTimestamp: Long = 0L,
    val lastBackupSize: Long = 0L,
    val lastBackupEpoch: Long = 0L,
    val remoteBackupFileId: String? = null,
    val googleAccountEmail: String? = null
)

/**
 * Manages persisted configuration and state for Google Drive AppData backups.
 */
class BackupPrefs(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadCurrentState())
    val state: StateFlow<BackupPreferencesState> = _state.asStateFlow()

    private fun loadCurrentState(): BackupPreferencesState {
        return BackupPreferencesState(
            autoBackupEnabled = prefs.getBoolean(KEY_AUTO_BACKUP_ENABLED, true),
            backupOverWifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true),
            backupWhenCharging = prefs.getBoolean(KEY_CHARGING_ONLY, true),
            lastBackupTimestamp = prefs.getLong(KEY_LAST_BACKUP_TIMESTAMP, 0L),
            lastBackupSize = prefs.getLong(KEY_LAST_BACKUP_SIZE, 0L),
            lastBackupEpoch = prefs.getLong(KEY_LAST_BACKUP_EPOCH, 0L),
            remoteBackupFileId = prefs.getString(KEY_REMOTE_FILE_ID, null),
            googleAccountEmail = prefs.getString(KEY_GOOGLE_ACCOUNT_EMAIL, null)
        )
    }

    private fun refreshState() {
        _state.value = loadCurrentState()
    }

    var autoBackupEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BACKUP_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_BACKUP_ENABLED, value).apply()
            refreshState()
        }

    var backupOverWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
            refreshState()
        }

    var backupWhenCharging: Boolean
        get() = prefs.getBoolean(KEY_CHARGING_ONLY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CHARGING_ONLY, value).apply()
            refreshState()
        }

    var lastBackupTimestamp: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_TIMESTAMP, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_BACKUP_TIMESTAMP, value).apply()
            refreshState()
        }

    var lastBackupSize: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_SIZE, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_BACKUP_SIZE, value).apply()
            refreshState()
        }

    var lastBackupEpoch: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_EPOCH, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_BACKUP_EPOCH, value).apply()
            refreshState()
        }

    var remoteBackupFileId: String?
        get() = prefs.getString(KEY_REMOTE_FILE_ID, null)
        set(value) {
            prefs.edit().putString(KEY_REMOTE_FILE_ID, value).apply()
            refreshState()
        }

    var googleAccountEmail: String?
        get() = prefs.getString(KEY_GOOGLE_ACCOUNT_EMAIL, null)
        set(value) {
            prefs.edit().putString(KEY_GOOGLE_ACCOUNT_EMAIL, value).apply()
            refreshState()
        }

    fun updateBackupSuccess(fileId: String, size: Long, epoch: Long) {
        prefs.edit()
            .putString(KEY_REMOTE_FILE_ID, fileId)
            .putLong(KEY_LAST_BACKUP_SIZE, size)
            .putLong(KEY_LAST_BACKUP_EPOCH, epoch)
            .putLong(KEY_LAST_BACKUP_TIMESTAMP, System.currentTimeMillis())
            .apply()
        refreshState()
    }

    fun clear() {
        prefs.edit().clear().apply()
        refreshState()
    }

    companion object {
        private const val PREFS_NAME = "nexa_backup_preferences"
        private const val KEY_AUTO_BACKUP_ENABLED = "key_auto_backup_enabled"
        private const val KEY_WIFI_ONLY = "key_wifi_only"
        private const val KEY_CHARGING_ONLY = "key_charging_only"
        private const val KEY_LAST_BACKUP_TIMESTAMP = "key_last_backup_timestamp"
        private const val KEY_LAST_BACKUP_SIZE = "key_last_backup_size"
        private const val KEY_LAST_BACKUP_EPOCH = "key_last_backup_epoch"
        private const val KEY_REMOTE_FILE_ID = "key_remote_file_id"
        private const val KEY_GOOGLE_ACCOUNT_EMAIL = "key_google_account_email"
    }
}
