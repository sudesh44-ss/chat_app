# NEXA — Phase 3 Initial Architecture & Stability Audit Report

**Date:** 2026-11-04  
**Author:** NEXA Systems & Reliability Engineering Team  
**Scope:** Android Client (`H:\chat_app\app`) & Go Backend (`H:\chat_app\backend`)  
**Status:** COMPLETE — BASELINE AUDIT FOR PHASE 3 STABILITY, OPTIMIZATION & RELEASE READINESS  

---

## 1. Executive Summary

Following the successful completion of Phase 1 (Cryptographic Core, Double Ratchet, Room SQLCipher, Ed25519 Handshake) and Phase 2 (Authentication, Multi-Device Sync, Sequential Room Migrations, Privacy FCM, 124/124 Unit Tests Passing), Phase 3 focuses on **real-world stability, high-throughput reliability, network fault tolerance, resource optimization, Android 14/15/16-KB compatibility, and Google Play release packaging**.

This audit inspects every component in the codebase to identify critical reliability issues, memory leaks, concurrency races, protocol mismatches, and release obstacles.

---

## 2. Severity-Ranked Audit Findings

### 🔴 CRITICAL (P0) — Immediate Fix Required

#### Finding P3-01: WebRTC Native Teardown Order & Memory Leak in P2P Engine
- **Location:** `app/src/main/java/com/nexa/app/core/webrtc/WebRtcClient.kt` & `app/src/main/java/com/nexa/app/data/repository/P2PTransferRepositoryImpl.kt` (lines 466–475)
- **Defect:** In `cleanupTransfer()`, `activePeerConnections.remove(transferId)?.dispose()` is called before `activeDataChannels.remove(transferId)?.dispose()`. Furthermore, neither `DataChannel.close()` nor `PeerConnection.close()` is called prior to `dispose()`.
- **Impact:** Disposing a native `PeerConnection` before closing and disposing its attached SCTP `DataChannel` triggers native C++ memory corruption (`SIGSEGV` / `use-after-free` in `libjingle_peerconnection_so.so`).
- **Resource Leak:** In `setupReceiverPeerConnection()`, `RandomAccessFile(tmpFile, "rw")` is allocated. If the connection fails or is aborted before EOF, the file descriptor is never closed, leading to file descriptor leakage.
- **Missing State Machine:** No thread-safe lifecycle state machine (`NEW`, `INITIALIZING`, `CONNECTING`, `CONNECTED`, `DISCONNECTED`, `RECONNECTING`, `FAILED`, `CLOSING`, `CLOSED`) guards against double-close or concurrent invocation.
- **Handover Failure:** No controlled ICE restart (`MediaConstraints` with `IceRestart=true`) is implemented to recover from Wi-Fi <-> Cellular handovers.

---

#### Finding P3-02: Android 14+ (API 34/35) NSD Service Resolution Failure
- **Location:** `app/src/main/java/com/nexa/app/core/webrtc/LanDiscoveryBridge.kt` (lines 79–92)
- **Defect:** `LanDiscoveryBridge` uses `NsdManager.resolveService()` directly inside `onServiceFound()`. 
- **Impact:** 
  1. `NsdManager.resolveService()` was formally deprecated in Android 14 (API 34).
  2. Calling `resolveService()` concurrently while an existing resolution is in flight fails immediately with `NsdManager.FAILURE_ALREADY_ACTIVE` (error code 3). In multi-peer environments, peer resolution fails deterministically.
  3. No duplicate filtering exists: multiple mDNS multicast responses trigger repeated resolution requests.
  4. Active resolve callbacks are not retained or cancelled during `stop()`.

---

#### Finding P3-03: Outbox Double Ratchet Key Re-Encryption & Data SMS Transport Inefficiency
- **Location:** `app/src/main/java/com/nexa/app/data/repository/ChatRepositoryImpl.kt` (lines 199 & 488) & `app/src/main/java/com/nexa/app/core/sms/SmsPayloadCodec.kt`
- **Defect 1 (Key Burning):** When a message is initially dispatched via `sendMessage()`, `doubleRatchetEngine.ratchetEncrypt()` is executed, advancing the sender ratchet chain. If offline, the message remains `PENDING`. When `drainOutboxQueue()` triggers upon reconnection, it calls `ratchetEncrypt()` a *second* time for the same message!
- **Impact:** Advances the Double Ratchet key schedule twice for a single message, corrupting ratchet chain sequence synchronization and causing recipient decryption failures.
- **Defect 2 (SMS Segmentation & Header Overhead):** Current Data SMS header uses 12 bytes (`[Magic 2B][Message ID 8B][Chunk Index 1B][Total Chunks 1B]`), leaving only 121 bytes for payload. It lacks CRC8 packet integrity validation and has an unbounded session cache in `ChunkReassembler` (1-hour TTL without count limits).
- **Required Standard:** 5-byte compact header `[MsgID: 2B][Seq: 1B][Total: 1B][CRC8: 1B]` with max 100 concurrent reassembly slots, 10-minute expiry, and strict zero-UTF8 binary byte integrity.

---

#### Finding P3-04: WebSocket Manager Reconnection Device ID Loss & Backoff Jitter
- **Location:** `app/src/main/java/com/nexa/app/core/network/WebSocketManager.kt` (lines 58–69, 192–205)
- **Defect:** `connect(nxId: String, deviceId: String?)` caches `currentNxId` but ignores `deviceId`. When `scheduleReconnect()` re-establishes connection after network drops, it invokes `initiateSocketConnection(id)` without passing `deviceId`.
- **Impact:** The client reconnects as `default` device, breaking multi-device routing and sibling fanout on the Go backend relay.
- **Jitter Defect:** Reconnection uses deterministic exponential backoff without randomized jitter (`delayMs = min(30000, delayMs * 2)`), risking "thundering herd" reconnection storms on backend relay restart.
- **Zombie Socket:** Old `activeWebSocket` is not explicitly closed or terminated before initiating a new connection instance on network handover.

---

#### Finding P3-05: Go Backend Binary Packet Concatenation & Closed Channel Panic
- **Location:** `backend/internal/connection/client.go` (lines 278–282) & `backend/internal/connection/hub.go` (lines 76, 120, 141, 153, 178, 200)
- **Defect 1 (Packet Corruption):** In `ClientSession.WritePump()`:
  ```go
  w, _ := c.Conn.NextWriter(websocket.BinaryMessage)
  w.Write(message)
  for i := 0; i < len(c.Send); i++ {
      w.Write(<-c.Send)
  }
  ```
  This drains queued binary messages by appending them into the *same* WebSocket frame! Because Protobuf messages do not contain self-delimiting length frames at this layer, the Android client fails to parse concatenated frames or silently drops appended packets during high-speed burst flushes.
- **Defect 2 (Send on Closed Channel Panic):** `Hub.Unregister()` closes `session.Send`. If another goroutine (such as `Hub.RouteDecodedPacket` or sibling multi-device sync) attempts to send into `session.Send` concurrently, Go run-time throws an unrecoverable panic: `panic: send on closed channel`.

---

#### Finding P3-06: Release Build Configuration, ProGuard Rules & 16 KB Page-Size Audit
- **Location:** `app/build.gradle.kts`
- **Defect:**
  1. `app/proguard-rules.pro` does not exist on disk, while `build.gradle.kts` references it.
  2. `isMinifyEnabled` is set to `false` in `release` build type.
  3. No release signing configuration (`signingConfigs.release`) is defined.
  4. 16 KB page-size compatibility audit must be verified across all native libraries (`libsqlcipher.so`, `libjingle_peerconnection_so.so`, `libimage_processing_util_jni.so`, `libsurface_util_jni.so`, `libandroidx.graphics.path.so`) across all 64-bit architectures (`arm64-v8a`, `x86_64`) in both APK and AAB release bundles.

---

### 🟡 HIGH (P1) — Optimization & Fault Tolerance

#### Finding P3-07: Compose Recomposition and Memory Allocations
- In transfer progress and chat UI, list items and progress states should use `@Immutable` data models and stable keys in `LazyColumn` to eliminate excessive recompositions during 64 KB chunk transfers.

#### Finding P3-08: Go Backend Goroutine Bounds & Signaling Circuit Breaker
- In `backend/internal/signaling/hub.go`, verify circuit breakers and push debouncing under high-load peer connection renegotiation.

---

## 3. Action Plan & Implementation Sequence

| Step | Target Component | File(s) | Target Goal |
|---|---|---|---|
| **Step 3** | WebRTC Resource Cleanup & ICE Restart | `WebRtcClient.kt`, `P2PTransferRepositoryImpl.kt` | Correct disposal sequence, atomic state machine, handle leaks, ICE restart handover |
| **Step 4** | Android 14+ NSD & Binary Data SMS | `LanDiscoveryBridge.kt`, `SmsPayloadCodec.kt`, `NexaDataSmsSender.kt`, `NexaDataSmsReceiver.kt` | `ServiceInfoCallback` API 34+, 5-byte header, bounded reassembly, zero key burn |
| **Step 5** | Offline Messaging & WebSocket Reconnect | `WebSocketManager.kt`, `ChatRepositoryImpl.kt`, `client.go`, `hub.go` | Jittered backoff, deviceId preservation, safe channel sends, unconcatenated frames |
| **Step 6** | Physical Device Test Protocol | `docs/PHASE_3_PHYSICAL_DEVICE_TEST_REPORT.md` | Formal test matrix & blocked hardware documentation |
| **Step 7** | 16 KB Page-Size Compatibility Audit | `docs/16KB_PAGE_SIZE_COMPATIBILITY_REPORT.md` | Complete verification of all 64-bit ELF LOAD segments |
| **Step 8** | Security Invariants Verification | All security components | Verify zero plaintext leaks, SQLCipher, Ed25519, Double Ratchet |
| **Step 9** | ProGuard, Release Signing & Bundle | `proguard-rules.pro`, `build.gradle.kts` | Production release bundle (`.aab`) & release APK (`.apk`) |
| **Step 10** | Play Store & Play Protect Reports | `docs/PLAY_STORE_RELEASE_CHECKLIST.md`, `docs/PLAY_PROTECT_INVESTIGATION_REPORT.md` | Final readiness & release verification |
