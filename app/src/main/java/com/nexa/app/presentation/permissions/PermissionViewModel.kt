package com.nexa.app.presentation.permissions

import android.content.Context
import androidx.lifecycle.ViewModel
import com.nexa.app.core.permission.PermissionHelper
import com.nexa.app.core.permission.PermissionItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PermissionUiState(
    val permissions: List<PermissionItem> = emptyList(),
    val isAllGranted: Boolean = false,
    val showLocationNoticeForBle: Boolean = false
)

class PermissionViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PermissionUiState())
    val uiState: StateFlow<PermissionUiState> = _uiState.asStateFlow()

    fun refreshPermissions(context: Context) {
        val items = PermissionHelper.getPermissionDisplayItems(context)
        val allGranted = items.all { it.isGranted }
        val locationNotice = PermissionHelper.isLocationNeededForBle()

        _uiState.update {
            it.copy(
                permissions = items,
                isAllGranted = allGranted,
                showLocationNoticeForBle = locationNotice
            )
        }
    }
}
