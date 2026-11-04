package com.nexa.app.domain.model

/**
 * Domain model representing a device address-book contact entry,
 * augmented with real-time NEXA user discovery status.
 *
 * Strict Privacy: Kept solely in local device memory/cache.
 * Full address-book entries are NEVER uploaded to any server.
 */
data class DeviceContact(
    val id: String,
    val displayName: String,
    val phoneNumber: String,
    val normalizedNumber: String,
    val isNexaUser: Boolean = false,
    val nexaNxId: String? = null,
    val nexaPublicKeyHex: String? = null,
    val avatarUri: String? = null
)
