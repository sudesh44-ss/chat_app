package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.DeviceSyncEntity

@Dao
interface DeviceSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncEntry(entry: DeviceSyncEntity): Long

    @Query("SELECT * FROM device_sync_log WHERE isSynced = 0 ORDER BY sequenceNumber ASC")
    suspend fun getPendingSyncEntries(): List<DeviceSyncEntity>

    @Query("UPDATE device_sync_log SET isSynced = 1 WHERE syncId = :syncId")
    suspend fun markAsSynced(syncId: String): Int

    @Query("SELECT MAX(sequenceNumber) FROM device_sync_log")
    suspend fun getLatestSequenceNumber(): Long?

    @Query("DELETE FROM device_sync_log WHERE isSynced = 1 AND timestamp < :olderThanTimestamp")
    suspend fun purgeSyncedEntriesOlderThan(olderThanTimestamp: Long): Int
}
