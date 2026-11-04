package com.nexa.app.presentation.otp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.domain.model.UserProfile
import com.nexa.app.domain.repository.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OtpUiState(
    val phone: String = "",
    val name: String = "",
    val age: Int? = null,
    val gender: String? = null,
    val bio: String? = null,
    val otpCode: String = "",
    val isVerifying: Boolean = false,
    val timerSeconds: Int = 60,
    val canResend: Boolean = false,
    val errorMessage: String? = null,
    val verifiedProfile: UserProfile? = null
) {
    val isOtpComplete: Boolean
        get() = otpCode.length == 6 && otpCode.all { it.isDigit() }
}

sealed interface OtpUiEvent {
    data class Init(
        val phone: String,
        val name: String,
        val age: Int?,
        val gender: String?,
        val bio: String?
    ) : OtpUiEvent
    data class OnOtpChanged(val otp: String) : OtpUiEvent
    data class OnOtpAutoFilled(val extractedCode: String) : OtpUiEvent
    data object VerifyOtp : OtpUiEvent
    data object ResendOtp : OtpUiEvent
    data object ClearError : OtpUiEvent
}

class OtpViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OtpUiState())
    val uiState: StateFlow<OtpUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null

    init {
        startResendTimer()
    }

    fun onEvent(event: OtpUiEvent) {
        when (event) {
            is OtpUiEvent.Init -> {
                _uiState.update {
                    it.copy(
                        phone = event.phone,
                        name = event.name,
                        age = event.age,
                        gender = event.gender,
                        bio = event.bio
                    )
                }
            }
            is OtpUiEvent.OnOtpChanged -> {
                val digits = event.otp.filter { it.isDigit() }.take(6)
                _uiState.update { it.copy(otpCode = digits) }
                if (digits.length == 6) {
                    verifyOtp()
                }
            }
            is OtpUiEvent.OnOtpAutoFilled -> {
                val digits = event.extractedCode.filter { it.isDigit() }.take(6)
                _uiState.update { it.copy(otpCode = digits) }
                if (digits.length == 6) {
                    verifyOtp()
                }
            }
            is OtpUiEvent.VerifyOtp -> verifyOtp()
            is OtpUiEvent.ResendOtp -> resendOtp()
            is OtpUiEvent.ClearError -> _uiState.update { it.copy(errorMessage = null) }
        }
    }

    private fun startResendTimer() {
        timerJob?.cancel()
        _uiState.update { it.copy(timerSeconds = 60, canResend = false) }
        timerJob = viewModelScope.launch {
            for (remaining in 59 downTo 0) {
                delay(1000)
                _uiState.update { it.copy(timerSeconds = remaining) }
            }
            _uiState.update { it.copy(canResend = true) }
        }
    }

    private fun resendOtp() {
        val state = _uiState.value
        if (!state.canResend) return

        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = authRepository.sendVerificationOtp(
                phone = state.phone,
                googleAuthToken = null
            )
            result.onSuccess {
                startResendTimer()
            }.onFailure { e ->
                _uiState.update {
                    it.copy(errorMessage = e.localizedMessage ?: "Failed to resend OTP")
                }
            }
        }
    }

    private fun verifyOtp() {
        val state = _uiState.value
        if (!state.isOtpComplete || state.isVerifying) return

        viewModelScope.launch {
            _uiState.update { it.copy(isVerifying = true, errorMessage = null) }

            val result = authRepository.verifyOtpAndRegister(
                phone = state.phone,
                otp = state.otpCode,
                name = state.name,
                age = state.age,
                gender = state.gender,
                bio = state.bio
            )

            result.onSuccess { profile ->
                _uiState.update {
                    it.copy(isVerifying = false, verifiedProfile = profile)
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isVerifying = false,
                        errorMessage = error.localizedMessage ?: "Verification failed. Please try again."
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
    }
}
