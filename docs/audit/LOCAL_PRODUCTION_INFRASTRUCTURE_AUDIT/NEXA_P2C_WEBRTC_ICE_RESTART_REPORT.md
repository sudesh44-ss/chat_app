# NEXA P2-C Reliability Report: Automatic WebRTC ICE Restart on Network Changes

## 1. Original Finding
In previous audits (`NEXA_P2P_FILE_TRANSFER_AUDIT.md`), `WebRtcClient.kt` provided a controlled `restartIce()` helper. However, automatic restart was not triggered when the Android network interface changed (e.g. Wi-Fi to cellular data, cellular to Wi-Fi, VPN toggle, or IP address shift). Ongoing P2P file transfers would enter `DISCONNECTED` or `FAILED` state and stall, requiring the transfer to be aborted or cancelled.

---

## 2. Verified Root Cause
1. **Disconnected Network Monitoring:** `P2PTransferRepositoryImpl` did not observe `NetworkMonitor` callbacks.
2. **Coarse-Grained Connectivity State:** `NetworkMonitor` emitted only a single `isOnline: Boolean` flag without exposing underlying transport classifications (Wi-Fi vs Cellular vs VPN) or network handles.
3. **Lack of Concurrency Guard:** The existing `triggerIceRestart()` method lacked atomic concurrency protection, risking duplicate simultaneous restarts on multiple rapid network callbacks.
4. **Missing Receiver Reconnection Signaling:** If the receiver experienced an ICE connection failure, it did not signal a resume request back to the sender.

---

## 3. Source Files Inspected & Modified

| File | Status | Description of Changes |
|---|---|---|
| `app/src/main/java/com/nexa/app/core/network/NetworkMonitor.kt` | **MODIFIED** | Added `NetworkTypeState` (`Wifi`, `Cellular`, `Vpn`, `Disconnected`), `NetworkChangeEvent`, and `networkChangeEvents` flow |
| `app/src/main/java/com/nexa/app/core/webrtc/WebRtcClient.kt` | **MODIFIED** | Added `isRestartingIce` atomic guard, `lastRestartTimestamp`, and `activeNetworkHandle` to `WebRtcSession` |
| `app/src/main/java/com/nexa/app/data/repository/P2PTransferRepositoryImpl.kt` | **MODIFIED** | Integrated `NetworkMonitor`, implemented 800ms debounced network interface change observation, rate-limited ICE restart, preserved healthy connections, and added receiver resume signaling |
| `app/src/main/java/com/nexa/app/NexaApplication.kt` | **MODIFIED** | Injected `networkMonitor` singleton into `P2PTransferRepositoryImpl` |
| `app/src/test/java/com/nexa/app/WebRtcIceRestartUnitTests.kt` | **NEW** | 6 unit tests verifying network classification, event detection, atomic restart locking, rate limiting, healthy connection preservation, and chunk continuity |

---

## 4. Implementation Details

### A. Fine-Grained Network Interface Observation
`NetworkMonitor.kt` now tracks:
- `NetworkTypeState`:
  - `Wifi(isMetered: Boolean, isValidated: Boolean)`
  - `Cellular(isMetered: Boolean, isValidated: Boolean)`
  - `Vpn(isValidated: Boolean)`
  - `Disconnected`
- `NetworkChangeEvent(networkState, isOnline, networkHandle, timestamp)`:
  Emitted whenever an interface changes, a network capability validates, or an active connection drops.

### B. Controlled ICE Restart Orchestration
When `onNetworkInterfaceChanged(event)` fires in `P2PTransferRepositoryImpl`:
1. **Debounce (800ms):** Filters out rapid transient intermediary states while the OS settles routing tables.
2. **Active Transfer Inspection:** Finds active transfers in `CONNECTING` or `TRANSFERRING` status.
3. **Healthy Connection Protection:** If the connection is already in `CONNECTED` or `COMPLETED` state on the same network handle, the restart is bypassed to avoid disrupting healthy active streaming.
4. **Interface Shift / Failure Trigger:** If the connection is `DISCONNECTED`, `FAILED`, or the network handle has changed:
   - Updates `session.activeNetworkHandle = event.networkHandle`.
   - Acquires the atomic lock `session.isRestartingIce.compareAndSet(false, true)`.
   - Enforces a 3-second minimum rate-limit between successive restarts on the same session.
   - Calls `webRtcClient.restartIce(pc)` which generates an SDP offer with `IceRestart=true`.
   - Dispatches the SDP offer to the peer via `SignalingMessage.SignalingSdp(sdpType="offer", ...)`.
   - Peer sets remote description, generates SDP answer, and returns it.
   - New ICE candidates from the new network interface are exchanged and added.
   - The connection transitions back to `CONNECTED`.

### C. Transfer Continuity & Chunk Deduplication
Because WebRTC SCTP DataChannels are maintained across ICE restarts on the same `PeerConnection`:
- The SCTP association re-binds to the newly established candidate pair.
- The DataChannel remains in `OPEN` state.
- Streaming continues from `transfer.chunksCompleted` without restarting from byte zero or duplicating chunks.
- Received chunk integrity is verified by ChaCha20-Poly1305 authentication tags.

---

## 5. Test Verification & Results

- **Unit Test File:** [`WebRtcIceRestartUnitTests.kt`](file:///H:/chat_app/app/src/test/java/com/nexa/app/WebRtcIceRestartUnitTests.kt)
- **Tests Executed:**
  1. `testNetworkTypeStateClassifications`: **PASS**. Verifies Wi-Fi, Cellular, and VPN state classifications.
  2. `testNetworkChangeEventDetectsInterfaceShift`: **PASS**. Verifies handle differences between Wi-Fi and Cellular.
  3. `testConcurrentIceRestartProtectionViaAtomicState`: **PASS**. Verifies CAS prevents duplicate concurrent restarts.
  4. `testIceRestartRateLimitingDebounce`: **PASS**. Verifies restarts within 1 second are suppressed while 3.5 seconds are permitted.
  5. `testHealthyConnectionPreservationDuringRedundantCallback`: **PASS**. Verifies healthy connections on the same interface are not disrupted.
  6. `testTransferContinuityAcrossNetworkHandover`: **PASS**. Verifies chunk progress is preserved without zero-byte resets.

---

## 6. Security and Protocol Invariants
- **100% P2P Preserved:** No file chunks ever transit through the signaling server or cloud storage.
- **E2EE Preserved:** DataChannel chunks remain ChaCha20-Poly1305 encrypted.
- **Relay Candidate Disclosure:** If TURN relay candidates are negotiated after network handover, connection diagnostics update accurately.

---

## 7. Status & Verdict
- **Status:** **PASS**
- **Residual Limitations:** If a device loses connectivity completely for longer than the signaling WebSocket heartbeat timeout (typically 60s), the signaling WebSocket must reconnect before the ICE restart SDP offer can be routed.
