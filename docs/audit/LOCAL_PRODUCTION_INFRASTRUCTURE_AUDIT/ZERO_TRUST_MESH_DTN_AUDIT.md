# NEXA — Zero-Trust Encrypted Mesh & DTN Architecture: Read-Only Implementation Audit

**Document Reference:** `docs/audit/ZERO_TRUST_MESH_DTN_AUDIT.md`  
**Execution Date:** November 9, 2026  
**Project:** NEXA Messaging Application (Android + Go Backend)  
**Workspace:** `H:\chat_app`  
**Audit Scope:** Android Client (`app/`), Go Backend (`backend/`), Cryptographic Infrastructure, Packet Formats, Room Database, and DTN Store-Carry-Forward Pipelines  
**Audit Mode:** Phase A — Read-Only Implementation Audit  

---

## 1. Executive Summary

This technical audit evaluates NEXA's existing implementation of **Zero-Trust Encrypted Mesh Networking and Delay-Tolerant Networking (DTN)**. The core objective is to enable privacy-preserving, store-carry-forward messaging across multiple intermediate devices—including moving devices belonging to untrusted strangers—without requiring continuous internet connectivity, without exposing private keys, and without leaking sender or recipient identities to relays.

### Core Architectural Conclusions
1. **Blind Relay Muling Exists and Protects E2EE Content:**  
   The Android application contains a working store-carry-forward pipeline implemented in [`NearbyMeshManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dtn/NearbyMeshManager.kt) and [`DtnRepositoryImpl.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/DtnRepositoryImpl.kt). Intermediate devices forward sealed binary envelopes ([`DtnEnvelope.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/dtn/DtnEnvelope.kt)) without possessing the keys required to decrypt the inner Double Ratchet payload.
2. **Critical Routing Privacy Vulnerability (Blind Token Linkability):**  
   The current routing token generator in [`BlindTokenGenerator.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/dtn/BlindTokenGenerator.kt) computes `BlindToken = Truncate_16(Blake3(recipientPublicKey || "NEXA_BLIND_DAY_SALT_V1_" || epochDay))`. Because a user's public key is static and the day salt is public, **any passive observer or rogue relay can pre-calculate a target user's blind token and track all envelopes destined for that user across the mesh and cloud relays.**
3. **Outer Envelope Tamper Resistance Gap:**  
   While the inner Double Ratchet payload is cryptographically authenticated with Poly1305 MAC, the outer binary envelope (`DtnEnvelope`) has **no digital signature or sender authentication**. An adversarial relay can alter `expiresAtEpochSec` or `hopLimit` without intermediate nodes detecting tampering before expending battery and storage.
4. **Cloud Gateway WebSocket Routing Disconnect:**  
   The Go backend provides `/api/v1/relay/opportunistic-dump` ([`dtn_handler.go`](file:///H:/chat_app/backend/internal/api/dtn_handler.go)), but the `Hub` ([`hub.go`](file:///H:/chat_app/backend/internal/connection/hub.go)) never registers client sessions for blind tokens, and WebSocket session flush only checks `"pending_" + nxId` rather than `"dtn_" + blindTokenHex`. Consequently, envelopes dumped to the cloud bridge by mules cannot currently be delivered to online recipients over WebSocket.
5. **Bloom Filter Saturation Risk:**  
   Peer synchronization in `NearbyMeshManager` relies on a 64-byte (512-bit) Bloom filter. When local envelope caches approach the 500-envelope quota, the Bloom filter false positive rate exceeds 80%, causing nodes to falsely believe peers already have envelopes and prematurely suppressing necessary forwards.
6. **Delivery State Granularity Gap:**  
   `MessageStatus` currently supports only `PENDING`, `SENT`, `DELIVERED`, `READ`, `FAILED`. It lacks `QUEUED`, `RELAYED`, and `EXPIRED`. Custody transfer to an intermediate data mule is not surfaced to sender UI as `RELAYED`, and is conflated with recipient delivery.

---

## 2. Feature Classification Matrix

Every inspected feature has been classified under the required five strict audit states:

| Component / Subsystem | Implementation File | Status | Description & Audit Evidence |
| :--- | :--- | :---: | :--- |
| **Double Ratchet E2EE** | [`DoubleRatchetEngine.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/crypto/DoubleRatchetEngine.kt) | **VERIFIED IMPLEMENTED** | X25519 DH ratchet, KDF root/chain ratchets, skipped-key cache in Room (`SkippedKeyEntity`), Poly1305 AEAD MAC verification. |
| **Keystore Master Encryption** | [`CryptoKeystoreManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt) | **VERIFIED IMPLEMENTED** | AES-256 GCM master key in `AndroidKeyStore`. Encrypts raw identity keys at rest; wipes RAM with `Arrays.fill(0)`. |
| **Hardware-Backing Verification** | [`CryptoKeystoreManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt) | **PARTIALLY IMPLEMENTED** | Master key is in `AndroidKeyStore`, but code does not query `KeyInfo.isInsideSecureHardware()` to verify StrongBox/TEE. |
| **Ed25519 Identity Signatures** | [`Ed25519Signer.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/crypto/Ed25519Signer.kt) | **VERIFIED IMPLEMENTED** | RFC 8032 point multiplication and signature generation/verification. |
| **Universal Transport Ingestion** | [`NexaUniversalTransportIngest.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/transport/NexaUniversalTransportIngest.kt) | **VERIFIED IMPLEMENTED** | Unified ingestion across WS, SMS, and Mesh; persistent pre-decryption deduplication; atomic Room persistence. |
| **Sealed Binary DTN Envelope** | [`DtnEnvelope.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/dtn/DtnEnvelope.kt) | **PARTIALLY IMPLEMENTED** | 45-byte binary envelope serialization; lacks magic bytes, protocol version, envelope type, and outer sender signature. |
| **Recipient Routing Identifiers** | [`BlindTokenGenerator.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/dtn/BlindTokenGenerator.kt) | **PARTIALLY IMPLEMENTED** | Uses deterministic `Blake3(pubKey || daySalt)`. Flawed: enables third-party tracking; lacks shared routing secret $K_{route}$. |
| **Local DTN Room Vault** | [`DtnEnvelopeDao.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/dao/DtnEnvelopeDao.kt), [`DtnEnvelopeEntity.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/entity/DtnEnvelopeEntity.kt) | **VERIFIED IMPLEMENTED** | Persistent SQLCipher storage; survives app and device restarts; indexed on `blindTokenHex` and `expiresAtEpochSec`. |
| **Hop Limit & TTL Decrement** | [`DtnEnvelope.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/dtn/DtnEnvelope.kt), [`NearbyMeshManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dtn/NearbyMeshManager.kt) | **VERIFIED IMPLEMENTED** | Default hop limit 7; decremented on each relay hop; dropped when 0; drops envelopes past 48-hour TTL. |
| **Storage Quota & FIFO Prune** | [`NearbyMeshManager.kt#L260`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dtn/NearbyMeshManager.kt#L260) | **PARTIALLY IMPLEMENTED** | Global FIFO limit of 500 items; vulnerable to cache-flush DoS (no per-peer or per-bandwidth quotas). |
| **Peer Inventory Exchange** | [`NearbyMeshManager.kt#L174`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dtn/NearbyMeshManager.kt#L174) | **PARTIALLY IMPLEMENTED** | 64-byte Bloom filter exchanged on connection; saturates at 500 items, causing up to 85% false positives. |
| **Persistent Deduplication** | [`NexaUniversalTransportIngest.kt#L162`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/transport/NexaUniversalTransportIngest.kt#L162) | **VERIFIED IMPLEMENTED** | Exact DB lookup `messageDao.getMessageByPacketId()` blocks duplicates across restarts and transports. |
| **Zero-Trust Relay Execution** | [`DtnRepositoryImpl.kt#L234`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/DtnRepositoryImpl.kt#L234) | **VERIFIED IMPLEMENTED** | Intermediate nodes store and forward without possessing Double Ratchet keys; payloads remain opaque ciphertext. |
| **Nearby Connections P2P Mesh** | [`NearbyMeshManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dtn/NearbyMeshManager.kt) | **VERIFIED IMPLEMENTED** | Google Play Services Nearby Connections with `Strategy.P2P_CLUSTER` and service ID `com.nexa.dtn.mesh`. |
| **Raw BLE GATT / L2CAP CoC** | [`MeshDrivers.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/mesh/transport/MeshDrivers.kt), [`MeshTransportRouter.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/mesh/transport/MeshTransportRouter.kt) | **PARTIALLY IMPLEMENTED** | `BleL2capDriver` interface exists; routing logic exists in router; concrete driver implementation missing. |
| **Native Wi-Fi Aware Driver** | [`MeshDrivers.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/mesh/transport/MeshDrivers.kt) | **NOT IMPLEMENTED** | `WifiAwareMeshDriver` interface exists, but concrete Android `WifiAwareManager` driver is not implemented. |
| **Algorithmic MANET Engines** | `app/src/main/java/com/nexa/app/core/mesh/engine/` | **VERIFIED IMPLEMENTED** | `AlohaBroadcastThrottle`, `BackpressureController`, `CompositeMetricEngine`, `LqiDecayEngine`, `SlidingReorderBuffer`. |
| **Opportunistic Cloud Bridge (Client)** | [`DtnSyncWorker.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dtn/DtnSyncWorker.kt), [`DtnRelayApiImpl.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/api/DtnRelayApiImpl.kt) | **VERIFIED IMPLEMENTED** | Batches envelopes from local SQLite and executes `POST /api/v1/relay/opportunistic-dump` on network available. |
| **Opportunistic Cloud Bridge (Backend)** | [`dtn_handler.go`](file:///H:/chat_app/backend/internal/api/dtn_handler.go), [`hub.go`](file:///H:/chat_app/backend/internal/connection/hub.go) | **PARTIALLY IMPLEMENTED** | Endpoint accepts batch dumps; validates headers; but fails to route to online clients (blind tokens not registered). |
| **Delivery ACKs** | [`NexaUniversalTransportIngest.kt#L247`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/transport/NexaUniversalTransportIngest.kt#L247) | **VERIFIED IMPLEMENTED** | Ingestion pipeline dispatches `ACK_DEVICE_DELIVERED` bound to original `packetId`. |
| **Delivery State Model** | [`MessageStatus.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/domain/model/MessageStatus.kt) | **PARTIALLY IMPLEMENTED** | Lacks `QUEUED`, `RELAYED`, `EXPIRED`. Relay custody is not distinct from final delivery in UI. |
| **Continuous Background Relaying** | [`AndroidManifest.xml`](file:///H:/chat_app/app/src/main/AndroidManifest.xml) | **BLOCKED BY PLATFORM** | Android Doze mode and background limits terminate BLE discovery unless running a foreground service with `connectedDevice`. |

---

## 3. Deep Architectural Inspection

### 3.1 Android Cryptographic Architecture & Keystore

#### Double Ratchet & Prekey Agreement
- **Implementation:** [`DoubleRatchetEngine.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/crypto/DoubleRatchetEngine.kt).
- **Session Keys:**
  - `RootKey`: 32 bytes derived via HKDF-SHA256 from initial shared DH secret.
  - `SenderChainKey` & `ReceiverChainKey`: Ratcheted continuously per message using symmetric HMAC-SHA256.
  - `MessageKey`: Derived from chain keys; encrypts payload using AES-256-GCM / Poly1305.
  - `DH Ratchet`: Generates fresh ephemeral X25519 keypairs upon receiving reply, providing forward secrecy and post-compromise security.
- **Offline Session Resumption:**
  - Session state is persisted in SQLCipher table `ratchet_sessions` via [`RatchetDao.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/local/dao/RatchetDao.kt).
  - Out-of-order packets: Skipped message keys are cached in `skipped_message_keys` with index `(contactNxId, ratchetPublicKeyHex, messageIndex)`, allowing offline packets to decrypt safely regardless of arrival order.
- **Android Keystore Master Protection:**
  - [`CryptoKeystoreManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt) maintains alias `nexa_master_vault_key` using `KeyProperties.KEY_ALGORITHM_AES` in `AndroidKeyStore`.
  - Raw private keys (32-byte Ed25519 signing seed, 32-byte X25519 DH key) are encrypted at rest with the master key.
  - Volatile buffers are explicitly zeroed with `Arrays.fill(raw, 0.toByte())` inside `withDecryptedPrivateKey`.

---

### 3.2 Privacy-Preserving Routing Identifiers

#### The Blind Token Generator Flaw
In [`BlindTokenGenerator.kt:18-28`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/dtn/BlindTokenGenerator.kt#L18-L28):
```kotlin
fun generateToken(recipientPublicKeyBytes: ByteArray, epochDay: Long = currentEpochDay()): ByteArray {
    val saltBytes = "$SALT_PREFIX$epochDay".toByteArray(StandardCharsets.UTF_8)
    val input = ByteArray(recipientPublicKeyBytes.size + saltBytes.size)
    System.arraycopy(recipientPublicKeyBytes, 0, input, 0, recipientPublicKeyBytes.size)
    System.arraycopy(saltBytes, 0, input, recipientPublicKeyBytes.size, saltBytes.size)

    val fullHash = Blake3Hasher.hash(input)
    val token = ByteArray(16)
    System.arraycopy(fullHash, 0, token, 0, 16)
    return token
}
```

#### Vulnerability Analysis
1. **Public Key Determinism:**
   A recipient's public key is known to any contact who looked up the user. Because `SALT_PREFIX = "NEXA_BLIND_DAY_SALT_V1_"` is hardcoded and `epochDay` is public clock time, the token is deterministic for any observer.
2. **Global Tracking:**
   An adversary with a list of target public keys can compute all daily blind tokens in advance. By listening to Nearby Connections cluster syncs or intercepting `/api/v1/relay/opportunistic-dump` on the backend, the adversary can track when specific users receive messages without decrypting the payload.
3. **Required Architecture (Phase C):**
   - Routing identifiers must be computed as:
     $$\text{RecipientRoutingID} = \text{Truncate}_{16}\left(\text{HMAC-SHA256}(K_{route}, \text{RecipientReference} \parallel \text{EpochWindow})\right)$$
   - $K_{route}$ must be derived from the shared Double Ratchet root key or initial bilateral DH secret ($SK$).
   - Strangers and third-party relays do not possess $K_{route}$ and cannot link the routing ID to any known user or identity key.

---

### 3.3 Universal Packet Envelope Analysis

#### Current Binary Envelope Structure (`DtnEnvelope.kt`)
```
+----------------------------------------------------------------------------------------------------+
| EnvelopeID (16B) | BlindToken (16B) | HopLimit (1B) | ExpiresAt (8B) | PayloadSize (4B) | Payload  |
+----------------------------------------------------------------------------------------------------+
  <-------------------------- 45 Bytes Header --------------------------> <--- Encrypted Bytes --->
```

#### Missing Structural Elements (Phase D)
1. **Magic Bytes:** Missing 4-byte identification header (e.g., `0x4E, 0x58, 0x44, 0x54` = `"NXDT"`).
2. **Protocol Version:** Missing 1-byte version field (e.g., `0x01`).
3. **Packet Type:** Missing 1-byte packet type identifier (e.g., `DATA`, `ACK`, `SYNC`).
4. **Sender Envelope Signature:** The outer header is unsigned. An attacker can modify `ExpiresAt` or `BlindToken` in transit.
5. **Separation of Immutable vs Mutable Data:**
   - **Immutable Authenticated Envelope:** `[Magic][Version][UUID][RoutingID][Type][CreatedAt][ExpiresAt][PayloadLen][EncryptedPayload][SenderEd25519Signature]`.
   - **Mutable Relay Metadata:** `[HopLimit][HopCount][RelayTimestamp]`.

---

### 3.4 Blind Relay Muling & Store-Carry-Forward

#### Data Mule Custody Flow ([`NearbyMeshManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dtn/NearbyMeshManager.kt))
1. **Cluster Formation:** Uses Google Nearby Connections `Strategy.P2P_CLUSTER` advertising service `com.nexa.dtn.mesh`.
2. **Bloom Filter Exchange:** On connection, sends 64-byte Bloom filter.
3. **Delta Replication:** Peer sends envelopes that are not recognized in the Bloom filter and have `hopLimit > 0`.
4. **Relay Persistence:**
   - Mule verifies `hopLimit > 0` and `expiresAtEpochSec > now`.
   - Decrements `hopLimit = (hopLimit - 1).toByte()`.
   - Inserts into local Room `dtn_envelopes`.
   - Mule cannot decrypt `encryptedPayload` because it lacks the recipient's Double Ratchet keys.
5. **Storage Quota Enforcement:**
   - Enforces `MAX_CACHE_ENVELOPE_COUNT = 500`.
   - When limit is reached, executes `envelopeDao.pruneOldest(100)` to reclaim space.
   - *Vulnerability:* Rogue peers can exploit this by spamming 500 bogus envelopes to evict genuine queued envelopes.

---

### 3.5 Cloud Gateway Bridge & Backend Routing

#### Client Upload Pipeline ([`DtnSyncWorker.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dtn/DtnSyncWorker.kt))
- WorkManager schedules periodic opportunistic dump when `NetworkType.CONNECTED` is detected.
- Queries `envelopeDao.getActiveEnvelopesForSync(nowSec)`.
- Dispatches batched binary envelopes via `POST /api/v1/relay/opportunistic-dump` (`DtnRelayApiImpl.kt`).

#### Backend Disconnect ([`hub.go`](file:///H:/chat_app/backend/internal/connection/hub.go) & [`dtn_handler.go`](file:///H:/chat_app/backend/internal/api/dtn_handler.go))
1. When `/api/v1/relay/opportunistic-dump` receives batched envelopes:
   ```go
   h.hub.RouteDtnEnvelope(blindTokenHex, rawBytes)
   ```
2. In `hub.go:296-311`:
   - Checks `h.blindTokens[blindTokenHex]`.
   - **Defect:** `RegisterBlindToken` is never called by the WebSocket connection lifecycle.
   - The hub always assumes the recipient is offline and pushes the envelope to Redis under `"dtn_" + blindTokenHex`.
3. When the recipient device connects to WebSocket:
   - Hub calls `flushPendingQueue(session.NxId, session)`, which queries Redis key `"pending_" + session.NxId`.
   - **Defect:** It never checks `"dtn_" + blindTokenHex`!
   - Result: All opportunistic envelopes dumped to the cloud bridge are orphaned in Redis until their 24h TTL expires.

---

## 4. Security, Privacy & Abuse Analysis

### 4.1 Threat Model Evaluation

| Threat Vector | Current Defense Status | Risk Level | Remediating Action Required |
| :--- | :--- | :---: | :--- |
| **Payload Eavesdropping by Mule** | Double Ratchet AEAD payload encryption prevents decryption by any node lacking session keys. | **LOW** | Preserve existing Double Ratchet protocol. |
| **Metadata Tracking via Blind Tokens** | Public key + static day salt creates deterministic, linkable tokens across all relays. | **HIGH** | Replace with shared-secret $K_{route}$ HMAC routing identifiers. |
| **Outer Envelope Forgery / Tampering** | Outer envelope is unsigned; relays cannot verify if expiry epoch or tokens were modified. | **HIGH** | Implement Ed25519 digital signature on immutable envelope header. |
| **Cache-Eviction Denial of Service** | Any peer can push 500 bogus envelopes, triggering FIFO purge of genuine queued messages. | **HIGH** | Add per-peer ingress quotas, packet size validation, and minimum PoW or signature checks. |
| **Bloom Filter Dropped Envelopes** | 64-byte Bloom filter saturates at 500 items ($P_{fp} > 80\%$), suppressing replication. | **HIGH** | Replace/augment Bloom filter with exact UUID set exchange or Merkle tree sync. |
| **Delivery ACK Forgery** | Intermediate nodes could theoretically generate bogus ACKs if ACKs are not recipient-signed. | **MEDIUM** | Ensure delivery ACKs require authenticated recipient signature bound to message UUID. |
| **Relay Battery & Radio Exhaustion** | Continuous background discovery without throttling can rapidly drain mobile device battery. | **MEDIUM** | Integrate `BackpressureController` and `CompositeMetricEngine` battery cost scaling. |

---

## 5. Exact Files Requiring Modification in Phases B–L

### 5.1 Android Client (`app/`)
1. [`app/src/main/java/com/nexa/app/core/dtn/DtnEnvelope.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/dtn/DtnEnvelope.kt):
   - Redesign binary format: magic bytes (`NXDT`), protocol version, message UUID, ephemeral routing ID, packet type, creation/expiry timestamps, immutable authenticated payload, sender Ed25519 signature, mutable relay metadata (hop limit, hop count).
2. [`app/src/main/java/com/nexa/app/core/dtn/BlindTokenGenerator.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/dtn/BlindTokenGenerator.kt):
   - Replace deterministic public-key hash with shared-secret $K_{route}$ ephemeral HMAC routing identifiers with $\pm 1$ epoch rotation window.
3. [`app/src/main/java/com/nexa/app/core/dtn/DtnConstants.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/dtn/DtnConstants.kt):
   - Define protocol magic constants, maximum envelope sizes (64 KB for BLE frames / 1 MB for Wi-Fi streams), and per-peer ingress rate limits.
4. [`app/src/main/java/com/nexa/app/data/remote/dtn/NearbyMeshManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/remote/dtn/NearbyMeshManager.kt):
   - Replace saturated 64-byte Bloom filter with exact set delta exchange or Merkle tree sync.
   - Add per-peer rate limiting and storage quotas to prevent cache-flush DoS.
5. [`app/src/main/java/com/nexa/app/data/repository/DtnRepositoryImpl.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/data/repository/DtnRepositoryImpl.kt):
   - Integrate signed envelope construction and $K_{route}$ routing identifier resolution.
   - Update message delivery states (`CREATED` $\to$ `QUEUED` $\to$ `RELAYED` $\to$ `DELIVERED`).
6. [`app/src/main/java/com/nexa/app/core/transport/NexaUniversalTransportIngest.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/transport/NexaUniversalTransportIngest.kt):
   - Update `processDtnEnvelope` to parse versioned envelope and verify signature before ingestion.
7. [`app/src/main/java/com/nexa/app/domain/model/MessageStatus.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/domain/model/MessageStatus.kt):
   - Add `QUEUED`, `RELAYED`, and `EXPIRED` states.
8. [`app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/security/CryptoKeystoreManager.kt):
   - Add `isHardwareBacked(): Boolean` method to verify `KeyInfo.isInsideSecureHardware()` on the active master key.
9. [`app/src/main/AndroidManifest.xml`](file:///H:/chat_app/app/src/main/AndroidManifest.xml):
   - Add foreground service declaration for mesh muling with `android:foregroundServiceType="connectedDevice|dataSync"` to survive background execution.

### 5.2 Go Backend (`backend/`)
1. [`backend/internal/connection/hub.go`](file:///H:/chat_app/backend/internal/connection/hub.go):
   - Implement client blind token registration on WebSocket connection so cloud bridge dumps can be routed immediately to online recipients.
   - In `flushPendingQueue`, check and deliver queued envelopes from Redis key `"dtn_" + blindToken`.
2. [`backend/internal/api/dtn_handler.go`](file:///H:/chat_app/backend/internal/api/dtn_handler.go):
   - Update envelope parser to support the versioned `NXDT` envelope format.

---

## 6. Implementation Action Plan (Phases B–L)

```
[Phase B: Cryptographic Architecture & Envelope Signing]
  - Define immutable envelope authenticated with Ed25519.
  - Implement Hardware-Backed Keystore verification utility.

[Phase C: Privacy-Preserving Routing Identifiers]
  - Implement K_route HMAC-based ephemeral routing ID generator with epoch rotation.
  - Recipient detection pipeline with multi-epoch tolerance.

[Phase D: Universal Versioned Packet Envelope]
  - Implement NXDT binary envelope with magic bytes, version, UUID, routing ID, and strict bounds.

[Phase E & F: Blind Relay, Store-and-Forward & Exact Deduplication]
  - Hardened local Room queue with priority and retry scheduling.
  - Replace saturated Bloom filter with exact UUID inventory exchange.
  - Ingress abuse control and per-peer cache quotas.

[Phase G & H: Zero-Trust Policy & Discovery Routing]
  - Decouple social graph from relaying.
  - Wire CompositeMetricEngine (ETX, battery penalty) into relay decision path.

[Phase I: Internet Gateway Bridge Reconciliation]
  - Fix Hub blind-token registration and Redis queue flushing in Go backend.

[Phase J: Delivery Semantics & State Model]
  - Add RELAYED, QUEUED, EXPIRED to MessageStatus.
  - Distinguish relay custody ACK from final recipient delivery ACK.

[Phase K & L: Automated Testing & Verification]
  - Cryptographic privacy tests, routing tests, abuse & DoS tests, and real-device testing.
```

---

*Phase A Read-Only Implementation Audit Complete. Awaiting approval to proceed to Phase B & Phase C.*
