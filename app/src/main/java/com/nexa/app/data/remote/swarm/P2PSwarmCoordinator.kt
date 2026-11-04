package com.nexa.app.data.remote.swarm

import android.util.Log
import com.nexa.app.data.local.dao.SwarmCacheDao
import com.nexa.app.data.local.entity.FileMetadataEntity
import com.nexa.app.data.local.entity.FileStatus
import com.nexa.app.data.remote.signaling.SwarmSignalingClient
import com.nexa.app.data.remote.signaling.SwarmSignalingDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class P2PSwarmCoordinator(
    private val signalingClient: SwarmSignalingClient,
    private val swarmCacheDao: SwarmCacheDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // fileHash -> List of seeder peer IDs
    private val knownSeeders = ConcurrentHashMap<String, MutableSet<String>>()

    private val _metadataEvents = MutableSharedFlow<FileMetadataEntity>(extraBufferCapacity = 32)
    val metadataEvents: SharedFlow<FileMetadataEntity> = _metadataEvents.asSharedFlow()

    init {
        observeSwarmSignaling()
    }

    private fun observeSwarmSignaling() {
        scope.launch {
            signalingClient.incomingMessages.collect { msg ->
                try {
                    when (msg) {
                        is SwarmSignalingDto.SwarmResponse -> {
                            val seeders = knownSeeders.computeIfAbsent(msg.fileHash) { ConcurrentHashMap.newKeySet() }
                            seeders.addAll(msg.seeders)
                            Log.i(TAG, "Discovered ${msg.seeders.size} seeders for hash ${msg.fileHash}")
                        }

                        is SwarmSignalingDto.MetadataBroadcast -> {
                            val entity = FileMetadataEntity(
                                fileHash = msg.fileHash,
                                conversationId = msg.conversationId,
                                senderNxId = msg.senderNxId,
                                fileName = msg.fileName,
                                fileSizeBytes = msg.fileSizeBytes,
                                totalChunks = msg.totalChunks,
                                blurHash = msg.blurHash,
                                status = FileStatus.METADATA_ONLY
                            )
                            swarmCacheDao.upsertMetadata(entity)
                            _metadataEvents.emit(entity)
                            Log.i(TAG, "Persisted metadata envelope for ${msg.fileName} (~1 KB, 0 bytes file payload)")
                        }

                        is SwarmSignalingDto.SwarmAdvertise -> {
                            val seeders = knownSeeders.computeIfAbsent(msg.fileHash) { ConcurrentHashMap.newKeySet() }
                            seeders.add(msg.peerId)
                        }

                        else -> Unit
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling swarm signaling", e)
                }
            }
        }
    }

    fun querySeeders(fileHash: String, myPeerId: String) {
        val query = SwarmSignalingDto.SwarmQuery(fileHash, myPeerId)
        signalingClient.sendMessage(query)
    }

    fun advertiseSeeding(fileHash: String, myPeerId: String, totalChunks: Int) {
        val advertise = SwarmSignalingDto.SwarmAdvertise(fileHash, myPeerId, totalChunks)
        signalingClient.sendMessage(advertise)
    }

    fun broadcastMetadata(entity: FileMetadataEntity) {
        val broadcast = SwarmSignalingDto.MetadataBroadcast(
            fileHash = entity.fileHash,
            conversationId = entity.conversationId,
            senderNxId = entity.senderNxId,
            fileName = entity.fileName,
            fileSizeBytes = entity.fileSizeBytes,
            totalChunks = entity.totalChunks,
            blurHash = entity.blurHash
        )
        signalingClient.sendMessage(broadcast)
    }

    fun getKnownSeeders(fileHash: String): List<String> {
        return knownSeeders[fileHash]?.toList().orEmpty()
    }

    companion object {
        private const val TAG = "P2PSwarmCoordinator"
    }
}
