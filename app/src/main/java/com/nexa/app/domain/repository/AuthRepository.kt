package com.nexa.app.domain.repository

import com.nexa.app.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    suspend fun isLoggedIn(): Boolean
    suspend fun getActiveNxId(): String?
    fun getActiveProfile(): Flow<UserProfile?>
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
