# NEXA — Delivery Acknowledgment Protocol Specification

**Status:** IMPLEMENTED & ENFORCED  
**Version:** 2.0  
**Scope:** Wire Packet ACK Payloads, Semantics, Cryptographic Binding & Queue Reconciliation  

---

## 1. Protocol Objective

The NEXA Delivery Acknowledgment Protocol provides authentic, end-to-end delivery tracking, sender UI state synchronization, and Cloud Gateway offline queue reconciliation.

Acknowledgments serve two distinct architectural roles:
1. **Upstream Gateway Reconciliation:** Signals the Cloud Gateway that an offline-buffered message has been safely received, decrypted, and persisted by the recipient, authorizing the permanent erasure of the message from Redis.
2. **End-to-End Sender Synchronization:** Relays device-level confirmation back to the original sender to advance message status indicators in the user interface (`QUEUED` $\to$ `DELIVERED` $\to$ `READ`).

---

## 2. Wire Packet Structure

Delivery acknowledgments are encoded using NEXA's binary wire format (`tag:value` / protobuf schema):

```protobuf
enum AckStatus {
  ACK_UNKNOWN          = 0;
  ACK_SERVER_RECEIVED  = 1; // Server received & accepted packet
  ACK_DEVICE_DELIVERED = 2; // Recipient device decrypted & persisted packet
  ACK_DEVICE_READ      = 3; // Recipient user viewed message in conversation
}

message DeliveryAckPayload {
  string original_packet_id = 1; // ID of message acknowledged
  AckStatus status           = 2; // ACK status enum
  string recipient_nx_id    = 3; // Intended recipient NX-ID
  string device_id          = 4; // Intended recipient Device ID
  int64 timestamp           = 5; // Unix epoch milliseconds
  bytes signature           = 6; // Ed25519 signature over canonical digest
}

message NexaWirePacket {
  string packet_id           = 1;
  PacketType type            = 2; // PACKET_ACK = 2
  string sender_nx_id        = 3; // Acknowledging party (Recipient)
  string recipient_nx_id     = 4; // Original sender or "SERVER"
  int64 timestamp            = 5; // Unix epoch milliseconds
  DeliveryAckPayload ack     = 7; // Tag 7
}
```

---

## 3. Strict Pre-Decryption vs Post-Persistence Boundaries

A `PACKET_ACK` with `ACK_DEVICE_DELIVERED` is **strictly post-decryption and post-persistence**:

```
[Inbound Packet]
       │
       ▼
(Check Exact Dedup)
       ├─── Already Processed ──► Return idempotent ACK & exit
       ▼
(Double Ratchet Decrypt)
       ├─── Decryption Failed ──► Mark FAILED, DO NOT EMIT DELIVERY ACK!
       ▼
(Commit to Room Database)
       ├─── Disk Error ────────► Withhold ACK!
       ▼
(Commit Succeeded) ─────────────► Compute AckDigestHelper -> Sign with Ed25519
                                         │
                                         ▼
                               Dispatch PACKET_ACK (ACK_DEVICE_DELIVERED)
```

1. **Pre-decryption ACK is strictly forbidden:** A device must never acknowledge delivery for ciphertext it cannot decrypt.
2. **Post-persistence requirement:** The decrypted message entity must be committed to the SQLite/SQLCipher `messages` table before dispatching the ACK. If SQLite throws an `SQLiteFullException` or I/O error, the ACK is withheld so the upstream gateway or peer will redeliver when storage is freed.

---

## 4. Cryptographic Authentication & Anti-Spoofing

### 4.1 Canonical Digest Generation
To prevent tampering or cross-message replay, the delivery ACK signature is calculated over a deterministic canonical digest:

$$\text{Digest} = \text{SHA-256}("NEXA\_DELIVERY\_ACK\_V1" \mathbin{\Vert} \text{OriginalPacketID} \mathbin{\Vert} \text{Status} \mathbin{\Vert} \text{RecipientNxID} \mathbin{\Vert} \text{DeviceID} \mathbin{\Vert} \text{Timestamp})$$

Implemented in `com.nexa.app.core.crypto.AckDigestHelper.kt`:

```kotlin
fun computeDeliveryAckDigest(
    originalPacketId: String,
    status: AckStatus,
    recipientNxId: String,
    deviceId: String,
    timestamp: Long
): ByteArray
```

### 4.2 Gateway Anti-Spoofing & Authorization Checks
To prevent malicious clients from acknowledging and deleting other users' offline messages, the Cloud Gateway enforces strict wire-level checks in `RouteDecodedPacket`:

```go
if packet.Type == wire.PacketType_PACKET_ACK && packet.Ack != nil {
    if packet.Ack.Status == wire.AckStatus_ACK_DEVICE_DELIVERED {
        if sender.Authenticated && sender.NxId == packet.SenderNxId {
            // Authorized: Sender owns this queue
            h.buffer.Acknowledge(ctx, sender.NxId, packet.Ack.OriginalPacketId)
        } else {
            // Security rejection: Attempt to spoof another user's ACK
            log.Printf("[Hub] Security warning: unauthenticated or spoofed delivery ACK from %s for %s", 
                sender.NxId, packet.SenderNxId)
            return false
        }
    }
}
```

1. **Cryptographic Identity Binding:** The client session must have completed challenge-response authentication with its Ed25519 identity key (`sender.Authenticated == true`).
2. **Identity Equality:** The session's authenticated `NxId` must strictly equal the ACK packet's `SenderNxId`. A session authenticated as `NX-ATTACKER` cannot emit an ACK claiming `SenderNxId = NX-BOB`.

---

## 5. Recipient Verification at the Sender

When the original sender receives an inbound `PACKET_ACK`:
1. It looks up the target message by `originalPacketId`.
2. It verifies that `packet.SenderNxId == message.RecipientNxId` (mismatched recipients are rejected).
3. If the recipient contact's Ed25519 public key is known, it verifies `packet.Ack.Signature` over the canonical digest.
4. If valid, message status transitions from `QUEUED` or `RELAYED` to `DELIVERED` (double checkmark in UI).
5. Duplicate delivery ACKs are processed idempotently without state corruption.
