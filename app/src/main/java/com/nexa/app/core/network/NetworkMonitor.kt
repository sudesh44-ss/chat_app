package com.nexa.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Supported underlying transport types for network telemetry.
 */
enum class TransportType {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    OTHER
}

/**
 * Structured network classification that avoids mutually exclusive collapse:
 * Accurately represents concurrent VPN over Wi-Fi or Cellular, metered vs unmetered,
 * and validated Internet routing without losing underlying physical interface telemetry.
 */
data class NetworkClassification(
    val hasWifi: Boolean = false,
    val hasCellular: Boolean = false,
    val hasEthernet: Boolean = false,
    val isVpnActive: Boolean = false,
    val isMetered: Boolean = false,
    val isValidated: Boolean = false,
    val isInternetAvailable: Boolean = false,
    val primaryTransport: TransportType = TransportType.NONE
) {
    /**
     * Diagnostic string representation of active network interfaces.
     */
    fun toDiagnosticString(): String = buildString {
        append("Transports: [")
        val transports = mutableListOf<String>()
        if (hasWifi) transports.add("Wi-Fi")
        if (hasCellular) transports.add("Cellular")
        if (hasEthernet) transports.add("Ethernet")
        if (isVpnActive) transports.add("VPN")
        if (transports.isEmpty()) transports.add("None")
        append(transports.joinToString(", "))
        append("], Primary: ").append(primaryTransport)
        append(", Metered: ").append(isMetered)
        append(", Validated: ").append(isValidated)
        append(", Online: ").append(isInternetAvailable)
    }
}

/**
 * Fine-grained network connection classification for P2P and background synchronization.
 */
sealed class NetworkTypeState {
    data object Disconnected : NetworkTypeState()
    data class Wifi(val isMetered: Boolean = false, val isValidated: Boolean = true) : NetworkTypeState()
    data class Cellular(val isMetered: Boolean = true, val isValidated: Boolean = true) : NetworkTypeState()
    data class Ethernet(val isMetered: Boolean = false, val isValidated: Boolean = true) : NetworkTypeState()
    data class Vpn(val isValidated: Boolean = true) : NetworkTypeState()
    data class Other(val isValidated: Boolean = true) : NetworkTypeState()
}

/**
 * Event emitted when the active network interface, transport, or capability changes.
 */
data class NetworkChangeEvent(
    val networkState: NetworkTypeState,
    val isOnline: Boolean,
    val networkHandle: Long,
    val classification: NetworkClassification = NetworkClassification(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Observes Android network interface transitions (Wi-Fi <-> Cellular, VPN, drops).
 * Notifies interested subsystems (WebRTC ICE restart, WorkManager, WebSocket) of link transitions.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(checkCurrentConnectivity())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _networkTypeState = MutableStateFlow(determineCurrentTypeState())
    val networkTypeState: StateFlow<NetworkTypeState> = _networkTypeState.asStateFlow()

    private val _networkClassification = MutableStateFlow(determineCurrentClassification())
    val networkClassification: StateFlow<NetworkClassification> = _networkClassification.asStateFlow()

    private val _networkChangeEvents = MutableSharedFlow<NetworkChangeEvent>(extraBufferCapacity = 32)
    val networkChangeEvents: SharedFlow<NetworkChangeEvent> = _networkChangeEvents.asSharedFlow()

    private var activeNetworkHandle: Long = getActiveHandle()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val handle = extractNetworkHandle(network)
            activeNetworkHandle = handle
            _isOnline.value = true
            val typeState = classifyNetwork(network)
            val classification = classifyNetworkClassification(network)
            _networkTypeState.value = typeState
            _networkClassification.value = classification
            _networkChangeEvents.tryEmit(
                NetworkChangeEvent(
                    networkState = typeState,
                    isOnline = true,
                    networkHandle = handle,
                    classification = classification
                )
            )
        }

        override fun onLost(network: Network) {
            val handle = extractNetworkHandle(network)
            val isStillOnline = checkCurrentConnectivity()
            _isOnline.value = isStillOnline
            val typeState = determineCurrentTypeState()
            val classification = determineCurrentClassification()
            _networkTypeState.value = typeState
            _networkClassification.value = classification
            _networkChangeEvents.tryEmit(
                NetworkChangeEvent(
                    networkState = typeState,
                    isOnline = isStillOnline,
                    networkHandle = if (isStillOnline) getActiveHandle() else 0L,
                    classification = classification
                )
            )
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val handle = extractNetworkHandle(network)
            activeNetworkHandle = handle
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            _isOnline.value = hasInternet

            val typeState = classifyCapabilities(capabilities)
            val classification = classifyCapabilitiesToClassification(capabilities)
            _networkTypeState.value = typeState
            _networkClassification.value = classification
            _networkChangeEvents.tryEmit(
                NetworkChangeEvent(
                    networkState = typeState,
                    isOnline = hasInternet,
                    networkHandle = handle,
                    classification = classification
                )
            )
        }
    }

    init {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (ignored: Exception) {}
    }

    private fun checkCurrentConnectivity(): Boolean {
        val cm = connectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun determineCurrentTypeState(): NetworkTypeState {
        val cm = connectivityManager ?: return NetworkTypeState.Disconnected
        val activeNetwork = cm.activeNetwork ?: return NetworkTypeState.Disconnected
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkTypeState.Disconnected
        return classifyCapabilities(capabilities)
    }

    private fun determineCurrentClassification(): NetworkClassification {
        val cm = connectivityManager ?: return NetworkClassification()
        val activeNetwork = cm.activeNetwork ?: return NetworkClassification()
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkClassification()
        return classifyCapabilitiesToClassification(capabilities)
    }

    private fun classifyNetwork(network: Network): NetworkTypeState {
        val cm = connectivityManager ?: return NetworkTypeState.Disconnected
        val capabilities = cm.getNetworkCapabilities(network) ?: return NetworkTypeState.Disconnected
        return classifyCapabilities(capabilities)
    }

    private fun classifyNetworkClassification(network: Network): NetworkClassification {
        val cm = connectivityManager ?: return NetworkClassification()
        val capabilities = cm.getNetworkCapabilities(network) ?: return NetworkClassification()
        return classifyCapabilitiesToClassification(capabilities)
    }

    private fun classifyCapabilities(capabilities: NetworkCapabilities): NetworkTypeState {
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                NetworkTypeState.Ethernet(isMetered = isMetered, isValidated = isValidated)
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                NetworkTypeState.Wifi(isMetered = isMetered, isValidated = isValidated)
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                NetworkTypeState.Cellular(isMetered = isMetered, isValidated = isValidated)
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> {
                NetworkTypeState.Vpn(isValidated = isValidated)
            }
            else -> {
                NetworkTypeState.Other(isValidated = isValidated)
            }
        }
    }

    private fun classifyCapabilitiesToClassification(capabilities: NetworkCapabilities?): NetworkClassification {
        if (capabilities == null) return NetworkClassification()

        val hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val hasCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val hasEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && isValidated

        return evaluateClassification(
            hasWifi = hasWifi,
            hasCellular = hasCellular,
            hasEthernet = hasEthernet,
            isVpn = isVpn,
            isMetered = isMetered,
            isValidated = isValidated,
            hasInternet = hasInternet
        )
    }

    private fun extractNetworkHandle(network: Network): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            network.networkHandle
        } else {
            network.hashCode().toLong()
        }
    }

    private fun getActiveHandle(): Long {
        val cm = connectivityManager ?: return 0L
        val activeNetwork = cm.activeNetwork ?: return 0L
        return extractNetworkHandle(activeNetwork)
    }

    companion object {
        /**
         * Pure deterministic evaluation of network classification without Android framework dependencies.
         */
        fun evaluateClassification(
            hasWifi: Boolean,
            hasCellular: Boolean,
            hasEthernet: Boolean,
            isVpn: Boolean,
            isMetered: Boolean,
            isValidated: Boolean,
            hasInternet: Boolean
        ): NetworkClassification {
            val primary = when {
                hasEthernet -> TransportType.ETHERNET
                hasWifi -> TransportType.WIFI
                hasCellular -> TransportType.CELLULAR
                isVpn -> TransportType.VPN
                hasInternet -> TransportType.OTHER
                else -> TransportType.NONE
            }

            return NetworkClassification(
                hasWifi = hasWifi,
                hasCellular = hasCellular,
                hasEthernet = hasEthernet,
                isVpnActive = isVpn,
                isMetered = isMetered,
                isValidated = isValidated,
                isInternetAvailable = hasInternet,
                primaryTransport = primary
            )
        }
    }
}
