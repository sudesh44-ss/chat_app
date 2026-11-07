# NEXA — Phase E Protocol Design Corrections Gate

**Date:** 2026-11-07  
**Branch:** `main`  
**Status:** PROTOCOL CORRECTIONS SPECIFICATION (Read-Only Design Phase)

---

## 1. Executive Summary & Audit Context

During the Phase E Cryptographic Gate Review, five critical protocol design issues were identified in the preliminary architecture. In accordance with the project gate directives, this document establishes the precise technical resolutions for each issue before any implementation in Step 3 is authorized.

| Defect / Gate Finding | Root Cause Analysis | Remediation Summary |
|---|---|---|
| **1. Identity Key Separation** | Cross-curve conversion or ambiguity between Ed25519 signing and X25519 Diffie-Hellman keys. | Separate independent keypairs; explicit Ed25519 cryptographic certification of the X25519 identity key. |
| **2. Message Header & AEAD Structure** | X25519 ratchet public key was conflated with the 12-byte AEAD nonce in `nexa_wire.proto`. | Formal Signal Double Ratchet header ($DHR$, $PN$, $N$, $session\_id$); strictly 12-byte AEAD nonce; header authenticated as Associated Data ($AD$). |
| **3. Exact X3DH Specification** | Ambiguity in Diffie-Hellman output concatenation order, HKDF salt/info, and absence of OPK. | Standard Signal X3DH ($DH_1 \parallel DH_2 \parallel DH_3 [\parallel DH_4]$); canonical salt `"NEXA_X3DH_SALT_V1"`; asymmetric initial state definitions. |
| **4. Mutating OPK Allocation** | `GET /api/v1/users/{nxId}/prekey` marked keys as consumed, violating HTTP idempotency. | Idempotent two-phase claim protocol (`POST /api/v1/prekeys/claim`) with time-bounded reservations (`reserved_until`). |
| **5. Offline Durability & Deletion** | Server popped Redis pending queue prior to client delivery acknowledgment, risking message loss. | Ack-before-deletion protocol with Redis stream/staging and client-side transaction safety in SQLCipher Room DB. |

---

## 2. Point-by-Point Correction Analysis

### 2.1 Issue 1: Identity Key Separation and Curve Segregation

#### The Problem
In initial drafts, the relationship between identity signing keys (Ed25519) and identity key-agreement keys (X25519) was under-specified, leaving open the risk of:
1. Attempting on-the-fly birational equivalence point conversion ($u = (1+y)/(1-y)$) between Edwards and Montgomery curves.
2. Sign ambiguity and small-subgroup vulnerabilities when converting private scalar values without rigorous twist-security validation.
3. Conflating account-level identity with device-level identity.

#### The Resolution
1. **Absolute Algorithmic Separation:**
   - **Ed25519** is used exclusively for digital signatures (RFC 8032) and authentication proofs.
   - **X25519** is used exclusively for Diffie-Hellman key agreement (RFC 7748).
   - Under no circumstances does the client convert private or public keys between Ed25519 and X25519. Both keypairs are generated independently using high-entropy CSPRNG scalars.
2. **Cryptographic Binding:**
   - The device X25519 identity public key ($IK_{\text{device\_dh}}$) is cryptographically certified by the device Ed25519 signing key ($IK_{\text{device\_sign}}$) via a canonical TLV binary attestation:
     $$\text{Attestation} = \text{Ed25519Sign}(sk_{\text{sign}}, \text{EncodeTLV}(\text{"NXBI"}, \text{version}=1, \text{ts}, \text{nx\_id}, \text{device\_id}, pk_{\text{sign}}, pk_{\text{dh}}))$$
   - The backend validates this signature before committing the public key to `public.identity_keys` or the prekey registry.
3. **Hierarchical Device vs. Account Relationship:**
   - The primary account identity key ($IK_{\text{account}}$) certifies secondary device signing keys ($IK_{\text{device\_sign}}$).
   - Each authorized device holds its own distinct X25519 identity key, signed prekey, and one-time prekey pool. E2EE sessions operate strictly **device-to-device pairwise**.

---

### 2.2 Issue 2: Message Header and AEAD Nonce Structure

#### The Problem
In the initial `nexa_wire.proto`:
```protobuf
message EncryptedMessagePayload {
  int64 conversation_seq = 1;
  bytes ciphertext = 2;
  bytes nonce = 3;    // Overloaded to hold 32-byte X25519 Ephemeral Public Key!
  bytes auth_tag = 4; // 16-byte Poly1305 tag
}
```
This conflated the 32-byte X25519 ratchet public key with the 12-byte AEAD nonce. ChaCha20-Poly1305 (RFC 8439) strictly requires a **96-bit (12-byte)** nonce. Storing a 32-byte public key in a field named `nonce` while deriving a 12-byte nonce implicitly created protocol fragility, prevented transmission of Double Ratchet parameters ($PN$, $N$), and broke wire verification.

#### The Resolution
1. **Explicit Double Ratchet Header:**
   The wire protocol separates the Double Ratchet state header from the AEAD parameters:
   ```protobuf
   message DoubleRatchetHeader {
     bytes ratchet_public_key = 1;     // Exactly 32 bytes X25519 public key (DHR)
     uint32 previous_chain_length = 2; // PN: Message count in previous sending chain
     uint32 message_index = 3;         // N: Message index in current sending chain
     string session_id = 4;            // Canonical UUID of pairwise session
     int64 one_time_prekey_id = 5;     // Optional OPK ID (initial message only)
     bytes ephemeral_public_key = 6;   // Optional 32-byte EK (initial X3DH message only)
   }
   ```
2. **Strict AEAD Nonce Generation (12 Bytes):**
   - The AEAD nonce is strictly 12 bytes, derived deterministically per message key ($MK$):
     $$\text{Nonce}_{12} = \text{0x4E, 0x45, 0x58, 0x41} \parallel \text{uint64}(message\_index)$$
   - Because $MK$ is refreshed per message by the KDF chain ($CK_{i} \to MK_i$), each $(MK, \text{Nonce})$ tuple is mathematically guaranteed to be single-use.
3. **Associated Data ($AD$) Binding:**
   - The entire serialized `DoubleRatchetHeader` is passed as Associated Data ($AD$) to ChaCha20-Poly1305:
     $$(Ciphertext, AuthTag) = \text{ChaCha20-Poly1305-Encrypt}(MK, \text{Nonce}_{12}, Plaintext, AD=\text{HeaderBytes})$$
   - Any tampering with the ratchet key, sequence numbers, or session ID causes Poly1305 tag verification to fail immediately.

---

### 2.3 Issue 3: Exact Signal X3DH Specification

#### The Problem
The initial design draft omitted:
- Exact byte ordering for DH output concatenation.
- Canonical HKDF salt and info domain separation strings.
- Behavior when Bob's prekey bundle contains an OPK vs when OPK pool is depleted.
- Asymmetric initial states between Initiator (Alice) and Responder (Bob).

#### The Resolution
1. **Diffie-Hellman Ordering:**
   - When Bob's OPK is present:
     $$DH_1 = \text{X25519}(sk_{ik_a}, pk_{spk_b})$$
     $$DH_2 = \text{X25519}(sk_{ek_a}, pk_{ik_b})$$
     $$DH_3 = \text{X25519}(sk_{ek_a}, pk_{spk_b})$$
     $$DH_4 = \text{X25519}(sk_{ek_a}, pk_{opk_b})$$
     $$KM = DH_1 \parallel DH_2 \parallel DH_3 \parallel DH_4 \quad (\text{128 bytes total})$$
   - When Bob's OPK is absent (exhausted pool):
     $$KM = DH_1 \parallel DH_2 \parallel DH_3 \quad (\text{96 bytes total})$$
2. **Official Signal X3DH Standard HKDF Derivation:**
   - Curve25519 Prefix: $F = \text{0xFF} \times 32$ (32 bytes of `0xFF`)
   - $IKM = F \parallel KM$ (160 bytes with OPK, 128 bytes without)
   - Salt: 32 zero bytes (`0x00` $\times 32$)
   - $PRK = \text{HMAC-SHA256}(\text{Salt}, IKM)$
   - Info: `"NexaX3DH"` (Application Domain String)
   - Master Shared Secret $SK = \text{HKDF-Expand}(PRK, Info, 32)$
3. **Asymmetric Ratchet Initialization:**
   - **Alice (Initiator):**
     Sets $RK = SK$. Generates fresh initial ratchet keypair $DHR_A$. Computes:
     $$(RK, CK_s) = \text{KDF}_{RK}(RK, \text{X25519}(sk_{dhr_a}, pk_{spk_b}))$$
     Sending chain is active; receiving chain is uninitialized.
   - **Bob (Responder):**
     Sets $RK = SK$. Receives Alice's $DHR_A^{\text{pub}}$. Computes:
     $$(RK, CK_r) = \text{KDF}_{RK}(RK, \text{X25519}(sk_{spk_b}, pk_{dhr_a}))$$
     Receiving chain is active; sending chain is uninitialized until Bob transmits a reply.

---

### 2.4 Issue 4: One-Time Prekey (OPK) Lifecycle & Mutating GET Rectification

#### The Problem
The prototype endpoint `GET /api/v1/users/{nxId}/prekey` marked the selected OPK as consumed (`is_consumed = TRUE`). In HTTP/1.1 (RFC 7231 / RFC 9110), `GET` requests must be safe and idempotent. A mutating `GET` causes:
- OPK depletion from network retries, crawler pre-fetches, or browser navigation.
- Lost OPKs if the HTTP connection drops after server commit but before client receipt.
- Desynchronization where Bob has a private OPK that the server considers consumed, or Alice attempts to use an OPK that was consumed by a dropped retry.

#### The Resolution
1. **Idempotent Claim Endpoint (`POST /api/v1/prekeys/claim`):**
   - Senders must explicitly claim an OPK via a mutating `POST` containing a client-generated UUID `claim_id`.
   - If the network drops and the client retries with the same `claim_id`, the backend idempotently returns the **exact same reserved OPK**.
2. **Two-Phase Reservation State Machine:**
   - States: `AVAILABLE` $\to$ `RESERVED` (5-minute TTL) $\to$ `CONSUMED` (or `EXPIRED_RELEASED`).
   - If Alice fails to send the initial message within 5 minutes, a background sweeper releases the reservation back to `AVAILABLE`.
   - Final consumption occurs atomically when Alice dispatches the handshake packet.
3. **Pool Monitoring & Replenishment:**
   - When Bob's available OPK count drops below 20, the backend pushes a `PREKEY_REPLENISH_REQUIRED` event.
   - Bob's client automatically generates and uploads 50 new OPKs.

---

### 2.5 Issue 5: Offline Ciphertext Durability & Ack-Before-Deletion

#### The Problem
The existing `flushPendingQueue` in `hub.go` invoked `PopAllPending()`, which read all messages from Redis and immediately deleted the Redis key (`r.client.Del(ctx, key)`) before the client received or acknowledged them. If the WebSocket closed or failed during transmission, buffered messages were permanently destroyed.

#### The Resolution
1. **Server-Side Ack-Before-Deletion Protocol:**
   - Pending messages in Redis are maintained in an ordered stream or two-phase staging structure (`pending:<nxId>` and `unacked:<nxId>:<deviceId>`).
   - Messages are streamed to the client down the WebSocket upon reconnection.
   - The server **only** deletes a message from Redis after receiving an authenticated `PACKET_ACK` (`ACK_DEVICE_DELIVERED`) carrying the message's `packet_id`.
   - If the client disconnects before sending `ACK`, unacknowledged messages are automatically reclaimed and re-streamed on the next session connection.
2. **Client-Side Deduplication & Transaction Safety:**
   - If a network partition causes duplicate delivery of a message that was already processed, the client checks its SQLCipher deduplication index (`received_packets_table`).
   - If already present, the client immediately returns `ACK_DEVICE_DELIVERED` to silence server retries, without re-advancing the ratchet or inserting duplicate rows into the chat database.
   - All state updates in [`DoubleRatchetEngine.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/crypto/DoubleRatchetEngine.kt) (ratchet session update, message insertion, skipped key pruning) are wrapped in an atomic SQLCipher transaction (`@Transaction`). If any step fails, the ratchet state is rolled back cleanly.

---

## 3. Specification Roadmap & Deliverables Status

To ensure complete clarity and traceability, this correction gate produces five dedicated documents in `docs/audit/`:

1. [PHASE_E_PROTOCOL_CORRECTIONS.md](file:///H:/chat_app/docs/audit/PHASE_E_PROTOCOL_CORRECTIONS.md) *(This Document)*: High-level audit reconciliation and summary of corrected protocol decisions.
2. [PHASE_E_KEY_LIFECYCLE_SPEC.md](file:///H:/chat_app/docs/audit/PHASE_E_KEY_LIFECYCLE_SPEC.md): Comprehensive specification of key hierarchies, cross-curve independence, certification signatures, and device registration.
3. [PHASE_E_PROTOBUF_MIGRATION_PLAN.md](file:///H:/chat_app/docs/audit/PHASE_E_PROTOBUF_MIGRATION_PLAN.md): Schema migration for `nexa_wire.proto`, Double Ratchet wire headers, 12-byte AEAD nonce derivation, and backward compatibility.
4. [PHASE_E_PROTOCOL_DESIGN.md](file:///H:/chat_app/docs/audit/PHASE_E_PROTOCOL_DESIGN.md): Fully revised master protocol specification including exact Signal X3DH, Double Ratchet state transitions, two-phase OPK claim architecture, and Redis ack-before-deletion durability.
5. [PHASE_E_CRYPTOGRAPHIC_AUDIT.md](file:///H:/chat_app/docs/audit/PHASE_E_CRYPTOGRAPHIC_AUDIT.md): Fully revised architectural audit incorporating the corrected trust boundaries and remediation gates.
