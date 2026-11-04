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
