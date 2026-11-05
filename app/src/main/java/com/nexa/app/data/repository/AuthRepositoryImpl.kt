package com.nexa.app.data.repository

import android.os.Build
import com.nexa.app.core.security.CryptoKeystoreManager
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.entity.UserEntity
import com.nexa.app.data.local.entity.toDomain
import com.nexa.app.data.remote.api.AuthApiService
import com.nexa.app.data.remote.dto.GoogleAuthResponse
import com.nexa.app.data.remote.dto.InitiateReverseVerifyRequestDto
import com.nexa.app.data.remote.dto.InitiateReverseVerifyResponseDto
import com.nexa.app.data.remote.dto.RegisterInitRequest
import com.nexa.app.data.remote.dto.UserResolveDto
import com.nexa.app.data.remote.dto.VerificationStatusDto
import com.nexa.app.data.remote.dto.VerifyOtpRequest
import com.nexa.app.domain.model.UserProfile
import com.nexa.app.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AuthRepositoryImpl(
    private val apiService: AuthApiService,
    private val userDao: UserDao,
    private val keystoreManager: CryptoKeystoreManager,
    private val secureStorage: SecureStorage
) : AuthRepository {

    override suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        val hasToken = !secureStorage.getAuthToken().isNullOrBlank()
        val hasLocalSession = userDao.hasActiveSession()
        hasToken && hasLocalSession
    }

    override suspend fun getActiveNxId(): String? = withContext(Dispatchers.IO) {
        secureStorage.getActiveNxId() ?: userDao.getActiveNxId()
    }

    override fun getActiveProfile(): Flow<UserProfile?> {
        return userDao.getActiveUserFlow()
            .map { it?.toDomain(secureStorage.getAvatarUrl()) }
            .flowOn(Dispatchers.IO)
    }

    override suspend fun loginWithGoogle(idToken: String): Result<GoogleAuthResponse> = withContext(Dispatchers.IO) {
        try {
            val deviceId = secureStorage.getOrCreateDeviceId()
            val keys = keystoreManager.getOrCreateIdentityKeyPair(secureStorage)

            val response = apiService.googleAuth(
                idToken = idToken,
                deviceId = deviceId,
                publicKeyHex = keys.publicKeyHex
            ).getOrThrow()

            if (response.status == "LOGIN_SUCCESS" && !response.sessionAuthToken.isNullOrBlank() && !response.nxId.isNullOrBlank()) {
                // Instantly logged in on same physical device
                secureStorage.saveAuthToken(response.sessionAuthToken)
                secureStorage.saveActiveNxId(response.nxId)
                if (!response.prefillAvatar.isNullOrBlank()) {
                    secureStorage.saveAvatarUrl(response.prefillAvatar)
                }

                val userEntity = UserEntity(
                    nxId = response.nxId,
                    fullName = response.prefillName ?: "NEXA User",
                    phoneNumber = response.phoneNumber ?: "",
                    age = null,
                    gender = null,
                    bio = null,
                    publicKeyHex = keys.publicKeyHex,
                    isVerified = true,
                    createdAt = System.currentTimeMillis()
                )
                userDao.saveUserProfile(userEntity)
            }

            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun initiateReverseVerify(
        phone: String,
        name: String,
        avatarUrl: String?,
        gender: String?,
        age: Int?,
        bio: String?,
        googleUid: String,
        googleAuthToken: String?,
        isReverify: Boolean
    ): Result<InitiateReverseVerifyResponseDto> = withContext(Dispatchers.IO) {
        try {
            val keys = keystoreManager.getOrCreateIdentityKeyPair(secureStorage)
            val deviceId = secureStorage.getOrCreateDeviceId()
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

            val req = InitiateReverseVerifyRequestDto(
                googleUid = googleUid,
                googleAuthToken = googleAuthToken,
                phoneNumber = phone,
                name = name,
                avatarUrl = avatarUrl,
                gender = gender,
                age = age,
                bio = bio,
                publicKeyHex = keys.publicKeyHex,
                deviceId = deviceId,
                deviceName = deviceName,
                isReverify = isReverify
            )

            apiService.initiateReverseVerify(req)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun pollVerificationStatus(nonce: String): Result<VerificationStatusDto> = withContext(Dispatchers.IO) {
        apiService.getVerificationStatus(nonce)
    }

    override suspend fun saveVerifiedSession(
        nxId: String,
        sessionToken: String,
        name: String,
        phone: String,
        avatarUrl: String?,
        age: Int?,
        gender: String?,
        bio: String?
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val keys = keystoreManager.getOrCreateIdentityKeyPair(secureStorage)

            secureStorage.saveAuthToken(sessionToken)
            secureStorage.saveActiveNxId(nxId)
            if (!avatarUrl.isNullOrBlank()) {
                secureStorage.saveAvatarUrl(avatarUrl)
            }

            val userEntity = UserEntity(
                nxId = nxId,
                fullName = name,
                phoneNumber = phone,
                age = age,
                gender = gender,
                bio = bio,
                publicKeyHex = keys.publicKeyHex,
                isVerified = true,
                createdAt = System.currentTimeMillis()
            )
            userDao.saveUserProfile(userEntity)

            Result.success(userEntity.toDomain(avatarUrl))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun resolveUser(target: String): Result<UserResolveDto> = withContext(Dispatchers.IO) {
        apiService.resolveUser(target, secureStorage.getAuthToken())
    }

    override suspend fun sendVerificationOtp(
        phone: String,
        googleAuthToken: String?
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.requestOtp(
                RegisterInitRequest(
                    googleAuthToken = googleAuthToken,
                    phoneNumber = phone
                )
            ).getOrThrow()
            Result.success(response.otpSent)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun verifyOtpAndRegister(
        phone: String,
        otp: String,
        name: String,
        age: Int?,
        gender: String?,
        bio: String?
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val identityKeys = keystoreManager.generateIdentityKeyPair()

            val response = apiService.verifyOtpAndRegister(
                VerifyOtpRequest(
                    phoneNumber = phone,
                    otpCode = otp,
                    publicKeyHex = identityKeys.publicKeyHex,
                    profileName = name,
                    age = age,
                    gender = gender,
                    bio = bio
                )
            ).getOrThrow()

            val userEntity = UserEntity(
                nxId = response.nxId,
                fullName = name,
                phoneNumber = phone,
                age = age,
                gender = gender,
                bio = bio,
                publicKeyHex = identityKeys.publicKeyHex,
                isVerified = true,
                createdAt = response.registeredTimestamp
            )
            userDao.saveUserProfile(userEntity)

            secureStorage.saveAuthToken(response.sessionAuthToken)
            secureStorage.saveActiveNxId(response.nxId)

            Result.success(userEntity.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearSession() = withContext(Dispatchers.IO) {
        userDao.clearProfile()
        secureStorage.clearAll()
    }
}
