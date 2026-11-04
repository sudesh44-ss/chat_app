package com.nexa.app.core.mesh.dtn

import com.nexa.app.data.local.dao.MeshOutboxDao
import com.nexa.app.data.local.entity.MeshEnvelopeEntity
import java.util.concurrent.TimeUnit

/**
 * DTN Custody Transfer & Store-and-Forward Manager (Scenario 17).
 * Retains payloads in encrypted SQLite store until a moving Data Mule returns CUSTODY_ACCEPTED.
 * Purges un-forwarded envelopes upon 48-hour TTL expiry.
 */
class CustodyTransferManager(
    private val outboxDao: MeshOutboxDao
) {
    private val defaultTtlMs = TimeUnit.HOURS.toMillis(48) // 48-Hour TTL

    suspend fun holdForCustody(
        envelopeId: String,
        destinationId: String,
        rawPayload: ByteArray
    ) {
        val now = System.currentTimeMillis()
        val entity = MeshEnvelopeEntity(
            envelopeId = envelopeId,
            destinationId = destinationId,
            payload = rawPayload,
            status = "OUTBOX_HOLD",
            createdAt = now,
            expiresAt = now + defaultTtlMs
        )
        outboxDao.insertEnvelope(entity)
    }

    suspend fun onCustodyAckReceived(envelopeId: String) {
        // Data Mule accepted custody; safely release from local sender storage
        outboxDao.deleteEnvelope(envelopeId)
    }

    suspend fun purgeExpiredEnvelopes() {
        val now = System.currentTimeMillis()
        outboxDao.deleteExpiredEnvelopes(now)
    }
}
