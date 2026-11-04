package com.nexa.app.domain.model

data class UserProfile(
    val nxId: String,
    val fullName: String,
    val phoneNumber: String,
    val age: Int? = null,
    val gender: String? = null,
    val bio: String? = null,
    val publicKeyHex: String,
    val isVerified: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
