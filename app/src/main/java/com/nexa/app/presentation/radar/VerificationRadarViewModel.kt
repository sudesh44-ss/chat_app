package com.nexa.app.presentation.radar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.domain.repository.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RadarVerificationStatus {
    IDLE,
    AWAITING_SMS_DISPATCH,
    DETECTING_SIM,
    WAITING_SIM_SELECTION,
    SENDING_SMS,
    LISTENING_RADAR,
    VERIFIED,
    EXPIRED,
    ERROR
}

data class SimSlotInfo(
    val subId: Int,
    val slotIndex: Int,
    val displayName: String,
    val carrierName: String
)

data class VerificationRadarUiState(
    val nonce: String = "",
    val inboundNumber: String = "",
    val smsPayload: String = "",
    val phoneNumber: String = "",
    val name: String = "",
    val avatarUrl: String? = null,
    val age: Int? = null,
    val gender: String? = null,
    val bio: String? = null,
    val isReverify: Boolean = false,
    val secondsRemaining: Int = 90,
    val status: RadarVerificationStatus = RadarVerificationStatus.IDLE,
    val activeSims: List<SimSlotInfo> = emptyList(),
    val selectedSim: SimSlotInfo? = null,
    val allocatedNxId: String? = null,
    val errorMessage: String? = null
)

/**
 * ViewModel managing Zero-Permission Client-Initiated Reverse SMS Verification.
 *
 * Zero-Permission Architecture:
 * - Operates without dangerous telephony permissions (SEND_SMS, RECEIVE_SMS, READ_PHONE_STATE).
 * - Dispatches pre-filled Intent.ACTION_SENDTO to the user's default SMS application (Google Messages).
 * - Listens for inbound gateway verification confirmation via high-speed HTTPS polling / WebSocket.
 * - 100% compliant with Google Play Protect and Android 15 Restricted Settings.
 */
class VerificationRadarViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    companion object {
        private const val TAG = "VerificationRadarVM"
    }

    private val _uiState = MutableStateFlow(VerificationRadarUiState())
    val uiState: StateFlow<VerificationRadarUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null
    private var pollingJob: Job? = null

    fun initialize(
        nonce: String,
        inboundNumber: String,
        smsPayload: String,
        phone: String,
        name: String,
        avatarUrl: String?,
        age: Int?,
        gender: String?,
        bio: String?,
        expiresInSeconds: Int = 90,
        isReverify: Boolean = false,
        context: Context? = null
    ) {
        if (_uiState.value.nonce == nonce && _uiState.value.status != RadarVerificationStatus.IDLE) {
            return
        }

        _uiState.update {
            it.copy(
                nonce = nonce,
                inboundNumber = inboundNumber,
                smsPayload = smsPayload,
                phoneNumber = phone,
                name = name,
                avatarUrl = avatarUrl,
                age = age,
                gender = gender,
                bio = bio,
                isReverify = isReverify,
                secondsRemaining = expiresInSeconds,
                status = RadarVerificationStatus.AWAITING_SMS_DISPATCH,
                errorMessage = null
            )
        }

        startCountdownTimer()

        // Automatically launch native SMS composer if context is available
        if (context != null) {
            openNativeSmsApp(context)
        }
    }

    private fun startCountdownTimer() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (isActive && _uiState.value.secondsRemaining > 0) {
                delay(1000L)
                _uiState.update { current ->
                    val next = current.secondsRemaining - 1
                    if (next <= 0 && current.status != RadarVerificationStatus.VERIFIED) {
                        current.copy(secondsRemaining = 0, status = RadarVerificationStatus.EXPIRED)
                    } else {
                        current.copy(secondsRemaining = next)
                    }
                }
            }
        }
    }

    /**
     * Opens Android's Native SMS Composer with pre-filled destination and payload.
     * ZERO DANGEROUS PERMISSIONS REQUIRED.
     */
    fun openNativeSmsApp(context: Context) {
        val inbound = _uiState.value.inboundNumber
        val payload = _uiState.value.smsPayload

        if (inbound.isBlank() || payload.isBlank()) {
            Log.w(TAG, "Cannot launch SMS composer: inboundNumber or payload is blank")
            return
        }

        Log.i(TAG, "Launching Native SMS Composer (Zero Permission Intent): To=$inbound, Body=$payload")

        val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$inbound")
            putExtra("sms_body", payload)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(smsIntent)
            _uiState.update {
                it.copy(
                    status = RadarVerificationStatus.LISTENING_RADAR,
                    errorMessage = null
                )
            }
            startPollingStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch native SMS application: ${e.message}", e)
            _uiState.update {
                it.copy(
                    status = RadarVerificationStatus.ERROR,
                    errorMessage = "No SMS application found on device. Please install Google Messages or standard SMS app."
                )
            }
        }
    }

    // Alias for compatibility
    fun detectSimsAndSend(context: Context) {
        openNativeSmsApp(context)
    }

    // Alias for compatibility
    fun selectSimAndSend(context: Context, sim: SimSlotInfo) {
        openNativeSmsApp(context)
    }

    // Alias for compatibility
    fun sendReverseVerificationSms(context: Context, sim: SimSlotInfo? = null) {
        openNativeSmsApp(context)
    }

    /**
     * High-frequency status polling loop checking backend for carrier handshake match.
     */
    private fun startPollingStatus() {
        pollingJob?.cancel()
        val nonce = _uiState.value.nonce
        if (nonce.isBlank()) return

        pollingJob = viewModelScope.launch {
            while (isActive && _uiState.value.status == RadarVerificationStatus.LISTENING_RADAR) {
                delay(2500L) // Poll every 2.5 seconds
                val result = authRepository.pollVerificationStatus(nonce)
                result.onSuccess { dto ->
                    Log.d(TAG, "Polled verification status: ${dto.status}, isVerified=${dto.isVerified}, nxId=${dto.nxId}")
                    if (dto.status == "VERIFIED" || dto.isVerified) {
                        val nxId = dto.nxId ?: ""
                        val token = dto.sessionAuthToken ?: ""
                        val currentState = _uiState.value

                        // Save verified profile and cryptographic session
                        authRepository.saveVerifiedSession(
                            nxId = nxId,
                            sessionToken = token,
                            name = dto.name ?: currentState.name,
                            phone = dto.phone ?: currentState.phoneNumber,
                            avatarUrl = dto.avatarUrl ?: currentState.avatarUrl,
                            age = currentState.age,
                            gender = currentState.gender,
                            bio = currentState.bio
                        )

                        _uiState.update {
                            it.copy(
                                status = RadarVerificationStatus.VERIFIED,
                                allocatedNxId = nxId
                            )
                        }
                        return@launch
                    } else if (dto.status == "EXPIRED") {
                        _uiState.update { it.copy(status = RadarVerificationStatus.EXPIRED) }
                        return@launch
                    }
                }.onFailure { err ->
                    Log.d(TAG, "Verification polling pending: ${err.message}")
                }
            }
        }
    }

    /**
     * Re-initiates verification with backend, generating a fresh cryptographic nonce and re-opening SMS app.
     */
    fun retryVerification(context: Context) {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    status = RadarVerificationStatus.AWAITING_SMS_DISPATCH,
                    errorMessage = null
                )
            }

            val result = authRepository.initiateReverseVerify(
                phone = state.phoneNumber,
                name = state.name,
                avatarUrl = state.avatarUrl,
                gender = state.gender,
                age = state.age,
                bio = state.bio,
                googleUid = "",
                googleAuthToken = null,
                isReverify = state.isReverify
            )

            result.onSuccess { payload ->
                initialize(
                    nonce = payload.nonce,
                    inboundNumber = payload.inboundReceiverNumber,
                    smsPayload = payload.smsPayload,
                    phone = state.phoneNumber,
                    name = state.name,
                    avatarUrl = state.avatarUrl,
                    age = state.age,
                    gender = state.gender,
                    bio = state.bio,
                    expiresInSeconds = payload.expiresInSeconds,
                    isReverify = state.isReverify,
                    context = context
                )
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        status = RadarVerificationStatus.ERROR,
                        errorMessage = err.localizedMessage ?: "Retry failed. Please check connection and try again."
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
        pollingJob?.cancel()
    }
}
