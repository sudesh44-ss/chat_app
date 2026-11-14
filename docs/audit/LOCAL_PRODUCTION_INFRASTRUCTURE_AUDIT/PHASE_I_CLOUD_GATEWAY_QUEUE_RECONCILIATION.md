# NEXA — Phase I: Cloud Gateway Queue Reconciliation & State Audit

**Project:** NEXA Android + Go Backend  
**Component:** Cloud Gateway (Hub) + Ephemeral Queue (RedisBuffer) + Delivery Acknowledgment Protocol  
**Date:** 2026-11-09  
**Status:** IMPLEMENTED & VERIFIED  

---

## 1. Executive Summary

Phase I addresses the critical offline message durability and reconciliation gap between the NEXA Cloud Gateway, the Redis ephemeral message queue, authenticated client WebSocket sessions, and recipient cryptographic delivery acknowledgements.

Prior to Phase I, messages buffered in Redis while a recipient was offline were popped using `PopAllPending`, which performed an immediate destructive deletion (`DEL pending:<key>`) prior to confirming whether the client actually received the packet or successfully processed it. If a network drop, crash, or buffer overflow occurred mid-stream, queued messages were irreversibly destroyed, breaking Double Ratchet session synchronization.

Phase I replaces destructive queue popping with an **atomic, stateful claim-and-acknowledge lifecycle**:
```
MESSAGE_CREATED → GATEWAY_RECEIVED → QUEUED → DELIVERY_ATTEMPTED → IN_FLIGHT → DELIVERED → ACKNOWLEDGED → QUEUE_REMOVED
```

Under Phase I:
1. **Never Delete on Socket Write:** Buffered packets are claimed into an `IN_FLIGHT` sorted set with a 30-second lease. They remain persisted until the recipient returns a valid, authenticated `PACKET_ACK` (`ACK_DEVICE_DELIVERED`).
2. **Reversion on Write Failure:** If a client WebSocket's send channel is saturated or closed, in-flight messages are immediately reverted back to `QUEUED` in their original FIFO sequence.
3. **Lease Reclamation:** If an in-flight message is not acknowledged within the timeout window (due to abrupt connection termination), subsequent reconnection or claim calls automatically reclaim the expired in-flight entries and re-queue them for delivery.
4. **Multi-Device & Dedup Safety:** When a message is acknowledged by the recipient's primary device, it is cleared from the gateway queue, preventing sibling devices from experiencing duplicate ratchet advancement.
5. **Anti-Spoofing & Authentication Binding:** Delivery ACKs can only reconcile and delete messages from a user's queue if the ACK originates from an authenticated session whose `session.NxId` cryptographically matches `packet.SenderNxId`.

---

## 2. Architecture & Queue Schema

### 2.1 Redis Key Layout

To ensure atomicity, idempotency, and sub-millisecond lease tracking without storing plaintext content, Redis keys follow a strict partitioned schema:

| Key Format | Type | Description | Ephemeral TTL |
|:---|:---|:---|:---|
| `nexa:q:msg:<queueKey>` | Hash | Maps `messageID` $\to$ JSON-serialized `QueueEntry` metadata & ciphertext payload | 24 Hours |
| `nexa:q:pending:<queueKey>` | List | FIFO queue of pending `messageID` strings waiting for recipient delivery | 24 Hours |
| `nexa:q:inflight:<queueKey>` | Sorted Set (ZSET) | Active in-flight `messageID`s scored by their lease expiration timestamp (Unix float seconds) | 24 Hours |

### 2.2 QueueEntry Metadata

```go
type QueueEntry struct {
    MessageID        string      `json:"message_id"`
    QueueKey         string      `json:"queue_key"`
    Payload          []byte      `json:"payload"`
    EnqueuedAt       int64       `json:"enqueued_at"`
    ExpiresAt        int64       `json:"expires_at"`
    DeliveryAttempts int         `json:"delivery_attempts"`
    LastAttemptAt    int64       `json:"last_attempt_at"`
    Status           QueueStatus `json:"status"` // QUEUED, IN_FLIGHT, ACKNOWLEDGED, EXPIRED
}
```

- **Zero Plaintext:** The `Payload` holds only double-ratcheted ciphertext (`NexaWirePacket` bytes) or outer `NXDT` DTN envelopes. No plaintext message text, secret keys, or phone numbers are ever serialized into Redis.
- **Dead-Letter Purge:** A maximum retry threshold (`MaxDeliveryAttempts = 5`) ensures un-routable corrupted packets do not poison the queue indefinitely.

---

## 3. Reconciliation State Machine

```
              ┌─────────────────────────────────┐
              │          MESSAGE_CREATED        │
              └────────────────┬────────────────┘
                               │ Client transmits wire packet
                               ▼
              ┌─────────────────────────────────┐
              │         GATEWAY_RECEIVED        │
              └────────────────┬────────────────┘
                               │ Recipient offline / buffer full
                               ▼
        ┌──────────────────────────────────────────────┐
        │                    QUEUED                    │
        │   Hash: nexa:q:msg:<key>[id] = entry         │
        │   List: nexa:q:pending:<key>.RPUSH(id)       │
        └──────────────┬───────────────────────────────┘
                       │ Recipient connects (flushPendingQueue)
                       ▼
        ┌──────────────────────────────────────────────┐
        │                  IN_FLIGHT                   │
        │   List: nexa:q:pending:<key>.LPOP(id)        │
        │   ZSet: nexa:q:inflight:<key>.ZADD(deadline) │
        └──────────┬──────────────┬────────────────────┘
                   │              │
                   │ SafeSend ok  │ Socket write fails
                   │              └────────────────────────┐
                   ▼                                       │
        ┌──────────────────────────────────────┐           │
        │          DELIVERY_ATTEMPTED          │           │
        │       Streamed over WebSocket        │           │
        └──────────┬───────────────────────────┘           │
                   │                                       │
       ┌───────────┴───────────┐                           │
       │ Recipient returns     │ Lease timeout (> 30s)     │
       │ ACK_DEVICE_DELIVERED  │ or reconnect reclamation  │
       ▼                       ▼                           │
┌────────────────────┐   ┌─────────────────────────────────┼─────────┐
│    ACKNOWLEDGED    │   │         REVERTED / RECLAIMED    │         │
│  Hash: HDEL(id)    │   │   ZSet: ZREM(id)                │         │
│  ZSet: ZREM(id)    │   │   List: LPUSH(id) (FIFO preserved)        │
│                    │   └─────────────────────────────────┴─────────┘
│  DELIVERY FINAL    │
└────────────────────┘
```

---

## 4. Key Source Code Updates

1. **`backend/internal/queue/redis_buffer.go`:**
   - Extended `Buffer` interface with `Enqueue`, `ClaimPending`, `Acknowledge`, `RevertInFlight`, and `GetQueueMetrics`.
   - Idempotent `Enqueue` ensures duplicate packet submissions do not duplicate queue entries.
   - `ClaimPending` implements atomic batch claiming and automated reclamation of expired in-flight leases.
   - `Acknowledge` atomically purges the message from `nexa:q:msg`, `nexa:q:inflight`, and `nexa:q:pending`.
   - `RevertInFlight` moves unwritten packets back to `nexa:q:pending` without resetting expiration bounds.
   - Full in-memory fallback state machine for dev/testing environments when Redis is unavailable.

2. **`backend/internal/connection/hub.go`:**
   - Updated `bufferOffline` and `bufferOfflineDtn` to enqueue with deterministically extracted message IDs.
   - Updated `flushPendingQueue` and `FlushTokensQueue` to claim via `ClaimPending` and revert on socket failure in reverse index order to preserve exact FIFO ordering.
   - Intercepted `PACKET_ACK` (`ACK_DEVICE_DELIVERED`) in `RouteDecodedPacket` to validate sender authentication and trigger `h.buffer.Acknowledge`.
   - Added live atomic telemetry counters: `queuedMessagesCount`, `claimedInFlightCount`, `acknowledgedDeliveryCount`, and `revertedDeliveryCount`.

---

## 5. Verification Summary

| Test Case | Package | Focus Area | Result |
|:---|:---|:---|:---|
| `TestReliableQueue_Lifecycle` | `internal/queue` | Enqueue $\to$ Claim $\to$ In-Flight $\to$ ACK removal | **PASS** |
| `TestReliableQueue_ExpiredMessagesRejection` | `internal/queue` | Expired TTL entries purged without delivery | **PASS** |
| `TestReliableQueue_InFlightTimeoutReclamation` | `internal/queue` | Timed-out in-flight leases reclaimed and retried | **PASS** |
| `TestQueueReconciliation_EndToEndLifecycle` | `internal/connection` | Full client offline queue, claim, delivered ACK | **PASS** |
| `TestQueueReconciliation_SocketWriteFailure_RevertsInFlight` | `internal/connection` | Write failure reverts in-flight to pending FIFO | **PASS** |
| `TestQueueReconciliation_AntiSpoofingRejection` | `internal/connection` | Rogue session cannot acknowledge victim's queue | **PASS** |
| `TestQueueReconciliation_MultiDeviceDedup` | `internal/connection` | ACK on dev 1 prevents dev 2 duplicate streaming | **PASS** |
| `TestQueueReconciliation_DtnEnvelopeDeliveryAndAck` | `internal/connection` | Pairwise token envelope delivery and ACK purge | **PASS** |
| `testDebugUnitTest` | Android client | Client delivery ACK generation & ratchet decrypt | **PASS** |

All tests passed with zero regressions.
