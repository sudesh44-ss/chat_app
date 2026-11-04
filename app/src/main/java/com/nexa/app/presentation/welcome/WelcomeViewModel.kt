package com.nexa.app.presentation.welcome

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
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
    val proceedToPhoneOnly: Boolean = false
)

sealed interface WelcomeUiEvent {
    data class InitiateGoogleSignIn(val context: Context, val webClientId: String) : WelcomeUiEvent
    data object ContinueWithoutGoogle : WelcomeUiEvent
    data object ClearError : WelcomeUiEvent
}

class WelcomeViewModel : ViewModel() {

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
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            googleIdToken = googleIdTokenCredential.idToken,
                            userEmail = googleIdTokenCredential.id,
                            suggestedName = googleIdTokenCredential.displayName,
                            isSuccess = true,
                            errorMessage = null
                        )
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
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = null
                    )
                }
            } catch (e: GetCredentialException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Google Credential Manager error: ${e.message}"
                    )
                }
            } catch (e: Exception) {
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
