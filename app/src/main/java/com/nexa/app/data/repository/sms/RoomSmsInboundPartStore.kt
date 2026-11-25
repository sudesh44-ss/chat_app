package com.nexa.app.data.repository.sms

import android.util.Log
import com.nexa.app.core.sms.nx1.Nx1Base64Url
import com.nexa.app.core.sms.nx1.Nx1InboundPart
import com.nexa.app.core.sms.nx1.SmsInboundPartStore
import com.nexa.app.data.local.dao.SmsInboundPartDao
import com.nexa.app.data.local.entity.SmsInboundPartEntity

/**
 * SQLCipher-backed multipart reassembly buffer.
 * Survives process death, so partially received NX1 messages can complete after a restart.
 */
class RoomSmsInboundPartStore(
    private val partDao: SmsInboundPartDao
) : SmsInboundPartStore {

    override suspend fun insertIfAbsent(part: Nx1InboundPart): Boolean {
        val rowId = partDao.insertIfAbsent(
            SmsInboundPartEntity(
                messageId = part.messageId,
                senderNxId = part.senderNxId,
                partIndex = part.partIndex,
                totalParts = part.totalParts,
                payloadBase64 = Nx1Base64Url.encode(part.payload),
                receivedAt = part.receivedAt
            )
        )
        return rowId != -1L
    }

    override suspend fun partsFor(messageId: String, senderNxId: String): List<Nx1InboundPart> =
        partDao.partsFor(messageId, senderNxId).mapNotNull { entity ->
            val payload = Nx1Base64Url.decode(entity.payloadBase64)
            if (payload == null) {
                Log.w(TAG, "Dropped undecodable stored SMS part")
                null
            } else {
                Nx1InboundPart(
                    messageId = entity.messageId,
                    senderNxId = entity.senderNxId,
                    partIndex = entity.partIndex,
                    totalParts = entity.totalParts,
                    payload = payload,
                    receivedAt = entity.receivedAt
                )
            }
        }

    override suspend fun deleteMessage(messageId: String, senderNxId: String) {
        partDao.deleteMessage(messageId, senderNxId)
    }

    override suspend fun purgeExpired(beforeTimestamp: Long): Int = partDao.purgeExpired(beforeTimestamp)

    override suspend fun sessionCountSince(sinceTimestamp: Long): Int = partDao.sessionCountSince(sinceTimestamp)

    companion object {
        private const val TAG = "RoomSmsPartStore"
    }
}
