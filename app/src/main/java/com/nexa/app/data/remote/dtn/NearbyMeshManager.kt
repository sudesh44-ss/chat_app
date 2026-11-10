package com.nexa.app.data.remote.dtn

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.nexa.app.core.dtn.DtnBloomFilter
import com.nexa.app.core.dtn.DtnConstants
import com.nexa.app.core.dtn.DtnEnvelope
import com.nexa.app.data.local.dao.DtnEnvelopeDao
import com.nexa.app.data.local.entity.DtnEnvelopeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

sealed interface MeshState {
    data object Idle : MeshState
    data object AdvertisingAndDiscovering : MeshState
    data class Connected(val peerCount: Int) : MeshState
    data class Error(val message: String) : MeshState
}

/**
 * P2P Delay-Tolerant Networking Manager leveraging Google Play Services Nearby Connections.
 * Uses Strategy.P2P_CLUSTER for autonomous, decentralized ad-hoc peer clustering.
 * Executes a 64-Byte Bloom Filter handshake to suppress redundant data mule replication.
 */
class NearbyMeshManager(
    private val context: Context,
    private val envelopeDao: DtnEnvelopeDao,
    private val onEnvelopeReceivedForMe: (suspend (DtnEnvelope) -> Unit)? = null
) {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _meshState = MutableStateFlow<MeshState>(MeshState.Idle)
    val meshState: StateFlow<MeshState> = _meshState.asStateFlow()

    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val whitelistedPeers = ConcurrentHashMap.newKeySet<String>()
    private var isRunning = false

    fun whitelistPeer(peerId: String) {
        whitelistedPeers.add(peerId)
    }

    fun isPeerWhitelisted(peerId: String): Boolean = whitelistedPeers.contains(peerId)

    /**
     * Starts simultaneous Advertising and Discovery for opportunistic data muling.
     */
    fun start() {
        if (isRunning) return

        if (!hasRequiredPermissions()) {
            val msg = "Missing required Bluetooth / Location permissions for Nearby Mesh"
            Log.w(TAG, msg)
            _meshState.value = MeshState.Error(msg)
            return
        }

        try {
            isRunning = true
            _meshState.value = MeshState.AdvertisingAndDiscovering

            startAdvertising()
            startDiscovery()
            Log.i(TAG, "NEXA DTN Mesh initialized with ServiceId: ${DtnConstants.SERVICE_ID}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Nearby Mesh", e)
            _meshState.value = MeshState.Error(e.localizedMessage ?: "Mesh startup failed")
            stop()
        }
    }

    /**
     * Stops all radios, discovery, advertising, and releases peer connections.
     */
    fun stop() {
        isRunning = false
        try {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
            connectedEndpoints.clear()
            _meshState.value = MeshState.Idle
            Log.i(TAG, "NEXA DTN Mesh stopped cleanly")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Nearby client", e)
        }
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        client.startAdvertising(
            "NX_MULE",
            DtnConstants.SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnFailureListener { e ->
            Log.e(TAG, "Advertising initiation failed", e)
            _meshState.value = MeshState.Error("Advertising failed: ${e.message}")
        }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        client.startDiscovery(
            DtnConstants.SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnFailureListener { e ->
            Log.e(TAG, "Discovery initiation failed", e)
            _meshState.value = MeshState.Error("Discovery failed: ${e.message}")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Discovered nearby DTN peer: $endpointId (${info.endpointName})")
            // Automatically initiate connection to discovered cluster peer
            client.requestConnection(
                "NX_MULE",
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener { e ->
                Log.w(TAG, "Failed to request connection to $endpointId", e)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Lost nearby DTN peer: $endpointId")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Accepting opportunistic connection from $endpointId (${info.endpointName})")
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                _meshState.value = MeshState.Connected(connectedEndpoints.size)
                Log.i(TAG, "Connected to DTN peer $endpointId. Connected total: ${connectedEndpoints.size}")

                // Phase 1 Handshake: Send 64-byte Bloom filter of held envelope IDs
                scope.launch {
                    try {
                        val nowSec = System.currentTimeMillis() / 1000L
                        val heldEnvelopes = envelopeDao.getActiveEnvelopesForSync(nowSec)
                        val filter = DtnBloomFilter()
                        heldEnvelopes.forEach { filter.add(it.envelopeIdHex) }

                        val filterPayload = Payload.fromBytes(filter.toByteArray())
                        client.sendPayload(endpointId, filterPayload)
                        Log.d(TAG, "Sent 64-byte Bloom filter (${heldEnvelopes.size} held items) to $endpointId")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error generating or sending Bloom filter to $endpointId", e)
                    }
                }
            } else {
                Log.w(TAG, "Connection failed to $endpointId: ${result.status.statusCode}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            Log.d(TAG, "Disconnected from $endpointId. Remaining peers: ${connectedEndpoints.size}")
            if (connectedEndpoints.isEmpty() && isRunning) {
                _meshState.value = MeshState.AdvertisingAndDiscovering
            } else if (connectedEndpoints.isNotEmpty()) {
                _meshState.value = MeshState.Connected(connectedEndpoints.size)
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            scope.launch {
                handleInboundPayload(endpointId, bytes)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Optional telemetry progress
        }
    }

    private suspend fun handleInboundPayload(endpointId: String, bytes: ByteArray) {
        // Case 1: Inbound 64-byte Bloom Filter from peer -> Send delta envelopes peer lacks
        if (bytes.size == DtnConstants.BLOOM_FILTER_BYTE_SIZE) {
            try {
                val peerFilter = DtnBloomFilter.fromByteArray(bytes)
                val nowSec = System.currentTimeMillis() / 1000L
                val myActiveEnvelopes = envelopeDao.getActiveEnvelopesForSync(nowSec)

                val deltaItems = myActiveEnvelopes.filter { item ->
                    !peerFilter.contains(item.envelopeIdHex) && item.hopLimit > 0
                }

                Log.d(TAG, "Replicating ${deltaItems.size} delta envelopes to peer $endpointId")
                for (item in deltaItems) {
                    client.sendPayload(endpointId, Payload.fromBytes(item.rawEnvelopeBytes))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing peer Bloom filter from $endpointId", e)
            }
            return
        }

        // Case 2: Inbound raw DtnEnvelope
        val envelope = DtnEnvelope.deserialize(bytes)
        if (envelope == null) {
            Log.w(TAG, "Dropped malformed or truncated inbound DTN payload of size ${bytes.size} from $endpointId")
            return
        }

        if (envelope.hopLimit <= 0) {
            Log.d(TAG, "Dropped envelope ${envelope.envelopeIdHex}: Hop limit reached zero")
            return
        }

        // Check if envelope has already expired
        val nowSec = System.currentTimeMillis() / 1000L
        if (envelope.expiresAtEpochSec <= nowSec) {
            Log.d(TAG, "Dropped envelope ${envelope.envelopeIdHex}: TTL expired")
            return
        }

        // Strict FIFO Quota Enforcement: Limit to 500 cached items
        val currentCount = envelopeDao.getTotalCachedCount()
        if (currentCount >= DtnConstants.MAX_CACHE_ENVELOPE_COUNT) {
            val pruned = envelopeDao.pruneOldest(DtnConstants.PRUNE_BATCH_SIZE)
            Log.i(TAG, "FIFO Storage quota exceeded ($currentCount items). Pruned $pruned oldest envelopes")
        }

        // Decrement hop limit for forward custody using NXDT mutable metadata update
        val decrementedEnvelope = envelope.withDecrementedHop(nowSec)
        if (decrementedEnvelope == null) {
            Log.d(TAG, "Dropped envelope ${envelope.envelopeIdHex}: Hop limit reached zero or exceeded bounds")
            return
        }

        val entity = DtnEnvelopeEntity(
            envelopeIdHex = decrementedEnvelope.envelopeIdHex,
            blindTokenHex = decrementedEnvelope.blindTokenHex,
            hopLimit = decrementedEnvelope.hopLimit.toInt(),
            rawEnvelopeBytes = decrementedEnvelope.serialize(),
            expiresAtEpochSec = decrementedEnvelope.expiresAtEpochSec
        )

        envelopeDao.insertEnvelope(entity)
        Log.i(
            TAG,
            "Stored DTN envelope [${decrementedEnvelope.envelopeIdHex.take(8)}...] with blind token [${decrementedEnvelope.blindTokenHex.take(8)}...], remaining hops: ${decrementedEnvelope.hopLimit}"
        )

        // Trigger local recipient callback if registered
        onEnvelopeReceivedForMe?.invoke(decrementedEnvelope)
    }

    private fun hasRequiredPermissions(): Boolean {
        // Location permission check
        val hasLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // Bluetooth permissions check on Android 12+ (API 31+)
        val hasBluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        // Wi-Fi Nearby Devices check on Android 13+ (API 33+)
        val hasWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return hasLocation && hasBluetooth && hasWifi
    }

    companion object {
        private const val TAG = "NearbyMeshManager"
    }
}
