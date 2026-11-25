package com.nexa.app.core.auth

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.nexa.app.domain.model.UserProfile
import com.nexa.app.domain.repository.AuthRepository
import com.truecaller.android.sdk.oAuth.CodeVerifierUtil
import com.truecaller.android.sdk.oAuth.TcOAuthCallback
import com.truecaller.android.sdk.oAuth.TcSdk
import com.truecaller.android.sdk.oAuth.TcSdkOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom

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
 * TruecallerAuthManager encapsulates 1-Tap phone verification via Truecaller OAuth SDK v3.
 *
 * Flow:
 * 1. Checks if the official Truecaller application (package: com.truecaller) is installed and OAuth is usable.
 * 2. Fetches a server-generated cryptographic nonce from NEXA Go backend to prevent replay attacks.
 * 3. Prepares PKCE parameters (codeVerifier and codeChallenge) and sets OAuth state/scopes.
 * 4. Launches Truecaller 1-Tap consent bottom-sheet via TcSdk.getInstance().getAuthorizationCode(fragmentActivity).
 * 5. On user consent, receives authorization code in TcOAuthCallback.
 * 6. Sends authorization code + codeVerifier to NEXA Go backend (/api/v1/auth/truecaller/verify) for validation.
 * 7. Commits session locally upon backend validation.
 */
class TruecallerAuthManager(
    private val authRepository: AuthRepository,
    val isTestMode: Boolean = false,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "TruecallerAuthManager"
        const val TRUECALLER_PACKAGE = "com.truecaller"

        fun generateCodeVerifier(): String {
            return try {
                CodeVerifierUtil.generateRandomCodeVerifier() ?: generateSecurePkceVerifier()
            } catch (_: Throwable) {
                generateSecurePkceVerifier()
            }
        }

        fun generateCodeChallenge(verifier: String): String {
            return try {
                CodeVerifierUtil.getCodeChallenge(verifier) ?: computeSha256Challenge(verifier)
            } catch (_: Throwable) {
                computeSha256Challenge(verifier)
            }
        }

        private fun generateSecurePkceVerifier(): String {
            val bytes = ByteArray(64)
            SecureRandom().nextBytes(bytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun computeSha256Challenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        }
    }

    private val _state = MutableStateFlow<TruecallerAuthState>(TruecallerAuthState.Idle)
    val state: StateFlow<TruecallerAuthState> = _state.asStateFlow()

    var currentNonce: String? = null
        private set

    var currentCodeVerifier: String? = null
        private set

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
                if (TcSdk.getInstance().isOAuthFlowUsable) {
                    isOAuthUsable = true
                }
            } catch (e: Exception) {
                Log.d(TAG, "TcSdk.isOAuthFlowUsable check: ${e.message}")
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
     * Launches the Truecaller 1-Tap OAuth bottom-sheet consent dialog using FragmentActivity.
     */
    fun startOAuthFlow(activity: Activity, callback: TcOAuthCallback): Result<Unit> {
        if (isTestMode) {
            return Result.success(Unit)
        }

        val fragmentActivity = activity as? FragmentActivity
            ?: return Result.failure(
                IllegalStateException("Activity must be a FragmentActivity to display Truecaller bottom-sheet")
            )

        val nonce = currentNonce ?: return Result.failure(
            IllegalStateException("Truecaller verification nonce not initialized. Call initiateVerification() first.")
        )

        return try {
            // Initialize TcSdk with caller callback and TcSdkOptions
            val tcSdkOptions = TcSdkOptions.Builder(fragmentActivity, callback)
                .sdkOptions(TcSdkOptions.OPTION_VERIFY_ONLY_TC_USERS)
                .buttonShapeOptions(TcSdkOptions.BUTTON_SHAPE_ROUNDED)
                .build()

            TcSdk.init(tcSdkOptions)

            if (!TcSdk.getInstance().isOAuthFlowUsable) {
                val err = "Truecaller 1-Tap OAuth flow is not usable on this device. Truecaller app must be logged in."
                _state.value = TruecallerAuthState.Error(err, "TC_FLOW_UNUSABLE")
                return Result.failure(IllegalStateException(err))
            }

            // Generate RFC 7636 PKCE codeVerifier and codeChallenge
            val verifier = generateCodeVerifier()
            currentCodeVerifier = verifier
            val codeChallenge = generateCodeChallenge(verifier)

            // Configure TcSdk OAuth parameters
            TcSdk.getInstance().apply {
                setOAuthState(nonce)
                setOAuthScopes(arrayOf("openid", "phone", "profile"))
                setCodeChallenge(codeChallenge)
            }

            // Launch bottom-sheet consent
            TcSdk.getInstance().getAuthorizationCode(fragmentActivity)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Truecaller getAuthorizationCode: ${e.message}", e)
            val errMsg = e.message ?: "Failed to launch Truecaller consent"
            _state.value = TruecallerAuthState.Error(errMsg, "TC_LAUNCH_FAILED")
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
            val effectiveVerifier = codeVerifier ?: currentCodeVerifier
            val result = authRepository.verifyWithTruecaller(
                payload = payload,
                signature = signature,
                requestNonce = nonce,
                accessToken = accessToken,
                authorizationCode = authorizationCode,
                codeVerifier = effectiveVerifier,
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
        currentCodeVerifier = null
        _state.value = TruecallerAuthState.Idle
    }
}
