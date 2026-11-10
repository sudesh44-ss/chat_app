package com.nexa.app.core.sync

import android.util.Log
import com.nexa.app.core.proto.EncryptedMessagePayload
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.proto.PacketType
import com.nexa.app.core.proto.PeerSyncAction
import com.nexa.app.core.proto.PeerSyncPayload
import com.nexa.app.core.transport.NexaUniversalTransportIngest
import com.nexa.app.core.transport.TransportMedium
import com.nexa.app.data.local.dao.ContactDao
import com.nexa.app.data.local.dao.MessageDao
import com.nexa.app.data.local.dao.MessageDedupDao
import com.nexa.app.data.local.dao.PeerSyncCursorDao
import com.nexa.app.data.local.entity.MessageEntity
import com.nexa.app.data.local.entity.PeerSyncCursorEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Authenticated, bounded, resumable Anti-Entropy Peer Synchronization Engine.
 *
 * Implements pairwise message reconciliation across temporary network disconnections:
 * 1. Scope Enforcement: Strictly pairwise (myNxId <-> peerNxId).
 * 2. Authorization Guard: Both peers must be established contacts.
 * 3. Bounded Anti-Entropy: Maximum 50 candidate messages per exchange batch.
 * 4. Exact Deduplication Integration: Uses MessageDedupDao authoritative index.
 * 5. Resumable State: Progress persisted in peer_sync_cursors table.
 */
class PeerSyncEngine(
    private val myNxIdProvider: suspend () -> String,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val messageDedupDao: MessageDedupDao,
    private val peerSyncCursorDao: PeerSyncCursorDao,
    private val universalTransportIngest: NexaUniversalTransportIngest,
    private val packetSender: suspend (NexaWirePacket, TransportMedium) -> Boolean
) {

    private val sessionMutex = Mutex()
    private val activeSessions = ConcurrentHashMap<String, SyncSessionState>()

    data class SyncSessionState(
        val sessionId: String,
        val peerNxId: String,
        val conversationId: String,
        val medium: TransportMedium,
        val startedAt: Long = System.currentTimeMillis(),
        var lastActivity: Long = System.currentTimeMillis(),
        var isCompleted: Boolean = false
    )

    init {
        // Register this engine with universal ingestion pipeline
        universalTransportIngest.peerSyncHandler = { packet, medium ->
            handleInboundPeerSync(packet, medium)
        }
    }

    /**
     * Initiates anti-entropy synchronization with an authorized peer over a given transport medium.
     */
    suspend fun initiateSync(
        peerNxId: String,
        medium: TransportMedium
    ): String? = withContext(Dispatchers.IO) {
        val myNxId = myNxIdProvider()
        if (myNxId.isBlank() || peerNxId.isBlank() || myNxId == peerNxId) {
            Log.w(TAG, "[$medium] Rejected invalid sync initiation between '$myNxId' and '$peerNxId'")
            return@withContext null
        }

        // 1. Authorization check: Peer must exist in local contacts
        val contact = contactDao.getContact(peerNxId)
        if (contact == null) {
            Log.w(TAG, "[$medium] Cannot initiate sync: Peer '$peerNxId' is not an authorized contact")
            return@withContext null
        }

        val conversationId = deriveConversationId(myNxId, peerNxId)
        val cursor = peerSyncCursorDao.getCursor(conversationId)
        val cursorTimestamp = cursor?.lastSyncTimestamp ?: 0L
        val cursorSequence = cursor?.lastSyncSequence ?: 0L
        val sessionId = UUID.randomUUID().toString()

        val sessionState = SyncSessionState(
            sessionId = sessionId,
            peerNxId = peerNxId,
            conversationId = conversationId,
            medium = medium
        )
        activeSessions[sessionId] = sessionState

        val helloPayload = PeerSyncPayload(
            action = PeerSyncAction.SYNC_HELLO,
            sessionId = sessionId,
            conversationId = conversationId,
            cursorTimestamp = cursorTimestamp,
            cursorSequence = cursorSequence,
            syncTimestamp = System.currentTimeMillis()
        )

        val packet = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_PEER_SYNC,
            senderNxId = myNxId,
            recipientNxId = peerNxId,
            timestamp = System.currentTimeMillis(),
            peerSync = helloPayload
        )

        Log.i(TAG, "[$medium] Initiated SyncSession=$sessionId with peer=$peerNxId (cursorTimestamp=$cursorTimestamp)")
        val sent = packetSender(packet, medium)
        if (sent) sessionId else null
    }

    /**
     * Handles inbound PACKET_PEER_SYNC packets from any transport medium.
     */
    suspend fun handleInboundPeerSync(
        packet: NexaWirePacket,
        medium: TransportMedium
    ): Boolean = withContext(Dispatchers.IO) {
        val payload = packet.peerSync ?: run {
            Log.w(TAG, "[$medium] Dropped PACKET_PEER_SYNC with missing PeerSyncPayload")
            return@withContext false
        }

        val myNxId = myNxIdProvider()
        val peerNxId = packet.senderNxId
        val expectedConversationId = deriveConversationId(myNxId, peerNxId)

        // 1. Strict Scope Validation: conversationId must strictly match pairwise pair
        if (payload.conversationId != expectedConversationId) {
            Log.e(
                TAG,
                "[$medium] SECURITY ALERT: PeerSync scope violation! Claimed conversation='${payload.conversationId}', expected='$expectedConversationId'. Rejecting."
            )
            sendErrorAck(
                sessionId = payload.sessionId,
                targetPeer = peerNxId,
                conversationId = expectedConversationId,
                errorMessage = "UNAUTHORIZED_CONVERSATION_SCOPE",
                medium = medium
            )
            return@withContext false
        }

        // 2. Authorization Validation: Peer must be an established contact
        val contact = contactDao.getContact(peerNxId)
        if (contact == null) {
            Log.w(TAG, "[$medium] Dropped PeerSync packet from unauthorized peer '$peerNxId'")
            sendErrorAck(
                sessionId = payload.sessionId,
                targetPeer = peerNxId,
                conversationId = expectedConversationId,
                errorMessage = "CONTACT_NOT_FOUND",
                medium = medium
            )
            return@withContext false
        }

        when (payload.action) {
            PeerSyncAction.SYNC_HELLO -> handleSyncHello(packet, payload, medium)
            PeerSyncAction.SYNC_HELLO_ACK -> handleSyncHelloAck(packet, payload, medium)
            PeerSyncAction.SYNC_INVENTORY_REQ -> handleSyncInventoryReq(packet, payload, medium)
            PeerSyncAction.SYNC_INVENTORY_RESP -> handleSyncInventoryResp(packet, payload, medium)
            PeerSyncAction.SYNC_PULL_REQ -> handleSyncPullReq(packet, payload, medium)
            PeerSyncAction.SYNC_PULL_RESP -> handleSyncPullResp(packet, payload, medium)
            PeerSyncAction.SYNC_ACK -> handleSyncAck(packet, payload, medium)
            PeerSyncAction.SYNC_UNKNOWN -> false
        }
    }

    private suspend fun handleSyncHello(
        packet: NexaWirePacket,
        payload: PeerSyncPayload,
        medium: TransportMedium
    ): Boolean {
        val myNxId = myNxIdProvider()
        val conversationId = payload.conversationId
        val localCursor = peerSyncCursorDao.getCursor(conversationId)

        Log.i(TAG, "[$medium] Received SYNC_HELLO from ${packet.senderNxId} (peerCursor=${payload.cursorTimestamp})")

        // 1. Respond with SYNC_HELLO_ACK containing our local cursor
        val ackPayload = PeerSyncPayload(
            action = PeerSyncAction.SYNC_HELLO_ACK,
            sessionId = payload.sessionId,
            conversationId = conversationId,
            cursorTimestamp = localCursor?.lastSyncTimestamp ?: 0L,
            cursorSequence = localCursor?.lastSyncSequence ?: 0L,
            syncTimestamp = System.currentTimeMillis()
        )
        val ackPacket = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_PEER_SYNC,
            senderNxId = myNxId,
            recipientNxId = packet.senderNxId,
            timestamp = System.currentTimeMillis(),
            peerSync = ackPayload
        )
        packetSender(ackPacket, medium)

        // 2. Also send SYNC_INVENTORY_REQ to request missing messages since our cursor
        val reqPayload = PeerSyncPayload(
            action = PeerSyncAction.SYNC_INVENTORY_REQ,
            sessionId = payload.sessionId,
            conversationId = conversationId,
            cursorTimestamp = localCursor?.lastSyncTimestamp ?: 0L,
            cursorSequence = localCursor?.lastSyncSequence ?: 0L,
            syncTimestamp = System.currentTimeMillis()
        )
        val reqPacket = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_PEER_SYNC,
            senderNxId = myNxId,
            recipientNxId = packet.senderNxId,
            timestamp = System.currentTimeMillis(),
            peerSync = reqPayload
        )
        return packetSender(reqPacket, medium)
    }

    private suspend fun handleSyncHelloAck(
        packet: NexaWirePacket,
        payload: PeerSyncPayload,
        medium: TransportMedium
    ): Boolean {
        val myNxId = myNxIdProvider()
        val conversationId = payload.conversationId
        val localCursor = peerSyncCursorDao.getCursor(conversationId)

        Log.i(TAG, "[$medium] Received SYNC_HELLO_ACK from ${packet.senderNxId} (peerCursor=${payload.cursorTimestamp})")

        // Send SYNC_INVENTORY_REQ to fetch message inventory newer than our local cursor
        val reqPayload = PeerSyncPayload(
            action = PeerSyncAction.SYNC_INVENTORY_REQ,
            sessionId = payload.sessionId,
            conversationId = conversationId,
            cursorTimestamp = localCursor?.lastSyncTimestamp ?: 0L,
            cursorSequence = localCursor?.lastSyncSequence ?: 0L,
            syncTimestamp = System.currentTimeMillis()
        )
        val reqPacket = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_PEER_SYNC,
            senderNxId = myNxId,
            recipientNxId = packet.senderNxId,
            timestamp = System.currentTimeMillis(),
            peerSync = reqPayload
        )
        return packetSender(reqPacket, medium)
    }

    private suspend fun handleSyncInventoryReq(
        packet: NexaWirePacket,
        payload: PeerSyncPayload,
        medium: TransportMedium
    ): Boolean {
        val myNxId = myNxIdProvider()
        val conversationId = payload.conversationId

        // Query candidate message IDs newer than peer's cursor timestamp (bounded to MAX_BATCH_SIZE)
        val candidateUuids = messageDao.getMessageIdsAfterTimestamp(
            conversationId = conversationId,
            afterTimestamp = payload.cursorTimestamp,
            limit = MAX_BATCH_SIZE
        )

        Log.i(
            TAG,
            "[$medium] Responding to SYNC_INVENTORY_REQ from ${packet.senderNxId}: found ${candidateUuids.size} candidate messages"
        )

        val respPayload = PeerSyncPayload(
            action = PeerSyncAction.SYNC_INVENTORY_RESP,
            sessionId = payload.sessionId,
            conversationId = conversationId,
            messageIds = candidateUuids,
            syncTimestamp = System.currentTimeMillis()
        )
        val respPacket = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_PEER_SYNC,
            senderNxId = myNxId,
            recipientNxId = packet.senderNxId,
            timestamp = System.currentTimeMillis(),
            peerSync = respPayload
        )
        return packetSender(respPacket, medium)
    }

    private suspend fun handleSyncInventoryResp(
        packet: NexaWirePacket,
        payload: PeerSyncPayload,
        medium: TransportMedium
    ): Boolean {
        val myNxId = myNxIdProvider()
        val conversationId = payload.conversationId
        val candidates = payload.messageIds

        // Compute delta against local authoritative deduplication index
        val missingUuids = mutableListOf<String>()
        for (candidateId in candidates) {
            val exists = messageDedupDao.hasPacket(candidateId) > 0 || messageDao.getMessageByPacketId(candidateId) != null
            if (!exists) {
                missingUuids.add(candidateId)
            }
        }

        Log.i(
            TAG,
            "[$medium] Evaluated inventory from ${packet.senderNxId}: ${candidates.size} offered, ${missingUuids.size} missing"
        )

        if (missingUuids.isEmpty()) {
            // Already up to date! Update cursor and send SYNC_ACK
            val latestTimestamp = System.currentTimeMillis()
            peerSyncCursorDao.upsertCursor(
                PeerSyncCursorEntity(
                    conversationId = conversationId,
                    peerNxId = packet.senderNxId,
                    lastSyncTimestamp = latestTimestamp,
                    lastSyncedPacketId = candidates.lastOrNull(),
                    updatedAt = latestTimestamp
                )
            )
            return sendSyncAck(payload.sessionId, packet.senderNxId, conversationId, medium)
        }

        // Request missing message batch (bounded to MAX_BATCH_SIZE)
        val pullPayload = PeerSyncPayload(
            action = PeerSyncAction.SYNC_PULL_REQ,
            sessionId = payload.sessionId,
            conversationId = conversationId,
            messageIds = missingUuids.take(MAX_BATCH_SIZE),
            syncTimestamp = System.currentTimeMillis()
        )
        val pullPacket = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_PEER_SYNC,
            senderNxId = myNxId,
            recipientNxId = packet.senderNxId,
            timestamp = System.currentTimeMillis(),
            peerSync = pullPayload
        )
        return packetSender(pullPacket, medium)
    }

    private suspend fun handleSyncPullReq(
        packet: NexaWirePacket,
        payload: PeerSyncPayload,
        medium: TransportMedium
    ): Boolean {
        val myNxId = myNxIdProvider()
        val conversationId = payload.conversationId
        val requestedIds = payload.messageIds

        val envelopes = mutableListOf<ByteArray>()
        for (msgId in requestedIds) {
            val msgEntity = messageDao.getMessageByPacketId(msgId)
            if (msgEntity != null) {
                // Re-synthesize wire packet for the peer
                val wirePacket = NexaWirePacket(
                    packetId = msgEntity.packetId,
                    type = PacketType.PACKET_MESSAGE,
                    senderNxId = msgEntity.senderNxId,
                    recipientNxId = msgEntity.recipientNxId,
                    timestamp = msgEntity.timestamp,
                    message = EncryptedMessagePayload(
                        ciphertext = msgEntity.plainTextContent.toByteArray(Charsets.UTF_8)
                    )
                )
                envelopes.add(wirePacket.encode())
            }
        }

        Log.i(
            TAG,
            "[$medium] Responding to SYNC_PULL_REQ from ${packet.senderNxId}: packaged ${envelopes.size} envelopes"
        )

        val respPayload = PeerSyncPayload(
            action = PeerSyncAction.SYNC_PULL_RESP,
            sessionId = payload.sessionId,
            conversationId = conversationId,
            envelopes = envelopes,
            syncTimestamp = System.currentTimeMillis()
        )
        val respPacket = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_PEER_SYNC,
            senderNxId = myNxId,
            recipientNxId = packet.senderNxId,
            timestamp = System.currentTimeMillis(),
            peerSync = respPayload
        )
        return packetSender(respPacket, medium)
    }

    private suspend fun handleSyncPullResp(
        packet: NexaWirePacket,
        payload: PeerSyncPayload,
        medium: TransportMedium
    ): Boolean {
        val conversationId = payload.conversationId
        val envelopes = payload.envelopes
        var maxObservedTimestamp = 0L
        var lastPacketId: String? = null

        Log.i(TAG, "[$medium] Ingesting ${envelopes.size} missing envelopes from ${packet.senderNxId}")

        for (envBytes in envelopes) {
            try {
                val wirePacket = NexaWirePacket.decode(envBytes)
                if (wirePacket.timestamp > maxObservedTimestamp) {
                    maxObservedTimestamp = wirePacket.timestamp
                    lastPacketId = wirePacket.packetId
                }
                // Ingest through universal pipeline for atomic deduplication reservation and validation
                universalTransportIngest.ingestWirePacket(wirePacket, medium)
            } catch (e: Exception) {
                Log.w(TAG, "[$medium] Failed to decode synced envelope: ${e.message}")
            }
        }

        // Update local sync cursor
        if (maxObservedTimestamp > 0L) {
            peerSyncCursorDao.upsertCursor(
                PeerSyncCursorEntity(
                    conversationId = conversationId,
                    peerNxId = packet.senderNxId,
                    lastSyncTimestamp = maxObservedTimestamp,
                    lastSyncedPacketId = lastPacketId,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        // Send SYNC_ACK to finalize batch
        return sendSyncAck(payload.sessionId, packet.senderNxId, conversationId, medium)
    }

    private suspend fun handleSyncAck(
        packet: NexaWirePacket,
        payload: PeerSyncPayload,
        medium: TransportMedium
    ): Boolean {
        Log.i(
            TAG,
            "[$medium] Received SYNC_ACK for Session=${payload.sessionId} from ${packet.senderNxId}. Error='${payload.errorMessage}'"
        )
        activeSessions[payload.sessionId]?.let { session ->
            session.isCompleted = true
            session.lastActivity = System.currentTimeMillis()
        }
        return true
    }

    private suspend fun sendSyncAck(
        sessionId: String,
        targetPeer: String,
        conversationId: String,
        medium: TransportMedium
    ): Boolean {
        val myNxId = myNxIdProvider()
        val ackPayload = PeerSyncPayload(
            action = PeerSyncAction.SYNC_ACK,
            sessionId = sessionId,
            conversationId = conversationId,
            syncTimestamp = System.currentTimeMillis()
        )
        val packet = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_PEER_SYNC,
            senderNxId = myNxId,
            recipientNxId = targetPeer,
            timestamp = System.currentTimeMillis(),
            peerSync = ackPayload
        )
        return packetSender(packet, medium)
    }

    private suspend fun sendErrorAck(
        sessionId: String,
        targetPeer: String,
        conversationId: String,
        errorMessage: String,
        medium: TransportMedium
    ): Boolean {
        val myNxId = myNxIdProvider()
        val errorPayload = PeerSyncPayload(
            action = PeerSyncAction.SYNC_ACK,
            sessionId = sessionId,
            conversationId = conversationId,
            syncTimestamp = System.currentTimeMillis(),
            errorMessage = errorMessage
        )
        val packet = NexaWirePacket(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.PACKET_PEER_SYNC,
            senderNxId = myNxId,
            recipientNxId = targetPeer,
            timestamp = System.currentTimeMillis(),
            peerSync = errorPayload
        )
        return packetSender(packet, medium)
    }

    private fun deriveConversationId(id1: String, id2: String): String {
        return if (id1 < id2) "${id1}_$id2" else "${id2}_$id1"
    }

    companion object {
        private const val TAG = "PeerSyncEngine"
        const val MAX_BATCH_SIZE = 50
    }
}
