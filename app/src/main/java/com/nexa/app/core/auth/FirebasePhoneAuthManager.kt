package com.nexa.app.core.auth

import android.app.Activity
import android.util.Log
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.nexa.app.domain.model.UserProfile
import com.nexa.app.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * State representing Firebase Phone OTP verification progress.
 */
sealed interface FirebaseAuthState {
    data object Idle : FirebaseAuthState
    data class SendingOtp(val phone: String) : FirebaseAuthState
    data class CodeSent(
        val verificationId: String,
        val phone: String,
        val timeoutSeconds: Int = 60
    ) : FirebaseAuthState
    data object VerifyingOtp : FirebaseAuthState
    data class Verified(
        val idToken: String,
        val phone: String
    ) : FirebaseAuthState
    data class Success(val profile: UserProfile) : FirebaseAuthState
    data class Error(val message: String, val code: String? = null) : FirebaseAuthState
}

/**
 * FirebasePhoneAuthManager manages phone number ownership verification using Firebase Phone Auth.
 *
 * Architecture & Security Principles:
 * 1. Client requests Firebase to send a 6-digit SMS OTP to the user-entered phone number.
 * 2. Activity context is strictly required (application context is never passed).
 * 3. Client exchanges the OTP for a signed Firebase ID Token (RS256 JWT issued by Google).
 * 4. The raw ID Token is dispatched to NEXA Go Backend (/api/verify/firebase-phone or /api/v1/auth/firebase/verify).
 * 5. NEXA Go Backend independently verifies the Google JWKS signature, expiration, audience,
 *    and extracts the authoritative "phone_number" claim from Google.
 * 6. Fail-closed integrity: If Firebase verification or cryptographic checks fail, the flow halts.
 */
class FirebasePhoneAuthManager(
    private val authRepository: AuthRepository,
    private val isTestMode: Boolean = false,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "FirebasePhoneAuth"
        const val TEST_VERIFICATION_ID = "nexa_fb_test_verification_id"
        const val TEST_VALID_OTP = "123456"
        const val MOCK_ID_TOKEN = "mock_firebase_id_token_test_mode"

        /**
         * Normalizes phone numbers to standard E.164 (+91XXXXXXXXXX) format.
         * Strips whitespace, hyphens, and domestic prefixes.
         */
        fun formatPhoneE164(input: String, defaultCountryCode: String = "+91"): String {
            val trimmed = input.trim()
            val stripped = trimmed.replace(Regex("[\\s\\-\\(\\)]"), "")
            return when {
                stripped.startsWith("+") -> {
                    "+" + stripped.substring(1).replace(Regex("[^0-9]"), "")
                }
                stripped.startsWith("00") -> {
                    "+" + stripped.substring(2).replace(Regex("[^0-9]"), "")
                }
                stripped.startsWith("0") -> {
                    defaultCountryCode + stripped.substring(1).replace(Regex("[^0-9]"), "")
                }
                stripped.length == 10 && stripped.all { it.isDigit() } -> {
                    defaultCountryCode + stripped
                }
                stripped.startsWith("91") && stripped.length == 12 && stripped.all { it.isDigit() } -> {
                    "+" + stripped
                }
                else -> {
                    "+" + stripped.replace(Regex("[^0-9]"), "")
                }
            }
        }

        /**
         * Parses and maps Firebase exceptions to actionable, user-friendly diagnostic messages.
         */
        fun mapFirebaseException(e: Throwable): Pair<String, String> {
            val msg = e.message ?: ""
            val className = e.javaClass.simpleName
            val fbErrorCode = (e as? com.google.firebase.auth.FirebaseAuthException)?.errorCode
            val codeSuffix = if (!fbErrorCode.isNullOrBlank()) " [$fbErrorCode]" else ""
            val rawDetail = if (msg.isNotBlank()) ": $msg" else ""
            return when {
                e is FirebaseAuthInvalidCredentialsException ||
                className.contains("FirebaseAuthInvalidCredentialsException") ||
                fbErrorCode == "ERROR_INVALID_VERIFICATION_CODE" ||
                fbErrorCode == "ERROR_INVALID_PHONE_NUMBER" ||
                msg.contains("invalid-verification-code", ignoreCase = true) ||
                msg.contains("invalid phone", ignoreCase = true) -> {
                    "Invalid phone number or OTP code$codeSuffix$rawDetail" to (fbErrorCode ?: "INVALID_CREDENTIALS")
                }
                className.contains("FirebaseTooManyRequestsException") ||
                className.contains("TooManyRequests") ||
                fbErrorCode == "ERROR_TOO_MANY_REQUESTS" ||
                fbErrorCode == "ERROR_QUOTA_EXCEEDED" ||
                msg.contains("too-many-requests", ignoreCase = true) ||
                msg.contains("quota exceeded", ignoreCase = true) -> {
                    "SMS quota exceeded (10 SMS/day on Firebase free tier). Use Truecaller or add test numbers in Firebase Console$codeSuffix$rawDetail" to (fbErrorCode ?: "QUOTA_EXCEEDED")
                }
                fbErrorCode == "ERROR_APP_NOT_AUTHORIZED" ||
                fbErrorCode == "ERROR_MISSING_CLIENT_IDENTIFIER" ||
                msg.contains("Play Integrity", ignoreCase = true) ||
                msg.contains("app verification failed", ignoreCase = true) ||
                msg.contains("sha-1", ignoreCase = true) ||
                msg.contains("sha-256", ignoreCase = true) ||
                msg.contains("recaptcha", ignoreCase = true) ||
                msg.contains("safetyNet", ignoreCase = true) -> {
                    "App verification failed. Ensure SHA-1 is in Firebase Console & Play Integrity is enabled$codeSuffix$rawDetail" to (fbErrorCode ?: "INTEGRITY_CHECK_FAILED")
                }
                fbErrorCode == "ERROR_OPERATION_NOT_ALLOWED" ||
                msg.contains("operation-not-allowed", ignoreCase = true) -> {
                    "Phone Provider disabled in Firebase Console. Enable Phone Auth in Firebase Console > Authentication > Sign-in method$codeSuffix$rawDetail" to (fbErrorCode ?: "OPERATION_NOT_ALLOWED")
                }
                msg.contains("DEVELOPMENT_MODE_MISMATCH", ignoreCase = true) ||
                fbErrorCode == "ERROR_DEVELOPMENT_MODE_MISMATCH" -> {
                    "Development mode session mismatch. Cached session has been cleared, please try requesting OTP again$codeSuffix$rawDetail" to (fbErrorCode ?: "DEVELOPMENT_MODE_MISMATCH")
                }
                fbErrorCode == "ERROR_BILLING_NOT_ENABLED" ||
                msg.contains("blocked", ignoreCase = true) ||
                msg.contains("SMS region", ignoreCase = true) ||
                msg.contains("billing", ignoreCase = true) -> {
                    "SMS delivery restricted by Firebase policy or region$codeSuffix$rawDetail" to (fbErrorCode ?: "REGION_BLOCKED")
                }
                else -> {
                    val fallback = if (msg.isNotBlank()) msg else "Phone verification failed ($className)"
                    "$fallback$codeSuffix" to (fbErrorCode ?: "VERIFICATION_ERROR")
                }
            }
        }
    }

    private val _state = MutableStateFlow<FirebaseAuthState>(FirebaseAuthState.Idle)
    val state: StateFlow<FirebaseAuthState> = _state.asStateFlow()

    private var activeVerificationId: String? = null
    private var activePhone: String? = null
    private var activeResendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var activeResendTokenPhone: String? = null

    fun getActiveResendToken(): PhoneAuthProvider.ForceResendingToken? = activeResendToken

    /**
     * Sends a 6-digit SMS verification code to the target phone number.
     * Enforces that [activity] is provided (never passes Application context).
     * Attaches [forceResendingToken] if this is a resend request.
     */
    suspend fun sendOtp(
        phoneNumber: String,
        activity: Activity? = null,
        forceResendingToken: PhoneAuthProvider.ForceResendingToken? = null,
        isResend: Boolean = false
    ): Result<String> = withContext(ioDispatcher) {
        val cleanPhone = formatPhoneE164(phoneNumber)
        if (cleanPhone.length < 10) {
            val err = "Invalid phone number format: $phoneNumber"
            _state.value = FirebaseAuthState.Error(err, "INVALID_PHONE")
            return@withContext Result.failure(IllegalArgumentException(err))
        }

        // 1. Flush stale session state on fresh OTP requests or if phone number changed
        if (!isResend || cleanPhone != activeResendTokenPhone) {
            activeVerificationId = null
            activeResendToken = null
            activeResendTokenPhone = null
        }

        _state.value = FirebaseAuthState.SendingOtp(cleanPhone)
        activePhone = cleanPhone

        if (isTestMode) {
            val fakeVerId = "ver_" + UUID.randomUUID().toString().take(8)
            activeVerificationId = fakeVerId
            _state.value = FirebaseAuthState.CodeSent(
                verificationId = fakeVerId,
                phone = cleanPhone,
                timeoutSeconds = 60
            )
            return@withContext Result.success(fakeVerId)
        }

        // Live Firebase verification requires an Activity context for Play Integrity / reCAPTCHA verification
        if (activity == null) {
            val errMsg = "Activity context required for Firebase Phone Auth. Cannot use Application Context."
            Log.e(TAG, errMsg)
            _state.value = FirebaseAuthState.Error(errMsg, "ACTIVITY_REQUIRED")
            return@withContext Result.failure(IllegalStateException(errMsg))
        }

        try {
            val firebaseAuth = FirebaseAuth.getInstance()

            // Clear any cached development / test mode user session prior to fresh OTP dispatch
            if (!isResend) {
                runCatching {
                    firebaseAuth.signOut()
                    Log.i(TAG, "Cleared cached FirebaseAuth user session prior to fresh OTP dispatch")
                }
            }

            Log.i(TAG, "Triggering Firebase Phone Auth for $cleanPhone (isResend: $isResend) with Activity: ${activity.localClassName}")

            return@withContext suspendCancellableCoroutine<Result<String>> { continuation ->
                val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                    override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                        Log.i(TAG, "Instant verification completed by carrier / SMS Retriever")
                        val code = credential.smsCode
                        if (!code.isNullOrBlank()) {
                            Log.i(TAG, "SMS Retriever auto-extracted code: $code")
                        }
                        handleInstantVerification(firebaseAuth, credential) { verifiedResult ->
                            if (continuation.isActive) {
                                continuation.resume(verifiedResult)
                            }
                        }
                    }

                    override fun onVerificationFailed(exception: FirebaseException) {
                        val (mappedMsg, code) = mapFirebaseException(exception)
                        Log.e(TAG, "Firebase verification failed: $mappedMsg (code: $code)", exception)
                        _state.value = FirebaseAuthState.Error(mappedMsg, code)
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(exception))
                        }
                    }

                    override fun onCodeSent(
                        verificationId: String,
                        token: PhoneAuthProvider.ForceResendingToken
                    ) {
                        Log.i(TAG, "Firebase OTP Code sent, verificationId: $verificationId")
                        activeVerificationId = verificationId
                        activeResendToken = token
                        activeResendTokenPhone = cleanPhone
                        _state.value = FirebaseAuthState.CodeSent(
                            verificationId = verificationId,
                            phone = cleanPhone,
                            timeoutSeconds = 60
                        )
                        if (continuation.isActive) {
                            continuation.resume(Result.success(verificationId))
                        }
                    }
                }

                // Ensure activeResendToken is ONLY attached to PhoneAuthOptions.Builder when isResend == true
                // AND the target cleanPhone matches the exact phone number for which that token was issued
                val tokenToUse = if (isResend) {
                    when {
                        forceResendingToken != null -> forceResendingToken
                        activeResendToken != null && activeResendTokenPhone == cleanPhone -> activeResendToken
                        else -> null
                    }
                } else {
                    null
                }

                val builder = PhoneAuthOptions.newBuilder(firebaseAuth)
                    .setPhoneNumber(cleanPhone)
                    .setTimeout(60L, TimeUnit.SECONDS)
                    .setActivity(activity)
                    .setCallbacks(callbacks)

                if (tokenToUse != null) {
                    builder.setForceResendingToken(tokenToUse)
                    Log.i(TAG, "Attached ForceResendingToken for Firebase Phone Auth resend to $cleanPhone")
                }

                val options = builder.build()
                PhoneAuthProvider.verifyPhoneNumber(options)
            }

        } catch (e: Exception) {
            val (mappedMsg, code) = mapFirebaseException(e)
            Log.e(TAG, "Firebase SDK dispatch error: $mappedMsg", e)
            _state.value = FirebaseAuthState.Error(mappedMsg, code)
            Result.failure(e)
        }
    }

    private fun handleInstantVerification(
        firebaseAuth: FirebaseAuth,
        credential: PhoneAuthCredential,
        onComplete: ((Result<String>) -> Unit)? = null
    ) {
        try {
            firebaseAuth.signInWithCredential(credential)
                .addOnSuccessListener { authResult ->
                    authResult.user?.getIdToken(true)?.addOnSuccessListener { tokenResult ->
                        val token = tokenResult.token
                        if (!token.isNullOrBlank()) {
                            val phone = activePhone ?: ""
                            _state.value = FirebaseAuthState.Verified(
                                idToken = token,
                                phone = phone
                            )
                            onComplete?.invoke(Result.success("INSTANT_VERIFIED:$token"))
                        } else {
                            val err = Exception("Firebase returned empty ID token during instant verification")
                            _state.value = FirebaseAuthState.Error(err.message ?: "", "EMPTY_TOKEN")
                            onComplete?.invoke(Result.failure(err))
                        }
                    }?.addOnFailureListener { e ->
                        val (mappedMsg, code) = mapFirebaseException(e)
                        _state.value = FirebaseAuthState.Error(mappedMsg, code)
                        onComplete?.invoke(Result.failure(e))
                    }
                }
                .addOnFailureListener { e ->
                    val (mappedMsg, code) = mapFirebaseException(e)
                    Log.e(TAG, "Failed handling instant verification credential: $mappedMsg", e)
                    _state.value = FirebaseAuthState.Error(mappedMsg, code)
                    onComplete?.invoke(Result.failure(e))
                }
        } catch (e: Exception) {
            val (mappedMsg, code) = mapFirebaseException(e)
            Log.e(TAG, "Exception handling instant verification credential", e)
            _state.value = FirebaseAuthState.Error(mappedMsg, code)
            onComplete?.invoke(Result.failure(e))
        }
    }

    /**
     * Verifies the 6-digit code and produces a Firebase ID token.
     */
    suspend fun verifyOtp(
        verificationId: String,
        smsCode: String
    ): Result<String> = withContext(ioDispatcher) {
        val cleanCode = smsCode.trim()
        if (cleanCode.length != 6 || !cleanCode.all { it.isDigit() }) {
            val err = "OTP must be exactly 6 digits"
            _state.value = FirebaseAuthState.Error(err, "INVALID_OTP_FORMAT")
            return@withContext Result.failure(IllegalArgumentException(err))
        }

        _state.value = FirebaseAuthState.VerifyingOtp

        // Check test/development mode OTP (strictly isolated to testMode)
        if (isTestMode) {
            val mockToken = "mock_jwt_firebase_" + UUID.randomUUID().toString()
            val phone = activePhone ?: "+919876543210"
            _state.value = FirebaseAuthState.Verified(idToken = mockToken, phone = phone)
            return@withContext Result.success(mockToken)
        }

        // Live credential verification
        try {
            val credential = PhoneAuthProvider.getCredential(verificationId, cleanCode)
            val firebaseAuth = FirebaseAuth.getInstance()

            return@withContext suspendCancellableCoroutine<Result<String>> { continuation ->
                firebaseAuth.signInWithCredential(credential)
                    .addOnSuccessListener { authResult ->
                        val user = authResult.user
                        if (user != null) {
                            user.getIdToken(true)
                                .addOnSuccessListener { tokenResult ->
                                    val idToken = tokenResult.token
                                    if (!idToken.isNullOrBlank()) {
                                        val phone = activePhone ?: ""
                                        _state.value = FirebaseAuthState.Verified(idToken = idToken, phone = phone)
                                        if (continuation.isActive) {
                                            continuation.resume(Result.success(idToken))
                                        }
                                    } else {
                                        val err = Exception("Firebase returned empty ID token")
                                        _state.value = FirebaseAuthState.Error(err.message ?: "", "EMPTY_TOKEN")
                                        if (continuation.isActive) continuation.resume(Result.failure(err))
                                    }
                                }
                                .addOnFailureListener { e ->
                                    val (mappedMsg, code) = mapFirebaseException(e)
                                    _state.value = FirebaseAuthState.Error(mappedMsg, code)
                                    if (continuation.isActive) continuation.resume(Result.failure(e))
                                }
                        } else {
                            val err = Exception("FirebaseUser is null after credential verification")
                            _state.value = FirebaseAuthState.Error(err.message ?: "", "NULL_USER")
                            if (continuation.isActive) continuation.resume(Result.failure(err))
                        }
                    }
                    .addOnFailureListener { exception ->
                        val (mappedMsg, code) = mapFirebaseException(exception)
                        Log.e(TAG, "Firebase credential sign-in failed: $mappedMsg", exception)
                        _state.value = FirebaseAuthState.Error(mappedMsg, code)
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(exception))
                        }
                    }
            }

        } catch (e: Exception) {
            val (mappedMsg, code) = mapFirebaseException(e)
            Log.e(TAG, "OTP verification exception: $mappedMsg", e)
            _state.value = FirebaseAuthState.Error(mappedMsg, code)
            Result.failure(e)
        }
    }

    /**
     * Submits the verified Firebase ID Token to NEXA Go Backend.
     */
    suspend fun submitTokenToBackend(
        idToken: String,
        googleAuthToken: String? = null,
        email: String? = null,
        name: String? = null,
        avatarUrl: String? = null,
        gender: String? = null,
        age: Int? = null,
        bio: String? = null,
        isReverify: Boolean = false
    ): Result<UserProfile> = withContext(ioDispatcher) {
        try {
            val result = authRepository.verifyWithFirebase(
                idToken = idToken,
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
            _state.value = FirebaseAuthState.Success(profile)
            Result.success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Backend token verification failed: ${e.message}", e)
            val errMsg = e.message ?: "Backend verification failed"
            _state.value = FirebaseAuthState.Error(errMsg, "BACKEND_REJECTED")
            Result.failure(e)
        }
    }

    /**
     * Resets manager state.
     */
    fun reset() {
        activeVerificationId = null
        activePhone = null
        activeResendToken = null
        activeResendTokenPhone = null
        runCatching { FirebaseAuth.getInstance().signOut() }
        _state.value = FirebaseAuthState.Idle
    }
}
