package com.nexa.app.data.remote.signaling

import android.util.Log
import com.nexa.app.core.network.ServerEndpointConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class SwarmSignalingClient(
    private val serverUrl: String = DEFAULT_SWARM_URL,
    private val client: OkHttpClient = defaultClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _incomingMessages = MutableSharedFlow<SwarmSignalingDto>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<SwarmSignalingDto> = _incomingMessages.asSharedFlow()

    private var activeWebSocket: WebSocket? = null
    private var myPeerId: String? = null

    fun connect(peerId: String, sessionToken: String? = null) {
        myPeerId = peerId
        disconnect()

        val baseUrl = "$serverUrl/ws/swarm?peer_id=$peerId"
        val fullUrl = if (!sessionToken.isNullOrBlank()) "$baseUrl&token=$sessionToken" else baseUrl
        val requestBuilder = Request.Builder().url(fullUrl)
        if (!sessionToken.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $sessionToken")
        }
        val request = requestBuilder.build()

        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Swarm WebSocket connected for Peer ID: $peerId")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = SwarmSignalingDto.fromJson(text)
                if (msg != null) {
                    scope.launch {
                        _incomingMessages.emit(msg)
                    }
                } else {
                    Log.w(TAG, "Unrecognized swarm payload: $text")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Swarm WebSocket closing: $code / $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Swarm WebSocket failure: ${t.localizedMessage}")
            }
        })
    }

    fun sendMessage(msg: SwarmSignalingDto): Boolean {
        val socket = activeWebSocket ?: return false
        val jsonStr = msg.toJson().toString()
        return socket.send(jsonStr)
    }

    fun disconnect() {
        activeWebSocket?.close(1000, "Normal closure")
        activeWebSocket = null
    }

    companion object {
        private const val TAG = "SwarmSignalingClient"
        val DEFAULT_SWARM_URL = ServerEndpointConfig.wsBaseUrl

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
