package com.nexa.app.presentation.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val googleToken: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isOtpTriggered: Boolean = false
) {
    val isFormValid: Boolean
        get() = name.isNotBlank() && phone.replace(Regex("[^0-9]"), "").length >= 10
}

sealed interface ProfileSetupUiEvent {
    data class OnNameChanged(val name: String) : ProfileSetupUiEvent
    data class OnPhoneChanged(val phone: String) : ProfileSetupUiEvent
    data class OnAgeChanged(val age: String) : ProfileSetupUiEvent
    data class OnGenderChanged(val gender: String) : ProfileSetupUiEvent
    data class OnBioChanged(val bio: String) : ProfileSetupUiEvent
    data class SetGoogleToken(val token: String?) : ProfileSetupUiEvent
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
                val cleaned = event.phone.take(14)
                _uiState.update { it.copy(phone = cleaned) }
            }
            is ProfileSetupUiEvent.OnAgeChanged -> {
                val digitsOnly = event.age.filter { it.isDigit() }.take(3)
                _uiState.update { it.copy(age = digitsOnly) }
            }
            is ProfileSetupUiEvent.OnGenderChanged -> _uiState.update { it.copy(gender = event.gender) }
            is ProfileSetupUiEvent.OnBioChanged -> _uiState.update { it.copy(bio = event.bio.take(240)) }
            is ProfileSetupUiEvent.SetGoogleToken -> _uiState.update { it.copy(googleToken = event.token) }
            is ProfileSetupUiEvent.ClearError -> _uiState.update { it.copy(errorMessage = null) }
            is ProfileSetupUiEvent.RequestOtp -> triggerOtp()
        }
    }

    private fun triggerOtp() {
        val state = _uiState.value
        if (!state.isFormValid) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.sendVerificationOtp(
                phone = state.phone.trim(),
                googleAuthToken = state.googleToken
            )
            result.onSuccess {
                _uiState.update { it.copy(isLoading = false, isOtpTriggered = true) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.localizedMessage ?: "Failed to send verification OTP"
                    )
                }
            }
        }
    }
}
