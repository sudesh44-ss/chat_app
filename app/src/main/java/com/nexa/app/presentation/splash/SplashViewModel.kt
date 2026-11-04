package com.nexa.app.presentation.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    private val _navigationState = MutableStateFlow<SplashNavigationState>(SplashNavigationState.Loading)
    val navigationState: StateFlow<SplashNavigationState> = _navigationState.asStateFlow()

    init {
        checkSession()
    }

    fun checkSession() {
        viewModelScope.launch {
            // Smooth branded splash delay
            delay(1200)
            val loggedIn = authRepository.isLoggedIn()
            if (loggedIn) {
                _navigationState.value = SplashNavigationState.NavigateToDashboard
            } else {
                _navigationState.value = SplashNavigationState.NavigateToWelcome
            }
        }
    }
}
