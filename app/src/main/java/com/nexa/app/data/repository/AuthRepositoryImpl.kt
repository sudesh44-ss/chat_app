package com.nexa.app.data.repository

import android.os.Build
import android.util.Log
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
import com.nexa.app.domain.model.AccountStatus
import com.nexa.app.domain.model.UserProfile
import com.nexa.app.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AuthRepositoryImpl(
    private val apiService: AuthApiService,
    private val userDao: UserDao,
    private val keystoreManager: CryptoKeystoreManager,
    private val secureStorage: SecureStorage
) : AuthRepository {

    private val _accountStatus = MutableStateFlow(
        try {
            AccountStatus.valueOf(secureStorage.getAccountStatus())
        } catch (e: Exception) {
            AccountStatus.ACTIVE
        }
    )

    override fun getAccountStatus(): Flow<AccountStatus> = _accountStatus.asStateFlow()

    override suspend fun updateDisplayName(name: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val nxId = getActiveNxId() ?: return@withContext Result.failure(IllegalStateException("No active account"))
            userDao.updateFullName(nxId, name)
            // Best-effort remote sync
            val token = secureStorage.getAuthToken()
            if (!token.isNullOrBlank()) {
                apiService.updateProfile(sessionToken = token, name = name)
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun requestChangePhoneOtp(newPhone: String): Result<Boolean> = withContext(Dispatchers.IO) {
        sendVerificationOtp(newPhone, null)
    }

    override suspend fun verifyChangePhoneOtp(newPhone: String, otp: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val nxId = getActiveNxId() ?: return@withContext Result.failure(IllegalStateException("No active account"))
            if (otp.length == 6) {
                userDao.updatePhoneNumber(nxId, newPhone)
                Result.success(true)
            } else {
                Result.failure(IllegalArgumentException("Invalid 6-digit OTP code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deactivateAccount(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            secureStorage.saveAccountStatus(AccountStatus.DEACTIVATED.name)
            _accountStatus.value = AccountStatus.DEACTIVATED
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun reactivateAccount(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            secureStorage.saveAccountStatus(AccountStatus.ACTIVE.name)
            _accountStatus.value = AccountStatus.ACTIVE
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun requestDeletionOtp(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val profile = userDao.getLocalProfile()
            if (profile != null) {
                sendVerificationOtp(profile.phoneNumber, null)
            }
            _accountStatus.value = AccountStatus.DELETION_REQUESTED
            Result.success(true)
        } catch (e: Exception) {
            _accountStatus.value = AccountStatus.DELETION_REQUESTED
            Result.success(true)
        }
    }

    override suspend fun verifyDeletionOtp(otp: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (otp.length == 6) {
                _accountStatus.value = AccountStatus.IDENTITY_VERIFIED
                Result.success(true)
            } else {
                Result.failure(IllegalArgumentException("Invalid 6-digit OTP code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun confirmPermanentDeletion(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            _accountStatus.value = AccountStatus.DELETION_PROCESSING
            val token = secureStorage.getAuthToken()
            val deviceId = secureStorage.getDeviceId()
            if (token != null && deviceId != null) {
                try {
                    apiService.revokeDevice(token, deviceId)
                } catch (e: Exception) {
                    // Best-effort remote revocation
                }
            }
            clearSession()
            secureStorage.saveAccountStatus(AccountStatus.DELETED.name)
            _accountStatus.value = AccountStatus.DELETED
            Result.success(true)
        } catch (e: Exception) {
            _accountStatus.value = AccountStatus.DELETION_FAILED
            Result.failure(e)
        }
    }

    override suspend fun cancelDeletionRequest(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            secureStorage.saveAccountStatus(AccountStatus.ACTIVE.name)
            _accountStatus.value = AccountStatus.ACTIVE
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        try {
            val hasToken = !secureStorage.getAuthToken().isNullOrBlank()
            val hasLocalSession = userDao.hasActiveSession()
            hasToken && hasLocalSession
        } catch (e: Exception) {
            Log.e("AuthRepositoryImpl", "Failed to check session state", e)
            false
        }
    }

    override suspend fun getActiveNxId(): String? = withContext(Dispatchers.IO) {
        secureStorage.getActiveNxId() ?: userDao.getActiveNxId()
    }

    override fun getActiveProfile(): Flow<UserProfile?> {
        return userDao.getActiveUserFlow()
            .map { it?.toDomain(secureStorage.getAvatarUrl()) }
            .catch { e ->
                Log.e("AuthRepositoryImpl", "Error reading active user profile flow", e)
                emit(null)
            }
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

            var finalResponse = response
            // If the server requires hardware challenge proof of possession
            if (response.status == "REQUIRE_DEVICE_CHALLENGE" && !response.challenge.isNullOrBlank()) {
                val challengeHex = response.challenge
                val challengeId = response.challengeId ?: challengeHex
                val challengeBytes = challengeHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

                val hardwareSecurity = com.nexa.app.core.keystore.NexaHardwareSecurity()
                val signatureBytes = hardwareSecurity.signChallenge(challengeBytes)
                val signatureHex = signatureBytes.joinToString("") { "%02x".format(it) }

                val verifyResult = apiService.verifyLoginChallenge(
                    com.nexa.app.data.remote.dto.LoginVerifyRequestDto(
                        challengeId = challengeId,
                        challenge = challengeHex,
                        nxId = response.nxId ?: "",
                        deviceId = deviceId,
                        signature = signatureHex
                    )
                ).getOrThrow()

                finalResponse = response.copy(
                    status = "LOGIN_SUCCESS",
                    sessionAuthToken = verifyResult.sessionAuthToken,
                    nxId = verifyResult.nxId,
                    prefillName = verifyResult.user?.name ?: response.prefillName,
                    phoneNumber = verifyResult.user?.phone ?: response.phoneNumber,
                    prefillAvatar = verifyResult.user?.avatarUrl ?: response.prefillAvatar
                )
            }

            if (finalResponse.status == "LOGIN_SUCCESS" && !finalResponse.sessionAuthToken.isNullOrBlank() && !finalResponse.nxId.isNullOrBlank()) {
                // Instantly logged in on same physical device with verified hardware Keystore signature
                secureStorage.saveAuthToken(finalResponse.sessionAuthToken)
                secureStorage.saveActiveNxId(finalResponse.nxId)
                if (!finalResponse.prefillAvatar.isNullOrBlank()) {
                    secureStorage.saveAvatarUrl(finalResponse.prefillAvatar)
                }

                val userEntity = UserEntity(
                    nxId = finalResponse.nxId,
                    fullName = finalResponse.prefillName ?: "NEXA User",
                    phoneNumber = finalResponse.phoneNumber ?: "",
                    email = finalResponse.email,
                    avatarUrl = finalResponse.prefillAvatar,
                    age = null,
                    gender = null,
                    bio = null,
                    publicKeyHex = keys.publicKeyHex,
                    isVerified = true,
                    createdAt = System.currentTimeMillis()
                )
                userDao.saveUserProfile(userEntity)
            }

            Result.success(finalResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun requestAndVerifyLoginChallenge(
        nxId: String,
        deviceId: String
    ): Result<com.nexa.app.data.remote.dto.LoginVerifyResponseDto> = withContext(Dispatchers.IO) {
        try {
            val challengeResp = apiService.requestLoginChallenge(
                com.nexa.app.data.remote.dto.LoginChallengeRequestDto(
                    nxId = nxId,
                    deviceId = deviceId
                )
            ).getOrThrow()

            val challengeBytes = challengeResp.challenge.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val hardwareSecurity = com.nexa.app.core.keystore.NexaHardwareSecurity()
            val signatureBytes = hardwareSecurity.signChallenge(challengeBytes)
            val signatureHex = signatureBytes.joinToString("") { "%02x".format(it) }

            val verifyResp = apiService.verifyLoginChallenge(
                com.nexa.app.data.remote.dto.LoginVerifyRequestDto(
                    challengeId = challengeResp.challengeId,
                    challenge = challengeResp.challenge,
                    nxId = nxId,
                    deviceId = deviceId,
                    signature = signatureHex
                )
            ).getOrThrow()

            secureStorage.saveAuthToken(verifyResp.sessionAuthToken)
            secureStorage.saveActiveNxId(verifyResp.nxId)

            val keys = keystoreManager.getOrCreateIdentityKeyPair(secureStorage)
            val userEntity = UserEntity(
                nxId = verifyResp.nxId,
                fullName = verifyResp.user?.name ?: "NEXA User",
                phoneNumber = verifyResp.user?.phone ?: "",
                age = null,
                gender = null,
                bio = null,
                publicKeyHex = keys.publicKeyHex,
                isVerified = true,
                createdAt = System.currentTimeMillis()
            )
            userDao.saveUserProfile(userEntity)

            Result.success(verifyResp)
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
        bio: String?,
        email: String?
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
                email = email,
                avatarUrl = avatarUrl,
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

    override suspend fun fetchRemoteProfile(): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val token = secureStorage.getAuthToken()
                ?: return@withContext Result.failure(IllegalStateException("No active authentication token"))
            val remoteDto = apiService.fetchProfile(token).getOrThrow()

            secureStorage.saveActiveNxId(remoteDto.nxId)
            if (!remoteDto.avatarUrl.isNullOrBlank()) {
                secureStorage.saveAvatarUrl(remoteDto.avatarUrl)
            }

            val keys = keystoreManager.getOrCreateIdentityKeyPair(secureStorage)
            val userEntity = UserEntity(
                nxId = remoteDto.nxId,
                fullName = remoteDto.profileName ?: "NEXA User",
                phoneNumber = remoteDto.phoneNumber ?: "",
                email = remoteDto.email,
                avatarUrl = remoteDto.avatarUrl,
                age = remoteDto.age,
                gender = remoteDto.gender,
                bio = remoteDto.bio,
                publicKeyHex = remoteDto.publicKeyHex ?: keys.publicKeyHex,
                isVerified = remoteDto.phoneVerified,
                createdAt = if (remoteDto.createdAt > 0) remoteDto.createdAt else System.currentTimeMillis()
            )
            userDao.saveUserProfile(userEntity)
            Result.success(userEntity.toDomain(remoteDto.avatarUrl))
        } catch (e: Exception) {
            Log.w("AuthRepositoryImpl", "Failed to sync remote profile: ${e.message}")
            val local = userDao.getLocalProfile()
            if (local != null) {
                Result.success(local.toDomain(secureStorage.getAvatarUrl()))
            } else {
                Result.failure(e)
            }
        }
    }

    override suspend fun updateRemoteProfile(
        name: String?,
        bio: String?,
        gender: String?,
        age: Int?,
        avatarUrl: String?
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val token = secureStorage.getAuthToken()
                ?: return@withContext Result.failure(IllegalStateException("No active authentication token"))

            // 1. Update on remote backend
            apiService.updateProfile(
                sessionToken = token,
                name = name,
                bio = bio,
                gender = gender,
                age = age,
                avatarUrl = avatarUrl
            ).getOrThrow()

            // 2. Update local DB
            val local = userDao.getLocalProfile()
            if (local != null) {
                val updated = local.copy(
                    fullName = name ?: local.fullName,
                    bio = bio ?: local.bio,
                    gender = gender ?: local.gender,
                    age = age ?: local.age,
                    avatarUrl = avatarUrl ?: local.avatarUrl
                )
                userDao.saveUserProfile(updated)
            }
            if (!avatarUrl.isNullOrBlank()) {
                secureStorage.saveAvatarUrl(avatarUrl)
            }
            Result.success(true)
        } catch (e: Exception) {
            Log.e("AuthRepositoryImpl", "Failed to update profile: ${e.message}", e)
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
            val identityKeys = keystoreManager.getOrCreateIdentityKeyPair(secureStorage)

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
