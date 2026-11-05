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
    val status: String, // "LOGIN_SUCCESS", "NEW_USER", "REQUIRE_SIM_VERIFICATION"
    val googleUid: String? = null,
    val email: String? = null,
    val prefillName: String? = null,
    val prefillAvatar: String? = null,
    val sessionAuthToken: String? = null,
    val nxId: String? = null,
    val registeredPhoneMasked: String? = null,
    val phoneNumber: String? = null,
    val inboundReceiverNumber: String? = null
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
    val smsPayload: String,
    val expiresInSeconds: Int
)

data class VerificationStatusDto(
    val status: String, // "PENDING", "VERIFIED", "EXPIRED"
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
