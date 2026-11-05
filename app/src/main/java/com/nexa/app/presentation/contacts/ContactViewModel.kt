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
    val successMessage: String? = null
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
            successMessage = addr.successMessage
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

    fun clearMessages() {
        _addressState.update { it.copy(error = null, successMessage = null) }
    }
}
