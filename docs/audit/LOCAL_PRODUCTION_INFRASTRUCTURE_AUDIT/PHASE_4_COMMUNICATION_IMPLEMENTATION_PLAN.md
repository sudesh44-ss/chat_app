# NEXA — Phase 4: Multi-Transport Communication Implementation Plan

**Plan Type:** Step-by-Step Remediation & Real-Device Integration Plan  
**Target Codebase:** `H:\chat_app` (Go Backend & Android Client)  
**Authority Hierarchy:** `1architecture.md` / `docs/*.md` / `PHASE_4_REAL_DEVICE_COMMUNICATION_AUDIT.md`  
**Status:** **PROPOSED — AWAITING EXPLICIT USER APPROVAL TO COMMENCE IMPLEMENTATION**

---

## 1. Executive Summary & Objective

This document defines the structured implementation plan to resolve the communication transport issues, carrier network limitations, WebRTC NAT traversal blockers, and outbox persistence gaps identified during the Phase 4 Real-Device Audit (`PHASE_4_REAL_DEVICE_COMMUNICATION_AUDIT.md`).

### Targeted Remediation Areas:
1. **[P0] Data SMS UI Gating & Runtime SecurityException Prevention:** Safe-guard the UI against attempting binary SMS dispatches when `SEND_SMS` is not declared or granted.
2. **[P1] Dynamic WebRTC TURN Relay Integration:** Integrate backend-issued ephemeral TURN credentials (Coturn / Cloudflare Calls) to resolve the 15–25% P2P connection failure rate on Symmetric Carrier NATs.
3. **[P1] Automated WebRTC ICE Restart on Network Handover:** Wire `NetworkMonitor` network-shift events directly to `WebRtcClient.restartIce()` to maintain P2P file streams across Wi-Fi &harr; Cellular transitions.
4. **[P2] Outbox Encrypted Wire Packet Persistence:** Store serialized `wirePacketBytes` in SQLite Room to prevent unnecessary Double Ratchet key chain advancements when pending outbox messages are drained after an app restart.
5. **[P2] Durable Multi-Part SMS Chunk Cache:** Persist incomplete binary SMS chunks in Room to survive app process death during cellular reassembly.
6. **[P2] Physical Fleet Testing Runbook Execution:** Implement automated telemetry hooks to log radio convergence latency and battery consumption on the physical 3-device testbed.

---

## 2. Issue 1: Data SMS UI Exposure & Runtime SecurityException (P0)

### 2.1 File & Class Details
- **Exact Files:**  
  - `app/src/main/java/com/nexa/app/presentation/chat/ChatViewModel.kt`  
  - `app/src/main/java/com/nexa/app/presentation/chat/components/ChatInputBar.kt`  
  - `app/src/main/java/com/nexa/app/data/remote/sms/NexaDataSmsSender.kt`
- **Class/Function:** `ChatViewModel.selectTransport()`, `NexaDataSmsSender.sendDataSms()`

### 2.2 Current Behavior
The UI exposes `TransportType.DATA_SMS` in the transport selection sheet. When selected, `sendDataSms` is invoked, which calls `SmsManager.sendDataMessage()`. Because `SEND_SMS` is not declared in `AndroidManifest.xml` (to comply with Google Play Protect and Android 15 ECM), the call immediately throws a `SecurityException`, resulting in a failed message dispatch or unhandled exception.

### 2.3 Expected Behavior
In standard production builds where telephony permissions are absent, `TransportType.DATA_SMS` must be hidden from the UI selector or disabled with a clear user notice (`"Direct Cellular SMS requires F-Droid build with telephony permissions"`). The dispatcher must fail closed with a user-facing error rather than throwing an unhandled `SecurityException`.

### 2.4 Required Fix
1. In `ChatViewModel.kt`, inspect runtime manifest permissions:
   ```kotlin
   fun getAvailableTransports(): List<TransportType> {
       val transports = mutableListOf(TransportType.INTERNET, TransportType.DTN_MESH)
       if (hasTelephonySmsPermission(context)) {
           transports.add(TransportType.DATA_SMS)
       }
       return transports
   }
   ```
2. In `NexaDataSmsSender.kt:43`, catch permission exceptions gracefully:
   ```kotlin
   if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
       return Result.failure(TelephonyPermissionMissingException("SEND_SMS permission is required for binary Data SMS transport."))
   }
   ```

### 2.5 Risk & Dependencies
- **Risk:** Low. Pure safety guard preventing crashes.
- **Dependencies:** Android permission inspector.
- **Priority:** **P0 (Critical Blocker)**.

---

## 3. Issue 2: Dynamic WebRTC TURN Relay Integration (P1)

### 3.1 File & Class Details
- **Exact Files:**  
  - `app/src/main/java/com/nexa/app/core/webrtc/WebRtcClient.kt`  
  - `backend/cmd/server/main.go`  
  - `backend/internal/api/turn_handler.go` (new)
- **Class/Function:** `WebRtcClient.buildPeerConnection()`

### 3.2 Current Behavior
`WebRtcClient.kt` lists only Google STUN: `stun:stun.l.google.com:19302`. When two devices are connected to mobile carrier networks (which enforce Carrier-Grade Symmetric NAT), direct UDP hole punching fails ~15–25% of the time, resulting in `ICE connection failed`.

### 3.3 Expected Behavior
When initiating WebRTC signaling, the client queries `/api/v1/webrtc/turn-credentials`. The backend returns ephemeral time-limited TURN credentials (e.g. Cloudflare Calls / Coturn). `WebRtcClient` appends both STUN and TURN relay servers to `PeerConnection.IceServer`. If STUN fails, traffic seamlessly relays through TURN.

### 3.4 Required Fix
1. **Backend Endpoint:** Add `GET /api/v1/webrtc/turn-credentials`:
   ```go
   // Generates HMAC-SHA1 ephemeral TURN credentials with 24h TTL
   func (h *TurnHandler) GetCredentials(w http.ResponseWriter, r *http.Request) {
       username, credential := generateTurnCredentials(h.secret, authenticatedNxId, 24*time.Hour)
       json.NewEncoder(w).Encode(TurnCredentialsResponse{
           Uris: []string{"turn:turn.nexa.network:3478?transport=udp", "turn:turn.nexa.network:3478?transport=tcp"},
           Username: username,
           Credential: credential,
           Ttl: 86400,
       })
   }
   ```
2. **Android Client:** Fetch credentials before initializing `PeerConnection`:
   ```kotlin
   val turnCreds = turnApiService.getEphemeralCredentials()
   val iceServers = mutableListOf(
       PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
   )
   turnCreds?.let { creds ->
       iceServers.add(
           PeerConnection.IceServer.builder(creds.uris)
               .setUsername(creds.username)
               .setPassword(creds.credential)
               .createIceServer()
       )
   }
   ```

### 3.5 Risk & Dependencies
- **Risk:** Medium. Requires deployment of a Coturn instance or Cloudflare Calls TURN token in production environment.
- **Priority:** **P1 (High)**.

---

## 4. Issue 3: Automated WebRTC ICE Restart on Network Handover (P1)

### 4.1 File & Class Details
- **Exact Files:**  
  - `app/src/main/java/com/nexa/app/core/webrtc/WebRtcClient.kt`  
  - `app/src/main/java/com/nexa/app/core/network/NetworkMonitor.kt`  
  - `app/src/main/java/com/nexa/app/data/repository/SecureFileTransferRepositoryImpl.kt`
- **Class/Function:** `WebRtcClient.restartIce()`, `SecureFileTransferRepositoryImpl.onNetworkHandover()`

### 4.2 Current Behavior
`WebRtcClient.kt` implements `restartIce()`, which creates an SDP offer with `IceRestart=true`. However, there is no listener connecting `NetworkMonitor` network change events to active file transfer sessions. When a device switches from Wi-Fi to cellular during an active transfer, the connection freezes indefinitely.

### 4.3 Expected Behavior
When `NetworkMonitor` detects an active network interface shift, it notifies the active file transfer session. The session calls `webRtcClient.restartIce()`, gathers new ICE candidates from the new network interface, exchanges candidates with the peer over WebSocket signaling, and resumes SCTP data streaming without restarting from byte 0.

### 4.4 Required Fix
```kotlin
// In SecureFileTransferRepositoryImpl.kt
networkMonitor.networkState
    .drop(1) // Skip initial state
    .onEach { newState ->
        if (activeTransferSessions.isNotEmpty() && newState.isConnected) {
            Log.i(TAG, "Network handover detected during active P2P transfer. Triggering ICE restart...")
            activeTransferSessions.values.forEach { session ->
                session.webRtcClient?.restartIce()
            }
        }
    }
    .launchIn(repositoryScope)
```

### 4.5 Risk & Dependencies
- **Risk:** Low. Utilizes existing `restartIce()` logic.
- **Priority:** **P1 (High)**.

---

## 5. Issue 4: Outbox Encrypted Wire Packet Persistence (P2)

### 5.1 File & Class Details
- **Exact Files:**  
  - `app/src/main/java/com/nexa/app/data/local/entity/MessageEntity.kt`  
  - `app/src/main/java/com/nexa/app/data/repository/ChatRepositoryImpl.kt`
- **Class/Function:** `ChatRepositoryImpl.drainOutboxQueue()`

### 5.2 Current Behavior
When an outgoing message is composed, it is encrypted via Double Ratchet and stored in an in-memory `pendingWirePackets: MutableMap<String, ByteArray>`. If the app process dies while disconnected, `pendingWirePackets` is wiped. On next startup, `drainOutboxQueue()` re-encrypts the pending message. While the same `packetId` is preserved, re-encryption advances the Double Ratchet key chain, causing the recipient to skip ratchet keys.

### 5.3 Expected Behavior
Add a `wirePacketBytes: ByteArray?` column to `MessageEntity`. When a message is first encrypted, persist the exact wire packet bytes to SQLite Room. When draining the outbox after an app restart, dispatch the stored bytes directly, ensuring zero Double Ratchet key advancement.

### 5.4 Required Fix
1. In `MessageEntity.kt`:
   ```kotlin
   @ColumnInfo(name = "wire_packet_bytes", typeAffinity = ColumnInfo.BLOB)
   val wirePacketBytes: ByteArray? = null
   ```
2. In `ChatRepositoryImpl.kt`:
   When draining outbox on reconnect, if `msg.wirePacketBytes != null`, transmit directly without calling `ratchetEncrypt()`. Clear `wirePacketBytes` upon receiving `ACK_DEVICE_DELIVERED`.

### 5.5 Risk & Dependencies
- **Risk:** Low. Room migration v20 &rarr; v21.
- **Priority:** **P2 (Medium)**.

---

## 6. Issue 5: Durable Multi-Part SMS Chunk Cache (P2)

### 6.1 File & Class Details
- **Exact File:** `app/src/main/java/com/nexa/app/core/sms/SmsPayloadCodec.kt`
- **Class/Function:** `ChunkReassembler`

### 6.2 Current Behavior
Incomplete SMS chunks are stored in an in-memory `ConcurrentHashMap`. If part 1 of a multi-part Data SMS arrives and the phone reboots before part 2 arrives, part 1 is permanently lost.

### 6.3 Expected Behavior
Persist incomplete chunks to a Room table `sms_reassembly_chunks` with a 24-hour expiration timestamp. Clean up expired chunks periodically.

### 6.4 Priority: **P2 (Medium)**

---

## 7. Issue 6: 3-Device Physical Mesh Fleet Staging Runbook

### 7.1 Scope & Testbed Setup
- 3 physical Android test devices (Pixel 8, Vivo V27, Samsung S23).
- Place in an RF-attenuated facility with cellular data and external Wi-Fi disabled (Airplane mode ON; BLE and Wi-Fi chips toggled ON).
- Execute Runbook 2 from `PHASE_4_REAL_DEVICE_TEST_MATRIX.md`.
- Measure:
  1. BLE discovery latency (target < 3000ms).
  2. Bloom filter handshake duration (target < 500ms).
  3. Mule store-and-forward delivery latency across moving nodes.
  4. Idle Bluetooth battery consumption (% per hour).

---

## 8. Step-by-Step Implementation Sequence

```
┌────────────────────────────────────────────────────────┐
│ Phase 4 Implementation Gates                           │
└────────────────────────────────────────────────────────┘
                           │
                           ▼
 [Gate 1: User Approval of Audit & Implementation Plan]
                           │
                           ▼
 [Step 1: Data SMS UI Safety Gating & Crash Prevention]
  - Filter available transports based on declared permissions
  - Catch SecurityException in NexaDataSmsSender
  - Run: ./gradlew testDebugUnitTest --tests "*DataSms*"
                           │
                           ▼
 [Step 2: WebRTC TURN Server Integration]
  - Add backend GET /api/v1/webrtc/turn-credentials
  - Update WebRtcClient to inject TURN relay servers
  - Run: go test ./internal/api/...
                           │
                           ▼
 [Step 3: WebRTC ICE Restart on Network Handover]
  - Wire NetworkMonitor state changes to active P2P sessions
  - Trigger restartIce() on Wi-Fi <-> Cellular shift
  - Run: ./gradlew testDebugUnitTest --tests "*IceRestart*"
                           │
                           ▼
 [Step 4: Outbox Wire Packet Persistence]
  - Add wire_packet_bytes to MessageEntity
  - Update outbox drain logic in ChatRepositoryImpl
  - Run: ./gradlew testDebugUnitTest --tests "*ChatMessaging*"
                           │
                           ▼
 [Step 5: Physical Fleet Staging Execution]
  - Deploy builds to staging physical devices (Pixel 8, Vivo V27, Samsung S23)
  - Execute Runbooks 1, 2, and 3
  - Record physical fleet telemetry
                           │
                           ▼
 [Gate 2: Phase 4 Final Verification Report]
```

---

## 9. Verification & Phase Completion Criteria

The Phase 4 remediation will be considered **COMPLETE** when:
1. Data SMS no longer causes runtime crashes when selected without permissions.
2. WebRTC peer connections successfully negotiate over TURN relays when STUN is blocked by Symmetric NAT.
3. In-flight P2P file transfers automatically survive Wi-Fi &harr; Cellular network switches via automated ICE restart.
4. Outbox messages survive app restarts without advancing Double Ratchet key chains.
5. All 190 automated transport tests pass without regressions.
6. Physical QA runbooks are executed and certified on real Android hardware.

---

**End of Implementation Plan.**  
*Awaiting explicit user approval before touching source files.*
