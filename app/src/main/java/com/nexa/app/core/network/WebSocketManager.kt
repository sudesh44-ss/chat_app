package com.nexa.app.core.network

import android.util.Log
import com.nexa.app.core.crypto.Ed25519Signer
import com.nexa.app.core.proto.AuthResponsePayload
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.proto.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebSocketManager(
    private val serverBaseUrl: String = "${ServerEndpointConfig.wsBaseUrl}/ws",
    private var identitySigner: ((dataToSign: ByteArray) -> Pair<ByteArray, ByteArray>)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite read for persistent socket
        .pingInterval(25, TimeUnit.SECONDS)     // HTTP-level ping keepalive
        .build()

    private var activeWebSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var currentNxId: String? = null
    private var currentDeviceId: String? = null
    private val isManuallyClosed = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<NexaWirePacket>(extraBufferCapacity = 64)
    val incomingPackets: SharedFlow<NexaWirePacket> = _incomingPackets.asSharedFlow()

    private var onAuthenticatedListener: (suspend () -> Unit)? = null

    fun setIdentitySigner(signer: (dataToSign: ByteArray) -> Pair<ByteArray, ByteArray>) {
        this.identitySigner = signer
    }

    fun setOnAuthenticatedListener(listener: suspend () -> Unit) {
        this.onAuthenticatedListener = listener
    }

    fun connect(nxId: String, deviceId: String? = null) {
        if (nxId.isBlank()) {
            Log.w(TAG, "Cannot connect WebSocket with blank nxId")
            return
        }
        currentNxId = nxId
        currentDeviceId = deviceId
        isManuallyClosed.set(false)
        if (_connectionState.value == ConnectionState.Connected ||
            _connectionState.value == ConnectionState.Connecting
        ) {
            return
        }

        reconnectJob?.cancel()
        initiateSocketConnection(nxId, deviceId)
    }

    private fun initiateSocketConnection(nxId: String, deviceId: String? = null) {
        try {
            activeWebSocket?.cancel()
        } catch (ignored: Exception) {}
        activeWebSocket = null

        _connectionState.value = ConnectionState.Connecting
        try {
            val url = if (!deviceId.isNullOrEmpty()) {
                "$serverBaseUrl?nx_id=$nxId&device_id=$deviceId"
            } else {
                "$serverBaseUrl?nx_id=$nxId"
            }
            val request = Request.Builder().url(url).build()

            activeWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket transport opened for NX-ID: $nxId, awaiting Ed25519 challenge...")
                    // Note: Remains in Connecting state until server sends PACKET_AUTH_SUCCESS
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    try {
                        val packet = NexaWirePacket.decode(bytes.toByteArray())
                        when (packet.type) {
                            PacketType.PACKET_AUTH_CHALLENGE -> {
                                handleAuthChallenge(webSocket, nxId, packet)
                            }
                            PacketType.PACKET_AUTH_SUCCESS -> {
                                Log.i(TAG, "Ed25519 authentication ACK confirmed by server for $nxId")
                                _connectionState.value = ConnectionState.Connected
                                startHeartbeatPing()
                                scope.launch {
                                    try {
                                        onAuthenticatedListener?.invoke()
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Error in onAuthenticatedListener: ${e.message}")
                                    }
                                }
                            }
                            PacketType.PACKET_AUTH_FAILURE -> {
                                Log.e(TAG, "Server rejected Ed25519 authentication signature for $nxId")
                                _connectionState.value = ConnectionState.Error(
                                    SecurityException("Server rejected Ed25519 authentication")
                                )
                                webSocket.close(4001, "Authentication failed")
                            }
                            else -> {
                                scope.launch {
                                    _incomingPackets.emit(packet)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error decoding incoming wire packet", e)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    handleDisconnect(null)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "WebSocket connection failed: ${t.message}")
                    handleDisconnect(t)
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initiate WebSocket connection to $serverBaseUrl", e)
            handleDisconnect(e)
        }
    }

    private fun handleAuthChallenge(webSocket: WebSocket, nxId: String, packet: NexaWirePacket) {
        try {
            val challenge = packet.authChallenge ?: return
            val now = System.currentTimeMillis()
            if (challenge.expiresAt in 1..now) {
                Log.w(TAG, "Received expired auth challenge from server (now=$now, exp=${challenge.expiresAt})")
                return
            }

            val nonce = challenge.challengeNonce
            val challengeTs = challenge.timestamp
            // Canonical dataToSign: challengeNonce + ":" + nxId + ":" + challengeTimestamp
            val dataToSign = nonce + ":$nxId:$challengeTs".toByteArray(Charsets.UTF_8)

            val (signature, publicKey) = try {
                identitySigner?.invoke(dataToSign) ?: run {
                    // Ephemeral Ed25519 signing fallback if no persistent provider configured
                    val privKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                    val pubKey = Ed25519Signer.derivePublicKey(privKey)
                    val sig = Ed25519Signer.sign(dataToSign, privKey)
                    Pair(sig, pubKey)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Identity challenge signer threw exception, falling back to ephemeral key", e)
                val privKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                val pubKey = Ed25519Signer.derivePublicKey(privKey)
                val sig = Ed25519Signer.sign(dataToSign, privKey)
                Pair(sig, pubKey)
            }

            val responsePacket = NexaWirePacket(
                packetId = "auth_resp_${UUID.randomUUID()}",
                type = PacketType.PACKET_AUTH_RESPONSE,
                senderNxId = nxId,
                recipientNxId = "SERVER",
                timestamp = now,
                authResponse = AuthResponsePayload(
                    signature = signature,
                    publicKey = publicKey,
                    timestamp = now
                )
            )

            val encoded = responsePacket.encode()
            webSocket.send(encoded.toByteString())
            Log.d(TAG, "Dispatched Ed25519 auth response packet to server for NX-ID: $nxId")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling auth challenge", e)
        }
    }

    private fun startHeartbeatPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            try {
                while (isActive && _connectionState.value == ConnectionState.Connected) {
                    delay(30_000) // 30-second ping interval
                    val pingPacket = NexaWirePacket(
                        packetId = UUID.randomUUID().toString(),
                        type = PacketType.PACKET_PING,
                        senderNxId = currentNxId ?: "",
                        timestamp = System.currentTimeMillis()
                    )
                    sendPacket(pingPacket)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Heartbeat ping loop terminated: ${e.message}")
            }
        }
    }

    private fun handleDisconnect(cause: Throwable?) {
        pingJob?.cancel()
        _connectionState.value = if (cause != null) ConnectionState.Error(cause) else ConnectionState.Disconnected

        if (!isManuallyClosed.get() && currentNxId != null) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            try {
                var baseDelayMs = 1500L
                while (isActive && !isManuallyClosed.get() && _connectionState.value !is ConnectionState.Connected) {
                    val jitter = (0..500).random().toLong()
                    val delayMs = (baseDelayMs + jitter).coerceAtMost(30_000L)
                    delay(delayMs)
                    currentNxId?.let { id ->
                        Log.d(TAG, "Attempting WebSocket reconnect to relay for $id (device: $currentDeviceId) with backoff ${delayMs}ms...")
                        initiateSocketConnection(id, currentDeviceId)
                    }
                    baseDelayMs = (baseDelayMs * 2).coerceAtMost(30_000L) // Exponential backoff max 30s
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reconnect loop terminated: ${e.message}")
            }
        }
    }

    /**
     * Serializes packet into compact binary Protobuf and transmits over WebSocket.
     */
    fun sendPacket(packet: NexaWirePacket): Boolean {
        val socket = activeWebSocket
        if (socket == null || _connectionState.value !is ConnectionState.Connected) {
            return false
        }
        val binaryData = packet.encode().toByteString()
        return socket.send(binaryData)
    }

    /**
     * Sends raw binary byte array directly over active WebSocket connection.
     */
    fun sendBinary(bytes: ByteArray): Boolean {
        val socket = activeWebSocket
        if (socket == null || _connectionState.value !is ConnectionState.Connected) {
            return false
        }
        return socket.send(bytes.toByteString())
    }

    /**
     * Sends transient typing indicator packet (RAM-only, zero disk/ephemeral queue persistence).
     */
    fun sendTyping(recipientNxId: String, conversationId: String, isTyping: Boolean): Boolean {
        val packet = NexaWirePacket(
            packetId = "typing_${UUID.randomUUID()}",
            type = PacketType.PACKET_TYPING,
            senderNxId = currentNxId ?: "",
            recipientNxId = recipientNxId,
            timestamp = System.currentTimeMillis(),
            typing = com.nexa.app.core.proto.TypingPayload(
                conversationId = conversationId,
                isTyping = isTyping
            )
        )
        return sendPacket(packet)
    }

    /**
     * Registers active pairwise rotating routing tokens with the server Hub (Phase C).
     */
    fun registerRoutingTokens(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        val packet = NexaWirePacket(
            packetId = "tokens_${UUID.randomUUID()}",
            type = PacketType.PACKET_REGISTER_ROUTING_TOKENS,
            senderNxId = currentNxId ?: "",
            recipientNxId = "SERVER",
            timestamp = System.currentTimeMillis(),
            routingTokens = com.nexa.app.core.proto.RoutingTokenRegistrationPayload(tokens)
        )
        val success = sendPacket(packet)
        if (success) {
            Log.i(TAG, "Submitted ${tokens.size} active pairwise routing tokens to server")
        }
        return success
    }

    fun disconnect() {
        isManuallyClosed.set(true)
        reconnectJob?.cancel()
        pingJob?.cancel()
        activeWebSocket?.close(1000, "Normal closure")
        activeWebSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    companion object {
        private const val TAG = "WebSocketManager"
    }
}
