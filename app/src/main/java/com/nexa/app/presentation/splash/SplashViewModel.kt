package com.nexa.app.presentation.splash

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.core.config.AppLaunchConfig
import com.nexa.app.domain.repository.AuthRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SplashNavigationState {
    data object Loading : SplashNavigationState
    data object NavigateToDashboard : SplashNavigationState
    data object NavigateToWelcome : SplashNavigationState
}

class SplashViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    companion object {
        private const val TAG = "SplashViewModel"
    }

    private val _navigationState = MutableStateFlow<SplashNavigationState>(SplashNavigationState.Loading)
    val navigationState: StateFlow<SplashNavigationState> = _navigationState.asStateFlow()

    init {
        checkSession()
    }

    fun checkSession() {
        viewModelScope.launch {
            try {
                // Smooth branded splash delay
                delay(1200)

                // Optional development-only bypass: directly navigate to Home/Dashboard Screen if explicitly enabled
                if (AppLaunchConfig.isBypassAuthToHomeScreenEnabled) {
                    Log.i(TAG, "DEV_BYPASS_AUTH_TO_HOME_SCREEN is active: navigating directly to Home Screen")
                    _navigationState.value = SplashNavigationState.NavigateToDashboard
                    return@launch
                }

                // Authentic startup flow: validate session, credentials, and onboarding state
                val sessionValid = try {
                    authRepository.validateSession()
                } catch (e: Exception) {
                    Log.e(TAG, "Error validating session in authRepository", e)
                    false
                }
                if (sessionValid) {
                    _navigationState.value = SplashNavigationState.NavigateToDashboard
                } else {
                    _navigationState.value = SplashNavigationState.NavigateToWelcome
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during splash session check", e)
                _navigationState.value = SplashNavigationState.NavigateToWelcome
            }
        }
    }
}
