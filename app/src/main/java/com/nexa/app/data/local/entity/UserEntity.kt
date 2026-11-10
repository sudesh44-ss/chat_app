package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.nexa.app.domain.model.UserProfile

@Entity(tableName = "user_profile")
data class UserEntity(
    @PrimaryKey
    val nxId: String,
    val fullName: String,
    val phoneNumber: String,
    val email: String? = null,
    val avatarUrl: String? = null,
    val age: Int?,
    val gender: String?,
    val bio: String?,
    val publicKeyHex: String,
    val isVerified: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

fun UserEntity.toDomain(fallbackAvatarUrl: String? = null): UserProfile {
    return UserProfile(
        nxId = nxId,
        fullName = fullName,
        phoneNumber = phoneNumber,
        email = email,
        avatarUrl = avatarUrl ?: fallbackAvatarUrl,
        age = age,
        gender = gender,
        bio = bio,
        publicKeyHex = publicKeyHex,
        isVerified = isVerified,
        createdAt = createdAt
    )
}

fun UserProfile.toEntity(): UserEntity {
    return UserEntity(
        nxId = nxId,
        fullName = fullName,
        phoneNumber = phoneNumber,
        email = email,
        avatarUrl = avatarUrl,
        age = age,
        gender = gender,
        bio = bio,
        publicKeyHex = publicKeyHex,
        isVerified = isVerified,
        createdAt = createdAt
    )
}
