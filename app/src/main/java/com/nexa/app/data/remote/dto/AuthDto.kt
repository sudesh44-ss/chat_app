package com.nexa.app.data.remote.dto

data class RegisterInitRequest(
    val googleAuthToken: String?,
    val phoneNumber: String
)

data class RegisterInitResponse(
    val challenge: String,
    val otpSent: Boolean,
    val expiresAt: Long
)

data class VerifyOtpRequest(
    val phoneNumber: String,
    val otpCode: String,
    val publicKeyHex: String,
    val profileName: String,
    val age: Int?,
    val gender: String?,
    val bio: String?
)

data class RegistrationSuccessResponse(
    val nxId: String,
    val sessionAuthToken: String,
    val registeredTimestamp: Long
)

data class AuthResponse(
    val nxId: String,
    val sessionToken: String,
    val serverTimestamp: Long
)

data class GoogleAuthRequest(
    val idToken: String,
    val deviceId: String? = null,
    val publicKeyHex: String? = null
)

data class GoogleAuthResponse(
    val status: String, // "LOGIN_SUCCESS", "REQUIRE_DEVICE_CHALLENGE", "NEW_USER", "REQUIRE_SIM_VERIFICATION"
    val googleUid: String? = null,
    val email: String? = null,
    val prefillName: String? = null,
    val prefillAvatar: String? = null,
    val sessionAuthToken: String? = null,
    val nxId: String? = null,
    val registeredPhoneMasked: String? = null,
    val phoneNumber: String? = null,
    val inboundReceiverNumber: String? = null,
    val challengeId: String? = null,
    val challenge: String? = null,
    val expiresInSeconds: Int? = null,
    val deviceId: String? = null
)

data class LoginChallengeRequestDto(
    val nxId: String,
    val deviceId: String
)

data class LoginChallengeResponseDto(
    val status: String,
    val challengeId: String,
    val challenge: String,
    val nxId: String,
    val deviceId: String,
    val expiresInSeconds: Int,
    val timestamp: Long
)

data class LoginVerifyRequestDto(
    val challengeId: String? = null,
    val challenge: String,
    val nxId: String,
    val deviceId: String,
    val signature: String
)

data class UserProfileSummaryDto(
    val nxId: String,
    val name: String? = null,
    val phone: String? = null,
    val avatarUrl: String? = null
)

data class LoginVerifyResponseDto(
    val status: String,
    val sessionAuthToken: String,
    val sessionToken: String? = null,
    val nxId: String,
    val deviceId: String,
    val user: UserProfileSummaryDto? = null
)

data class InitiateReverseVerifyRequestDto(
    val googleUid: String,
    val googleAuthToken: String? = null,
    val email: String? = null,
    val phoneNumber: String,
    val name: String,
    val avatarUrl: String? = null,
    val gender: String? = null,
    val age: Int? = null,
    val bio: String? = null,
    val publicKeyHex: String,
    val dhPublicKeyHex: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val isReverify: Boolean = false
)

data class InitiateReverseVerifyResponseDto(
    val status: String,
    val nonce: String,
    val inboundReceiverNumber: String,
    val inboundNumber: String = inboundReceiverNumber,
    val smsPayload: String,
    val expiresInSeconds: Int,
    val sessionId: String? = null,
    val verificationMethod: String? = null,
    val nxId: String? = null,
    val sessionAuthToken: String? = null,
    val sessionToken: String? = null,
    val verifiedAt: Long? = null
)

data class RemoteUserProfileDto(
    val id: String? = null,
    val nxId: String,
    val publicKeyHex: String? = null,
    val phoneNumber: String? = null,
    val phoneVerified: Boolean = false,
    val isDiscoverableByPhone: Boolean = true,
    val googleId: String? = null,
    val email: String? = null,
    val profileName: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val gender: String? = null,
    val age: Int? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class VerificationStatusDto(
    val status: String, // "PENDING", "VERIFIED", "EXPIRED"
    val isVerified: Boolean = (status == "VERIFIED"),
    val expiresInSeconds: Int? = null,
    val nxId: String? = null,
    val sessionAuthToken: String? = null,
    val name: String? = null,
    val phone: String? = null,
    val avatarUrl: String? = null
)

data class UserResolveDto(
    val nxId: String,
    val name: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val isFriend: Boolean = false
)
