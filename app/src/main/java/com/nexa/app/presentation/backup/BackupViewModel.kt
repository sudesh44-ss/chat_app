package com.nexa.app.presentation.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.core.backup.Bip39PassphraseHelper
import com.nexa.app.core.util.DriveResource
import com.nexa.app.data.drive.DriveFile
import com.nexa.app.data.drive.GoogleDriveAuthManager
import com.nexa.app.data.local.preferences.BackupPreferencesState
import com.nexa.app.data.local.preferences.BackupPrefs
import com.nexa.app.domain.repository.DriveBackupRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel managing backup creation, remote discovery, passphrase configuration, and multi-device restoration.
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

    val preferencesState: StateFlow<BackupPreferencesState> = backupPrefs.state

    private val _isAuthorized = MutableStateFlow(authManager.isAuthorized())
    val isAuthorized: StateFlow<Boolean> = _isAuthorized.asStateFlow()

    private val _recoveryPassphrase = MutableStateFlow(backupRepository.getRecoveryPassphrase())
    val recoveryPassphrase: StateFlow<String?> = _recoveryPassphrase.asStateFlow()

    private val _isPassphraseRevealed = MutableStateFlow(false)
    val isPassphraseRevealed: StateFlow<Boolean> = _isPassphraseRevealed.asStateFlow()

    private var activeBackupJob: Job? = null
    private var activeRestoreJob: Job? = null

    init {
        refreshAuthState()
        if (recoveryPassphrase.value.isNullOrBlank()) {
            val generated = backupRepository.generateOrGetRecoveryPassphrase()
            _recoveryPassphrase.value = generated
        }
    }

    fun refreshAuthState() {
        val account = authManager.getLastSignedInAccount()
        _isAuthorized.value = account != null
        if (account?.email != null) {
            backupPrefs.googleAccountEmail = account.email
        }
    }

    fun triggerBackup() {
        val passphrase = _recoveryPassphrase.value
            ?: backupRepository.generateOrGetRecoveryPassphrase().also { _recoveryPassphrase.value = it }

        activeBackupJob?.cancel()
        activeBackupJob = viewModelScope.launch {
            backupRepository.createAndUploadBackup(passphrase).collect { resource ->
                _backupResource.value = resource
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

    fun restoreBackup(fileId: String, mnemonicPhrase: String) {
        val normalized = Bip39PassphraseHelper.normalize(mnemonicPhrase)
        if (!Bip39PassphraseHelper.validateMnemonic(normalized)) {
            _restoreResource.value = DriveResource.Error("Invalid 12-word recovery passphrase. Please verify all words.")
            return
        }

        activeRestoreJob?.cancel()
        activeRestoreJob = viewModelScope.launch {
            backupRepository.downloadAndRestoreBackup(fileId, normalized).collect { resource ->
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

    fun setAutoBackupEnabled(enabled: Boolean) {
        backupPrefs.autoBackupEnabled = enabled
    }

    fun setWifiOnly(enabled: Boolean) {
        backupPrefs.backupOverWifiOnly = enabled
    }

    fun setChargingOnly(enabled: Boolean) {
        backupPrefs.backupWhenCharging = enabled
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
}
