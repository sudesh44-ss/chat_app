# NEXA — Redis Queue Reliability & Ephemeral Storage Architecture

**Status:** IMPLEMENTED & AUDITED  
**Version:** 1.0  
**Scope:** Ephemeral Storage Primitives, Atomic Operations, Degraded Mode & Fault Tolerance  

---

## 1. Architectural Philosophy

NEXA's backend operates as a **Zero-Disk RAM Traffic Police**:
- Zero PostgreSQL table inserts or updates for messages, typing events, or delivery receipts.
- Zero server-side disk storage of message content or ratchet keys.
- Offline durability is delegated exclusively to an **ephemeral Redis buffer** with a 24-hour Time-to-Live (TTL).

Because messages exist only in ephemeral Redis while recipients are offline, the reliability of queue operations is paramount. Message loss in the gateway corrupts the cryptographic Double Ratchet ratchet chain, requiring users to reset conversations.

---

## 2. Redis Data Structures & Atomic Operations

Phase I organizes queue keys into three distinct Redis data structures per recipient or routing token:

```
                  ┌──────────────────────────────────────────┐
                  │          nexa:q:msg:<queueKey>           │
                  │                   (HASH)                 │
                  │   Maps MessageID -> Full QueueEntry JSON │
                  └─────────────────────┬────────────────────┘
                                        │
                 ┌──────────────────────┴──────────────────────┐
                 ▼                                             ▼
  ┌─────────────────────────────┐               ┌─────────────────────────────┐
  │  nexa:q:pending:<queueKey>  │               │ nexa:q:inflight:<queueKey>  │
  │            (LIST)           │               │            (ZSET)           │
  │   FIFO list of MessageIDs   │               │ MessageIDs scored by lease  │
  │    waiting to be claimed    │               │  expiration timestamp (sec) │
  └─────────────────────────────┘               └─────────────────────────────┘
```

### 2.1 Enqueue (`Enqueue`)
To prevent duplicates when a client re-submits a packet over a flaky connection:
1. `HEXISTS nexa:q:msg:<queueKey> <messageID>`:
   - If true: return `nil` immediately (idempotent no-op).
2. Atomic Pipeline:
   - `HSET nexa:q:msg:<queueKey> <messageID> <serializedEntry>`
   - `RPUSH nexa:q:pending:<queueKey> <messageID>`
   - `EXPIRE nexa:q:msg:<queueKey> 86400`
   - `EXPIRE nexa:q:pending:<queueKey> 86400`

### 2.2 Claiming & Automated Reclamation (`ClaimPending`)
Executed when a recipient connects. Runs in two atomic phases:

#### Phase A: In-Flight Lease Reclamation
Reclaims messages whose leases expired without receiving an ACK:
1. `nowScore = time.Now().UnixNano() / 1e9`
2. `ZRANGEBYSCORE nexa:q:inflight:<queueKey> -inf <nowScore>`
3. For each timed-out `<messageID>`:
   - `ZREM nexa:q:inflight:<queueKey> <messageID>`
   - `HGET nexa:q:msg:<queueKey> <messageID>`
   - If `deliveryAttempts >= MaxDeliveryAttempts (5)` or `now > expiresAt`:
     - `HDEL nexa:q:msg:<queueKey> <messageID>` (Purged as dead-letter).
   - Else:
     - `LPUSH nexa:q:pending:<queueKey> <messageID>` (Re-queued at head for immediate retry).

#### Phase B: Atomic Batch Claim
Claims up to `batchSize` (default 50) pending messages:
1. `LPOP nexa:q:pending:<queueKey>`
2. `HGET nexa:q:msg:<queueKey> <messageID>`
3. If valid:
   - Increment `deliveryAttempts++`.
   - Update `lastAttemptAt = now`, `status = IN_FLIGHT`.
   - `deadlineScore = time.Now().Add(ackTimeout).UnixNano() / 1e9`
   - Atomic Pipeline:
     - `HSET nexa:q:msg:<queueKey> <messageID> <updatedEntry>`
     - `ZADD nexa:q:inflight:<queueKey> <deadlineScore> <messageID>`
     - `EXPIRE nexa:q:inflight:<queueKey> 86400`

### 2.3 Permanent Removal (`Acknowledge`)
Executed when a verified `ACK_DEVICE_DELIVERED` packet arrives:
1. Atomic Pipeline:
   - `ZREM nexa:q:inflight:<queueKey> <messageID>`
   - `HDEL nexa:q:msg:<queueKey> <messageID>`
   - `LREM nexa:q:pending:<queueKey> 0 <messageID>`

### 2.4 Immediate Socket Failure Reversion (`RevertInFlight`)
Executed if `session.SafeSend` fails during streaming:
1. Atomic Pipeline:
   - `ZREM nexa:q:inflight:<queueKey> <messageID>`
   - `LPUSH nexa:q:pending:<queueKey> <messageID>`
   - Updates `status = QUEUED` in hash.
- Note: When reverting a batch, items are reverted in reverse index order ($N-1 \text{ down to } i$) to ensure the original FIFO sequence is strictly preserved.

---

## 3. In-Memory Degraded Mode Fallback

In development, unit testing, or during temporary Redis cluster outages:
- `RedisBuffer` automatically engages an in-memory thread-safe fallback engine guarded by `sync.Mutex`.
- Maintained maps:
  - `memEntries map[string]map[string]*QueueEntry` (mirrors Redis Hash)
  - `memPending map[string][]string` (mirrors Redis List)
  - `memInFlight map[string]map[string]int64` (mirrors Redis ZSET, storing millisecond lease deadlines)
- High-precision millisecond timestamps guarantee that timer-sensitive tests and rapid reconnects behave identically to production Redis.
- Production safety: In production (`APP_ENV=production`), missing Redis configurations trigger critical log warnings to alert operators immediately.

---

## 4. Privacy & Zero-Knowledge Guarantees

1. **No Plaintext Storage:** The payload stored inside `QueueEntry.Payload` contains only the encrypted ciphertext envelope. The Redis server has no access to Double Ratchet ephemeral keys or identity secrets.
2. **Deterministic Message ID Extraction:** Message IDs are extracted safely without decrypting payloads:
   - For wire packets: `pkt.PacketId` from the outer wire frame.
   - For DTN envelopes: 16-byte message UUID at header offset 20..35 (`NXDT` v1).
   - Fallback: SHA-256 digest of packet bytes.
3. **Automatic Ephemeral Expiration:** All Redis keys carry a 24-hour TTL (`EXPIRE ... 86400`). Any un-delivered message is automatically evicted by Redis memory management after 24 hours.
