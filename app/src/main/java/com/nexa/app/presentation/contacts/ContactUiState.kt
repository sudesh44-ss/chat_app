package com.nexa.app.presentation.contacts

import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.remote.dto.BlockedUserItemDto
import com.nexa.app.data.remote.dto.ConnectionRequestItemDto
import com.nexa.app.data.remote.dto.DiscoveredUserDto
import com.nexa.app.domain.model.DeviceContact

data class ContactUiState(
    val mutualFriends: List<ContactEntity> = emptyList(),
    val pendingReceived: List<ContactEntity> = emptyList(),
    val pendingSent: List<ContactEntity> = emptyList(),
    val pendingCount: Int = 0,
    val deviceContacts: List<DeviceContact> = emptyList(),
    val searchQuery: String = "",
    val isPermissionGranted: Boolean = false,
    val isPermissionDeniedPermanently: Boolean = false,
    val permissionChecked: Boolean = false,
    val isLoading: Boolean = false,
    val isNetworkError: Boolean = false,
    val isPoWComputing: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    // Phase D fields
    val discoveredUser: DiscoveredUserDto? = null,
    val isSearchingUser: Boolean = false,
    val blockedUsers: List<BlockedUserItemDto> = emptyList(),
    val outgoingRequests: List<ConnectionRequestItemDto> = emptyList(),
    val incomingRequests: List<ConnectionRequestItemDto> = emptyList(),
    val isLoadingIncomingRequests: Boolean = false,
    val incomingRequestsError: String? = null
)
