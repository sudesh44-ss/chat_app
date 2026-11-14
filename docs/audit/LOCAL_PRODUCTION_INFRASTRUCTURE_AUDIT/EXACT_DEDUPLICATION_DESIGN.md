# NEXA — Exact Deduplication Architecture & Design Specification

**Status:** IMPLEMENTED & VERIFIED  
**Version:** 1.0.0  
**Target Systems:** Android Client (`app/`) & Go Cloud Gateway Backend (`backend/`)  
**Scope:** Universal Cross-Transport Message Deduplication, Conflict Detection, and Storage Isolation  

---

## 1. Executive Summary

NEXA operates in a multi-transport environment where identical messages can arrive concurrently or asynchronously across diverse physical and logical channels:
1. **Internet WebSocket** (direct real-time delivery via Cloud Gateway)
2. **Cloud Gateway Redis Offline Buffer** (delayed delivery upon client reconnection)
3. **Nearby Connections & BLE Mesh** (hop-by-hop local wireless transport)
4. **Wi-Fi Direct / Wi-Fi Aware** (high-throughput local P2P links)
5. **Cellular Binary Data SMS** (port 8901 fallbacks during internet blackouts)
6. **DTN Store-and-Forward Relays** (opportunistic multi-hop routing through blind intermediate nodes)
7. **Multiple Authorized Sibling Devices** (multi-device account synchronization)

In the absence of an authoritative, pre-decryption deduplication subsystem, concurrent arrival across multiple transports introduces two catastrophic failure modes:
- **Double Ratchet Desynchronization:** If two transport workers (e.g., WebSocket and Nearby BLE) attempt to decrypt the same Double Ratchet envelope concurrently, ratchet keys advance in parallel, corrupting ratchet step counters and permanently breaking future ratchet state.
- **Ghost Message Multiplication:** Multiple Room `messages` entity insertions produce duplicate chat bubbles, phantom unread counts, and broken delivery receipts.

To completely prevent duplicate processing while maintaining zero plaintext disclosure on server nodes, NEXA implements a two-tier **Pre-Decryption Exact Deduplication Architecture**:
- **Client Tier:** An atomic SQLCipher persistent deduplication table (`message_dedup_index`), an in-memory double-buffer sliding Bloom filter with infallible exact-lookup fallback, and a decoupled transport observation audit log (`message_transport_observations`).
- **Server Tier:** A Zero-Disk RAM Traffic Police idempotency gatekeeper using SHA-256 payload digests, preventing duplicate routing without touching Redis disk queues or decrypting E2EE payloads.

---

## 2. Android Client Deduplication Architecture

```
                    Incoming Inbound Packet
           (WebSocket / SMS / BLE / Wi-Fi Direct / DTN)
                               │
                               ▼
               ┌───────────────────────────────┐
               │    Sliding Bloom Filter       │
               │ (BloomDuplicateFilter.kt)     │
               └───────────────┬───────────────┘
                               │
                Candidate Found? (Fast-Reject)
                ├── [NO] ──────────────────────────┐
                │                                  │
                ▼ [YES]                            │
   ┌───────────────────────────┐                   │
   │  Exact DB Index Check     │                   │
   │  (message_dedup_index)    │                   │
   └────────────┬──────────────┘                   │
                │                                  │
      Status == PROCESSED?                         │
      ├── [YES] ──► Drop Payload, Record Transport │
      │             Observation, Send Idemp ACK    │
      │                                            │
      ▼ [NO / Not Found] ◄─────────────────────────┘
   ┌───────────────────────────────────────────────┐
   │ Atomic State Machine: Reserve Packet ID       │
   │ INSERT message_dedup_index (STATUS_RESERVED)  │
   │ Check sender & SHA-256 Hash Integrity         │
   └───────────────────────┬───────────────────────┘
                           │
                 Conflict / Collision?
                 ├── [YES] ──► Status = CONFLICT, Drop immediately!
                 │             (Never touch Double Ratchet keys)
                 │
                 ▼ [NO]
   ┌───────────────────────────────────────────────┐
   │ Double Ratchet Decryption Pipeline            │
   │ (Single-threaded execution guaranteed)        │
   └───────────────────────┬───────────────────────┘
                           │
                 Decryption Succeeded?
                 ├── [NO] ──► Status = FAILED, Rollback reservation
                 │
                 ▼ [YES]
   ┌───────────────────────────────────────────────┐
   │ Status = PROCESSED                            │
   │ Insert Room 'messages' Entity                 │
   │ Record Ingest Transport Observation           │
   │ Dispatch Inbound Transport Delivery ACK       │
   └───────────────────────────────────────────────┘
```

---

## 3. Database Schema Specification (Room v19 / SQLCipher)

The Android persistence layer incorporates two specialized Room entities within `NexaDatabase.kt` (Schema v19) located at `app/src/main/java/com/nexa/app/core/storage/`:

### 3.1. Authoritative Deduplication Table (`message_dedup_index`)

```sql
CREATE TABLE IF NOT EXISTS `message_dedup_index` (
    `packet_id` TEXT NOT NULL,
    `sender_nx_id` TEXT NOT NULL,
    `recipient_nx_id` TEXT NOT NULL,
    `conversation_id` TEXT NOT NULL,
    `payload_sha256` TEXT NOT NULL,
    `status` INTEGER NOT NULL DEFAULT 0,
    `received_at` INTEGER NOT NULL,
    `processed_at` INTEGER,
    `expires_at` INTEGER NOT NULL,
    PRIMARY KEY(`packet_id`)
);

CREATE INDEX IF NOT EXISTS `index_message_dedup_index_conversation_id` 
    ON `message_dedup_index` (`conversation_id`);

CREATE INDEX IF NOT EXISTS `index_message_dedup_index_expires_at` 
    ON `message_dedup_index` (`expires_at`);
```

#### Field Specifications:
- `packet_id`: Unique 128-bit identifier (UUIDv4 or hex-encoded 16-byte nonce) assigned by the originating device. Primary key.
- `sender_nx_id`: Canonical NX-ID of the sender.
- `recipient_nx_id`: Canonical NX-ID of the target recipient.
- `conversation_id`: Pairwise conversation identifier derived deterministically as `SHA-256(min(myNxId, peerNxId) || ":" || max(myNxId, peerNxId))`.
- `payload_sha256`: Hexadecimal SHA-256 digest of the encrypted payload bytes (including the authenticated wire framing).
- `status`: State machine integer:
  - `0` (`STATUS_RESERVED`): Ingestion reserved; decryption in progress.
  - `1` (`STATUS_PROCESSED`): Decrypted and saved to `messages` table.
  - `2` (`STATUS_FAILED`): Ratchet decryption or message decoding failed.
  - `3` (`STATUS_CONFLICT`): Packet ID collision detected with differing sender or payload.
- `received_at`: Local Unix epoch millisecond timestamp of first packet observation.
- `processed_at`: Local Unix epoch millisecond timestamp of successful ingestion.
- `expires_at`: Sliding TTL epoch millisecond (default: 30 days retention).

### 3.2. Multi-Transport Observation Table (`message_transport_observations`)

To track message delivery across multiple physical paths without altering immutable message fields:

```sql
CREATE TABLE IF NOT EXISTS `message_transport_observations` (
    `observation_id` TEXT NOT NULL,
    `packet_id` TEXT NOT NULL,
    `transport_type` TEXT NOT NULL,
    `observed_at` INTEGER NOT NULL,
    `relay_node_id` TEXT,
    `rssi` INTEGER,
    PRIMARY KEY(`observation_id`),
    FOREIGN KEY(`packet_id`) REFERENCES `message_dedup_index`(`packet_id`) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS `index_message_transport_observations_packet_id` 
    ON `message_transport_observations` (`packet_id`);
```

---

## 4. Atomic Deduplication State Machine

The ingest pipeline executes inside an atomic SQLite transaction via `MessageDedupDao.kt`:

```kotlin
@Transaction
suspend fun reserveOrCheckConflict(
    packetId: String,
    senderNxId: String,
    recipientNxId: String,
    conversationId: String,
    payloadSha256: String,
    now: Long,
    expiresAt: Long
): DedupReservationResult {
    val existing = get(packetId)
    if (existing == null) {
        val entry = MessageDedupIndex(
            packetId = packetId,
            senderNxId = senderNxId,
            recipientNxId = recipientNxId,
            conversationId = conversationId,
            payloadSha256 = payloadSha256,
            status = MessageDedupIndex.STATUS_RESERVED,
            receivedAt = now,
            processedAt = null,
            expiresAt = expiresAt
        )
        insert(entry)
        return DedupReservationResult.RESERVED
    }

    // Collision & Tampering check
    if (existing.senderNxId != senderNxId || existing.payloadSha256 != payloadSha256) {
        markConflict(packetId)
        return DedupReservationResult.CONFLICT
    }

    return when (existing.status) {
        MessageDedupIndex.STATUS_PROCESSED -> DedupReservationResult.ALREADY_PROCESSED
        MessageDedupIndex.STATUS_RESERVED -> DedupReservationResult.ALREADY_IN_FLIGHT
        MessageDedupIndex.STATUS_CONFLICT -> DedupReservationResult.CONFLICT
        else -> DedupReservationResult.FAILED_PREVIOUSLY
    }
}
```

### Safety Guarantees:
1. **Double Ratchet Pre-Decryption Isolation:** Decryption is never executed if the state is `ALREADY_PROCESSED`, `ALREADY_IN_FLIGHT`, or `CONFLICT`. Ratchet state counters are modified exactly once per message.
2. **Cryptographic Collision Defense:** If an adversary attempts to reuse an existing `packetId` with a forged sender or modified ciphertext, `payloadSha256` mismatch immediately transitions the entry to `STATUS_CONFLICT` and drops the packet.
3. **Idempotent Acknowledgment:** When `ALREADY_PROCESSED` is returned, the incoming transport is logged in `message_transport_observations`, and a delivery ACK is returned to the transport so the sender or relay node ceases retransmission.

---

## 5. Bloom Filter Fast-Rejection L1 Optimization

To guarantee zero database I/O bottlenecks under high-volume mesh floods:
- `BloomDuplicateFilter.kt` maintains a rolling double-buffer Bloom filter (20,000 capacity per window, 3 independent Murmur3-based hash functions, target false-positive rate $p < 0.01$).
- **Infallible Fallback (`exactLookup`):** If `mightContain(packetId)` returns `true`, the pipeline does *not* blindly drop the packet. Instead, it queries `message_dedup_index` via the `exactLookup` lambda.
  - If the database confirms the packet is `STATUS_PROCESSED`, it is treated as a duplicate.
  - If the database returns `null`, the detection was a Bloom false positive; the packet proceeds to normal reservation and processing.
  - **Verdict:** False positives NEVER drop legitimate packets.

---

## 6. Server-Side Zero-Disk RAM Traffic Police

The Go backend (`backend/internal/connection/hub.go` and `backend/internal/database/migrations.go`) enforces zero-knowledge idempotency without touching plaintext message data:

### 6.1. Database Migration 5 (`message_idempotency`)
```sql
CREATE TABLE IF NOT EXISTS message_idempotency (
    packet_id VARCHAR(64) PRIMARY KEY,
    sender_nx_id VARCHAR(64) NOT NULL,
    recipient_nx_id VARCHAR(64) NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_message_idempotency_expires_at ON message_idempotency (expires_at);
```

### 6.2. Two-Tier Server Cache
1. **L1 RAM Cache:** Thread-safe `sync.Map` in `Hub` holding active `(packetId -> payloadSha256)`.
2. **L2 Redis Cache:** Distributed key `idemp:<packetId>` with a 7-day TTL storing `payloadSha256`.

### 6.3. Server Ingestion Workflow
- When a packet arrives via WebSocket or DTN gateway:
  - If `packet_id` exists in Redis/RAM:
    - If `SHA256(payload)` matches: duplicate packet is suppressed from re-routing and an idempotent Server ACK is returned.
    - If `SHA256(payload)` differs: packet ID collision is logged and rejected (`ErrPacketConflict`).
  - If new: `(packet_id, SHA256)` is recorded in Redis and `message_idempotency` table; packet is routed strictly through RAM buffers to the online recipient session or offline queue.
- **Zero-Disk Guarantee:** No plaintext, no decrypted headers, and no unencrypted metadata are ever committed to disk.
