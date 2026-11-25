package com.nexa.app.core.auth

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.nexa.app.domain.model.UserProfile
import com.nexa.app.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * State representing Truecaller 1-Tap verification progress.
 */
sealed interface TruecallerAuthState {
    data object Idle : TruecallerAuthState
    data object CheckingAvailability : TruecallerAuthState
    data class Available(val truecallerInstalled: Boolean) : TruecallerAuthState
    data object RequestingNonce : TruecallerAuthState
    data class AwaitingUserConsent(val nonce: String) : TruecallerAuthState
    data object VerifyingWithBackend : TruecallerAuthState
    data class Success(val profile: UserProfile) : TruecallerAuthState
    data class Error(val message: String, val code: String? = null) : TruecallerAuthState
}

/**
 * TruecallerAuthManager encapsulates 1-Tap phone verification via Truecaller SDK / Intent.
 *
 * Flow:
 * 1. Checks if the official Truecaller application (package: com.truecaller) is installed.
 * 2. Fetches a server-generated cryptographic nonce from NEXA Go backend to prevent replay attacks.
 * 3. Launches Truecaller 1-Tap consent flow.
 * 4. Captures the signed profile payload + RSA signature.
 * 5. Sends the signed payload to NEXA Go backend (/api/v1/auth/truecaller/verify) for independent
 *    cryptographic verification against Truecaller public keys.
 * 6. Commits session locally upon backend validation.
 */
class TruecallerAuthManager(
    private val authRepository: AuthRepository,
    private val isTestMode: Boolean = false,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "TruecallerAuthManager"
        const val TRUECALLER_PACKAGE = "com.truecaller"
    }

    private val _state = MutableStateFlow<TruecallerAuthState>(TruecallerAuthState.Idle)
    val state: StateFlow<TruecallerAuthState> = _state.asStateFlow()

    private var currentNonce: String? = null

    /**
     * Checks if Truecaller is installed and eligible for 1-Tap verification.
     * Evaluates package visibility (com.truecaller) and OAuth flow usability.
     */
    fun checkAvailability(context: Context): Boolean {
        if (isTestMode) {
            _state.value = TruecallerAuthState.Available(truecallerInstalled = true)
            return true
        }
        val isInstalled = try {
            val pm = context.packageManager
            pm.getPackageInfo(TRUECALLER_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "Truecaller package not found via PackageManager: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Truecaller package: ${e.message}")
            false
        }

        var isOAuthUsable = isInstalled
        if (isInstalled) {
            try {
                val tcSdkClass = Class.forName("com.truecaller.android.sdk.oAuth.TcSdk")
                val getInstanceMethod = tcSdkClass.getMethod("getInstance")
                val tcSdk = getInstanceMethod.invoke(null)
                if (tcSdk != null) {
                    val isUsableMethod = tcSdkClass.getMethod("isOAuthFlowUsable")
                    isOAuthUsable = isUsableMethod.invoke(tcSdk) as? Boolean ?: isInstalled
                    Log.d(TAG, "TcSdk.getInstance().isOAuthFlowUsable = $isOAuthUsable")
                }
            } catch (_: ClassNotFoundException) {
                // Truecaller SDK class not linked at runtime; package visibility stands
            } catch (e: Exception) {
                Log.w(TAG, "TcSdk evaluation error: ${e.message}")
            }
        }

        _state.value = TruecallerAuthState.Available(truecallerInstalled = isOAuthUsable)
        return isOAuthUsable
    }

    /**
     * Initiates the Truecaller 1-Tap verification flow:
     * 1. Obtains anti-replay nonce from backend.
     * 2. Returns the nonce for SDK intent initialization.
     */
    suspend fun initiateVerification(): Result<String> = withContext(ioDispatcher) {
        _state.value = TruecallerAuthState.RequestingNonce
        try {
            val nonceResult = authRepository.getTruecallerNonce()
            val nonce = nonceResult.getOrThrow()
            currentNonce = nonce
            _state.value = TruecallerAuthState.AwaitingUserConsent(nonce)
            Result.success(nonce)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate Truecaller verification: ${e.message}", e)
            val errMsg = e.message ?: "Failed to generate Truecaller verification nonce"
            _state.value = TruecallerAuthState.Error(errMsg, "NONCE_FAILED")
            Result.failure(e)
        }
    }

    /**
     * Verifies the Truecaller signed profile response or OAuth authorization code with NEXA Go backend.
     */
    suspend fun verifyWithBackend(
        payload: String? = null,
        signature: String? = null,
        accessToken: String? = null,
        authorizationCode: String? = null,
        codeVerifier: String? = null,
        googleAuthToken: String? = null,
        email: String? = null,
        name: String? = null,
        avatarUrl: String? = null,
        gender: String? = null,
        age: Int? = null,
        bio: String? = null,
        isReverify: Boolean = false
    ): Result<UserProfile> = withContext(ioDispatcher) {
        _state.value = TruecallerAuthState.VerifyingWithBackend
        try {
            val nonce = currentNonce
            val result = authRepository.verifyWithTruecaller(
                payload = payload,
                signature = signature,
                requestNonce = nonce,
                accessToken = accessToken,
                authorizationCode = authorizationCode,
                codeVerifier = codeVerifier,
                googleAuthToken = googleAuthToken,
                email = email,
                name = name,
                avatarUrl = avatarUrl,
                gender = gender,
                age = age,
                bio = bio,
                isReverify = isReverify
            )

            val profile = result.getOrThrow()
            _state.value = TruecallerAuthState.Success(profile)
            Result.success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Truecaller backend verification failed: ${e.message}", e)
            val errMsg = e.message ?: "Truecaller verification failed"
            _state.value = TruecallerAuthState.Error(errMsg, "VERIFICATION_FAILED")
            Result.failure(e)
        }
    }

    /**
     * Resets manager state to Idle.
     */
    fun reset() {
        currentNonce = null
        _state.value = TruecallerAuthState.Idle
    }
}
