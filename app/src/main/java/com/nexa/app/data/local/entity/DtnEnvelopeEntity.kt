package com.nexa.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dtn_envelopes",
    indices = [
        Index(value = ["blindTokenHex"]),
        Index(value = ["expiresAtEpochSec"]),
        Index(value = ["receivedAtTimestamp"]),
        Index(value = ["custodyState"])
    ]
)
data class DtnEnvelopeEntity(
    @PrimaryKey val envelopeIdHex: String,
    val blindTokenHex: String,
    val hopLimit: Int,
    val rawEnvelopeBytes: ByteArray,
    val expiresAtEpochSec: Long,
    val receivedAtTimestamp: Long = System.currentTimeMillis(),
    val custodyState: String = "CUSTODY_ACCEPTED",
    val custodianNodeId: String = "",
    val nextHopNodeId: String? = null,
    val transferredAtTimestamp: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DtnEnvelopeEntity
        if (envelopeIdHex != other.envelopeIdHex) return false
        if (blindTokenHex != other.blindTokenHex) return false
        if (hopLimit != other.hopLimit) return false
        if (!rawEnvelopeBytes.contentEquals(other.rawEnvelopeBytes)) return false
        if (expiresAtEpochSec != other.expiresAtEpochSec) return false
        if (receivedAtTimestamp != other.receivedAtTimestamp) return false
        if (custodyState != other.custodyState) return false
        if (custodianNodeId != other.custodianNodeId) return false
        if (nextHopNodeId != other.nextHopNodeId) return false
        if (transferredAtTimestamp != other.transferredAtTimestamp) return false
        return true
    }

    override fun hashCode(): Int {
        var result = envelopeIdHex.hashCode()
        result = 31 * result + blindTokenHex.hashCode()
        result = 31 * result + hopLimit
        result = 31 * result + rawEnvelopeBytes.contentHashCode()
        result = 31 * result + expiresAtEpochSec.hashCode()
        result = 31 * result + receivedAtTimestamp.hashCode()
        result = 31 * result + custodyState.hashCode()
        result = 31 * result + custodianNodeId.hashCode()
        result = 31 * result + (nextHopNodeId?.hashCode() ?: 0)
        result = 31 * result + (transferredAtTimestamp?.hashCode() ?: 0)
        return result
    }
}
