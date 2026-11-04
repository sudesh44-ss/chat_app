package com.nexa.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexa.app.data.local.entity.DtnEnvelopeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DtnEnvelopeDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEnvelope(envelope: DtnEnvelopeEntity): Long

    @Query("SELECT * FROM dtn_envelopes WHERE expiresAtEpochSec > :currentEpochSec ORDER BY receivedAtTimestamp ASC LIMIT 50")
    suspend fun getActiveEnvelopesForSync(currentEpochSec: Long): List<DtnEnvelopeEntity>

    @Query("SELECT rawEnvelopeBytes FROM dtn_envelopes WHERE blindTokenHex = :tokenHex LIMIT 20")
    suspend fun findMatchingEnvelopes(tokenHex: String): List<ByteArray>

    @Query("DELETE FROM dtn_envelopes WHERE expiresAtEpochSec <= :currentEpochSec")
    suspend fun purgeExpiredEnvelopes(currentEpochSec: Long): Int

    // FIFO Quota Control: Evict the oldest envelopes when threshold is reached
    @Query("DELETE FROM dtn_envelopes WHERE envelopeIdHex IN (SELECT envelopeIdHex FROM dtn_envelopes ORDER BY receivedAtTimestamp ASC LIMIT :count)")
    suspend fun pruneOldest(count: Int): Int

    @Query("DELETE FROM dtn_envelopes WHERE envelopeIdHex = :envelopeIdHex")
    suspend fun deleteEnvelope(envelopeIdHex: String): Int

    @Query("SELECT COUNT(*) FROM dtn_envelopes")
    suspend fun getTotalCachedCount(): Int

    @Query("SELECT COUNT(*) FROM dtn_envelopes")
    fun getCachedCountFlow(): Flow<Int>
}
