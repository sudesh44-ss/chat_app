package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ratchet_sessions")
data class RatchetStateEntity(
    @PrimaryKey val contactNxId: String,
    val rootKeyHex: String,
    val senderChainKeyHex: String,
    val receiverChainKeyHex: String,
    val senderSeqNumber: Int,
    val receiverSeqNumber: Int,
    val localDhPrivateKeyHex: String,
    val localDhPublicKeyHex: String,
    val remoteDhPublicKeyHex: String,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)
