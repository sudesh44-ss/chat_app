package com.nexa.app.presentation.radar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
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
        context: Context
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
                status = RadarVerificationStatus.DETECTING_SIM
            )
        }

        startCountdownTimer()
        detectSimsAndSend(context)
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

    fun detectSimsAndSend(context: Context) {
        val hasPhonePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val sims = mutableListOf<SimSlotInfo>()
        if (hasPhonePermission) {
            try {
                val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val activeList: List<SubscriptionInfo>? = subManager?.activeSubscriptionInfoList
                if (!activeList.isNullOrEmpty()) {
                    activeList.forEach { info ->
                        sims.add(
                            SimSlotInfo(
                                subId = info.subscriptionId,
                                slotIndex = info.simSlotIndex,
                                displayName = info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}",
                                carrierName = info.carrierName?.toString() ?: "Carrier"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read subscription info: ${e.message}")
            }
        }

        _uiState.update { it.copy(activeSims = sims) }

        if (sims.size > 1) {
            // Dual-SIM detected: prompt user to choose the SIM slot matching registered phone number
            _uiState.update { it.copy(status = RadarVerificationStatus.WAITING_SIM_SELECTION) }
        } else {
            val selected = sims.firstOrNull()
            _uiState.update { it.copy(selectedSim = selected) }
            triggerSendSms(context, selected)
        }
    }

    fun selectSimAndSend(context: Context, sim: SimSlotInfo) {
        _uiState.update { it.copy(selectedSim = sim) }
        triggerSendSms(context, sim)
    }

    fun triggerSendSms(context: Context, sim: SimSlotInfo?) {
        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasSmsPermission) {
            _uiState.update {
                it.copy(
                    status = RadarVerificationStatus.ERROR,
                    errorMessage = "SMS permission required to dispatch zero-cost reverse verification beacon."
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(status = RadarVerificationStatus.SENDING_SMS) }
            try {
                val subId = sim?.subId
                val smsManager = if (subId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
                } else if (subId != null) {
                    @Suppress("DEPRECATION")
                    SmsManager.getSmsManagerForSubscriptionId(subId)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                val inbound = _uiState.value.inboundNumber
                val payload = _uiState.value.smsPayload

                Log.i(TAG, "Dispatching reverse SMS beacon to $inbound with payload $payload (SIM slot: ${sim?.slotIndex})")
                smsManager.sendTextMessage(inbound, null, payload, null, null)

                _uiState.update { it.copy(status = RadarVerificationStatus.LISTENING_RADAR) }
                startPollingStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send reverse verification SMS: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        status = RadarVerificationStatus.ERROR,
                        errorMessage = "Could not send verification SMS: ${e.localizedMessage ?: "Unknown error"}. You can also send it manually."
                    )
                }
            }
        }
    }

    private fun startPollingStatus() {
        pollingJob?.cancel()
        val nonce = _uiState.value.nonce
        if (nonce.isBlank()) return

        pollingJob = viewModelScope.launch {
            while (isActive && _uiState.value.status == RadarVerificationStatus.LISTENING_RADAR) {
                delay(2000L) // Poll every 2 seconds
                val result = authRepository.pollVerificationStatus(nonce)
                result.onSuccess { dto ->
                    Log.d(TAG, "Polled verification status: ${dto.status}, nxId=${dto.nxId}")
                    when (dto.status) {
                        "VERIFIED" -> {
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
                            countdownJob?.cancel()
                            pollingJob?.cancel()
                        }
                        "EXPIRED" -> {
                            _uiState.update { it.copy(status = RadarVerificationStatus.EXPIRED) }
                            countdownJob?.cancel()
                            pollingJob?.cancel()
                        }
                    }
                }.onFailure { err ->
                    Log.w(TAG, "Poll query transient error: ${err.message}")
                }
            }
        }
    }

    fun retryVerification(context: Context) {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(status = RadarVerificationStatus.DETECTING_SIM, errorMessage = null) }
            val reinit = authRepository.initiateReverseVerify(
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
            reinit.onSuccess { payload ->
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
                        errorMessage = err.localizedMessage ?: "Retry failed"
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
