package com.nexa.app.data.remote.dto

/**
 * A device/session linked to the authenticated NEXA account.
 * Returned by GET /api/v1/devices.
 *
 * Revoked devices are returned for history. They are never treated as active.
 */
data class DeviceInfoDto(
    val deviceId: String,
    val deviceName: String,
    val nxId: String? = null,
    val isRevoked: Boolean = false,
    val revokedAt: Long = 0L,
    val revokedReason: String? = null,
    val createdAt: Long = 0L,
    val lastActiveAt: Long = 0L
)
