package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Encrypted DTN Store-and-Forward envelope entity (Scenario 6 & 17).
 * Holds custody payloads for up to 48 hours until moving Data Mules confirm custody.
 */
@Entity(tableName = "mesh_outbox")
data class MeshEnvelopeEntity(
    @PrimaryKey val envelopeId: String,
    val destinationId: String,
    val payload: ByteArray,
    val status: String = "OUTBOX_HOLD",
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + (48 * 3600 * 1000L) // 48h TTL
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MeshEnvelopeEntity
        return envelopeId == other.envelopeId
    }

    override fun hashCode(): Int = envelopeId.hashCode()
}
