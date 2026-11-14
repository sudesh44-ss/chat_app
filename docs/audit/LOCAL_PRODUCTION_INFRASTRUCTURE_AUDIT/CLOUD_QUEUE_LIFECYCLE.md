# NEXA — Cloud Queue Message Lifecycle Specification

**Status:** APPROVED & IMPLEMENTED  
**Version:** 1.0  
**Scope:** Gateway Queue State Transitions, Leases, and Dead-Letter Policy  

---

## 1. Lifecycle Overview

The NEXA Cloud Queue Lifecycle governs how messages transition from initial creation on a client device through the Cloud Gateway, into ephemeral Redis storage during recipient absence, and finally to confirmed device delivery and queue clearance.

```
+---------------------------------------------------------------------------------------+
|                                    MESSAGE LIFECYCLE                                  |
|                                                                                       |
|   [1] MESSAGE_CREATED                                                                 |
|            │                                                                          |
|            ▼ (Wire Packet transmitted via TLS WebSocket)                              |
|   [2] GATEWAY_RECEIVED ─────── (Sender receives ACK_SERVER_RECEIVED)                  |
|            │                                                                          |
|            ├────────────────────────────┐                                             |
|            │ (Recipient Online)         │ (Recipient Offline)                         |
|            ▼                            ▼                                             |
|   [RAM Direct Relay]          [3] QUEUED                                              |
|            │                            │                                             |
|            │                            ▼ (Recipient Connects & Claims)               |
|            │                  [4] IN_FLIGHT (30s Lease)                               |
|            │                            │                                             |
|            │                            ├────────────────────────┐                    |
|            │                            │ (SafeSend Success)     │ (Socket Full/Err)  |
|            │                            ▼                        ▼                    |
|            └──────────────────► [5] DELIVERY_ATTEMPTED      [REVERT TO QUEUED]        |
|                                         │                                             |
|                                         ▼ (Decrypted & Persisted on Device)           |
|                                 [6] DELIVERED                                         |
|                                         │                                             |
|                                         ▼ (PACKET_ACK with ACK_DEVICE_DELIVERED)      |
|                                 [7] ACKNOWLEDGED                                      |
|                                         │                                             |
|                                         ▼ (Atomic HDEL & ZREM)                        |
|                                 [8] QUEUE_REMOVED                                     |
+---------------------------------------------------------------------------------------+
```

---

## 2. Stage-by-Stage Specifications

### Stage 1: `MESSAGE_CREATED`
- **Location:** Sender Device (Android).
- **Actions:**
  - Plaintext payload encrypted via Double Ratchet Engine.
  - Encrypted payload wrapped in `NexaWirePacket(type=PACKET_MESSAGE, packetId=UUID, senderNxId, recipientNxId, timestamp)`.
  - Wire packet queued in local Room outbox (`MessageEntity.status = SENT_PENDING`).

### Stage 2: `GATEWAY_RECEIVED`
- **Location:** Cloud Gateway (`hub.go`).
- **Actions:**
  - Authenticated session verified (`sender.Authenticated == true`).
  - Connection & blocklist validation (`IsConnected == true`, `IsBlocked == false`).
  - Immediate `PACKET_ACK(status=ACK_SERVER_RECEIVED)` generated and returned to sender.
  - Multi-device self-sync: Packet forwarded in real-time to sender's sibling devices.
  - Routing decision:
    - If recipient has active WebSocket sessions $\to$ deliver directly via RAM `SafeSend`.
    - If recipient has 0 active sessions $\to$ proceed to `QUEUED`.

### Stage 3: `QUEUED`
- **Location:** Redis Ephemeral Storage (`redis_buffer.go`).
- **Storage Primitives:**
  - Hash Entry: `HSET nexa:q:msg:<recipientNxId> <packetId> <QueueEntry JSON>`
  - List Push: `RPUSH nexa:q:pending:<recipientNxId> <packetId>`
  - Key Expiry: 24 Hours (`DefaultTTL = 24 * time.Hour`).
- **Idempotency:**
  - If `<packetId>` already exists in `nexa:q:msg:<recipientNxId>`, `HExists` returns true and the duplicate enqueue is suppressed without re-inserting into the pending list.
- **Background Wake-Up:**
  - Gateway triggers FCM wake-up ping (`triggerFcmWakeup`) for registered device tokens of the recipient.

### Stage 4: `IN_FLIGHT`
- **Trigger:** Recipient device connects and establishes authenticated WebSocket session.
- **Operation:**
  - Gateway calls `ClaimPending(ctx, recipientNxId, batchSize=50, ackTimeout=30s)`.
  - Atomic transition:
    - `LPOP` from `nexa:q:pending:<recipientNxId>`.
    - Update `QueueEntry.Status = IN_FLIGHT`, `DeliveryAttempts++`, `LastAttemptAt = now`.
    - `ZADD nexa:q:inflight:<recipientNxId> <deadlineScore> <packetId>`.
- **Lease Duration:** 30 Seconds.

### Stage 5: `DELIVERY_ATTEMPTED`
- **Location:** Gateway to Recipient Transport.
- **Path A: Socket Write Succeeded (`session.SafeSend == true`)**
  - Bytes pushed onto client WebSocket frame.
  - Message remains in `IN_FLIGHT` state awaiting recipient confirmation.
- **Path B: Socket Write Failed (`session.SafeSend == false`)**
  - Session buffer full or disconnected during stream.
  - Gateway immediately executes `RevertInFlight(ctx, recipientNxId, packetId)`.
  - Reversion removes entry from `nexa:q:inflight` and prepends to `nexa:q:pending` (`LPUSH`), preserving strict FIFO delivery order.

### Stage 6: `DELIVERED`
- **Location:** Recipient Device (Android).
- **Actions:**
  - Packet received over WebSocket in `ChatRepositoryImpl.kt`.
  - Payload decrypted via Double Ratchet Engine (`ratchetDecrypt`).
  - Deduplication Index check: If packet ID was already persisted in Room DB, suppress double decryption / double ratchet advancement.
  - Message persisted in Room database (`MessageEntity.status = DELIVERED`).
  - Local push notification posted if app is in background.
  - Recipient immediately formats `PACKET_ACK`:
    - `type = PACKET_ACK`
    - `ack.originalPacketId = packet.packetId`
    - `ack.status = ACK_DEVICE_DELIVERED`
    - `senderNxId = myNxId`
    - `recipientNxId = senderNxId`
  - Transmitted back to Gateway over authenticated WebSocket.

### Stage 7: `ACKNOWLEDGED`
- **Location:** Cloud Gateway (`RouteDecodedPacket`).
- **Security Check:**
  - Verify `sender.Authenticated == true && sender.NxId == packet.SenderNxId`.
- **Reconciliation:**
  - Calls `h.buffer.Acknowledge(ctx, recipientNxId, packet.Ack.OriginalPacketId)`.
  - Redis pipeline executes:
    - `ZREM nexa:q:inflight:<key> <packetId>`
    - `HDEL nexa:q:msg:<key> <packetId>`
    - `LREM nexa:q:pending:<key> 0 <packetId>`
  - Increments atomic metric: `acknowledgedDeliveryCount++`.

### Stage 8: `QUEUE_REMOVED`
- **Status:** Final.
- The message is completely erased from Redis memory.
- Forward ACK is delivered to original sender (`ACK_DEVICE_DELIVERED`) to advance their outbox UI indicator to double checkmarks.

---

## 3. Failure & Edge Case Handling

| Scenario | System Reaction | Recovery Action |
|:---|:---|:---|
| **Abrupt Disconnect Mid-Stream** | Socket drops before `SafeSend` completes or during ACK wait. | Message remains in `IN_FLIGHT` ZSET. When 30s lease expires, next claim automatically executes Step A reclamation (`ZREM` + `LPUSH`), making it pending for next reconnect. |
| **Server Crash Mid-Stream** | Cloud Gateway process crashes. | In-flight messages and pending lists remain safely stored in Redis. Upon Gateway restart, connecting clients claim their pending and expired in-flight messages. |
| **Max Retry Exceeded** | Poison packet causes client to crash repeatedly on decrypt. | Each claim increments `DeliveryAttempts`. When `DeliveryAttempts >= 5`, Step A reclamation executes `HDEL` and drops the packet with dead-letter log event. |
| **Expired 24h Message** | Recipient remains offline for > 24 hours. | If `ExpiresAt > 0 && now > ExpiresAt`, packet is purged during claim without delivery. |
| **Multi-Device Client** | Recipient has Phone and Tablet. | Offline queue is keyed to the user (`NX-ID`). First device to connect claims and ACKs it. Second device connecting later finds queue empty, avoiding duplicate ratchet burn. |
