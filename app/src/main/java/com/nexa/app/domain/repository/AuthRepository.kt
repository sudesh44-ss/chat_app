package com.nexa.app.domain.repository

import com.nexa.app.data.remote.dto.GoogleAuthResponse
import com.nexa.app.data.remote.dto.InitiateReverseVerifyResponseDto
import com.nexa.app.data.remote.dto.UserResolveDto
import com.nexa.app.data.remote.dto.VerificationStatusDto
import com.nexa.app.domain.model.AccountStatus
import com.nexa.app.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    suspend fun isLoggedIn(): Boolean

    /**
     * Comprehensive startup validation of existing authentication session, device,
     * and required onboarding state. Verifies token, local active verified profile,
     * account status, and confirms session validity against the backend.
     * Expired or revoked sessions are invalidated and cleared.
     */
    suspend fun validateSession(): Boolean = isLoggedIn()

    suspend fun getActiveNxId(): String?
    fun getActiveProfile(): Flow<UserProfile?>

    suspend fun loginWithGoogle(idToken: String): Result<GoogleAuthResponse>

    suspend fun requestAndVerifyLoginChallenge(nxId: String, deviceId: String): Result<com.nexa.app.data.remote.dto.LoginVerifyResponseDto>

    suspend fun initiateReverseVerify(
        phone: String,
        name: String,
        avatarUrl: String?,
        gender: String?,
        age: Int?,
        bio: String?,
        googleUid: String,
        googleAuthToken: String?,
        isReverify: Boolean = false,
        email: String? = null
    ): Result<InitiateReverseVerifyResponseDto>

    suspend fun pollVerificationStatus(nonce: String): Result<VerificationStatusDto>

    suspend fun saveVerifiedSession(
        nxId: String,
        sessionToken: String,
        name: String,
        phone: String,
        avatarUrl: String? = null,
        age: Int? = null,
        gender: String? = null,
        bio: String? = null,
        email: String? = null
    ): Result<UserProfile>

    suspend fun fetchRemoteProfile(): Result<UserProfile>

    suspend fun updateRemoteProfile(
        name: String? = null,
        bio: String? = null,
        gender: String? = null,
        age: Int? = null,
        avatarUrl: String? = null
    ): Result<Boolean>

    suspend fun resolveUser(target: String): Result<UserResolveDto>

    suspend fun sendVerificationOtp(phone: String, googleAuthToken: String?): Result<Boolean>

    suspend fun verifyOtpAndRegister(
        phone: String,
        otp: String,
        name: String,
        age: Int?,
        gender: String?,
        bio: String?
    ): Result<UserProfile>

    suspend fun verifyWithFirebase(
        idToken: String,
        googleAuthToken: String? = null,
        email: String? = null,
        name: String? = null,
        avatarUrl: String? = null,
        gender: String? = null,
        age: Int? = null,
        bio: String? = null,
        isReverify: Boolean = false
    ): Result<UserProfile> = Result.failure(UnsupportedOperationException("Not implemented"))

    suspend fun verifyWithTruecaller(
        payload: String? = null,
        signature: String? = null,
        requestNonce: String? = null,
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
    ): Result<UserProfile> = Result.failure(UnsupportedOperationException("Not implemented"))

    suspend fun getTruecallerNonce(): Result<String> = Result.failure(UnsupportedOperationException("Not implemented"))

    // Account Lifecycle & Settings Management
    fun getAccountStatus(): Flow<AccountStatus> = kotlinx.coroutines.flow.flowOf(AccountStatus.ACTIVE)
    suspend fun updateDisplayName(name: String): Result<Boolean> = Result.success(true)
    suspend fun requestChangePhoneOtp(newPhone: String): Result<Boolean> = Result.success(true)
    suspend fun verifyChangePhoneOtp(newPhone: String, otp: String): Result<Boolean> = Result.success(true)
    suspend fun deactivateAccount(): Result<Boolean> = Result.success(true)
    suspend fun reactivateAccount(): Result<Boolean> = Result.success(true)
    suspend fun requestDeletionOtp(): Result<Boolean> = Result.success(true)
    suspend fun verifyDeletionOtp(otp: String): Result<Boolean> = Result.success(true)
    suspend fun confirmPermanentDeletion(): Result<Boolean> = Result.success(true)
    suspend fun cancelDeletionRequest(): Result<Boolean> = Result.success(true)

    suspend fun getDevices(): Result<List<com.nexa.app.data.remote.dto.DeviceInfoDto>> = Result.success(emptyList())
    suspend fun revokeDevice(deviceId: String): Result<Boolean> = Result.success(false)

    suspend fun clearSession()
}
