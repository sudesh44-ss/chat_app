package com.nexa.app.presentation.settings.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.data.local.preferences.LockScreenMode
import com.nexa.app.data.local.preferences.NotificationPreferences
import com.nexa.app.data.local.preferences.NotificationPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface NotificationSettingsEvent {
    data class ToggleDirectMessages(val enabled: Boolean) : NotificationSettingsEvent
    data class ToggleMessagePreview(val enabled: Boolean) : NotificationSettingsEvent
    data class ToggleGroups(val enabled: Boolean) : NotificationSettingsEvent
    data class ToggleGroupMentionsOnly(val enabled: Boolean) : NotificationSettingsEvent
    data class ToggleCalls(val enabled: Boolean) : NotificationSettingsEvent
    data class ToggleAllowCallsDuringQuietHours(val allowed: Boolean) : NotificationSettingsEvent
    data class SetLockScreenMode(val mode: LockScreenMode) : NotificationSettingsEvent
    data class ToggleQuietHours(val enabled: Boolean) : NotificationSettingsEvent
    data class SetQuietHoursWindow(val startHour: Int, val startMin: Int, val endHour: Int, val endMin: Int) : NotificationSettingsEvent
    data class ToggleFileTransfers(val enabled: Boolean) : NotificationSettingsEvent
    data class ToggleMeshDiscovery(val enabled: Boolean) : NotificationSettingsEvent
    data class UpdateSystemPermissionState(val isGranted: Boolean) : NotificationSettingsEvent
}

class NotificationSettingsViewModel(
    private val notificationPrefs: NotificationPrefs
) : ViewModel() {

    val preferencesState: StateFlow<NotificationPreferences> = notificationPrefs.preferencesState

    private val _isSystemPermissionGranted = MutableStateFlow(true)
    val isSystemPermissionGranted: StateFlow<Boolean> = _isSystemPermissionGranted.asStateFlow()

    fun onEvent(event: NotificationSettingsEvent) {
        when (event) {
            is NotificationSettingsEvent.ToggleDirectMessages -> {
                notificationPrefs.setDirectMessagesEnabled(event.enabled)
            }
            is NotificationSettingsEvent.ToggleMessagePreview -> {
                notificationPrefs.setMessagePreviewEnabled(event.enabled)
            }
            is NotificationSettingsEvent.ToggleGroups -> {
                notificationPrefs.setGroupsEnabled(event.enabled)
            }
            is NotificationSettingsEvent.ToggleGroupMentionsOnly -> {
                notificationPrefs.setGroupMentionsOnly(event.enabled)
            }
            is NotificationSettingsEvent.ToggleCalls -> {
                notificationPrefs.setCallsEnabled(event.enabled)
            }
            is NotificationSettingsEvent.ToggleAllowCallsDuringQuietHours -> {
                notificationPrefs.setAllowCallsDuringQuietHours(event.allowed)
            }
            is NotificationSettingsEvent.SetLockScreenMode -> {
                notificationPrefs.setLockScreenVisibility(event.mode)
            }
            is NotificationSettingsEvent.ToggleQuietHours -> {
                notificationPrefs.setQuietHoursEnabled(event.enabled)
            }
            is NotificationSettingsEvent.SetQuietHoursWindow -> {
                notificationPrefs.setQuietHoursTimeWindow(
                    event.startHour,
                    event.startMin,
                    event.endHour,
                    event.endMin
                )
            }
            is NotificationSettingsEvent.ToggleFileTransfers -> {
                notificationPrefs.setFileTransfersEnabled(event.enabled)
            }
            is NotificationSettingsEvent.ToggleMeshDiscovery -> {
                notificationPrefs.setMeshDiscoveryAlertsEnabled(event.enabled)
            }
            is NotificationSettingsEvent.UpdateSystemPermissionState -> {
                _isSystemPermissionGranted.value = event.isGranted
            }
        }
    }
}
