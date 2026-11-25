package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.SmsInboundPartEntity
import com.nexa.app.data.local.entity.SmsKeyMaterialEntity
import com.nexa.app.data.local.entity.SmsPacketEntity
import com.nexa.app.data.local.entity.SmsSessionEntity

@Dao
interface SmsSessionDao {

    @Query("SELECT * FROM sms_sessions WHERE peerNxId = :peerNxId LIMIT 1")
    suspend fun getSession(peerNxId: String): SmsSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SmsSessionEntity): Long

    @Query("UPDATE sms_sessions SET sendCounter = :counter, lastUsedAt = :now WHERE peerNxId = :peerNxId")
    suspend fun updateSendCounter(peerNxId: String, counter: Long, now: Long): Int

    @Query("UPDATE sms_sessions SET ackSendCounter = :counter, lastUsedAt = :now WHERE peerNxId = :peerNxId")
    suspend fun updateAckSendCounter(peerNxId: String, counter: Long, now: Long): Int

    @Query("UPDATE sms_sessions SET recvHighestCounter = :highest, recvBitmap = :bitmap, lastUsedAt = :now WHERE peerNxId = :peerNxId")
    suspend fun updateReceiveWindow(peerNxId: String, highest: Long, bitmap: Long, now: Long): Int

    @Query("UPDATE sms_sessions SET ackRecvHighestCounter = :highest, ackRecvBitmap = :bitmap, lastUsedAt = :now WHERE peerNxId = :peerNxId")
    suspend fun updateAckReceiveWindow(peerNxId: String, highest: Long, bitmap: Long, now: Long): Int

    @Query("UPDATE sms_sessions SET isRevoked = 1, lastUsedAt = :now WHERE peerNxId = :peerNxId")
    suspend fun revokeSession(peerNxId: String, now: Long): Int

    @Query("DELETE FROM sms_sessions WHERE peerNxId = :peerNxId")
    suspend fun deleteSession(peerNxId: String): Int

    @Query("SELECT * FROM sms_sessions")
    suspend fun getAllSessions(): List<SmsSessionEntity>
}

@Dao
interface SmsKeyMaterialDao {

    @Query("SELECT * FROM sms_key_material WHERE keyId = :keyId LIMIT 1")
    suspend fun get(keyId: String): SmsKeyMaterialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(material: SmsKeyMaterialEntity): Long
}

@Dao
interface SmsPacketDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(packet: SmsPacketEntity): Long

    @Query("SELECT * FROM sms_packets WHERE messageId = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: String): SmsPacketEntity?

    @Query("SELECT * FROM sms_packets WHERE packetId = :packetId LIMIT 1")
    suspend fun getByPacketId(packetId: String): SmsPacketEntity?

    @Query("UPDATE sms_packets SET handoffState = :state, updatedAt = :now, failureKind = :failureKind WHERE messageId = :messageId")
    suspend fun updateState(messageId: String, state: String, now: Long, failureKind: String?): Int

    @Query("SELECT * FROM sms_packets WHERE direction = 'OUTBOUND' AND handoffState IN ('QUEUED', 'HANDED_OFF') ORDER BY createdAt ASC LIMIT 50")
    suspend fun getPendingOutbound(): List<SmsPacketEntity>

    @Query("DELETE FROM sms_packets WHERE createdAt < :before AND handoffState IN ('DELIVERED_APP_ACK', 'CANCELLED')")
    suspend fun purgeSettledBefore(before: Long): Int
}

@Dao
interface SmsInboundPartDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(part: SmsInboundPartEntity): Long

    @Query("SELECT * FROM sms_inbound_parts WHERE messageId = :messageId AND senderNxId = :senderNxId ORDER BY partIndex ASC")
    suspend fun partsFor(messageId: String, senderNxId: String): List<SmsInboundPartEntity>

    @Query("DELETE FROM sms_inbound_parts WHERE messageId = :messageId AND senderNxId = :senderNxId")
    suspend fun deleteMessage(messageId: String, senderNxId: String): Int

    @Query("DELETE FROM sms_inbound_parts WHERE receivedAt < :beforeTimestamp")
    suspend fun purgeExpired(beforeTimestamp: Long): Int

    @Query("SELECT COUNT(DISTINCT messageId || '::' || senderNxId) FROM sms_inbound_parts WHERE receivedAt >= :sinceTimestamp")
    suspend fun sessionCountSince(sinceTimestamp: Long): Int
}
