package com.nexa.app.core.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * PeerConnection lifecycle state machine for SCTP DataChannels and WebRTC P2P transfers.
 */
enum class PeerLifecycleState {
    NEW,
    INITIALIZING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    RECONNECTING,
    FAILED,
    CLOSING,
    CLOSED
}

/**
 * Diagnostic status distinguishing direct P2P, TURN relay, and failure states.
 */
enum class ConnectionDiagnostic {
    DIRECT_P2P,
    TURN_RELAYED,
    ICE_FAILED,
    TURN_UNAVAILABLE,
    CONNECTING,
    DISCONNECTED
}

/**
 * Configuration model for WebRTC TURN relay servers.
 * Supports UDP, TCP, and TLS (TURNS) transports with secure credential handling and redaction.
 */
data class TurnServerConfig(
    val url: String,
    val username: String? = null,
    val credential: String? = null,
    val transport: String = "udp"
) {
    fun toIceServer(): PeerConnection.IceServer {
        val cleanUrl = buildCleanUrl()
        val builder = PeerConnection.IceServer.builder(cleanUrl)
        if (!username.isNullOrBlank()) {
            builder.setUsername(username)
        }
        if (!credential.isNullOrBlank()) {
            builder.setPassword(credential)
        }
        return builder.createIceServer()
    }

    fun buildCleanUrl(): String {
        var base = url.trim()
        val isTurns = base.startsWith("turns:", ignoreCase = true)
        val isTurn = base.startsWith("turn:", ignoreCase = true)
        if (!isTurn && !isTurns) {
            base = "turn:$base"
        }
        return if (!base.contains("?transport=", ignoreCase = true) &&
            (transport.equals("tcp", ignoreCase = true) || transport.equals("udp", ignoreCase = true))
        ) {
            "$base?transport=${transport.lowercase()}"
        } else {
            base
        }
    }

    /**
     * Returns log-safe representation with credentials strictly redacted.
     */
    fun toRedactedString(): String {
        val userPart = if (!username.isNullOrBlank()) "user=$username" else "no-user"
        val credPart = if (!credential.isNullOrBlank()) "cred=***" else "no-cred"
        val cleanUrl = buildCleanUrl()
        return "TurnServerConfig(url=$cleanUrl, $userPart, $credPart)"
    }
}

/**
 * Thread-safe configuration manager for dynamic TURN relay registration.
 */
object TurnConfigManager {
    private val _turnConfigs = mutableListOf<TurnServerConfig>()
    val turnConfigs: List<TurnServerConfig> get() = synchronized(_turnConfigs) { _turnConfigs.toList() }

    fun setTurnServers(configs: List<TurnServerConfig>) {
        synchronized(_turnConfigs) {
            _turnConfigs.clear()
            _turnConfigs.addAll(configs)
        }
    }

    fun addTurnServer(config: TurnServerConfig) {
        synchronized(_turnConfigs) {
            _turnConfigs.add(config)
        }
    }

    fun clear() {
        synchronized(_turnConfigs) {
            _turnConfigs.clear()
        }
    }
}

/**
 * Managed WebRTC Session tracking atomic lifecycle states, diagnostics, and safe disposal.
 */
class WebRtcSession(
    val transferId: Long,
    val peerConnection: PeerConnection,
    var dataChannel: DataChannel? = null
) {
    val state = AtomicReference(PeerLifecycleState.NEW)
    val diagnostic = AtomicReference(ConnectionDiagnostic.CONNECTING)
    var activeCandidateType: String? = null
    val hasTurnConfig = AtomicBoolean(false)
    val isRestartingIce = AtomicBoolean(false)
    var lastRestartTimestamp: Long = 0L
    var activeNetworkHandle: Long = 0L
    val isClosed = AtomicBoolean(false)

    fun transitionTo(newState: PeerLifecycleState): Boolean {
        val current = state.get()
        if (current == PeerLifecycleState.CLOSED && newState != PeerLifecycleState.CLOSED) {
            return false // Cannot transition out of CLOSED
        }
        state.set(newState)
        return true
    }

    fun updateDiagnostic(diag: ConnectionDiagnostic) {
        diagnostic.set(diag)
    }

    /**
     * Executes strict sequential resource disposal:
     * 1. DataChannel.close()
     * 2. DataChannel.dispose()
     * 3. PeerConnection.close()
     * 4. PeerConnection.dispose()
     */
    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            state.set(PeerLifecycleState.CLOSING)
            WebRtcClient.safeDisposeSession(dataChannel, peerConnection)
            dataChannel = null
            state.set(PeerLifecycleState.CLOSED)
        }
    }
}

/**
 * WebRTC Client managing PeerConnectionFactory, STUN NAT Hole-Punching,
 * SDP Offer/Answer lifecycle, controlled ICE restarts, and SCTP DataChannels.
 */
class WebRtcClient(
    private val context: Context,
    private val turnConfigProvider: () -> List<TurnServerConfig> = { TurnConfigManager.turnConfigs }
) {

    val peerConnectionFactory: PeerConnectionFactory
    private val isDisposed = AtomicBoolean(false)

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    /**
     * Builds a WebRTC PeerConnection configured with STUN and configurable TURN relay servers.
     */
    fun buildPeerConnection(
        observer: PeerConnection.Observer,
        customTurnConfigs: List<TurnServerConfig>? = null
    ): PeerConnection? {
        if (isDisposed.get()) return null

        val activeTurnConfigs = customTurnConfigs ?: turnConfigProvider()
        val iceServers = buildIceServerList(activeTurnConfigs)

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        return peerConnectionFactory.createPeerConnection(rtcConfig, observer)
    }

    /**
     * Creates an ordered, reliable SCTP DataChannel for binary file streaming.
     */
    fun createDataChannel(
        peerConnection: PeerConnection,
        label: String = DATA_CHANNEL_LABEL,
        observer: DataChannel.Observer? = null
    ): DataChannel? {
        val init = DataChannel.Init().apply {
            ordered = true
            maxRetransmits = -1 // Reliable mode
            negotiated = false
        }
        val channel = peerConnection.createDataChannel(label, init)
        if (observer != null) {
            channel?.registerObserver(observer)
        }
        return channel
    }

    /**
     * Creates an SDP Offer asynchronously.
     */
    suspend fun createOffer(
        peerConnection: PeerConnection,
        constraints: MediaConstraints = MediaConstraints()
    ): Result<SessionDescription> = suspendCancellableCoroutine { continuation ->
        peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    continuation.resume(Result.success(desc))
                } else {
                    continuation.resume(Result.failure(NullPointerException("Generated SDP Offer was null")))
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                continuation.resume(Result.failure(Exception("Failed to create SDP Offer: $error")))
            }

            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * Triggers a controlled ICE restart on [peerConnection] for network interface handovers (e.g., Wi-Fi <-> Cellular).
     * Creates an SDP offer with IceRestart constraint, sets local description, and returns the offer SDP.
     */
    suspend fun restartIce(peerConnection: PeerConnection): Result<SessionDescription> {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        val offerResult = createOffer(peerConnection, constraints)
        return offerResult.mapCatching { sdpOffer ->
            val setLocalResult = setLocalDescription(peerConnection, sdpOffer)
            if (setLocalResult.isFailure) {
                throw setLocalResult.exceptionOrNull() ?: Exception("Failed to set local description for ICE restart")
            }
            sdpOffer
        }
    }

    /**
     * Creates an SDP Answer asynchronously.
     */
    suspend fun createAnswer(
        peerConnection: PeerConnection,
        constraints: MediaConstraints = MediaConstraints()
    ): Result<SessionDescription> = suspendCancellableCoroutine { continuation ->
        peerConnection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    continuation.resume(Result.success(desc))
                } else {
                    continuation.resume(Result.failure(NullPointerException("Generated SDP Answer was null")))
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                continuation.resume(Result.failure(Exception("Failed to create SDP Answer: $error")))
            }

            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * Sets local session description asynchronously.
     */
    suspend fun setLocalDescription(
        peerConnection: PeerConnection,
        desc: SessionDescription
    ): Result<Unit> = suspendCancellableCoroutine { continuation ->
        peerConnection.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                continuation.resume(Result.success(Unit))
            }

            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) {
                continuation.resume(Result.failure(Exception("Failed to set Local Description: $error")))
            }
        }, desc)
    }

    /**
     * Sets remote session description asynchronously.
     */
    suspend fun setRemoteDescription(
        peerConnection: PeerConnection,
        desc: SessionDescription
    ): Result<Unit> = suspendCancellableCoroutine { continuation ->
        peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                continuation.resume(Result.success(Unit))
            }

            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) {
                continuation.resume(Result.failure(Exception("Failed to set Remote Description: $error")))
            }
        }, desc)
    }

    /**
     * Releases PeerConnectionFactory on application shutdown.
     */
    fun dispose() {
        if (isDisposed.compareAndSet(false, true)) {
            try {
                peerConnectionFactory.dispose()
                Log.d(TAG, "PeerConnectionFactory disposed successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Exception during PeerConnectionFactory disposal", e)
            }
        }
    }

    companion object {
        const val DATA_CHANNEL_LABEL = "file_stream"
        private const val TAG = "WebRtcClient"

        /**
         * Builds an ICE server list combining default Google STUN with configured TURN servers.
         */
        fun buildIceServerList(
            turnConfigs: List<TurnServerConfig>? = null
        ): List<PeerConnection.IceServer> {
            val iceServers = mutableListOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
            )

            turnConfigs?.forEach { config ->
                try {
                    iceServers.add(config.toIceServer())
                    Log.d(TAG, "Configured ICE TURN server: ${config.toRedactedString()}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse ICE TURN server: ${config.toRedactedString()}", e)
                }
            }

            return iceServers
        }

        /**
         * Evaluates connection diagnostics distinguishing direct P2P vs TURN relayed vs failure modes.
         */
        fun determineDiagnostic(
            iceState: PeerConnection.IceConnectionState,
            candidateType: String?,
            hasTurnConfig: Boolean
        ): ConnectionDiagnostic {
            return when (iceState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    if (candidateType?.equals("relay", ignoreCase = true) == true) {
                        ConnectionDiagnostic.TURN_RELAYED
                    } else {
                        ConnectionDiagnostic.DIRECT_P2P
                    }
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    if (!hasTurnConfig) {
                        ConnectionDiagnostic.TURN_UNAVAILABLE
                    } else {
                        ConnectionDiagnostic.ICE_FAILED
                    }
                }
                PeerConnection.IceConnectionState.CHECKING -> ConnectionDiagnostic.CONNECTING
                PeerConnection.IceConnectionState.DISCONNECTED -> ConnectionDiagnostic.DISCONNECTED
                else -> ConnectionDiagnostic.CONNECTING
            }
        }

        /**
         * Safely closes and disposes an SCTP DataChannel without crashing native C++ runtime.
         */
        fun safeDisposeDataChannel(dataChannel: DataChannel?) {
            if (dataChannel == null) return
            try {
                dataChannel.close()
            } catch (e: Throwable) {
                Log.w(TAG, "Non-fatal error closing DataChannel", e)
            }
            try {
                dataChannel.dispose()
            } catch (e: Throwable) {
                Log.w(TAG, "Non-fatal error disposing DataChannel", e)
            }
        }

        /**
         * Safely closes and disposes a PeerConnection without crashing native C++ runtime.
         */
        fun safeDisposePeerConnection(peerConnection: PeerConnection?) {
            if (peerConnection == null) return
            try {
                peerConnection.close()
            } catch (e: Throwable) {
                Log.w(TAG, "Non-fatal error closing PeerConnection", e)
            }
            try {
                peerConnection.dispose()
            } catch (e: Throwable) {
                Log.w(TAG, "Non-fatal error disposing PeerConnection", e)
            }
        }

        /**
         * Enforces strict WebRTC native disposal order:
         * 1. DataChannel.close() -> DataChannel.dispose()
         * 2. PeerConnection.close() -> PeerConnection.dispose()
         */
        fun safeDisposeSession(dataChannel: DataChannel?, peerConnection: PeerConnection?) {
            safeDisposeDataChannel(dataChannel)
            safeDisposePeerConnection(peerConnection)
        }
    }
}
