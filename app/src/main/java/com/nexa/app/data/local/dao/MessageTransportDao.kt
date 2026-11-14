package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.MessageTransportEntity

@Dao
interface MessageTransportDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordObservation(observation: MessageTransportEntity): Long

    @Query("SELECT * FROM message_transport_observations WHERE packetId = :packetId ORDER BY observedAt ASC")
    suspend fun getObservationsForMessage(packetId: String): List<MessageTransportEntity>

    @Query("SELECT transportMedium FROM message_transport_observations WHERE packetId = :packetId ORDER BY observedAt ASC")
    suspend fun getObservedMediums(packetId: String): List<String>
}
