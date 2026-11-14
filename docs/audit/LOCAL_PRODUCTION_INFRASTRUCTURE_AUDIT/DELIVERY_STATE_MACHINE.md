# NEXA — Delivery State Machine Specification

**Status:** IMPLEMENTED & VERIFIED  
**Version:** 2.0  
**Scope:** Message Lifecycle, Multi-Transport States, Custody Separation & UI Indicators  

---

## 1. Architectural Overview

The NEXA Delivery State Machine models the lifecycle of outgoing and incoming messages across diverse network fabrics (WebSocket, Cloud Gateway, BLE, Wi-Fi Direct, Data SMS, and DTN Store-and-Forward Mesh).

### Core Invariant
**No intermediate relay, server queue, or custody event may ever mark a message as `DELIVERED`.**
Relay custody acceptance confirms storage and forwarding commitment by an untrusted intermediate hop, but provides **zero assurance** that the message has reached, decrypted, or persisted on the final recipient's device.

---

## 2. Formal Delivery States

| State | Entity Field Value | Category | Description |
| :--- | :--- | :--- | :--- |
| **`CREATED`** | `"CREATED"` | Local Draft / Staged | Message is constructed locally and committed to SQLite/Room outbox, awaiting transport dispatch. |
| **`QUEUED`** | `"QUEUED"` | Cloud Accepted | Cloud Gateway has accepted the envelope into Redis offline queue or memory buffer. |
| **`RELAYED`** | `"RELAYED"` | Intermediate Custody | One or more intermediate DTN mules/relays have accepted custody of the envelope and committed to forward it. |
| **`DELIVERED`** | `"DELIVERED"` | End-to-End Success | Final recipient device has successfully received, decrypted, and durably stored the message in Room. Authenticated Delivery ACK dispatched. |
| **`READ`** | `"READ"` | User Consumption | Final recipient user opened the chat session and viewed the decrypted message plaintext. |
| **`FAILED`** | `"FAILED"` | Terminal Failure | Cryptographic ratchet failure, permanent network rejection, signature spoofing, or exhausted hop limit without delivery. |
| **`EXPIRED`** | `"EXPIRED"` | Terminal Expiry | Message TTL expired in offline queue or DTN cache before reaching recipient. |

> **Note on Backwards Compatibility:**  
> The legacy states `PENDING` (maps semantically to `CREATED`) and `SENT` (maps semantically to `QUEUED`) remain supported in `MessageStatus.kt` so existing Room rows and database migrations remain 100% valid.

---

## 3. State Transition Matrix

```
       [User Submits]
              │
              ▼
         ┌─────────┐
         │ CREATED │ (Single hollow circle / clock)
         └────┬────┘
              │
      ┌───────┴────────────────────────────┐
      │ (Direct Internet/WS)               │ (Offline Mesh / DTN)
      ▼                                    ▼
┌───────────┐                         ┌───────────┐
│  QUEUED   │                         │  RELAYED  │
└─────┬─────┘                         └─────┬─────┘
      │                                     │
      │ ◄───────[Custody ACK from Relay]────┘
      │
      │ ◄───────[Recipient Delivery ACK (Signed)]
      ▼
┌───────────┐
│ DELIVERED │ (Double grey checkmarks)
└─────┬─────┘
      │
      │ ◄───────[Recipient Read ACK]
      ▼
┌───────────┐
│   READ    │ (Double blue checkmarks)
└───────────┘

Failure paths:
[Any Pre-Delivery State] ──(TTL Expired)──► [ EXPIRED ]
[Any Pre-Delivery State] ──(Fatal Error)──► [ FAILED ]
```

### Transition Table

| Source State | Event Trigger | Next State | Verified Action / Precondition |
| :--- | :--- | :--- | :--- |
| `CREATED` | WS send accepted by Gateway | `QUEUED` | Cloud Gateway buffers offline packet in Redis. |
| `CREATED` | DTN Envelope ingested by Mule | `RELAYED` | Relay persists envelope to `dtn_envelopes` and emits `PACKET_CUSTODY_ACK`. |
| `CREATED` | Direct P2P delivery & ACK | `DELIVERED` | Recipient decrypts and emits signed `PACKET_ACK`. |
| `QUEUED` | Next hop relay custody ACK | `RELAYED` | Downstream mule takes over custody; status updates to `RELAYED`. |
| `QUEUED` | Recipient Delivery ACK received | `DELIVERED` | Signed `DeliveryAckPayload(ACK_DEVICE_DELIVERED)` validated. Gateway clears Redis. |
| `RELAYED` | Downstream custody transfer | `RELAYED` | Custody moves along hop chain; sender status stays `RELAYED`. |
| `RELAYED` | Recipient Delivery ACK received | `DELIVERED` | End-to-end receipt confirmed by intended destination node. |
| `DELIVERED`| Recipient Read ACK received | `READ` | User opened message conversation (`ACK_DEVICE_READ`). |
| `CREATED` / `QUEUED` / `RELAYED` | Clock exceeds envelope TTL | `EXPIRED` | TTL expiration threshold reached; message cannot be forwarded. |
| `CREATED` / `QUEUED` / `RELAYED` | Authentication or Ratchet error | `FAILED` | Cryptographic signature failure or session desync. |

---

## 4. UI Checkmark and Icon Mappings

The presentation layer in `MessageBubble.kt` and `NexaMessageBubble.kt` maps delivery states strictly to intuitive status indicators:

| Status | Icon / Glyph | Color | Visual Meaning |
| :--- | :--- | :--- | :--- |
| `CREATED` | `Icons.Default.AccessTime` | Light Grey (`0xFF888888`) | Message staged locally in draft outbox. |
| `QUEUED` / `SENT` | Single Checkmark `✓` | Light Grey (`0xFF888888`) | Accepted by server or staged for routing. |
| `RELAYED` | Single Checkmark `✓` | Light Grey (`0xFF888888`) | Held in transit by relay nodes (NOT delivered). |
| `DELIVERED`| Double Checkmark `✓✓` | Light Grey (`0xFF888888`) | Safely persisted on recipient device. |
| `READ` | Double Checkmark `✓✓` | Electric Cyan / Blue (`0xFF00E5FF`) | Read by recipient user. |
| `FAILED` | `Icons.Default.ErrorOutline` | Deep Coral / Red (`0xFFFF5252`) | Delivery failed permanently. |
| `EXPIRED` | `Icons.Default.HourglassEmpty` | Muted Orange (`0xFFFFAB40`) | Packet lifetime expired before delivery. |

> **Critical Safety Rule:** Intermediate custody (`RELAYED`) **must never render a double checkmark**. Only `DELIVERED` and `READ` are authorized to display double checkmarks.

---

## 5. Expiry and Retention Policies

1. **Cloud Gateway Redis Offline Queue:**
   - Default TTL: 24 hours (`86,400 seconds`).
   - Hard Cap: 7 days (`604,800 seconds`).
   - Eviction: Automatically purged upon Redis key expiration or receipt of valid recipient `PACKET_ACK`.

2. **DTN Intermediate Relays (`dtn_envelopes`):**
   - Envelope Header: `expiresAtEpochSec = createdAtEpochSec + ttlSeconds`.
   - Purge Query: `DELETE FROM dtn_envelopes WHERE expiresAtEpochSec <= :currentEpochSec`.
   - Max Hop Limit: Decremented at each hop. Dropped if `hopLimit <= 0`.
   - State Recovery: Survives application process restarts via SQLite Room persistence.
