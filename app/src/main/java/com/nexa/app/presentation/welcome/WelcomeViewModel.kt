package com.nexa.app.presentation.welcome

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.nexa.app.data.remote.dto.GoogleAuthResponse
import com.nexa.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WelcomeUiState(
    val isLoading: Boolean = false,
    val googleIdToken: String? = null,
    val userEmail: String? = null,
    val suggestedName: String? = null,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false,
    val isInstantLogin: Boolean = false,
    val requireSimVerification: Boolean = false,
    val googleResponse: GoogleAuthResponse? = null,
    val proceedToPhoneOnly: Boolean = false
)

sealed interface WelcomeUiEvent {
    data class InitiateGoogleSignIn(val context: Context, val webClientId: String) : WelcomeUiEvent
    data object ContinueWithoutGoogle : WelcomeUiEvent
    data object ClearError : WelcomeUiEvent
}

class WelcomeViewModel(
    private val authRepository: AuthRepository? = null
) : ViewModel() {

    companion object {
        private const val TAG = "WelcomeViewModel"
    }

    private val _uiState = MutableStateFlow(WelcomeUiState())
    val uiState: StateFlow<WelcomeUiState> = _uiState.asStateFlow()

    fun onEvent(event: WelcomeUiEvent) {
        when (event) {
            is WelcomeUiEvent.InitiateGoogleSignIn -> signInWithCredentialManager(event.context, event.webClientId)
            is WelcomeUiEvent.ContinueWithoutGoogle -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        googleIdToken = null,
                        errorMessage = null,
                        proceedToPhoneOnly = true
                    )
                }
            }
            is WelcomeUiEvent.ClearError -> _uiState.update { it.copy(errorMessage = null) }
        }
    }

    private fun signInWithCredentialManager(context: Context, webClientId: String) {
        // Prevent concurrent or duplicate sign-in requests
        if (_uiState.value.isLoading) {
            Log.d(TAG, "Sign-in already in progress, ignoring duplicate tap")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val credentialManager = CredentialManager.create(context)
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(false)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(
                    request = request,
                    context = context
                )

                val credential = result.credential
                if (credential is androidx.credentials.CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val token = googleIdTokenCredential.idToken
                    val email = googleIdTokenCredential.id
                    val displayName = googleIdTokenCredential.displayName

                    Log.i(TAG, "Google Sign-In credential retrieved successfully. Verifying with NEXA backend...")

                    if (authRepository != null) {
                        val backendResult = authRepository.loginWithGoogle(token)
                        backendResult.onSuccess { resp ->
                            Log.i(TAG, "Backend Google Auth response: status=${resp.status}, nxId=${resp.nxId}")
                            when (resp.status) {
                                "LOGIN_SUCCESS" -> {
                                    _uiState.update {
                                        it.copy(
                                            isLoading = false,
                                            googleIdToken = token,
                                            userEmail = email,
                                            suggestedName = displayName,
                                            googleResponse = resp,
                                            isInstantLogin = true,
                                            errorMessage = null
                                        )
                                    }
                                }
                                "REQUIRE_SIM_VERIFICATION" -> {
                                    _uiState.update {
                                        it.copy(
                                            isLoading = false,
                                            googleIdToken = token,
                                            userEmail = email,
                                            suggestedName = displayName,
                                            googleResponse = resp,
                                            requireSimVerification = true,
                                            errorMessage = null
                                        )
                                    }
                                }
                                "REQUIRE_DEVICE_CHALLENGE" -> {
                                    viewModelScope.launch {
                                        val deviceId = resp.deviceId ?: "dev_default"
                                        val nxId = resp.nxId ?: ""
                                        val challengeResult = authRepository.requestAndVerifyLoginChallenge(nxId, deviceId)
                                        challengeResult.onSuccess {
                                            _uiState.update {
                                                it.copy(
                                                    isLoading = false,
                                                    googleIdToken = token,
                                                    userEmail = email,
                                                    suggestedName = displayName,
                                                    googleResponse = resp.copy(status = "LOGIN_SUCCESS"),
                                                    isInstantLogin = true,
                                                    errorMessage = null
                                                )
                                            }
                                        }.onFailure { chalErr ->
                                            _uiState.update {
                                                it.copy(
                                                    isLoading = false,
                                                    errorMessage = "Hardware authentication failed: ${chalErr.message}"
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> { // "NEW_USER"
                                    _uiState.update {
                                        it.copy(
                                            isLoading = false,
                                            googleIdToken = token,
                                            userEmail = email,
                                            suggestedName = resp.prefillName ?: displayName,
                                            googleResponse = resp,
                                            isSuccess = true,
                                            errorMessage = null
                                        )
                                    }
                                }
                            }
                        }.onFailure { err ->
                            Log.w(TAG, "Backend Google Auth verification failed, proceeding with client Google token: ${err.message}")
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    googleIdToken = token,
                                    userEmail = email,
                                    suggestedName = displayName,
                                    isSuccess = true,
                                    errorMessage = null
                                )
                            }
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                googleIdToken = token,
                                userEmail = email,
                                suggestedName = displayName,
                                isSuccess = true,
                                errorMessage = null
                            )
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Unexpected credential format returned by Google Sign-In"
                        )
                    }
                }
            } catch (e: GetCredentialCancellationException) {
                Log.d(TAG, "Google Sign-In canceled by user")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = null
                    )
                }
            } catch (e: NoCredentialException) {
                Log.w(TAG, "Credential Manager: No credentials available for account selection")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "No Google account credentials found on device. Please verify your Google account in Settings or try again."
                    )
                }
            } catch (e: GetCredentialInterruptedException) {
                Log.w(TAG, "Credential Manager request interrupted")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Sign-In request was interrupted. Please try again."
                    )
                }
            } catch (e: GetCredentialProviderConfigurationException) {
                Log.e(TAG, "Credential Manager provider configuration error", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Google Play Services configuration error. Please update Google Play Services."
                    )
                }
            } catch (e: GetCredentialUnsupportedException) {
                Log.e(TAG, "Credential Manager unsupported on this device", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Google Credential Manager is unsupported on this device."
                    )
                }
            } catch (e: GetCredentialException) {
                Log.e(TAG, "Credential Manager generic error: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Google Sign-In error: ${e.message}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during Google Sign-In", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "Google sign-in failed"
                    )
                }
            }
        }
    }
}
