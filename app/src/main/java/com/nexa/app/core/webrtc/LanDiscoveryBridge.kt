package com.nexa.app.core.webrtc

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

data class LanPeer(
    val peerId: String,
    val host: InetAddress,
    val port: Int
)

/**
 * Local Area Network Auto-Discovery via mDNS / Network Service Discovery (Scenario 12).
 * Automatically detects peers sharing the same Wi-Fi subnet/SSID and negotiates
 * direct LAN WebRTC sockets (30-50 MB/s), consuming zero mobile data.
 * Fully compatible with Android 14+ (API 34/35) ServiceInfoCallback while retaining
 * robust queued resolution fallback for API 24-33.
 */
class LanDiscoveryBridge(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val SERVICE_TYPE = "_nexa-p2p._tcp."

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // Track active modern API 34+ ServiceInfoCallbacks
    private val activeModernCallbacks = ConcurrentHashMap<String, Any>()

    // Discovered and resolved peers
    private val discoveredPeers = ConcurrentHashMap<String, LanPeer>()
    private val resolvingPeerNames = ConcurrentHashMap.newKeySet<String>()

    // Legacy sequential resolution queue to prevent FAILURE_ALREADY_ACTIVE (error code 3)
    private val legacyResolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isLegacyResolving = false

    private var onPeerFoundCallback: ((LanPeer) -> Unit)? = null

    fun setPeerFoundListener(listener: (LanPeer) -> Unit) {
        onPeerFoundCallback = listener
    }

    @Synchronized
    fun registerService(peerId: String, port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "nexa-$peerId"
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "LAN service registered: ${serviceInfo.serviceName} on port ${serviceInfo.port}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "LAN service registration failed: code=$errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "LAN service unregistered: ${serviceInfo.serviceName}")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "LAN service unregistration failed: code=$errorCode")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.w(TAG, "Exception registering LAN service", e)
        }
    }

    @Synchronized
    fun startDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery start failed: code=$errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery stop failed: code=$errorCode")
            }
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName ?: return
                if (serviceInfo.serviceType == SERVICE_TYPE && name.startsWith("nexa-")) {
                    val peerId = name.removePrefix("nexa-")
                    // Deduplicate in-flight and already discovered peers
                    if (discoveredPeers.containsKey(peerId) || !resolvingPeerNames.add(name)) {
                        return
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        resolveModernService(serviceInfo)
                    } else {
                        enqueueLegacyResolve(serviceInfo)
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val peerId = serviceInfo.serviceName?.removePrefix("nexa-") ?: return
                resolvingPeerNames.remove(serviceInfo.serviceName)
                discoveredPeers.remove(peerId)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    unregisterModernCallback(peerId)
                }
                Log.d(TAG, "LAN peer lost: $peerId")
            }
        }

        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "Exception starting NSD discovery", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveModernService(serviceInfo: NsdServiceInfo) {
        val peerId = serviceInfo.serviceName.removePrefix("nexa-")
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "Modern ServiceInfoCallback registration failed: $errorCode for $peerId")
                resolvingPeerNames.remove(serviceInfo.serviceName)
                activeModernCallbacks.remove(peerId)
            }

            override fun onServiceUpdated(resolvedInfo: NsdServiceInfo) {
                val host = resolvedInfo.hostAddresses.firstOrNull() ?: resolvedInfo.host
                if (host != null && resolvedInfo.port > 0) {
                    val lanPeer = LanPeer(peerId, host, resolvedInfo.port)
                    discoveredPeers[peerId] = lanPeer
                    onPeerFoundCallback?.invoke(lanPeer)
                    Log.i(TAG, "LAN peer resolved via API 34+ callback: $peerId -> $host:${resolvedInfo.port}")
                }
            }

            override fun onServiceLost() {
                discoveredPeers.remove(peerId)
                resolvingPeerNames.remove(serviceInfo.serviceName)
                Log.d(TAG, "LAN peer lost callback: $peerId")
            }

            override fun onServiceInfoCallbackUnregistered() {
                activeModernCallbacks.remove(peerId)
            }
        }

        activeModernCallbacks[peerId] = callback
        try {
            val executor = ContextCompat.getMainExecutor(context)
            nsdManager?.registerServiceInfoCallback(serviceInfo, executor, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register ServiceInfoCallback for $peerId", e)
            resolvingPeerNames.remove(serviceInfo.serviceName)
            activeModernCallbacks.remove(peerId)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun unregisterModernCallback(peerId: String) {
        val callback = activeModernCallbacks.remove(peerId) as? NsdManager.ServiceInfoCallback ?: return
        try {
            nsdManager?.unregisterServiceInfoCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister ServiceInfoCallback for $peerId", e)
        }
    }

    @Synchronized
    private fun enqueueLegacyResolve(serviceInfo: NsdServiceInfo) {
        legacyResolveQueue.addLast(serviceInfo)
        processNextLegacyResolve()
    }

    @Synchronized
    private fun processNextLegacyResolve() {
        if (isLegacyResolving || legacyResolveQueue.isEmpty()) {
            return
        }

        val serviceInfo = legacyResolveQueue.removeFirst()
        isLegacyResolving = true

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Legacy resolve failed: code=$errorCode for ${serviceInfo.serviceName}")
                resolvingPeerNames.remove(serviceInfo.serviceName)
                completeLegacyResolve()
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val peerId = resolvedInfo.serviceName.removePrefix("nexa-")
                val lanPeer = LanPeer(peerId, resolvedInfo.host, resolvedInfo.port)
                discoveredPeers[peerId] = lanPeer
                onPeerFoundCallback?.invoke(lanPeer)
                Log.i(TAG, "LAN peer resolved via legacy listener: $peerId -> ${resolvedInfo.host}:${resolvedInfo.port}")
                completeLegacyResolve()
            }
        }

        try {
            @Suppress("DEPRECATION")
            nsdManager?.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.w(TAG, "Exception invoking legacy resolveService", e)
            resolvingPeerNames.remove(serviceInfo.serviceName)
            completeLegacyResolve()
        }
    }

    @Synchronized
    private fun completeLegacyResolve() {
        isLegacyResolving = false
        processNextLegacyResolve()
    }

    fun getDiscoveredPeer(peerId: String): LanPeer? = discoveredPeers[peerId]

    fun isPeerOnLan(peerId: String): Boolean = discoveredPeers.containsKey(peerId)

    @Synchronized
    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            for ((peerId, callback) in activeModernCallbacks) {
                try {
                    (callback as? NsdManager.ServiceInfoCallback)?.let {
                        nsdManager?.unregisterServiceInfoCallback(it)
                    }
                } catch (ignored: Exception) {}
            }
            activeModernCallbacks.clear()
        }

        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (ignored: Exception) {}

        legacyResolveQueue.clear()
        isLegacyResolving = false
        resolvingPeerNames.clear()
        discoveredPeers.clear()
    }

    companion object {
        private const val TAG = "LanDiscoveryBridge"
    }
}
