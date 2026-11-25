package com.nexa.app.presentation.backup

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.core.backup.BackupCategoryConfig
import com.nexa.app.core.backup.BackupPasswordValidator
import com.nexa.app.core.backup.Bip39PassphraseHelper
import com.nexa.app.core.backup.PasswordValidationResult
import com.nexa.app.core.backup.RestoreInventory
import com.nexa.app.core.backup.RestorePlan
import com.nexa.app.core.backup.RestoreResultSummary
import com.nexa.app.core.util.DriveResource
import com.nexa.app.data.drive.DriveFile
import com.nexa.app.data.drive.GoogleDriveAuthManager
import com.nexa.app.data.local.preferences.BackupFrequency
import com.nexa.app.data.local.preferences.BackupPreferencesState
import com.nexa.app.data.local.preferences.BackupPrefs
import com.nexa.app.domain.manager.BackupScheduleManager
import com.nexa.app.domain.model.BackupMode
import com.nexa.app.domain.model.BackupRestorePoint
import com.nexa.app.domain.model.BackupScheduleConfig
import com.nexa.app.domain.model.ChatConversation
import com.nexa.app.domain.model.ConversationBackupPolicy
import com.nexa.app.domain.model.ConversationCustomCategories
import com.nexa.app.domain.repository.DriveBackupRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel managing Google Drive account state, granular backup categories,
 * scheduling, integrity verification, backup deletion, and selective cross-device restoration.
 */
class BackupViewModel(
    private val backupRepository: DriveBackupRepository,
    private val backupPrefs: BackupPrefs,
    private val authManager: GoogleDriveAuthManager,
    private val context: Context? = null
) : ViewModel() {

    private val _backupResource = MutableStateFlow<DriveResource<String>>(DriveResource.Idle)
    val backupResource: StateFlow<DriveResource<String>> = _backupResource.asStateFlow()

    private val _remoteBackupFile = MutableStateFlow<DriveResource<DriveFile?>>(DriveResource.Idle)
    val remoteBackupFile: StateFlow<DriveResource<DriveFile?>> = _remoteBackupFile.asStateFlow()

    private val _restoreResource = MutableStateFlow<DriveResource<Boolean>>(DriveResource.Idle)
    val restoreResource: StateFlow<DriveResource<Boolean>> = _restoreResource.asStateFlow()

    private val _restorePoints = MutableStateFlow<DriveResource<List<BackupRestorePoint>>>(DriveResource.Idle)
    val restorePoints: StateFlow<DriveResource<List<BackupRestorePoint>>> = _restorePoints.asStateFlow()

    private val _stagedInventory = MutableStateFlow<DriveResource<RestoreInventory?>>(DriveResource.Idle)
    val stagedInventory: StateFlow<DriveResource<RestoreInventory?>> = _stagedInventory.asStateFlow()

    private val _restoreSummary = MutableStateFlow<DriveResource<RestoreResultSummary?>>(DriveResource.Idle)
    val restoreSummary: StateFlow<DriveResource<RestoreResultSummary?>> = _restoreSummary.asStateFlow()

    private val _managementResource = MutableStateFlow<DriveResource<Boolean>>(DriveResource.Idle)
    val managementResource: StateFlow<DriveResource<Boolean>> = _managementResource.asStateFlow()

    val preferencesState: StateFlow<BackupPreferencesState> = backupPrefs.state

    private val _isAuthorized = MutableStateFlow(authManager.isAuthorized())
    val isAuthorized: StateFlow<Boolean> = _isAuthorized.asStateFlow()

    private val _recoveryPassphrase = MutableStateFlow(backupRepository.getRecoveryPassphrase())
    val recoveryPassphrase: StateFlow<String?> = _recoveryPassphrase.asStateFlow()

    private val _hasCustomPassword = MutableStateFlow(backupRepository.hasCustomBackupPassword())
    val hasCustomPassword: StateFlow<Boolean> = _hasCustomPassword.asStateFlow()

    private val _isPassphraseRevealed = MutableStateFlow(false)
    val isPassphraseRevealed: StateFlow<Boolean> = _isPassphraseRevealed.asStateFlow()

    private val _categoryConfig = MutableStateFlow(backupPrefs.categoryConfig)
    val categoryConfig: StateFlow<BackupCategoryConfig> = _categoryConfig.asStateFlow()

    private val _estimatedSize = MutableStateFlow(0L)
    val estimatedSize: StateFlow<Long> = _estimatedSize.asStateFlow()

    // Selective Restore Category Selection
    private val _restoreCategoryConfig = MutableStateFlow(BackupCategoryConfig())
    val restoreCategoryConfig: StateFlow<BackupCategoryConfig> = _restoreCategoryConfig.asStateFlow()

    // Preselected Restore Point for Direct Restore Navigation
    private val _selectedRestorePointForRestore = MutableStateFlow<BackupRestorePoint?>(null)
    val selectedRestorePointForRestore: StateFlow<BackupRestorePoint?> = _selectedRestorePointForRestore.asStateFlow()

    fun selectRestorePointForRestore(point: BackupRestorePoint?) {
        _selectedRestorePointForRestore.value = point
    }

    // Priority Conversation Streams
    val priorityConversations: Flow<List<ChatConversation>> = backupRepository.getPriorityConversations()
    val priorityPersonalConversations: Flow<List<ChatConversation>> = backupRepository.getPriorityPersonalConversations()
    val priorityGroupConversations: Flow<List<ChatConversation>> = backupRepository.getPriorityGroupConversations()

    val allPersonalConversations: Flow<List<ChatConversation>> = backupRepository.getAllPersonalConversations()
    val allGroupConversations: Flow<List<ChatConversation>> = backupRepository.getAllGroupConversations()

    val priorityChatCount: Flow<Int> = backupRepository.getPriorityChatCountFlow()
    val excludedChatCount: Flow<Int> = backupRepository.getExcludedChatCountFlow()
    val customPolicyChatCount: Flow<Int> = backupRepository.getCustomPolicyChatCountFlow()

    private var activeBackupJob: Job? = null
    private var activeRestoreJob: Job? = null
    private var activeStagingDir: File? = null

    init {
        refreshAuthState()
        if (recoveryPassphrase.value.isNullOrBlank()) {
            val generated = backupRepository.generateOrGetRecoveryPassphrase()
            _recoveryPassphrase.value = generated
        }
        recalculateEstimatedSize()
        pruneDanglingStagingDirs()
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

    // --- Backup Password & Recovery Controls (Part 8) ---

    fun setBackupPassword(password: String, confirmation: String): PasswordValidationResult {
        val result = BackupPasswordValidator.validate(password, confirmation)
        if (result.isValid) {
            val success = backupRepository.setBackupPassword(password)
            if (success) {
                _hasCustomPassword.value = true
            } else {
                return PasswordValidationResult(
                    isValid = false,
                    strength = result.strength,
                    errors = listOf("Failed to store backup password securely.")
                )
            }
        }
        return result
    }

    fun changeBackupPassword(
        currentPasswordOrMnemonic: String,
        newPassword: String,
        newConfirmation: String
    ): PasswordValidationResult {
        val validation = BackupPasswordValidator.validate(newPassword, newConfirmation)
        if (!validation.isValid) return validation

        val success = backupRepository.changeBackupPassword(currentPasswordOrMnemonic, newPassword)
        if (!success) {
            return PasswordValidationResult(
                isValid = false,
                strength = validation.strength,
                errors = listOf("Current password or 12-word recovery phrase is incorrect.")
            )
        }
        _hasCustomPassword.value = true
        return validation
    }

    fun resetBackupPasswordWithMnemonic(
        mnemonic: String,
        newPassword: String,
        newConfirmation: String
    ): PasswordValidationResult {
        val validation = BackupPasswordValidator.validate(newPassword, newConfirmation)
        if (!validation.isValid) return validation

        val success = backupRepository.resetBackupPasswordWithMnemonic(mnemonic, newPassword)
        if (!success) {
            return PasswordValidationResult(
                isValid = false,
                strength = validation.strength,
                errors = listOf("Invalid 12-word recovery mnemonic. Words do not match account recovery key.")
            )
        }
        _hasCustomPassword.value = true
        return validation
    }

    fun removeBackupPassword() {
        backupRepository.removeBackupPassword()
        _hasCustomPassword.value = false
    }

    // --- Backup Execution ---

    fun triggerBackup() {
        if (!_categoryConfig.value.hasAnySelected()) {
            _backupResource.value = DriveResource.Error("All backup categories are disabled. Please enable at least one category to back up.")
            return
        }

        val credential = backupRepository.getActiveBackupCredential()
            ?: _recoveryPassphrase.value
            ?: backupRepository.generateOrGetRecoveryPassphrase().also { _recoveryPassphrase.value = it }

        activeBackupJob?.cancel()
        activeBackupJob = viewModelScope.launch {
            backupRepository.createAndUploadBackup(credential, _categoryConfig.value).collect { resource ->
                _backupResource.value = resource
                if (resource is DriveResource.Success) {
                    scanRemoteBackup()
                    fetchRestorePoints()
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
                    fetchRestorePoints()
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

    fun restoreBackup(fileId: String, credential: String) {
        val trimmed = credential.trim()
        if (trimmed.isBlank()) {
            _restoreResource.value = DriveResource.Error("Please enter your backup password or 12-word recovery phrase.")
            return
        }

        val effectiveCredential = if (Bip39PassphraseHelper.validateMnemonic(trimmed)) {
            Bip39PassphraseHelper.normalize(trimmed)
        } else {
            trimmed
        }

        activeRestoreJob?.cancel()
        activeRestoreJob = viewModelScope.launch {
            backupRepository.downloadAndRestoreBackup(
                fileId = fileId,
                passphraseWords = effectiveCredential,
                restoreCategoryConfig = _restoreCategoryConfig.value
            ).collect { resource ->
                _restoreResource.value = resource
                if (resource is DriveResource.Success) {
                    if (Bip39PassphraseHelper.validateMnemonic(effectiveCredential)) {
                        _recoveryPassphrase.value = effectiveCredential
                    }
                }
            }
        }
    }

    fun cancelActiveRestore() {
        activeRestoreJob?.cancel()
        cleanupStagingData()
        _restoreResource.value = DriveResource.Idle
        _stagedInventory.value = DriveResource.Idle
        _restoreSummary.value = DriveResource.Idle
    }

    fun fetchRestorePoints() {
        viewModelScope.launch {
            backupRepository.getRestorePoints().collect { resource ->
                _restorePoints.value = resource
            }
        }
    }

    fun inspectBackup(fileId: String, credential: String) {
        val trimmed = credential.trim()
        if (trimmed.isBlank()) {
            _stagedInventory.value = DriveResource.Error("Please enter your backup password or 12-word recovery phrase.")
            return
        }

        val effectiveCredential = if (Bip39PassphraseHelper.validateMnemonic(trimmed)) {
            Bip39PassphraseHelper.normalize(trimmed)
        } else {
            trimmed
        }

        cleanupStagingData()
        activeRestoreJob?.cancel()
        activeRestoreJob = viewModelScope.launch {
            backupRepository.inspectBackup(fileId, effectiveCredential).collect { resource ->
                _stagedInventory.value = resource
                if (resource is DriveResource.Success && resource.data != null) {
                    activeStagingDir = resource.data.stagingDir
                    _restoreCategoryConfig.value = resource.data.availableCategories
                    if (Bip39PassphraseHelper.validateMnemonic(effectiveCredential)) {
                        _recoveryPassphrase.value = effectiveCredential
                    }
                } else if (resource is DriveResource.Error) {
                    cleanupStagingData()
                }
            }
        }
    }

    fun executeRestorePlan(inventory: RestoreInventory, plan: RestorePlan) {
        activeRestoreJob?.cancel()
        activeRestoreJob = viewModelScope.launch {
            backupRepository.executeRestorePlan(inventory, plan).collect { resource ->
                _restoreSummary.value = resource
                if (resource is DriveResource.Success) {
                    activeStagingDir = null
                    _restoreResource.value = DriveResource.Success(true)
                } else if (resource is DriveResource.Error) {
                    _restoreResource.value = DriveResource.Error(resource.message, resource.throwable)
                }
            }
        }
    }

    fun resetStagedInventory() {
        cleanupStagingData()
        _stagedInventory.value = DriveResource.Idle
    }

    fun cleanupStagingData() {
        activeStagingDir?.let { dir ->
            safeDeleteStagingDir(dir)
            activeStagingDir = null
        }
    }

    private fun pruneDanglingStagingDirs() {
        try {
            context?.cacheDir?.listFiles { file ->
                file.isDirectory && (file.name.startsWith("restore_staging_") || file.name.startsWith("staging_") || file.name.startsWith("nx_inspect_"))
            }?.forEach { lingeringDir ->
                safeDeleteStagingDir(lingeringDir)
            }
        } catch (ignored: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        activeRestoreJob?.cancel()
        cleanupStagingData()
    }

    fun resetRestoreSummary() {
        _restoreSummary.value = DriveResource.Idle
        _restoreResource.value = DriveResource.Idle
    }

    // --- Preferences & Schedule ---

    private fun triggerReschedule() {
        context?.let { ctx ->
            BackupScheduleManager.reschedule(ctx)
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        backupPrefs.autoBackupEnabled = enabled
        triggerReschedule()
    }

    fun setFrequency(frequency: BackupFrequency) {
        backupPrefs.backupFrequency = frequency
        triggerReschedule()
    }

    fun setWifiOnly(enabled: Boolean) {
        backupPrefs.backupOverWifiOnly = enabled
        triggerReschedule()
    }

    fun setChargingOnly(enabled: Boolean) {
        backupPrefs.backupWhenCharging = enabled
        triggerReschedule()
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        backupPrefs.backupNotificationsEnabled = enabled
    }

    // --- Backup Mode Architecture ---

    fun setBackupMode(mode: BackupMode) {
        backupPrefs.backupMode = mode
        triggerReschedule()
    }

    // --- Schedule Timing Configuration ---

    fun setDailyScheduleTime(hour: Int, minute: Int) {
        backupPrefs.setDailyScheduleTime(hour, minute)
        triggerReschedule()
    }

    fun setWeeklyScheduleDay(dayOfWeek: Int) {
        backupPrefs.setWeeklyScheduleDay(dayOfWeek)
        triggerReschedule()
    }

    fun setMonthlyScheduleDate(dayOfMonth: Int) {
        backupPrefs.setMonthlyScheduleDate(dayOfMonth)
        triggerReschedule()
    }

    fun setScheduleConfig(config: BackupScheduleConfig) {
        backupPrefs.scheduleConfig = config
        triggerReschedule()
    }

    fun getNextScheduledBackupTimeFormatted(): String {
        val state = preferencesState.value
        if (!state.autoBackupEnabled || state.backupMode == BackupMode.MANUAL_ONLY) {
            return "Manual Only (Automated backups disabled)"
        }
        val nextEpoch = BackupScheduleManager.computeNextScheduledTime(state.scheduleConfig)
        return BackupScheduleManager.formatNextScheduledTime(nextEpoch)
    }

    // --- Chat Priority Selections ---

    fun setConversationPriority(conversationId: String, isPriority: Boolean) {
        viewModelScope.launch {
            backupRepository.setConversationPriority(conversationId, isPriority)
        }
    }

    fun setBulkPriority(conversationIds: List<String>, isPriority: Boolean) {
        viewModelScope.launch {
            conversationIds.forEach { id ->
                backupRepository.setConversationPriority(id, isPriority)
            }
        }
    }

    fun setConversationBackupPolicy(
        conversationId: String,
        policy: ConversationBackupPolicy,
        customCategories: ConversationCustomCategories? = null
    ) {
        viewModelScope.launch {
            backupRepository.setConversationBackupPolicy(conversationId, policy, customCategories)
        }
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

    companion object {
        fun safeDeleteStagingDir(dir: File): Boolean {
            if (!dir.exists()) return true
            var success = dir.deleteRecursively()
            if (!success && dir.exists()) {
                try {
                    dir.walkBottomUp().forEach { f ->
                        f.setWritable(true)
                        f.delete()
                    }
                    success = !dir.exists()
                } catch (ignored: Exception) {}
            }
            return success
        }
    }
}
