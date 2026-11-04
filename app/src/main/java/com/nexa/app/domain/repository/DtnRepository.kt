package com.nexa.app.domain.repository

import com.nexa.app.core.dtn.DtnEnvelope
import com.nexa.app.data.remote.dtn.MeshState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface DtnRepository {
    val meshState: StateFlow<MeshState>
    val heldEnvelopeCount: Flow<Int>

    fun startMesh()
    fun stopMesh()

    suspend fun createAndCacheEnvelope(
        recipientNxId: String,
        recipientPublicKeyHex: String,
        plainText: String
    ): Result<DtnEnvelope>

    suspend fun ingestInboundEnvelope(envelope: DtnEnvelope): Boolean
    suspend fun checkAndConsumeEnvelopesForMe(): List<DtnEnvelope>
    suspend fun triggerOpportunisticDump(): Result<Int>
    fun whitelistMeshPeer(peerId: String)
}
