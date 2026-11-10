package com.nexa.app.presentation.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.core.backup.Bip39PassphraseHelper
import com.nexa.app.core.util.DriveResource
import com.nexa.app.data.drive.DriveFile
import com.nexa.app.data.drive.GoogleDriveAuthManager
import com.nexa.app.data.local.preferences.BackupFrequency
import com.nexa.app.data.local.preferences.BackupPreferencesState
import com.nexa.app.data.local.preferences.BackupPrefs
import com.nexa.app.domain.repository.DriveBackupRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel managing Google Drive account state, granular backup categories,
 * scheduling, integrity verification, backup deletion, and selective cross-device restoration.
 */
class BackupViewModel(
    private val backupRepository: DriveBackupRepository,
    private val backupPrefs: BackupPrefs,
    private val authManager: GoogleDriveAuthManager
) : ViewModel() {

    private val _backupResource = MutableStateFlow<DriveResource<String>>(DriveResource.Idle)
    val backupResource: StateFlow<DriveResource<String>> = _backupResource.asStateFlow()

    private val _remoteBackupFile = MutableStateFlow<DriveResource<DriveFile?>>(DriveResource.Idle)
    val remoteBackupFile: StateFlow<DriveResource<DriveFile?>> = _remoteBackupFile.asStateFlow()

    private val _restoreResource = MutableStateFlow<DriveResource<Boolean>>(DriveResource.Idle)
    val restoreResource: StateFlow<DriveResource<Boolean>> = _restoreResource.asStateFlow()

    private val _managementResource = MutableStateFlow<DriveResource<Boolean>>(DriveResource.Idle)
    val managementResource: StateFlow<DriveResource<Boolean>> = _managementResource.asStateFlow()

    val preferencesState: StateFlow<BackupPreferencesState> = backupPrefs.state

    private val _isAuthorized = MutableStateFlow(authManager.isAuthorized())
    val isAuthorized: StateFlow<Boolean> = _isAuthorized.asStateFlow()

    private val _recoveryPassphrase = MutableStateFlow(backupRepository.getRecoveryPassphrase())
    val recoveryPassphrase: StateFlow<String?> = _recoveryPassphrase.asStateFlow()

    private val _isPassphraseRevealed = MutableStateFlow(false)
    val isPassphraseRevealed: StateFlow<Boolean> = _isPassphraseRevealed.asStateFlow()

    private val _categoryConfig = MutableStateFlow(backupPrefs.categoryConfig)
    val categoryConfig: StateFlow<BackupCategoryConfig> = _categoryConfig.asStateFlow()

    private val _estimatedSize = MutableStateFlow(0L)
    val estimatedSize: StateFlow<Long> = _estimatedSize.asStateFlow()

    // Selective Restore Category Selection
    private val _restoreCategoryConfig = MutableStateFlow(BackupCategoryConfig())
    val restoreCategoryConfig: StateFlow<BackupCategoryConfig> = _restoreCategoryConfig.asStateFlow()

    private var activeBackupJob: Job? = null
    private var activeRestoreJob: Job? = null

    init {
        refreshAuthState()
        if (recoveryPassphrase.value.isNullOrBlank()) {
            val generated = backupRepository.generateOrGetRecoveryPassphrase()
            _recoveryPassphrase.value = generated
        }
        recalculateEstimatedSize()
    }

    fun refreshAuthState() {
        val account = authManager.getLastSignedInAccount()
        _isAuthorized.value = account != null
        if (account?.email != null) {
            backupPrefs.googleAccountEmail = account.email
            backupPrefs.googleAccountAvatarUrl = account.photoUrl?.toString()
        }
    }

    fun recalculateEstimatedSize() {
        viewModelScope.launch {
            val size = backupRepository.calculateBackupSize(_categoryConfig.value)
            _estimatedSize.value = size
        }
    }

    // --- Category Toggles ---

    fun updateCategory(transform: (BackupCategoryConfig) -> BackupCategoryConfig) {
        val updated = transform(_categoryConfig.value)
        _categoryConfig.value = updated
        backupPrefs.categoryConfig = updated
        recalculateEstimatedSize()
    }

    fun selectAllCategories() {
        val all = BackupCategoryConfig.allEnabled()
        _categoryConfig.value = all
        backupPrefs.categoryConfig = all
        recalculateEstimatedSize()
    }

    fun deselectAllCategories() {
        val none = BackupCategoryConfig.allDisabled()
        _categoryConfig.value = none
        backupPrefs.categoryConfig = none
        recalculateEstimatedSize()
    }

    // --- Selective Restore Category Toggles ---

    fun updateRestoreCategory(transform: (BackupCategoryConfig) -> BackupCategoryConfig) {
        _restoreCategoryConfig.value = transform(_restoreCategoryConfig.value)
    }

    fun setAllRestoreCategories(enabled: Boolean) {
        _restoreCategoryConfig.value = if (enabled) BackupCategoryConfig.allEnabled() else BackupCategoryConfig.allDisabled()
    }

    // --- Account Management ---

    fun disconnectGoogleAccount() {
        viewModelScope.launch {
            authManager.signOut()
            backupPrefs.googleAccountEmail = null
            backupPrefs.googleAccountAvatarUrl = null
            refreshAuthState()
            _remoteBackupFile.value = DriveResource.Idle
        }
    }

    // --- Backup Execution ---

    fun triggerBackup() {
        val passphrase = _recoveryPassphrase.value
            ?: backupRepository.generateOrGetRecoveryPassphrase().also { _recoveryPassphrase.value = it }

        activeBackupJob?.cancel()
        activeBackupJob = viewModelScope.launch {
            backupRepository.createAndUploadBackup(passphrase, _categoryConfig.value).collect { resource ->
                _backupResource.value = resource
                if (resource is DriveResource.Success) {
                    scanRemoteBackup()
                }
            }
        }
    }

    fun cancelActiveBackup() {
        activeBackupJob?.cancel()
        _backupResource.value = DriveResource.Idle
    }

    fun scanRemoteBackup() {
        viewModelScope.launch {
            backupRepository.checkRemoteBackup().collect { resource ->
                _remoteBackupFile.value = resource
            }
        }
    }

    fun deleteRemoteBackup(fileId: String) {
        viewModelScope.launch {
            backupRepository.deleteRemoteBackup(fileId).collect { resource ->
                _managementResource.value = resource
                if (resource is DriveResource.Success) {
                    _remoteBackupFile.value = DriveResource.Success(null)
                }
            }
        }
    }

    fun verifyRemoteBackupIntegrity(fileId: String) {
        viewModelScope.launch {
            backupRepository.verifyRemoteBackupIntegrity(fileId).collect { resource ->
                _managementResource.value = resource
            }
        }
    }

    // --- Restore Execution ---

    fun restoreBackup(fileId: String, mnemonicPhrase: String) {
        val normalized = Bip39PassphraseHelper.normalize(mnemonicPhrase)
        if (!Bip39PassphraseHelper.validateMnemonic(normalized)) {
            _restoreResource.value = DriveResource.Error("Invalid 12-word recovery passphrase. Please verify all words.")
            return
        }

        activeRestoreJob?.cancel()
        activeRestoreJob = viewModelScope.launch {
            backupRepository.downloadAndRestoreBackup(
                fileId = fileId,
                passphraseWords = normalized,
                restoreCategoryConfig = _restoreCategoryConfig.value
            ).collect { resource ->
                _restoreResource.value = resource
                if (resource is DriveResource.Success) {
                    _recoveryPassphrase.value = normalized
                }
            }
        }
    }

    fun cancelActiveRestore() {
        activeRestoreJob?.cancel()
        _restoreResource.value = DriveResource.Idle
    }

    // --- Preferences & Schedule ---

    fun setAutoBackupEnabled(enabled: Boolean) {
        backupPrefs.autoBackupEnabled = enabled
    }

    fun setFrequency(frequency: BackupFrequency) {
        backupPrefs.backupFrequency = frequency
    }

    fun setWifiOnly(enabled: Boolean) {
        backupPrefs.backupOverWifiOnly = enabled
    }

    fun setChargingOnly(enabled: Boolean) {
        backupPrefs.backupWhenCharging = enabled
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        backupPrefs.backupNotificationsEnabled = enabled
    }

    fun toggleRevealPassphrase() {
        _isPassphraseRevealed.value = !_isPassphraseRevealed.value
    }

    fun resetBackupResource() {
        _backupResource.value = DriveResource.Idle
    }

    fun resetRestoreResource() {
        _restoreResource.value = DriveResource.Idle
    }

    fun resetManagementResource() {
        _managementResource.value = DriveResource.Idle
    }
}
