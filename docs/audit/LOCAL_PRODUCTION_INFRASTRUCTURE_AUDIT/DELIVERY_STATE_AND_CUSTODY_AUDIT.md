# NEXA — Delivery State & Custody ACK Architectural Audit

**Date:** 2026-11-09  
**Audit Target:** Android Client (`app/`) & Go Backend (`backend/`)  
**Scope:** Message Delivery States, Custody Semantics, ACK Authentication, and Multi-Hop DTN Safety  

---

## 1. Executive Summary

An audit was conducted across the NEXA codebase to assess how message lifecycle states, acknowledgement frames, and delay-tolerant store-and-forward custody are currently handled.

### Key Audit Findings:
1. **Absence of Independent Custody ACK Semantics:**
   - Wire protocol (`proto/nexa_wire.proto`) defines only `PACKET_ACK` (tag 2) with `AckStatus` (`ACK_SERVER_RECEIVED`, `ACK_DEVICE_DELIVERED`, `ACK_DEVICE_READ`).
   - There is no dedicated packet type or data payload for **Relay Custody ACKs**.
   - When intermediate DTN mesh nodes receive an envelope, `NearbyMeshManager.kt` persists it to `dtn_envelopes`, but no authenticated Custody ACK is returned to the transmitting node or original sender.
2. **Delivery State Conflation Risk:**
   - In Android, `MessageStatus` currently consists of: `PENDING`, `SENT`, `DELIVERED`, `READ`, `FAILED`.
   - `SENT` is currently triggered when the Cloud Gateway returns `ACK_SERVER_RECEIVED`.
   - The system lacks formal intermediate states: `CREATED`, `QUEUED`, `RELAYED`, and `EXPIRED`.
   - Without an explicit `RELAYED` delivery state and a distinct `CustodyState` machine, intermediate relay actions risk being misinterpreted by client logic as final recipient delivery.
3. **Delivery ACK Authentication Deficit:**
   - In `proto/nexa_wire.proto`, `DeliveryAckPayload` consists only of:
     ```protobuf
     message DeliveryAckPayload {
       string original_packet_id = 1;
       AckStatus status = 2;
     }
     ```
   - It lacks:
     - Recipient identity binding (`recipient_nx_id`).
     - Acknowledging device binding (`device_id`).
     - Timestamp / nonce freshness context (`timestamp`).
     - Cryptographic signature (`signature`) proving the ACK originated from the authorized private key of the recipient device.
   - Without cryptographic binding, an unauthorized peer on the mesh or a rogue relay could forge an `ACK_DEVICE_DELIVERED` for any arbitrary `packet_id`, prematurely stopping sender retransmissions and tricking the user into believing an unreceived message was delivered.
4. **Queue Removal Prematurity Safeguard in Backend:**
   - The Go backend (`hub.go`) reconciles `ACK_DEVICE_DELIVERED` by removing the packet from Redis via `h.buffer.Acknowledge`.
   - It requires the sender of the ACK packet to match the authenticated WebSocket session (`sender.Authenticated && sender.NxId == packet.SenderNxId`).
   - However, if a future Custody ACK were mistakenly assigned `ACK_DEVICE_DELIVERED` or routed through the same unauthenticated handler, the recipient's offline queue would be prematurely deleted before the recipient actually received the message.
5. **Durable Persistence vs Decryption Definition:**
   - In NEXA, **Delivery ACK** signifies that the final recipient device has:
     1. Atomically reserved the packet in `message_dedup_index`.
     2. Successfully verified and decrypted the Double Ratchet AEAD ciphertext.
     3. Durably committed the plaintext and metadata to the encrypted SQLCipher `messages` table.
     4. Finalized `message_dedup_index` as `STATUS_PROCESSED`.
   - In contrast, **Custody ACK** is strictly for intermediate relay nodes:
     1. Relay verifies NXDT envelope structure, size, and cryptographic signature.
     2. Relay checks hop count and expiry window.
     3. Relay durably persists the sealed envelope into `dtn_envelopes`.
     4. Relay commits to forwarding the envelope onward.
     5. The relay **does NOT** decrypt the payload and **does NOT** report delivery.

---

## 2. Audit of Existing Components

### 2.1. Android Message Entity & Delivery Status
- File: `app/src/main/java/com/nexa/app/domain/model/MessageStatus.kt`
  - Current values: `PENDING`, `SENT`, `DELIVERED`, `READ`, `FAILED`.
  - Required expansion: Adapt to support `CREATED`, `QUEUED`, `RELAYED`, `DELIVERED`, `READ`, `FAILED`, `EXPIRED` while preserving existing UI backwards-compatibility.
- File: `app/src/main/java/com/nexa/app/data/local/entity/MessageEntity.kt`
  - Stores `status: String` mapped to `MessageStatus`.
- File: `app/src/main/java/com/nexa/app/data/local/dao/MessageDao.kt`
  - Updates status via `updateMessageStatus(packetId, status)`.

### 2.2. DTN Envelope & Custody Persistence
- File: `app/src/main/java/com/nexa/app/data/local/entity/DtnEnvelopeEntity.kt`
  - Currently stores: `envelopeIdHex`, `blindTokenHex`, `hopLimit`, `rawEnvelopeBytes`, `expiresAtEpochSec`, `receivedAtTimestamp`.
  - Missing fields: Custody state (`NOT_ACCEPTED`, `CUSTODY_ACCEPTED`, `FORWARDING`, `CUSTODY_TRANSFERRED`, `CUSTODY_RELEASED`, `CUSTODY_EXPIRED`, `CUSTODY_FAILED`), custodian identifier, next-hop identifier, and custody transfer timestamp.
- File: `app/src/main/java/com/nexa/app/data/local/dao/DtnEnvelopeDao.kt`
  - Provides basic insert/delete/prune queries, but lacks custody state transition methods.

### 2.3. Universal Transport Ingestion Pipeline
- File: `app/src/main/java/com/nexa/app/core/transport/NexaUniversalTransportIngest.kt`
  - Line 91: Handles `PacketType.PACKET_ACK` via `handleAckPacket()`.
  - Line 160: Updates `messageDao` using `AckStatus` mapping.
  - Line 354: Emits `ACK_DEVICE_DELIVERED` upon successful Double Ratchet decryption and Room insertion.
  - Line 410: Inserts un-owned DTN envelopes into `envelopeDao`, but does NOT generate a `PACKET_CUSTODY_ACK`.

### 2.4. Cloud Gateway Hub & Queue Reconciliation
- File: `backend/internal/connection/hub.go`
  - Lines 236-272: Reconciles `ACK_DEVICE_DELIVERED` by removing messages from Redis (`h.buffer.Acknowledge`).
  - Lines 326-345: Emits `ACK_SERVER_RECEIVED` to the sender when a message is received at the server.
  - Zero handling of `PACKET_CUSTODY_ACK`. If a relay sent a custody ACK with `ACK_DEVICE_DELIVERED`, it would purge the queue prematurely!

### 2.5. Wire Protocol Definitions
- Files: `proto/nexa_wire.proto`, `backend/internal/wire/wire.go`, `NexaWirePacket.kt`
  - Currently missing `PACKET_CUSTODY_ACK = 15`.
  - Currently missing `CustodyAckPayload`.
  - `DeliveryAckPayload` lacks cryptographic device signature and recipient binding.

---

## 3. Remediation & Design Blueprint

### 3.1. Wire Protocol Extension
1. Add `PACKET_CUSTODY_ACK = 15` to `PacketType`.
2. Define `CustodyStatus` enum:
   - `CUSTODY_STATUS_UNSPECIFIED = 0`
   - `CUSTODY_ACCEPTED = 1`
   - `CUSTODY_TRANSFERRED = 2`
   - `CUSTODY_REJECTED = 3`
   - `CUSTODY_EXPIRED = 4`
3. Define `CustodyAckPayload`:
   - `original_packet_id` (string)
   - `custodian_nx_id` (string)
   - `status` (CustodyStatus)
   - `timestamp` (int64)
   - `signature` (bytes: Ed25519 signature over canonical payload bytes)
4. Extend `DeliveryAckPayload`:
   - `original_packet_id` (string)
   - `status` (AckStatus)
   - `recipient_nx_id` (string)
   - `device_id` (string)
   - `timestamp` (int64)
   - `signature` (bytes: Ed25519 signature over canonical payload bytes)

### 3.2. Delivery State Machine Alignment
Align `MessageStatus`:
- `CREATED`: Message created in sender outbox (replaces/aliases `PENDING`).
- `QUEUED`: Accepted by Cloud Gateway queue (represented by `ACK_SERVER_RECEIVED` / `SENT`).
- `RELAYED`: Relay accepts custody or forwards (distinct state; does not show double check).
- `DELIVERED`: Validated recipient Delivery ACK received (double check).
- `READ`: Validated recipient Read ACK received (cyan double check).
- `FAILED`: Permanent delivery or validation failure.
- `EXPIRED`: Message TTL expired before delivery.

### 3.3. Independent Custody State Machine in Relays
In `dtn_envelopes`:
- `custody_state`: `NOT_ACCEPTED`, `CUSTODY_ACCEPTED`, `FORWARDING`, `CUSTODY_TRANSFERRED`, `CUSTODY_RELEASED`, `CUSTODY_EXPIRED`, `CUSTODY_FAILED`.
- Relay persistence check: Custody ACK is ONLY generated AFTER `envelopeDao.insertEnvelope()` succeeds and commits.
- Custody transfer: When forwarding to Relay B, Relay A marks `FORWARDING`. Only upon receiving and verifying a valid Custody ACK from Relay B does Relay A transition to `CUSTODY_TRANSFERRED` and release custody.

### 3.4. Backend Isolation
In `backend/internal/connection/hub.go`:
- `PACKET_CUSTODY_ACK` is routed RAM-only between peers/relays.
- `PACKET_CUSTODY_ACK` **NEVER** touches Redis offline queues or triggers `h.buffer.Acknowledge`.
- Only `PACKET_ACK` with authenticated `ACK_DEVICE_DELIVERED` from the recipient session triggers queue reconciliation.
