package com.nexa.app.presentation.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.data.local.entity.ContactEntity
import com.nexa.app.data.local.entity.ContactStatus
import com.nexa.app.domain.model.DeviceContact
import com.nexa.app.domain.repository.ContactRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Internal holder for address-book search and async UI flags.
 */
data class InternalAddressState(
    val deviceContacts: List<DeviceContact> = emptyList(),
    val searchQuery: String = "",
    val isPermissionGranted: Boolean = false,
    val isPermissionDeniedPermanently: Boolean = false,
    val isLoading: Boolean = false,
    val isNetworkError: Boolean = false,
    val isPoWComputing: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    val discoveredUser: com.nexa.app.data.remote.dto.DiscoveredUserDto? = null,
    val isSearchingUser: Boolean = false,
    val blockedUsers: List<com.nexa.app.data.remote.dto.BlockedUserItemDto> = emptyList(),
    val outgoingRequests: List<com.nexa.app.data.remote.dto.ConnectionRequestItemDto> = emptyList(),
    val incomingRequests: List<com.nexa.app.data.remote.dto.ConnectionRequestItemDto> = emptyList()
)

/**
 * ViewModel orchestrating contact discovery, address-book synchronization,
 * relationship state transitions, and background synchronization.
 */
class ContactViewModel(
    private val contactRepository: ContactRepository
) : ViewModel() {

    private val _addressState = MutableStateFlow(InternalAddressState())

    private val contactsFlow = combine(
        contactRepository.getContactsByStatus(ContactStatus.MUTUAL_FRIEND),
        contactRepository.getContactsByStatus(ContactStatus.REQUEST_RECEIVED),
        contactRepository.getContactsByStatus(ContactStatus.REQUEST_SENT),
        contactRepository.getPendingCount()
    ) { mutual, received, sent, count ->
        ContactUiState(
            mutualFriends = mutual,
            pendingReceived = received,
            pendingSent = sent,
            pendingCount = count
        )
    }

    val uiState: StateFlow<ContactUiState> = combine(
        contactsFlow,
        _addressState
    ) { baseState, addr ->
        val query = addr.searchQuery.trim()
        val filteredMutual = if (query.isBlank()) {
            baseState.mutualFriends
        } else {
            baseState.mutualFriends.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    (it.customNickname?.contains(query, ignoreCase = true) == true) ||
                    it.nxId.contains(query, ignoreCase = true)
            }
        }

        val filtered = if (addr.searchQuery.isBlank()) {
            addr.deviceContacts
        } else {
            addr.deviceContacts.filter {
                it.displayName.contains(addr.searchQuery, ignoreCase = true) ||
                    it.phoneNumber.contains(addr.searchQuery) ||
                    it.normalizedNumber.contains(addr.searchQuery)
            }
        }

        baseState.copy(
            mutualFriends = filteredMutual,
            deviceContacts = filtered,
            searchQuery = addr.searchQuery,
            isPermissionGranted = addr.isPermissionGranted,
            isPermissionDeniedPermanently = addr.isPermissionDeniedPermanently,
            isLoading = addr.isLoading,
            isNetworkError = addr.isNetworkError,
            isPoWComputing = addr.isPoWComputing,
            error = addr.error,
            successMessage = addr.successMessage,
            discoveredUser = addr.discoveredUser,
            isSearchingUser = addr.isSearchingUser,
            blockedUsers = addr.blockedUsers,
            outgoingRequests = addr.outgoingRequests,
            incomingRequests = addr.incomingRequests
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ContactUiState()
    )

    init {
        syncRequests()
    }

    fun setPermissionState(granted: Boolean, permanentlyDenied: Boolean = false) {
        _addressState.update {
            it.copy(
                isPermissionGranted = granted,
                isPermissionDeniedPermanently = permanentlyDenied
            )
        }
        if (granted) {
            loadDeviceContacts()
        }
    }

    fun onSearchQueryChanged(query: String) {
        _addressState.update { it.copy(searchQuery = query) }
    }

    fun loadDeviceContacts() {
        viewModelScope.launch {
            _addressState.update { it.copy(isLoading = true, isNetworkError = false, error = null) }

            val result = contactRepository.loadDeviceContacts()

            result.onSuccess { contacts ->
                _addressState.update {
                    it.copy(
                        isLoading = false,
                        deviceContacts = contacts
                    )
                }
            }.onFailure { e ->
                _addressState.update {
                    it.copy(
                        isLoading = false,
                        isNetworkError = true,
                        error = "Failed to synchronize contacts: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun refreshContacts() {
        if (_addressState.value.isPermissionGranted) {
            loadDeviceContacts()
        }
        syncRequests()
    }

    fun lookupSinglePhone(phone: String, onResult: (DeviceContact?) -> Unit) {
        viewModelScope.launch {
            _addressState.update { it.copy(isLoading = true) }
            val result = contactRepository.lookupSinglePhone(phone)
            _addressState.update { it.copy(isLoading = false) }
            onResult(result.getOrNull())
        }
    }

    fun syncRequests() {
        viewModelScope.launch {
            contactRepository.syncPendingRequests()
        }
    }

    fun sendFriendRequest(targetNxId: String, introMessage: String?, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _addressState.update { it.copy(isPoWComputing = true, error = null) }

            val result = contactRepository.sendFriendRequest(targetNxId, introMessage)

            result.onSuccess {
                _addressState.update {
                    it.copy(
                        isPoWComputing = false,
                        successMessage = "Connection request sent to $targetNxId"
                    )
                }
                onSuccess()
            }.onFailure { e ->
                _addressState.update {
                    it.copy(
                        isPoWComputing = false,
                        error = e.localizedMessage ?: "Failed to send connection request"
                    )
                }
            }
        }
    }

    fun acceptRequest(contact: ContactEntity) {
        viewModelScope.launch {
            _addressState.update { it.copy(error = null) }
            val result = contactRepository.acceptFriendRequest(contact)
            result.onFailure { e ->
                _addressState.update {
                    it.copy(error = e.localizedMessage ?: "Failed to accept request")
                }
            }
        }
    }

    fun rejectContact(nxId: String) {
        viewModelScope.launch {
            contactRepository.rejectOrBlockContact(nxId, block = false)
        }
    }

    fun blockContact(nxId: String) {
        viewModelScope.launch {
            contactRepository.rejectOrBlockContact(nxId, block = true)
        }
    }

    fun setContactNickname(nxId: String, customNickname: String?) {
        viewModelScope.launch {
            contactRepository.setContactNickname(nxId, customNickname)
        }
    }

    // Phase D: Connection & Contact Management Methods

    fun searchUserByNxId(nxId: String) {
        val cleanNxId = nxId.trim().uppercase()
        if (cleanNxId.isBlank()) return

        viewModelScope.launch {
            _addressState.update { it.copy(isSearchingUser = true, error = null, discoveredUser = null) }
            val result = contactRepository.discoverUser(cleanNxId)
            result.onSuccess { user ->
                _addressState.update { it.copy(isSearchingUser = false, discoveredUser = user) }
            }.onFailure { err ->
                _addressState.update {
                    it.copy(
                        isSearchingUser = false,
                        error = err.localizedMessage ?: "User not found or unavailable"
                    )
                }
            }
        }
    }

    fun clearDiscoveredUser() {
        _addressState.update { it.copy(discoveredUser = null, isSearchingUser = false) }
    }

    fun loadIncomingRequests() {
        viewModelScope.launch {
            val result = contactRepository.getIncomingConnectionRequests()
            result.onSuccess { requests ->
                _addressState.update { it.copy(incomingRequests = requests) }
            }
        }
    }

    fun loadOutgoingRequests() {
        viewModelScope.launch {
            val result = contactRepository.getOutgoingConnectionRequests()
            result.onSuccess { requests ->
                _addressState.update { it.copy(outgoingRequests = requests) }
            }
        }
    }

    fun acceptConnectionRequest(requestId: String, contact: ContactEntity) {
        viewModelScope.launch {
            _addressState.update { it.copy(error = null) }
            val result = contactRepository.acceptConnectionRequestById(requestId, contact)
            result.onSuccess {
                _addressState.update {
                    it.copy(successMessage = "Connected with ${contact.displayName}")
                }
                loadIncomingRequests()
            }.onFailure { err ->
                _addressState.update {
                    it.copy(error = err.localizedMessage ?: "Failed to accept connection request")
                }
            }
        }
    }

    fun rejectConnectionRequest(requestId: String, nxId: String) {
        viewModelScope.launch {
            val result = contactRepository.rejectConnectionRequestById(requestId, nxId)
            result.onSuccess {
                loadIncomingRequests()
            }.onFailure { err ->
                _addressState.update {
                    it.copy(error = err.localizedMessage ?: "Failed to reject connection request")
                }
            }
        }
    }

    fun cancelConnectionRequest(requestId: String) {
        viewModelScope.launch {
            val result = contactRepository.cancelConnectionRequestById(requestId)
            result.onSuccess {
                _addressState.update { it.copy(successMessage = "Request cancelled") }
                loadOutgoingRequests()
            }.onFailure { err ->
                _addressState.update {
                    it.copy(error = err.localizedMessage ?: "Failed to cancel connection request")
                }
            }
        }
    }

    fun removeConnection(targetNxId: String) {
        viewModelScope.launch {
            val result = contactRepository.removeConnection(targetNxId)
            result.onSuccess {
                _addressState.update { it.copy(successMessage = "Connection removed") }
            }.onFailure { err ->
                _addressState.update {
                    it.copy(error = err.localizedMessage ?: "Failed to remove connection")
                }
            }
        }
    }

    fun blockUser(targetNxId: String) {
        viewModelScope.launch {
            val result = contactRepository.blockUser(targetNxId)
            result.onSuccess {
                _addressState.update { it.copy(successMessage = "User blocked") }
                loadBlockedUsers()
            }.onFailure { err ->
                _addressState.update {
                    it.copy(error = err.localizedMessage ?: "Failed to block user")
                }
            }
        }
    }

    fun unblockUser(targetNxId: String) {
        viewModelScope.launch {
            val result = contactRepository.unblockUser(targetNxId)
            result.onSuccess {
                _addressState.update { it.copy(successMessage = "User unblocked") }
                loadBlockedUsers()
            }.onFailure { err ->
                _addressState.update {
                    it.copy(error = err.localizedMessage ?: "Failed to unblock user")
                }
            }
        }
    }

    fun loadBlockedUsers() {
        viewModelScope.launch {
            val result = contactRepository.getBlockedUsers()
            result.onSuccess { list ->
                _addressState.update { it.copy(blockedUsers = list) }
            }
        }
    }

    fun syncConnections() {
        viewModelScope.launch {
            contactRepository.syncConnectionsFromServer()
            loadIncomingRequests()
            loadOutgoingRequests()
        }
    }

    fun clearMessages() {
        _addressState.update { it.copy(error = null, successMessage = null) }
    }
}
