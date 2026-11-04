package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.MeshEnvelopeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MeshOutboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEnvelope(envelope: MeshEnvelopeEntity): Long

    @Query("SELECT * FROM mesh_outbox WHERE status = 'OUTBOX_HOLD' ORDER BY createdAt ASC")
    fun getPendingEnvelopes(): Flow<List<MeshEnvelopeEntity>>

    @Query("SELECT * FROM mesh_outbox WHERE status = 'OUTBOX_HOLD' ORDER BY createdAt ASC")
    suspend fun getPendingEnvelopesList(): List<MeshEnvelopeEntity>

    @Query("SELECT * FROM mesh_outbox WHERE envelopeId = :id LIMIT 1")
    suspend fun getEnvelope(id: String): MeshEnvelopeEntity?

    @Query("DELETE FROM mesh_outbox WHERE envelopeId = :id")
    suspend fun deleteEnvelope(id: String): Int

    @Query("DELETE FROM mesh_outbox WHERE expiresAt < :nowEpoch")
    suspend fun deleteExpiredEnvelopes(nowEpoch: Long): Int
}
