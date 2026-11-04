package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * State machine representing mutual relationship stages.
 */
enum class ContactStatus {
    REQUEST_SENT,     // User A sent, waiting for B (Chat input locked)
    REQUEST_RECEIVED, // User B received, can Accept/Reject
    MUTUAL_FRIEND,    // Bilateral trust established, full chat & calls unlocked
    BLOCKED,          // Silent network-level packet discard
    CONTACT_VERIFIED  // Day-0 Instant QR Verified Trust (Chat & Mesh unlocked)
}

@Entity(
    tableName = "contacts",
    indices = [Index(value = ["nxId"], unique = true)]
)
data class ContactEntity(
    @PrimaryKey val nxId: String,
    val displayName: String,
    val publicKeyHex: String,
    val introMessage: String?,
    val status: ContactStatus,
    val blePeerId: String? = null,
    val createdAtTimestamp: Long = System.currentTimeMillis()
)
