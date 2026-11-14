package com.nexa.app.presentation.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexa.app.data.local.entity.StatusViewEntity
import com.nexa.app.domain.model.StatusItem
import com.nexa.app.domain.model.UserStatusGroup
import com.nexa.app.domain.repository.StatusRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel governing status stories feed, user-wise grouping,
 * sequential story playback, view tracking, and status creation.
 */
class StatusViewModel(
    private val statusRepository: StatusRepository
) : ViewModel() {

    // Other users' status groups: Strictly ONE entry per user with active statuses
    val otherStatusGroups: StateFlow<List<UserStatusGroup>> = statusRepository
        .getUserStatusGroupsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mutedStatusGroups: StateFlow<List<UserStatusGroup>> = statusRepository
        .getMutedUserStatusGroupsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // My Status group: Strictly ONE entry for current user, or null if 0 active statuses
    val myStatusGroup: StateFlow<UserStatusGroup?> = statusRepository
        .getMyStatusGroupFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Active status group being viewed in the sequential story viewer
    private val _selectedStatusGroup = MutableStateFlow<UserStatusGroup?>(null)
    val selectedStatusGroup: StateFlow<UserStatusGroup?> = _selectedStatusGroup.asStateFlow()

    // Current status index inside the selected status group
    private val _currentStatusIndex = MutableStateFlow(0)
    val currentStatusIndex: StateFlow<Int> = _currentStatusIndex.asStateFlow()

    // Dialog control for creating a new status
    private val _showCreateStatusDialog = MutableStateFlow(false)
    val showCreateStatusDialog: StateFlow<Boolean> = _showCreateStatusDialog.asStateFlow()

    // Viewers sheet for inspecting peer views on a status item owned by current user
    private val _activeViewersStatus = MutableStateFlow<StatusItem?>(null)
    val activeViewersStatus: StateFlow<StatusItem?> = _activeViewersStatus.asStateFlow()

    private val _statusViewersList = MutableStateFlow<List<StatusViewEntity>>(emptyList())
    val statusViewersList: StateFlow<List<StatusViewEntity>> = _statusViewersList.asStateFlow()

    private var selectedStatusObservation: Job? = null

    fun openStatusGroup(group: UserStatusGroup, startIndex: Int = 0) {
        if (group.statuses.isEmpty()) {
            closeStatusGroup()
            return
        }
        selectedStatusObservation?.cancel()
        _selectedStatusGroup.value = group
        val firstUnviewed = group.statuses.indexOfFirst { !it.isViewed }
        val selectedIndex = if (startIndex == 0 && firstUnviewed >= 0) firstUnviewed else startIndex
        _currentStatusIndex.value = selectedIndex.coerceIn(0, group.statuses.lastIndex)
        selectedStatusObservation = viewModelScope.launch {
            statusRepository.getStatusesForUserFlow(group.userNxId).collect { currentStatuses ->
                if (currentStatuses.isEmpty()) {
                    closeStatusGroup()
                } else {
                    val latest = group.copy(
                        statuses = currentStatuses,
                        latestTimestamp = currentStatuses.maxOfOrNull { it.createdAt } ?: 0L,
                        hasUnviewedStatus = currentStatuses.any { !it.isViewed }
                    )
                    _selectedStatusGroup.value = latest
                    _currentStatusIndex.value = _currentStatusIndex.value.coerceIn(0, latest.statuses.lastIndex)
                }
            }
        }
    }

    fun closeStatusGroup() {
        selectedStatusObservation?.cancel()
        selectedStatusObservation = null
        _selectedStatusGroup.value = null
        _currentStatusIndex.value = 0
    }

    fun refreshStatusAvailability() {
        viewModelScope.launch {
            statusRepository.refreshAvailability()
        }
    }

    fun nextStatus() {
        val group = _selectedStatusGroup.value ?: return
        if (_currentStatusIndex.value < group.statuses.lastIndex) {
            _currentStatusIndex.value += 1
        } else {
            closeStatusGroup()
        }
    }

    fun previousStatus() {
        if (_currentStatusIndex.value > 0) {
            _currentStatusIndex.value -= 1
        }
    }

    fun markStatusViewed(statusId: String) {
        viewModelScope.launch {
            statusRepository.markStatusViewed(statusId)
        }
    }

    fun deleteStatus(statusId: String) {
        viewModelScope.launch {
            statusRepository.deleteStatus(statusId)
            val currentGroup = _selectedStatusGroup.value
            if (currentGroup != null) {
                val remaining = currentGroup.statuses.filter { it.statusId != statusId }
                if (remaining.isEmpty()) {
                    closeStatusGroup()
                } else {
                    _selectedStatusGroup.value = currentGroup.copy(statuses = remaining)
                    if (_currentStatusIndex.value >= remaining.size) {
                        _currentStatusIndex.value = remaining.lastIndex
                    }
                }
            }
        }
    }

    fun createTextStatus(caption: String, privacy: String = "CONTACTS") {
        if (caption.isBlank()) return
        viewModelScope.launch {
            try {
                statusRepository.createTextStatus(caption.trim(), privacy)
                _showCreateStatusDialog.value = false
            } catch (e: Exception) {
                android.util.Log.e("StatusViewModel", "Failed to create text status", e)
            }
        }
    }

    fun createMediaStatus(
        mediaUri: String,
        caption: String = "",
        statusType: String = "IMAGE",
        privacy: String = "CONTACTS"
    ) {
        if (mediaUri.isBlank()) return
        viewModelScope.launch {
            try {
                statusRepository.createMediaStatus(mediaUri, caption.trim(), statusType, privacy)
                _showCreateStatusDialog.value = false
            } catch (e: Exception) {
                android.util.Log.e("StatusViewModel", "Failed to create media status", e)
            }
        }
    }

    fun showCreateDialog() {
        _showCreateStatusDialog.value = true
    }

    fun dismissCreateDialog() {
        _showCreateStatusDialog.value = false
    }

    fun showStatusViewers(status: StatusItem) {
        _activeViewersStatus.value = status
        viewModelScope.launch {
            _statusViewersList.value = statusRepository.getStatusViewers(status.statusId)
        }
    }

    fun dismissStatusViewers() {
        _activeViewersStatus.value = null
        _statusViewersList.value = emptyList()
    }

    class Factory(
        private val statusRepository: StatusRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StatusViewModel(statusRepository) as T
        }
    }
}
