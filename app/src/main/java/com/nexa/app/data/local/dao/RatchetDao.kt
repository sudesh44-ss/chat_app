package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.RatchetStateEntity
import com.nexa.app.data.local.entity.SkippedKeyEntity

@Dao
interface RatchetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: RatchetStateEntity): Long

    @Query("SELECT * FROM ratchet_sessions WHERE contactNxId = :contactNxId LIMIT 1")
    suspend fun getSession(contactNxId: String): RatchetStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkippedKey(key: SkippedKeyEntity): Long

    @Query("SELECT * FROM skipped_message_keys WHERE contactNxId = :contactNxId AND sequenceNumber = :seq LIMIT 1")
    suspend fun getSkippedKey(contactNxId: String, seq: Int): SkippedKeyEntity?

    @Query("DELETE FROM skipped_message_keys WHERE contactNxId = :contactNxId AND sequenceNumber = :seq")
    suspend fun deleteSkippedKey(contactNxId: String, seq: Int): Int

    @Query("DELETE FROM skipped_message_keys WHERE createdAtTimestamp < :thresholdTimestamp")
    suspend fun purgeOldSkippedKeys(thresholdTimestamp: Long): Int
}
