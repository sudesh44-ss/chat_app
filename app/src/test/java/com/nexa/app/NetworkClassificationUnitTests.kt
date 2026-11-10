package com.nexa.app

import com.nexa.app.core.network.NetworkChangeEvent
import com.nexa.app.core.network.NetworkClassification
import com.nexa.app.core.network.NetworkMonitor
import com.nexa.app.core.network.NetworkTypeState
import com.nexa.app.core.network.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for P3-C: Wi-Fi, Cellular, VPN, Ethernet and Metered Network Classification.
 *
 * Verifies:
 * 1. Coexisting VPN over Wi-Fi does not collapse into mutually exclusive VPN state.
 * 2. Coexisting VPN over Cellular accurately reflects metered status.
 * 3. High-throughput Ethernet link is classified with priority.
 * 4. Metered vs unmetered telemetry is preserved across transports.
 * 5. Full backward compatibility with existing NetworkTypeState and NetworkChangeEvent consumers.
 * 6. Disconnected / Airplane mode correctly resolves to TransportType.NONE and offline flags.
 * 7. Diagnostic string accurately represents active multi-interface topologies.
 */
class NetworkClassificationUnitTests {

    @Test
    fun testCoexistingVpnOverWifiPreservesBothTransports() {
        val classification = NetworkMonitor.evaluateClassification(
            hasWifi = true,
            hasCellular = false,
            hasEthernet = false,
            isVpn = true,
            isMetered = false,
            isValidated = true,
            hasInternet = true
        )

        assertTrue("Wi-Fi transport must be detected", classification.hasWifi)
        assertTrue("VPN transport must be active concurrently", classification.isVpnActive)
        assertFalse("Cellular must be inactive", classification.hasCellular)
        assertFalse("Ethernet must be inactive", classification.hasEthernet)
        assertFalse("Underlying Wi-Fi link is unmetered", classification.isMetered)
        assertTrue("Network link is validated", classification.isValidated)
        assertTrue("Internet is available", classification.isInternetAvailable)
        assertEquals("Primary transport is Wi-Fi", TransportType.WIFI, classification.primaryTransport)
    }

    @Test
    fun testCoexistingVpnOverCellularPreservesMeteredStatus() {
        val classification = NetworkMonitor.evaluateClassification(
            hasWifi = false,
            hasCellular = true,
            hasEthernet = false,
            isVpn = true,
            isMetered = true,
            isValidated = true,
            hasInternet = true
        )

        assertFalse("Wi-Fi must be inactive", classification.hasWifi)
        assertTrue("Cellular must be active", classification.hasCellular)
        assertTrue("VPN must be active over cellular", classification.isVpnActive)
        assertTrue("Cellular link must remain classified as metered", classification.isMetered)
        assertTrue("Internet is available", classification.isInternetAvailable)
        assertEquals("Primary transport is Cellular", TransportType.CELLULAR, classification.primaryTransport)
    }

    @Test
    fun testHighPriorityEthernetClassification() {
        val classification = NetworkMonitor.evaluateClassification(
            hasWifi = true, // Wi-Fi connected but Ethernet also plugged in
            hasCellular = false,
            hasEthernet = true,
            isVpn = false,
            isMetered = false,
            isValidated = true,
            hasInternet = true
        )

        assertTrue("Ethernet is active", classification.hasEthernet)
        assertTrue("Wi-Fi is active", classification.hasWifi)
        assertFalse("Ethernet is unmetered", classification.isMetered)
        assertEquals("Ethernet takes precedence over Wi-Fi as primary transport", TransportType.ETHERNET, classification.primaryTransport)
    }

    @Test
    fun testAirplaneModeOrCompleteOffline() {
        val classification = NetworkMonitor.evaluateClassification(
            hasWifi = false,
            hasCellular = false,
            hasEthernet = false,
            isVpn = false,
            isMetered = false,
            isValidated = false,
            hasInternet = false
        )

        assertFalse("No Wi-Fi", classification.hasWifi)
        assertFalse("No Cellular", classification.hasCellular)
        assertFalse("No Ethernet", classification.hasEthernet)
        assertFalse("No VPN", classification.isVpnActive)
        assertFalse("Not validated", classification.isValidated)
        assertFalse("No Internet", classification.isInternetAvailable)
        assertEquals("Primary transport is NONE", TransportType.NONE, classification.primaryTransport)
    }

    @Test
    fun testVpnStandaloneWithoutDirectInterface() {
        val classification = NetworkMonitor.evaluateClassification(
            hasWifi = false,
            hasCellular = false,
            hasEthernet = false,
            isVpn = true,
            isMetered = false,
            isValidated = true,
            hasInternet = true
        )

        assertTrue("VPN is active", classification.isVpnActive)
        assertEquals("Primary transport is VPN", TransportType.VPN, classification.primaryTransport)
    }

    @Test
    fun testDiagnosticStringOutput() {
        val classification = NetworkMonitor.evaluateClassification(
            hasWifi = true,
            hasCellular = false,
            hasEthernet = false,
            isVpn = true,
            isMetered = false,
            isValidated = true,
            hasInternet = true
        )

        val diag = classification.toDiagnosticString()
        assertTrue("Diagnostic contains Wi-Fi", diag.contains("Wi-Fi"))
        assertTrue("Diagnostic contains VPN", diag.contains("VPN"))
        assertTrue("Diagnostic contains Metered: false", diag.contains("Metered: false"))
        assertTrue("Diagnostic contains Primary: WIFI", diag.contains("Primary: WIFI"))
    }

    @Test
    fun testNetworkChangeEventCarriesClassification() {
        val classification = NetworkClassification(
            hasWifi = true,
            hasCellular = false,
            hasEthernet = false,
            isVpnActive = true,
            isMetered = false,
            isValidated = true,
            isInternetAvailable = true,
            primaryTransport = TransportType.WIFI
        )

        val event = NetworkChangeEvent(
            networkState = NetworkTypeState.Wifi(isMetered = false, isValidated = true),
            isOnline = true,
            networkHandle = 4001L,
            classification = classification
        )

        assertEquals("Network handle matches", 4001L, event.networkHandle)
        assertTrue("Online status is true", event.isOnline)
        assertTrue("Event contains coexisting VPN flag", event.classification.isVpnActive)
        assertTrue("Event contains Wi-Fi flag", event.classification.hasWifi)
        assertEquals("Primary transport is WIFI", TransportType.WIFI, event.classification.primaryTransport)
    }
}
