# NEXA — Android Compatibility & Transport Reliability Report

**Date:** 2026-11-04  
**Author:** Android Systems & Network Compatibility Specialist  
**Scope:** `LanDiscoveryBridge.kt`, `SmsPayloadCodec.kt`, `NexaDataSmsSender.kt`, `NexaDataSmsReceiver.kt`  
**Status:** COMPLETED — P0 REQUIREMENT VERIFIED  

---

## 1. Android 14+ (API 34/35) NSD Compatibility Architecture

### A. Root Cause Analysis
In Android 14 (API 34, `Build.VERSION_CODES.UPSIDE_DOWN_CAKE`), `NsdManager.resolveService()` was officially deprecated due to long-standing multi-threading race conditions in Android's mDNS daemon:
1. Calling `resolveService()` concurrently while another service is actively resolving triggers a hard exception `NsdManager.FAILURE_ALREADY_ACTIVE` (error code 3).
2. `resolveService()` is one-shot and fails to receive dynamic service updates (IP reassignment, subnet routing changes).
3. Android 14 introduces `NsdManager.registerServiceInfoCallback()`, which provides persistent, thread-safe asynchronous updates.

### B. Dual-Engine Architecture
The modernized `LanDiscoveryBridge` implements runtime SDK dispatching:
- **API 34+ (Android 14 / 15+):**
  Uses `registerServiceInfoCallback(serviceInfo, executor, callback)` with `NsdManager.ServiceInfoCallback`:
  - `onServiceUpdated`: Extracts IPv4/IPv6 address from `resolvedInfo.hostAddresses` or `resolvedInfo.host` and notifies peer listener.
  - `onServiceLost`: Emits peer disappearance and cleans internal routing tables.
  - `onServiceInfoCallbackUnregistered`: Safely cleans registered callback maps.
- **API 24 to 33 Fallback (Android 7.0 – Android 13):**
  Implements a strict sequential FIFO resolution queue (`legacyResolveQueue`). Only one `resolveService()` call is executed at any moment. `completeLegacyResolve()` dequeues the next service only after `onServiceResolved` or `onResolveFailed` completes. This deterministically eliminates `FAILURE_ALREADY_ACTIVE` errors.
- **Duplicate Discovery Suppression:**
  Discovered peer names are de-duplicated via `resolvingPeerNames.add(name)` before resolution begins.
- **Leak-Proof Resource Teardown:**
  `stop()` unregisters all active `ServiceInfoCallback`s, cancels legacy pending resolutions, unregisters discovery/registration listeners, and clears all collections.

---

## 2. Binary Data SMS Transport Safety & Reliability

### A. Strict Zero-UTF8 Binary Byte Preservation
- Binary WAP-push packets over Port 8901 carry ChaCha20-Poly1305 ciphertext.
- All ingestion in `NexaDataSmsReceiver` reads direct `ByteArray` from `SmsMessage.userData` (or `bundle.getByteArray("data")`).
- Zero conversions to `String`, `String(bytes, UTF_8)`, or character sanitizers occur anywhere in the pipeline.

### B. Dual-Format Segmentation & CRC-8 Verification
`SmsPayloadCodec` provides:
1. **Standard 12-byte Header:** `[Magic 2B: 'N','X'][Message ID 8B][Chunk Index 1B][Total Chunks 1B]` (121B payload max, 133B total packet).
2. **Compact 5-byte Header:** `[MsgID: 2B][Seq: 1B][Total: 1B][CRC8: 1B]` (128B payload max, 133B total packet).
3. **CRC-8 SMBus Integrity:** Computed across header and chunk payload using polynomial `x^8 + x^2 + x^1 + 1` (`0x07`). Corrupted packets with mismatched CRC are dropped silently.

### C. Bounded Memory Reassembly Buffer
`ChunkReassembler` guarantees system stability under high load:
- **Strict Bounded Capacity:** Enforces `maxConcurrentSessions = 100`. If new sessions arrive while capacity is reached, the oldest session by creation timestamp is evicted.
- **10-Minute Sliding TTL:** Session expiration timeout reduced from 1 hour to 10 minutes (`600,000 ms`), preventing stale chunk memory retention.
- **Thread-Safe Atomic Assembly:** All chunk ingest operations are synchronized, concatenating ordered binary slices without memory reallocation until full delivery is verified.
