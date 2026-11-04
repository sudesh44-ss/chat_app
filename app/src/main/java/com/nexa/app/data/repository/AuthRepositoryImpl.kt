package com.nexa.app.data.repository

import com.nexa.app.core.security.CryptoKeystoreManager
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.data.local.dao.UserDao
import com.nexa.app.data.local.entity.UserEntity
import com.nexa.app.data.local.entity.toDomain
import com.nexa.app.data.remote.api.AuthApiService
import com.nexa.app.data.remote.dto.RegisterInitRequest
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
            .map { it?.toDomain() }
            .flowOn(Dispatchers.IO)
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
            // Step 1: Generate identity keypair (Hardware KeyStore + Tink wrapping architecture)
            val identityKeys = keystoreManager.generateIdentityKeyPair()

            // Step 2: Backend cryptographic handshake (pass public key hex and profile details)
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

            // Step 3: Persist verified profile in local encrypted Room DB (SQLCipher)
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

            // Step 4: Persist session token and active NX-ID in EncryptedSharedPreferences
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
