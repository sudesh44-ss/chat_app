package com.nexa.app.presentation.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.data.remote.dto.InitiateReverseVerifyResponseDto
import com.nexa.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileSetupUiState(
    val name: String = "",
    val phone: String = "",
    val age: String = "",
    val gender: String = "",
    val bio: String = "",
    val avatarUrl: String? = null,
    val googleToken: String? = null,
    val googleUid: String? = null,
    val email: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isOtpTriggered: Boolean = false,
    val reverseVerifyPayload: InitiateReverseVerifyResponseDto? = null
) {
    val isPhoneValid: Boolean
        get() {
            val digits = phone.replace(Regex("[^0-9]"), "")
            // Enforce domestic Indian format if +91 or 10-digit without prefix, or international >= 10
            return if (phone.startsWith("+91") || (!phone.startsWith("+") && digits.length == 10)) {
                val domesticDigits = if (digits.startsWith("91") && digits.length == 12) digits.drop(2) else digits
                domesticDigits.length == 10 && domesticDigits.first() in '6'..'9'
            } else {
                digits.length in 10..15
            }
        }

    val isAgeValid: Boolean
        get() = age.isBlank() || (age.toIntOrNull() ?: 0) >= 13

    val isBioValid: Boolean
        get() = bio.length <= 140

    val isFormValid: Boolean
        get() = name.isNotBlank() && isPhoneValid && isAgeValid && isBioValid
}

sealed interface ProfileSetupUiEvent {
    data class OnNameChanged(val name: String) : ProfileSetupUiEvent
    data class OnPhoneChanged(val phone: String) : ProfileSetupUiEvent
    data class OnAgeChanged(val age: String) : ProfileSetupUiEvent
    data class OnGenderChanged(val gender: String) : ProfileSetupUiEvent
    data class OnBioChanged(val bio: String) : ProfileSetupUiEvent
    data class OnAvatarChanged(val avatarPathOrUrl: String?) : ProfileSetupUiEvent
    data class SetPrefillData(
        val token: String?,
        val uid: String?,
        val name: String?,
        val avatarUrl: String?,
        val email: String?
    ) : ProfileSetupUiEvent
    data class SetGoogleToken(val token: String?) : ProfileSetupUiEvent
    data object RequestReverseVerification : ProfileSetupUiEvent
    data object RequestOtp : ProfileSetupUiEvent
    data object ClearError : ProfileSetupUiEvent
}

class SetupViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileSetupUiState())
    val uiState: StateFlow<ProfileSetupUiState> = _uiState.asStateFlow()

    fun onEvent(event: ProfileSetupUiEvent) {
        when (event) {
            is ProfileSetupUiEvent.OnNameChanged -> _uiState.update { it.copy(name = event.name) }
            is ProfileSetupUiEvent.OnPhoneChanged -> {
                val cleaned = event.phone.take(15)
                _uiState.update { it.copy(phone = cleaned) }
            }
            is ProfileSetupUiEvent.OnAgeChanged -> {
                val digitsOnly = event.age.filter { it.isDigit() }.take(3)
                _uiState.update { it.copy(age = digitsOnly) }
            }
            is ProfileSetupUiEvent.OnGenderChanged -> _uiState.update { it.copy(gender = event.gender) }
            is ProfileSetupUiEvent.OnBioChanged -> _uiState.update { it.copy(bio = event.bio.take(140)) }
            is ProfileSetupUiEvent.OnAvatarChanged -> _uiState.update { it.copy(avatarUrl = event.avatarPathOrUrl) }
            is ProfileSetupUiEvent.SetPrefillData -> {
                _uiState.update {
                    it.copy(
                        googleToken = event.token ?: it.googleToken,
                        googleUid = event.uid ?: it.googleUid,
                        name = if (it.name.isBlank()) event.name ?: it.name else it.name,
                        avatarUrl = event.avatarUrl ?: it.avatarUrl,
                        email = event.email ?: it.email
                    )
                }
            }
            is ProfileSetupUiEvent.SetGoogleToken -> _uiState.update { it.copy(googleToken = event.token) }
            is ProfileSetupUiEvent.ClearError -> _uiState.update { it.copy(errorMessage = null) }
            is ProfileSetupUiEvent.RequestReverseVerification -> triggerReverseVerification()
            is ProfileSetupUiEvent.RequestOtp -> triggerReverseVerification()
        }
    }

    private fun triggerReverseVerification() {
        val state = _uiState.value
        if (!state.isFormValid) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.initiateReverseVerify(
                phone = state.phone.trim(),
                name = state.name.trim(),
                avatarUrl = state.avatarUrl,
                gender = state.gender.trim().takeIf { it.isNotBlank() },
                age = state.age.toIntOrNull(),
                bio = state.bio.trim().takeIf { it.isNotBlank() },
                googleUid = state.googleUid ?: "",
                googleAuthToken = state.googleToken,
                isReverify = false
            )
            result.onSuccess { payload ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        reverseVerifyPayload = payload,
                        isOtpTriggered = true
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.localizedMessage ?: "Failed to initiate reverse verification"
                    )
                }
            }
        }
    }
}
