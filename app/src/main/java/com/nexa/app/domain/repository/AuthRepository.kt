package com.nexa.app.domain.repository

import com.nexa.app.data.remote.dto.GoogleAuthResponse
import com.nexa.app.data.remote.dto.InitiateReverseVerifyResponseDto
import com.nexa.app.data.remote.dto.UserResolveDto
import com.nexa.app.data.remote.dto.VerificationStatusDto
import com.nexa.app.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    suspend fun isLoggedIn(): Boolean
    suspend fun getActiveNxId(): String?
    fun getActiveProfile(): Flow<UserProfile?>

    suspend fun loginWithGoogle(idToken: String): Result<GoogleAuthResponse>

    suspend fun initiateReverseVerify(
        phone: String,
        name: String,
        avatarUrl: String?,
        gender: String?,
        age: Int?,
        bio: String?,
        googleUid: String,
        googleAuthToken: String?,
        isReverify: Boolean = false
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
        bio: String? = null
    ): Result<UserProfile>

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

    suspend fun clearSession()
}
