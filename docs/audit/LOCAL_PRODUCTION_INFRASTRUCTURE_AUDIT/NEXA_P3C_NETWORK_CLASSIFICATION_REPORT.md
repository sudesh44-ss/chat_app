# NEXA — P3-C Android Network Classification Report

**Phase:** P3 Public Launch Readiness  
**Target Subsystem:** Android Network Monitor, Multi-Interface Telemetry & Capability Classification  
**Evaluation Date:** November 2026  
**Status:** **PASS / VERIFIED**

---

## 1. Problem Statement & Root Cause

Previously, network monitors commonly used a single `when` statement over Android `NetworkCapabilities.hasTransport(...)`. This resulted in a **mutually exclusive collapse**:
- When a user enabled a VPN on Android, `capabilities.hasTransport(TRANSPORT_VPN)` returned `true`, causing the classifier to emit `NetworkTypeState.Vpn`.
- Consequently, downstream subsystems (WebRTC ICE candidate gathering, large backup uploads, P2P swarm transfers, WorkManager schedules) were blinded to the underlying physical transport:
  - Did the connection run over unmetered fiber Wi-Fi or metered cellular roaming?
  - Was Ethernet available with high throughput?
  - Was the underlying physical interface actually connected to validated Internet?

This mutual-exclusion defect risked:
1. Triggering heavy background backups or media downloads over expensive metered cellular plans while obscured by an active VPN.
2. Degrading WebRTC candidate generation by treating VPN links as isolated interfaces rather than binding candidates to both the VPN tun interface and physical interface.

---

## 2. Technical Implementation (`NetworkMonitor.kt`)

### 2.1 Structured Multi-Transport Model
`NetworkMonitor.kt` introduces `NetworkClassification` and `TransportType`:

```kotlin
enum class TransportType {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    OTHER
}

data class NetworkClassification(
    val hasWifi: Boolean = false,
    val hasCellular: Boolean = false,
    val hasEthernet: Boolean = false,
    val isVpnActive: Boolean = false,
    val isMetered: Boolean = false,
    val isValidated: Boolean = false,
    val isInternetAvailable: Boolean = false,
    val primaryTransport: TransportType = TransportType.NONE
)
```

### 2.2 Capability Evaluation Logic
`NetworkMonitor.evaluateClassification()` deterministically evaluates all concurrent transports without mutual exclusion:
- **Concurrent VPN Detection:** If `TRANSPORT_VPN` is active concurrently with `TRANSPORT_WIFI` or `TRANSPORT_CELLULAR`, both flags are set (`isVpnActive = true` AND `hasWifi = true` or `hasCellular = true`).
- **Metered Status Preservation:** Evaluates `NET_CAPABILITY_NOT_METERED`. Even if a VPN tunnel is active, if the underlying transport is cellular or a metered Wi-Fi hotspot, `isMetered = true` is accurately preserved.
- **Ethernet Prioritization:** If an Ethernet adapter is connected (e.g. Android TV, Dex, USB-C dock), `primaryTransport` prioritizes `ETHERNET` for maximum bandwidth allocation.
- **Internet Validation:** Checks both `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED` to filter out captive portals or offline Wi-Fi access points.

### 2.3 Backward Compatibility Preservation
To prevent breaking existing consumers:
- `NetworkTypeState` enum remains intact, with an added `Ethernet` subtype:
  - `Disconnected`
  - `Wifi(isMetered, isValidated)`
  - `Cellular(isMetered, isValidated)`
  - `Ethernet(isMetered, isValidated)`
  - `Vpn(isValidated)`
  - `Other(isValidated)`
- `NetworkChangeEvent` retains original constructor arguments, adding `classification: NetworkClassification = NetworkClassification()` with a default value:
  ```kotlin
  data class NetworkChangeEvent(
      val networkState: NetworkTypeState,
      val isOnline: Boolean,
      val networkHandle: Long,
      val classification: NetworkClassification = NetworkClassification(),
      val timestamp: Long = System.currentTimeMillis()
  )
  ```
- Both `networkTypeState: StateFlow<NetworkTypeState>` and `networkClassification: StateFlow<NetworkClassification>` are exposed on `NetworkMonitor`.

### 2.4 Diagnostic Telemetry
`NetworkClassification.toDiagnosticString()` provides structured, human-readable logging for debug screens and error telemetry:
```
Transports: [Wi-Fi, VPN], Primary: WIFI, Metered: false, Validated: true, Online: true
```

---

## 3. Verification & Test Evidence

All test cases pass in `app/src/test/java/com/nexa/app/NetworkClassificationUnitTests.kt`:

| Test Name | Verified Behavior | Status |
| :--- | :--- | :--- |
| `testCoexistingVpnOverWifiPreservesBothTransports` | Wi-Fi and VPN co-exist concurrently; unmetered and validated flags preserved; primary=WIFI. | **PASS** |
| `testCoexistingVpnOverCellularPreservesMeteredStatus` | VPN active over cellular; `isMetered=true` accurately retained; primary=CELLULAR. | **PASS** |
| `testHighPriorityEthernetClassification` | Ethernet connection plugged in takes precedence over Wi-Fi as primary transport. | **PASS** |
| `testAirplaneModeOrCompleteOffline` | Zero network interfaces correctly resolves to `TransportType.NONE` and all offline flags. | **PASS** |
| `testVpnStandaloneWithoutDirectInterface` | Standalone virtual interface correctly categorized with primary=VPN. | **PASS** |
| `testDiagnosticStringOutput` | Diagnostic string accurately formats multi-interface states. | **PASS** |
| `testNetworkChangeEventCarriesClassification` | Interface shift events carry both legacy `networkState` and rich `classification` objects. | **PASS** |

---

## 4. Conclusion & Verdict

- **Classification Status:** **PASS / VERIFIED**
- **Mutually Exclusive Collapse:** **ELIMINATED**
- **Metered Safety:** Guaranteed (Background uploads cannot be tricked by VPN tunnels).
- **Offline Mesh Independence:** Completely decoupled from internet monitoring; DTN Mesh radios operate autonomously regardless of internet validation status.
