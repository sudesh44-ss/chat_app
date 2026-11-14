# NEXA — Peer Synchronization (Anti-Entropy) Protocol Specification

**Status:** IMPLEMENTED & VERIFIED  
**Version:** 1.0.0  
**Wire Packet Tag:** `PACKET_PEER_SYNC = 14`  
**Target Systems:** Android Client (`app/src/main/java/com/nexa/app/core/sync/`) & Go Backend (`backend/internal/connection/`)  

---

## 1. Protocol Objective

During periods of physical disconnection, mesh network partitions, or offline battery sleep, peers inevitably miss messages. The **NEXA Peer Synchronization Protocol** is an authenticated, bounded, resumable anti-entropy reconciliation mechanism that enables two connected peers to reconcile missing messages securely over any transport (Internet WebSocket, Wi-Fi Direct, Nearby Connections, or BLE).

### Core Security Invariants:
1. **Pairwise Scoping:** Peer synchronization is strictly scoped to the conversation between the two communicating peers. No peer can query or obtain messages belonging to third-party conversations (`UNAUTHORIZED_CONVERSATION_SCOPE`).
2. **Mutual Contact Authorization:** Synchronization requires mutual connection authorization. Unconnected strangers attempting sync packets are immediately dropped (`STRANGER_SYNC_REJECTED`).
3. **Zero Plaintext Revelation:** Synchronization operates on E2EE wire envelopes. Relays and network observers never observe plaintext content or ratchet keys.
4. **Bounded Anti-Entropy:** Batch transfers are capped at 50 messages per round-trip to prevent memory exhaustion, battery drain, and radio saturation.
5. **Resumable State:** Persistent cursors in SQLCipher ensure interrupted sync sessions resume from the last acknowledged timestamp without repeating message transfers.

---

## 2. Wire Protocol Specification (`nexa_wire.proto`)

```protobuf
syntax = "proto3";

package nexa.wire;

enum PeerSyncAction {
    PEER_SYNC_ACTION_UNSPECIFIED = 0;
    PEER_SYNC_QUERY_RANGE       = 1;
    PEER_SYNC_OFFER_INVENTORY    = 2;
    PEER_SYNC_REQUEST_PACKETS    = 3;
    PEER_SYNC_DELIVER_BATCH      = 4;
    PEER_SYNC_ACK_BATCH          = 5;
}

message PeerSyncPayload {
    PeerSyncAction action        = 1;
    string conversation_id       = 2;
    int64 cursor_timestamp       = 3;
    int32 limit                  = 4;
    repeated string packet_ids   = 5;
    repeated NexaWirePacket packets = 6;
    int64 highest_timestamp     = 7;
}

message NexaWirePacket {
    PacketType type = 1; // Tag 14 = PACKET_PEER_SYNC
    string sender_nx_id = 2;
    string recipient_nx_id = 3;
    string packet_id = 4;
    int64 timestamp = 5;
    bytes payload = 6; // Serialized PeerSyncPayload
    bytes signature = 7;
}
```

### Protocol Action Codes:
- `QUERY_RANGE` (`1`): Initiator requests inventory of messages newer than `cursor_timestamp`.
- `OFFER_INVENTORY` (`2`): Responder supplies an array of candidate `packet_ids` (bounded by `limit`).
- `REQUEST_PACKETS` (`3`): Initiator specifies the subset of missing `packet_ids` after checking its local `message_dedup_index`.
- `DELIVER_BATCH` (`4`): Responder transmits the requested encrypted wire packets.
- `ACK_BATCH` (`5`): Initiator confirms successful local ingestion and updates sync cursor.

---

## 3. Protocol State Machine & 10-Step Handshake Flow

```
   Peer A (Initiator)                                 Peer B (Responder)
   ==================                                 ==================
           │                                                  │
   [1. Transport Link Established (WS / Wi-Fi / BLE)]        │
           │                                                  │
   [2. Read cursor from peer_sync_cursors]                    │
       QUERY_RANGE(cursor_timestamp, limit=50)                │
       ──────────────────────────────────────────────────────►│
                                                              │
                                                      [3. Scope Check: conversation_id == derive(A, B)]
                                                      [4. Query Room: messages > cursor_timestamp LIMIT 50]
                                                              │
                               OFFER_INVENTORY(packet_ids)    │
       ◄──────────────────────────────────────────────────────│
       │
   [5. Bloom / DB Dedup Check]
   [6. Filter missing_ids = offer \ local_dedup_index]
       │
       REQUEST_PACKETS(missing_ids)
       ──────────────────────────────────────────────────────►│
                                                              │
                                                      [7. Read encrypted envelopes for missing_ids]
                                                              │
                               DELIVER_BATCH(packets, high_ts)│
       ◄──────────────────────────────────────────────────────│
       │
   [8. Atomic Ingestion: reserve -> ratchetDecrypt -> insert]
   [9. Update peer_sync_cursors(peer_id, high_ts)]
       │
       ACK_BATCH(high_ts, count)
       ──────────────────────────────────────────────────────►│
                                                              │
                                                      [10. Advance sync state & complete]
```

---

## 4. Android Client Engine Architecture (`PeerSyncEngine.kt`)

The engine is encapsulated in `app/src/main/java/com/nexa/app/core/sync/PeerSyncEngine.kt` and coordinates Room DAOs with the network transport manager:

### 4.1. Cursor Persistence Entity (`peer_sync_cursors`)
```sql
CREATE TABLE IF NOT EXISTS `peer_sync_cursors` (
    `conversation_id` TEXT NOT NULL,
    `peer_nx_id` TEXT NOT NULL,
    `last_synced_timestamp` INTEGER NOT NULL,
    `last_sync_completed_at` INTEGER NOT NULL,
    PRIMARY KEY(`conversation_id`, `peer_nx_id`)
);
```

### 4.2. Pairwise Scope Gatekeeper
```kotlin
private fun validatePairwiseScope(
    myNxId: String,
    peerNxId: String,
    requestedConversationId: String
): Boolean {
    val canonicalScope = deriveConversationId(myNxId, peerNxId)
    return requestedConversationId == canonicalScope
}

fun deriveConversationId(userA: String, userB: String): String {
    val sorted = listOf(userA, userB).sorted()
    val raw = "${sorted[0]}:${sorted[1]}"
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
```

If an incoming sync request specifies a conversation ID that does not match `deriveConversationId(myNxId, peerNxId)`, the engine rejects the packet with:
```
[PeerSyncEngine] REJECTED: Foreign conversation scope requested by peer NX-ADVERSARY. Expected conversationId=e3b0c44..., got=782f91a...
```

### 4.3. Mutual Contact Authorization
Before replying to any sync query, `PeerSyncEngine` verifies:
```kotlin
val contact = contactDao.getContactByNxId(peerNxId)
if (contact == null || !contact.isAuthorized) {
    Log.w("PeerSyncEngine", "Dropping sync packet from unauthorized stranger: $peerNxId")
    return
}
```

---

## 5. Server-Side Routing & Hub Verification (`hub.go`)

In the Go backend (`backend/internal/connection/hub.go`), packet tag `wire.PacketType_PACKET_PEER_SYNC` (tag 14) is intercepted before normal relaying:

```go
case wire.PacketType_PACKET_PEER_SYNC:
    // Verify mutual relationship between sender and recipient
    if h.connRepo != nil {
        connected, err := h.connRepo.AreUsersConnected(packet.SenderNxId, packet.RecipientNxId)
        if err != nil || !connected {
            log.Printf("[Hub] Dropped PACKET_PEER_SYNC from %s to %s: not mutually connected",
                packet.SenderNxId, packet.RecipientNxId)
            return
        }
    }
    // Route in-memory to online recipient session
    recipientSession := h.lookupSession(packet.RecipientNxId)
    if recipientSession != nil {
        recipientSession.Send(packet)
    }
```

### Server Properties:
1. **Zero Storage:** Sync packets are ephemeral signaling; they are NEVER queued in Redis offline buffers. If the target peer is offline, the packet is discarded (anti-entropy triggers automatically when the peer connects).
2. **Mutual Connection Enforcement:** Non-connected accounts cannot exchange sync packets across the Cloud Gateway.
3. **RAM-Only Routing:** Zero disk I/O, preserving the Zero-Disk RAM Traffic Police model.
