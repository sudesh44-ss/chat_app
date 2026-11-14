# NEXA — Custody Acknowledgment Protocol Specification

**Status:** IMPLEMENTED & VERIFIED  
**Version:** 1.0  
**Scope:** Wire Format, Custody State Machine, Persistence Gating & Relay Responsibility  

---

## 1. Protocol Objective

The Custody Acknowledgment Protocol allows intermediate Delay-Tolerant Networking (DTN) mules and mesh nodes to confirm custody of sealed, zero-trust `NXDT` envelopes without breaking end-to-end encryption or leaking metadata.

A **Custody ACK** is an explicit hop-by-hop reliability guarantee between two directly communicating peers (e.g. Sender $\to$ Relay A, or Relay A $\to$ Relay B). It confirms that the custodian has:
1. Checked envelope structural validity and cryptographic signature.
2. Verified that the envelope's TTL has not expired and hop limit has not reached zero.
3. Decremented the hop count and updated relay metadata.
4. **Durably written and committed the envelope to SQLite/SQLCipher (`dtn_envelopes`)**.
5. Accepted forward custody obligation until transfer to a downstream peer.

---

## 2. Wire Protocol Definitions

### Protobuf Definition (`proto/nexa_wire.proto`)

```protobuf
enum CustodyStatus {
  CUSTODY_STATUS_UNSPECIFIED = 0;
  CUSTODY_ACCEPTED           = 1; // Node committed envelope to durable storage
  CUSTODY_TRANSFERRED        = 2; // Downstream hop accepted custody
  CUSTODY_REJECTED           = 3; // Structural, auth, or capacity failure
  CUSTODY_EXPIRED            = 4; // Envelope TTL expired in vault
}

message CustodyAckPayload {
  string original_packet_id = 1; // Target envelope UUID / hex identifier
  string custodian_nx_id    = 2; // NX-ID of acknowledging node
  CustodyStatus status      = 3; // Current custody status
  int64 timestamp           = 4; // Unix epoch milliseconds
  bytes signature           = 5; // Ed25519 signature over canonical digest
}

message NexaWirePacket {
  string packet_id           = 1;
  PacketType type            = 2; // PACKET_CUSTODY_ACK = 15
  string sender_nx_id        = 3; // Custodian NX-ID
  string recipient_nx_id     = 4; // Previous hop NX-ID
  int64 timestamp            = 5;
  CustodyAckPayload custody_ack = 15;
}
```

---

## 3. Custody State Machine

Intermediate DTN envelopes advance through 7 formal custody states:

```
    [Envelope Received]
             │
   (Persistence Check)
             ├─── [Storage Error / Disk Full] ──► CUSTODY_FAILED
             ▼
     CUSTODY_ACCEPTED (Emit PACKET_CUSTODY_ACK to upstream peer)
             │
    (Mule Carries Packet)
             ▼
        FORWARDING    (Actively scanning for downstream peer / recipient)
             │
             ├─── [Downstream Mule Confirmed Custody] ──► CUSTODY_TRANSFERRED
             │                                                  │
             │                                     (Prune policy / cleanup)
             │                                                  ▼
             │                                           CUSTODY_RELEASED
             │
             └─── [TTL Expired in Vault] ───────────────► CUSTODY_EXPIRED
```

### State Explanations

1. **`NOT_ACCEPTED`**: Inbound envelope has not yet been durably committed to storage.
2. **`CUSTODY_ACCEPTED`**: Stored in `dtn_envelopes`. Custodian is now durably responsible for packet delivery.
3. **`FORWARDING`**: Envelope is being actively advertised or transmitted over local radio (BLE, Wi-Fi Aware, Nearby Connections).
4. **`CUSTODY_TRANSFERRED`**: Downstream node returned a verified `PACKET_CUSTODY_ACK`. Custodian is relieved of primary forward duty.
5. **`CUSTODY_RELEASED`**: Envelope pruned from cache to reclaim storage space.
6. **`CUSTODY_EXPIRED`**: Envelope expired while waiting for next hop; purged safely.
7. **`CUSTODY_FAILED`**: Cryptographic verification failed or disk write rejected.

---

## 4. Absolute Invariants & Security Boundaries

### 4.1 Persistence Gating
A node **MUST NOT** send a `PACKET_CUSTODY_ACK` until the envelope has been durably committed to disk (`rowId > 0` in `DtnEnvelopeDao`).

```kotlin
val rowId = envelopeDao.insertEnvelope(entity)
if (rowId > 0) {
    // ONLY dispatch Custody ACK after disk commit succeeds
    val custodyAck = createSignedCustodyAck(decrementedEnvelope.envelopeIdHex, CustodyStatus.CUSTODY_ACCEPTED)
    ackDispatcher?.invoke(custodyAck, medium)
    return true
} else {
    // Storage failure: WITHHOLD Custody ACK!
    Log.w(TAG, "Mule Custody: Persistence failed. Withholding Custody ACK.")
    return false
}
```

### 4.2 Blind Relay Decryption Isolation
Intermediate nodes are blind mules. They:
- Do not hold the recipient's private Double Ratchet or identity keys.
- Cannot inspect plaintext payload or sender real identity.
- Validate only the outer envelope header and Ed25519 signature.

### 4.3 Cloud Gateway Offline Queue Isolation
In `backend/internal/connection/hub.go`, incoming `PACKET_CUSTODY_ACK` packets are strictly routed in memory:
- **`h.buffer.Acknowledge` is NEVER called for Custody ACKs.**
- Only genuine recipient `PACKET_ACK` (`ACK_DEVICE_DELIVERED`) can reconcile or delete messages from Redis.
- A malicious or rogue relay node cannot purge a recipient's offline queue by spamming Custody ACKs.

### 4.4 Sender Delivery State Isolation
When a sender receives a `PACKET_CUSTODY_ACK`:
- Message status advances to `RELAYED`.
- Message status **NEVER advances to `DELIVERED`**.
- UI continues to show a single checkmark (mule custody in progress).
