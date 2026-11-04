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

/**
 * WebSocket client for WebRTC SDP & ICE NAT hole-punching signaling.
 * Connects to /ws/signaling?nx_id={id}. Zero files or media transit through this connection.
 */
class SignalingClient(
    private val serverUrl: String = DEFAULT_SIGNALING_URL,
    private val client: OkHttpClient = defaultClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _incomingMessages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<SignalingMessage> = _incomingMessages.asSharedFlow()

    private var activeWebSocket: WebSocket? = null
    private var myNxId: String? = null

    fun connect(nxId: String) {
        myNxId = nxId
        disconnect()

        val fullUrl = "$serverUrl/ws/signaling?nx_id=$nxId"
        val request = Request.Builder().url(fullUrl).build()

        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Signaling WebSocket connected for NX-ID: $nxId")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = SignalingMessage.fromJson(text)
                if (msg != null) {
                    scope.launch {
                        _incomingMessages.emit(msg)
                    }
                } else {
                    Log.w(TAG, "Unrecognized signaling payload: $text")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Signaling WebSocket closing: $code / $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Signaling WebSocket failure: ${t.localizedMessage}")
            }
        })
    }

    fun sendMessage(msg: SignalingMessage): Boolean {
        val socket = activeWebSocket ?: return false
        val jsonStr = msg.toJson().toString()
        return socket.send(jsonStr)
    }

    fun disconnect() {
        activeWebSocket?.close(1000, "Normal closure")
        activeWebSocket = null
    }

    companion object {
        private const val TAG = "SignalingClient"
        val DEFAULT_SIGNALING_URL = ServerEndpointConfig.wsBaseUrl

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
