package com.nexa.app.data.remote.dto

data class DiscoveredUserDto(
    val nxId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val publicKeyHex: String,
    val isConnected: Boolean = false,
    val isPending: Boolean = false,
    val isBlocked: Boolean = false,
    val createdAt: Long = 0L
)

data class ConnectionRequestItemDto(
    val id: String,
    val senderNxId: String,
    val recipientNxId: String,
    val introMessage: String? = null,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long,
    val profile: DiscoveredUserDto? = null
)

data class ConnectionItemDto(
    val nxId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val publicKeyHex: String,
    val isConnected: Boolean = true,
    val createdAt: Long = 0L
)

data class BlockedUserItemDto(
    val nxId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val publicKeyHex: String,
    val createdAt: Long = 0L
)
