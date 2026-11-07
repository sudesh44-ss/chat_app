# NEXA — Phase E Offline Delivery Durability & Persistence Review

**Date:** 2026-11-07  
**Branch:** `main`  
**Status:** SPECIFICATION COMPLETE (Read-Only Design Phase)

---

## 1. Executive Summary & Audit Classification

### 1.1 Audit Finding & Classification: CORRECTED IN DESIGN
In the preliminary implementation of `backend/internal/connection/hub.go`, `flushPendingQueue()` called `PopAllPending()`, which extracted all queued packets from Redis and immediately deleted the Redis key (`r.client.Del(ctx, key)`) before the packets were streamed or delivered down the client's WebSocket pipe. If the client experienced a TCP reset, background kill, or Wi-Fi drop during streaming, the flushed messages were permanently lost.

This document reviews and establishes the complete technical specification for **Ack-Before-Deletion Durability**, Redis persistence configurations, duplicate delivery idempotency, and an architectural trade-off analysis regarding a **PostgreSQL Ciphertext Outbox**.

---

## 2. Redis Persistence & Atomic Transfer Architecture

```
+---------------------------------------------------------------------------------------+
|                       SERVER OFFLINE BUFFER LIFECYCLE (REDIS)                         |
+---------------------------------------------------------------------------------------+
|                                                                                       |
|   1. Sender pushes packet for offline recipient                                       |
|      ──► RPUSH pending:<recipientNxId>                                                |
|                                                                                       |
|   2. Recipient connects via WebSocket                                                 |
|      ──► Atomic Transfer: LMOVE pending:<recipientNxId>                              |
|                                 unacked:<recipientNxId>:<deviceId> RIGHT LEFT         |
|      ──► Stream packet to recipient over WebSocket                                    |
|                                                                                       |
|   3. Recipient receives, persists to SQLCipher, and sends PACKET_ACK                  |
|      ──► Atomic Deletion: LREM unacked:<recipientNxId>:<deviceId> 1 <packetBytes>    |
|                                                                                       |
|   4. If WebSocket drops before PACKET_ACK:                                            |
|      ──► Unacked packets remain staged; automatically re-streamed on next connection!  |
|                                                                                       |
+---------------------------------------------------------------------------------------+
```

### 2.1 Redis Server Persistence Configuration
To ensure zero message loss across Redis restarts or host maintenance, the production Redis instance must be configured with:
1. **Append-Only File (AOF):**
   - `appendonly yes`
   - `appendfsync everysec` (Maximum theoretical loss bounded to 1 second during catastrophic host hardware crash).
2. **RDB Periodic Snapshots:**
   - `save 900 1` (Save after 900 seconds if at least 1 key changed)
   - `save 300 10`
   - `save 60 1000`
3. **Memory Eviction Policy:**
   - `maxmemory-policy noeviction` (Refuses writes instead of silently dropping queued encrypted messages when memory limit is reached).

### 2.2 Atomic Queue-to-Unacked Transfer (`LMOVE`)
Instead of destructive `PopAllPending`, the Go backend uses atomic transfers:
- **Primary Queue:** `pending:<recipientNxId>`
- **In-Flight Staging List:** `unacked:<recipientNxId>:<deviceId>`
- **Atomic Transfer Command:**
  ```redis
  LMOVE pending:NX-BOB45678 unacked:NX-BOB45678:dev_bob_01 RIGHT LEFT
  ```
  This moves the message from the pending queue to the unacknowledged list atomically. If the server crashes at this exact instant, the message remains safely in `unacked`.

### 2.3 Atomic Acknowledgement and Deletion
1. The server streams the packet down the WebSocket.
2. The client receives the packet, executes the Double Ratchet state update and message plaintext commit inside a local SQLCipher `@Transaction`.
3. Upon transaction success, the client dispatches:
   ```protobuf
   NexaWirePacket {
     type: PACKET_ACK
     ack: DeliveryAckPayload {
       original_packet_id: "msg_uuid_12345"
       status: ACK_DEVICE_DELIVERED
     }
   }
   ```
4. The Go backend receives `ACK_DEVICE_DELIVERED` and removes the message from the staging list:
   ```redis
   LREM unacked:NX-BOB45678:dev_bob_01 1 <packetBytes>
   ```

### 2.4 Backend Restart & Crash Recovery Worker
Upon backend server startup, a background recovery worker runs every 60 seconds:
- Scans `unacked:*` keys.
- For any message whose in-flight staging timestamp exceeds 5 minutes (indicating client disconnected without sending ACK):
  ```redis
  LMOVE unacked:NX-BOB45678:dev_bob_01 pending:NX-BOB45678 RIGHT LEFT
  ```
- The message is returned to the active pending queue and immediately re-streamed when the client reconnects.

---

## 3. Client Duplicate Delivery & Transaction Safety

### 3.1 Idempotent Client Deduplication Table
If a network partition drops the client's `PACKET_ACK` in transit, the server will re-deliver the packet upon next reconnect.
To prevent duplicate UI entries or corrupting the Double Ratchet state machine:
1. SQLCipher Room DB maintains a `received_packets` deduplication index:
   ```kotlin
   @Entity(tableName = "received_packets")
   data class ReceivedPacketEntity(
       @PrimaryKey val packetId: String,
       val senderNxId: String,
       val receivedTimestamp: Long
   )
   ```
2. **Arrival Check:**
   When a packet arrives:
   ```kotlin
   if (packetDao.hasPacket(packet.packetId)) {
       // Duplicate delivery detected:
       // 1. Immediately send ACK_DEVICE_DELIVERED to clear server staging
       webSocketManager.sendAck(packet.packetId, AckStatus.ACK_DEVICE_DELIVERED)
       // 2. Suppress ratchet advancement and suppress UI insertion
       return
   }
   ```

### 3.2 SQLCipher Transaction Atomicity
All operations in [`DoubleRatchetEngine.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/core/crypto/DoubleRatchetEngine.kt) must be wrapped in an atomic transaction:
```kotlin
database.runInTransaction {
    packetDao.insert(ReceivedPacketEntity(packet.packetId, senderNxId, System.currentTimeMillis()))
    ratchetDao.upsertSession(updatedSession)
    messageDao.insertMessage(decryptedMessage)
    skippedKeyDao.delete(contactNxId, seqNumber)
}
```
If anything throws (e.g. storage full or constraint failure), the transaction rolls back completely: the ratchet does not advance, and the packet is re-processed on next attempt.

---

## 4. Architectural Analysis: Redis vs. PostgreSQL Ciphertext Outbox

The gate directives mandate evaluating whether a **PostgreSQL Ciphertext Outbox** is required.

### 4.1 Comparative Architectural Evaluation

| Evaluation Criteria | Pure Redis Ephemeral Queue (AOF Enabled) | PostgreSQL Ciphertext Outbox (`public.message_outbox`) |
|---|---|---|
| **Durability & ACID** | 1-second AOF fsync bound; theoretical loss on physical host failure. | Strict ACID durability; zero loss across full system crashes. |
| **Privacy & Zero-Knowledge** | **High:** Encrypted blobs live in RAM/AOF only up to 24h TTL; naturally ephemeral. | **Moderate:** Ciphertext blobs persisted to relational database tables until deletion. |
| **I/O Throughput & Scaling** | In-memory operations (> 100,000 ops/sec). Negligible latency impact. | Table inserts/deletes generate WAL writes and disk fragmentation. Requires vacuuming. |
| **Multi-Instance Sync** | Native Redis Pub/Sub and stream sharing across Go server instances. | Requires polling or PostgreSQL `LISTEN/NOTIFY`. |
| **Server Failure Recovery** | Unacked lists re-queued automatically via Redis primitives. | Relational query `SELECT * WHERE delivered = FALSE`. |

### 4.2 Architecture Recommendation: Hybrid Two-Tier Outbox
1. **Primary Operational Tier (Hot Path):**
   Use Redis with `appendonly yes` and the `LMOVE` unacked staging pattern for all standard real-time message routing and short-term offline buffering (up to 24 hours).
2. **PostgreSQL Fallback Outbox (Cold Path / Disaster Recovery):**
   If Redis is unreachable or operating in degraded mode, the Go server automatically redirects offline ciphertext buffering to PostgreSQL table `public.message_outbox`:
   ```sql
   CREATE TABLE IF NOT EXISTS public.message_outbox (
       id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
       recipient_nx_id VARCHAR(64) NOT NULL REFERENCES public.profiles(nx_id) ON DELETE CASCADE,
       packet_id VARCHAR(128) NOT NULL,
       raw_packet BYTEA NOT NULL,
       is_delivered BOOLEAN NOT NULL DEFAULT FALSE,
       created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
   );
   ```
3. **Zero-Knowledge Invariant Preserved:**
   Both Redis and PostgreSQL store **only** encrypted binary Protobuf payloads (`NexaWirePacket`). Server has zero access to message contents or keys.
