package com.nexa.app.presentation.verification

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.core.auth.FirebaseAuthState
import com.nexa.app.core.auth.FirebasePhoneAuthManager
import com.nexa.app.core.auth.TruecallerAuthState
import com.nexa.app.core.auth.TruecallerAuthManager
import com.nexa.app.domain.model.UserProfile
import com.nexa.app.domain.repository.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VerificationMethod {
    TRUECALLER,
    FIREBASE_OTP,
    REVERSE_SIM
}

data class PhoneVerificationUiState(
    val selectedMethod: VerificationMethod = VerificationMethod.FIREBASE_OTP,
    val phoneNumber: String = "",
    val name: String = "",
    val avatarUrl: String? = null,
    val age: Int? = null,
    val gender: String? = null,
    val bio: String? = null,
    val googleToken: String? = null,
    val googleUid: String? = null,
    val email: String? = null,
    val isReverify: Boolean = false,
    // Truecaller
    val isTruecallerInstalled: Boolean = false,
    val isTruecallerLoading: Boolean = false,
    // Firebase OTP
    val otpCode: String = "",
    val isOtpSent: Boolean = false,
    val verificationId: String? = null,
    val resendToken: com.google.firebase.auth.PhoneAuthProvider.ForceResendingToken? = null,
    val resendTimerSeconds: Int = 0,
    val isSendingOtp: Boolean = false,
    val isResendingOtp: Boolean = false,
    val isVerifyingOtp: Boolean = false,
    // SIM Radar
    val isRadarLoading: Boolean = false,
    // General
    val verifiedProfile: UserProfile? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null
) {
    val isFirebaseLoading: Boolean get() = isSendingOtp || isResendingOtp || isVerifyingOtp
    val canResendOtp: Boolean get() = resendTimerSeconds <= 0 && !isResendingOtp
    val isOtpValid: Boolean get() = otpCode.length == 6 && otpCode.all { it.isDigit() }
    val isPhoneValid: Boolean
        get() {
            val digits = phoneNumber.replace(Regex("[^0-9]"), "")
            return digits.length in 10..15
        }
}

class PhoneVerificationViewModel(
    private val authRepository: AuthRepository,
    private val truecallerManager: TruecallerAuthManager = TruecallerAuthManager(authRepository),
    private val firebaseManager: FirebasePhoneAuthManager = FirebasePhoneAuthManager(authRepository)
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhoneVerificationUiState())
    val uiState: StateFlow<PhoneVerificationUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            firebaseManager.state.collect { authState ->
                if (authState is FirebaseAuthState.Verified) {
                    if (_uiState.value.verifiedProfile == null && !_uiState.value.isVerifyingOtp) {
                        _uiState.update { it.copy(isVerifyingOtp = true, errorMessage = null) }
                        submitFirebaseTokenToBackend(authState.idToken)
                    }
                }
            }
        }
    }

    fun initParams(
        phone: String,
        name: String,
        avatarUrl: String?,
        age: Int?,
        gender: String?,
        bio: String?,
        googleToken: String?,
        googleUid: String?,
        email: String?,
        isReverify: Boolean
    ) {
        _uiState.update {
            it.copy(
                phoneNumber = if (it.phoneNumber.isBlank()) phone else it.phoneNumber,
                name = name,
                avatarUrl = avatarUrl,
                age = age,
                gender = gender,
                bio = bio,
                googleToken = googleToken,
                googleUid = googleUid,
                email = email,
                isReverify = isReverify
            )
        }
    }

    fun checkTruecallerAvailability(context: Context) {
        val installed = truecallerManager.checkAvailability(context)
        _uiState.update {
            it.copy(
                isTruecallerInstalled = installed,
                selectedMethod = if (installed) VerificationMethod.TRUECALLER else VerificationMethod.FIREBASE_OTP
            )
        }
    }

    fun selectMethod(method: VerificationMethod) {
        _uiState.update { it.copy(selectedMethod = method, errorMessage = null) }
    }

    fun onPhoneNumberChanged(newPhone: String) {
        if (newPhone != _uiState.value.phoneNumber) {
            firebaseManager.reset()
        }
        _uiState.update {
            it.copy(
                phoneNumber = newPhone,
                verificationId = null,
                otpCode = "",
                isOtpSent = false,
                resendToken = null,
                errorMessage = null
            )
        }
    }

    fun onOtpChanged(newOtp: String) {
        val clean = newOtp.filter { it.isDigit() }.take(6)
        _uiState.update { it.copy(otpCode = clean, errorMessage = null) }
    }

    fun startTruecallerVerification(
        activity: android.app.Activity? = null,
        authorizationCode: String? = null,
        codeVerifier: String? = null,
        payload: String? = null,
        signature: String? = null
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTruecallerLoading = true, errorMessage = null) }
            val nonceResult = truecallerManager.initiateVerification()
            nonceResult.fold(
                onSuccess = { nonce ->
                    // Case 1: In test mode or explicit verification credentials passed
                    if (truecallerManager.isTestMode || !authorizationCode.isNullOrBlank() || (!payload.isNullOrBlank() && !signature.isNullOrBlank())) {
                        executeBackendTruecallerVerification(
                            authorizationCode = authorizationCode ?: if (truecallerManager.isTestMode) "mock_tc_auth_code" else null,
                            codeVerifier = codeVerifier ?: if (truecallerManager.isTestMode) "mock_tc_code_verifier" else null,
                            payload = payload,
                            signature = signature
                        )
                        return@fold
                    }

                    // Case 2: Production mode with Activity -> Launch Truecaller 1-Tap OAuth flow
                    if (activity != null) {
                        val callback = object : com.truecaller.android.sdk.oAuth.TcOAuthCallback {
                            override fun onSuccess(tcOAuthData: com.truecaller.android.sdk.oAuth.TcOAuthData) {
                                viewModelScope.launch {
                                    executeBackendTruecallerVerification(
                                        authorizationCode = tcOAuthData.authorizationCode,
                                        codeVerifier = truecallerManager.currentCodeVerifier
                                    )
                                }
                            }

                            override fun onFailure(tcOAuthError: com.truecaller.android.sdk.oAuth.TcOAuthError) {
                                val errorMsg = mapTruecallerError(tcOAuthError.errorCode, tcOAuthError.errorMessage)
                                _uiState.update {
                                    it.copy(
                                        isTruecallerLoading = false,
                                        errorMessage = errorMsg
                                    )
                                }
                            }

                            override fun onVerificationRequired(tcOAuthError: com.truecaller.android.sdk.oAuth.TcOAuthError?) {
                                val errorMsg = mapTruecallerError(
                                    tcOAuthError?.errorCode ?: 0,
                                    tcOAuthError?.errorMessage ?: "Additional verification required. Please use SMS OTP."
                                )
                                _uiState.update {
                                    it.copy(
                                        isTruecallerLoading = false,
                                        errorMessage = errorMsg
                                    )
                                }
                            }
                        }

                        val launchResult = truecallerManager.startOAuthFlow(activity, callback)
                        launchResult.onFailure { err ->
                            _uiState.update {
                                it.copy(
                                    isTruecallerLoading = false,
                                    errorMessage = err.message ?: "Failed to launch Truecaller verification"
                                )
                            }
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isTruecallerLoading = false,
                                errorMessage = "Activity context is required to launch Truecaller 1-Tap."
                            )
                        }
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isTruecallerLoading = false,
                            errorMessage = err.message ?: "Failed to generate Truecaller verification nonce"
                        )
                    }
                }
            )
        }
    }

    private suspend fun executeBackendTruecallerVerification(
        authorizationCode: String?,
        codeVerifier: String?,
        payload: String? = null,
        signature: String? = null
    ) {
        val s = _uiState.value
        val verifyResult = truecallerManager.verifyWithBackend(
            payload = payload,
            signature = signature,
            accessToken = null,
            authorizationCode = authorizationCode,
            codeVerifier = codeVerifier,
            googleAuthToken = s.googleToken,
            email = s.email,
            name = s.name,
            avatarUrl = s.avatarUrl,
            gender = s.gender,
            age = s.age,
            bio = s.bio,
            isReverify = s.isReverify
        )
        verifyResult.fold(
            onSuccess = { profile ->
                _uiState.update {
                    it.copy(
                        isTruecallerLoading = false,
                        verifiedProfile = profile,
                        successMessage = "Verified via Truecaller!"
                    )
                }
            },
            onFailure = { err ->
                _uiState.update {
                    it.copy(
                        isTruecallerLoading = false,
                        errorMessage = err.message ?: "Truecaller verification failed"
                    )
                }
            }
        )
    }

    private fun mapTruecallerError(errorCode: Int, errorMessage: String?): String {
        return when (errorCode) {
            1 -> "Truecaller user not verified or account inactive (Code 1)"
            2 -> "Truecaller 1-Tap verification was cancelled or denied by user (Code 2)"
            3 -> "Invalid Truecaller request or configuration (Code 3)"
            4 -> "Truecaller Client ID unauthorized. Check developer portal SHA-1 fingerprint (Code 4)"
            10 -> "Truecaller SDK internal error. Please try SMS OTP (Code 10)"
            13 -> "Truecaller verification dismissed (Code 13)"
            else -> errorMessage?.takeIf { it.isNotBlank() } ?: "Truecaller error (Code: $errorCode)"
        }
    }

    fun sendFirebaseOtp(activity: android.app.Activity? = null, isResend: Boolean = false) {
        val phone = _uiState.value.phoneNumber.trim()
        if (phone.length < 10) {
            _uiState.update {
                it.copy(
                    isSendingOtp = false,
                    isResendingOtp = false,
                    errorMessage = "Please enter a valid phone number"
                )
            }
            return
        }
        viewModelScope.launch {
            if (isResend) {
                _uiState.update { it.copy(isResendingOtp = true, errorMessage = null) }
            } else {
                _uiState.update {
                    it.copy(
                        isSendingOtp = true,
                        errorMessage = null,
                        verificationId = null,
                        otpCode = "",
                        resendToken = null
                    )
                }
            }
            try {
                val forceToken = if (isResend) _uiState.value.resendToken else null
                val result = firebaseManager.sendOtp(
                    phoneNumber = phone,
                    activity = activity,
                    forceResendingToken = forceToken,
                    isResend = isResend
                )
                result.fold(
                    onSuccess = { verId ->
                        if (verId.startsWith("INSTANT_VERIFIED:")) {
                            val idToken = verId.removePrefix("INSTANT_VERIFIED:")
                            _uiState.update {
                                it.copy(
                                    isSendingOtp = false,
                                    isResendingOtp = false,
                                    isOtpSent = true,
                                    isVerifyingOtp = true,
                                    errorMessage = null
                                )
                            }
                            submitFirebaseTokenToBackend(idToken)
                        } else {
                            _uiState.update {
                                it.copy(
                                    isSendingOtp = false,
                                    isResendingOtp = false,
                                    isOtpSent = true,
                                    verificationId = verId,
                                    resendToken = firebaseManager.getActiveResendToken(),
                                    errorMessage = null
                                )
                            }
                            startResendTimer(60)
                        }
                    },
                    onFailure = { err ->
                        val errorMsg = (firebaseManager.state.value as? FirebaseAuthState.Error)?.message
                            ?: err.message
                            ?: "Failed to send OTP"
                        _uiState.update {
                            it.copy(
                                isSendingOtp = false,
                                isResendingOtp = false,
                                errorMessage = errorMsg
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to send OTP"
                _uiState.update {
                    it.copy(
                        isSendingOtp = false,
                        isResendingOtp = false,
                        errorMessage = errorMsg
                    )
                }
            } finally {
                _uiState.update {
                    it.copy(
                        isSendingOtp = false,
                        isResendingOtp = false
                    )
                }
            }
        }
    }

    fun verifyFirebaseOtp() {
        val state = _uiState.value
        val verId = state.verificationId
        val code = state.otpCode
        if (verId == null || code.length != 6) {
            _uiState.update {
                it.copy(
                    isVerifyingOtp = false,
                    errorMessage = "Please enter the full 6-digit code"
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isVerifyingOtp = true, errorMessage = null) }
            try {
                val result = firebaseManager.verifyOtp(verId, code)
                result.fold(
                    onSuccess = { idToken ->
                        submitFirebaseTokenToBackend(idToken)
                    },
                    onFailure = { err ->
                        val errorMsg = (firebaseManager.state.value as? FirebaseAuthState.Error)?.message
                            ?: err.message
                            ?: "Invalid OTP code"
                        _uiState.update {
                            it.copy(
                                isVerifyingOtp = false,
                                errorMessage = errorMsg
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                val errorMsg = e.message ?: "OTP verification failed"
                _uiState.update {
                    it.copy(
                        isVerifyingOtp = false,
                        errorMessage = errorMsg
                    )
                }
            } finally {
                _uiState.update { it.copy(isVerifyingOtp = false) }
            }
        }
    }

    private suspend fun submitFirebaseTokenToBackend(idToken: String) {
        val s = _uiState.value
        try {
            val result = firebaseManager.submitTokenToBackend(
                idToken = idToken,
                googleAuthToken = s.googleToken,
                email = s.email,
                name = s.name,
                avatarUrl = s.avatarUrl,
                gender = s.gender,
                age = s.age,
                bio = s.bio,
                isReverify = s.isReverify
            )
            result.fold(
                onSuccess = { profile ->
                    _uiState.update {
                        it.copy(
                            isVerifyingOtp = false,
                            verifiedProfile = profile,
                            successMessage = "Phone number verified via SMS OTP!"
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isVerifyingOtp = false,
                            errorMessage = err.message ?: "Backend verification failed"
                        )
                    }
                }
            )
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isVerifyingOtp = false,
                    errorMessage = e.message ?: "Backend verification failed"
                )
            }
        } finally {
            _uiState.update { it.copy(isVerifyingOtp = false) }
        }
    }

    private fun startResendTimer(seconds: Int) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                _uiState.update { it.copy(resendTimerSeconds = remaining) }
                delay(1000)
                remaining--
            }
            _uiState.update { it.copy(resendTimerSeconds = 0) }
        }
    }

    fun initiateSimRadar(
        onSuccess: (nonce: String, inboundNumber: String, payload: String, expiresIn: Int, sessionId: String) -> Unit
    ) {
        val s = _uiState.value
        val phone = s.phoneNumber.trim()
        if (phone.length < 10) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid phone number") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isRadarLoading = true, errorMessage = null) }
            val result = authRepository.initiateReverseVerify(
                phone = phone,
                name = s.name,
                avatarUrl = s.avatarUrl,
                gender = s.gender,
                age = s.age,
                bio = s.bio,
                googleUid = s.googleUid ?: "",
                googleAuthToken = s.googleToken,
                isReverify = s.isReverify,
                email = s.email
            )
            result.fold(
                onSuccess = { resp ->
                    _uiState.update { it.copy(isRadarLoading = false) }
                    val gateway = resp.gatewayNumber?.ifBlank { null }
                        ?: resp.inboundNumber?.ifBlank { null }
                        ?: resp.inboundReceiverNumber?.ifBlank { null }
                    if (gateway.isNullOrBlank()) {
                        _uiState.update {
                            it.copy(
                                errorMessage = "SIM Radar gateway unavailable — use Truecaller or SMS OTP"
                            )
                        }
                    } else {
                        val nonce = resp.nonce
                        val sessionId = resp.sessionId ?: ""
                        val payload = resp.smsPayload ?: "NEXA-VERIFY:$nonce:$sessionId"
                        val expiresIn = resp.expiresInSeconds
                        onSuccess(nonce, gateway, payload, expiresIn, sessionId)
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isRadarLoading = false,
                            errorMessage = err.message ?: "Failed to initiate SIM Radar verification"
                        )
                    }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
    }
}
