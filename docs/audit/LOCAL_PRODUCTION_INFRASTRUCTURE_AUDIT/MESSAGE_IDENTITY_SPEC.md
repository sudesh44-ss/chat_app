# NEXA — Message Identity Specification & Conflict Resolution Model

**Status:** IMPLEMENTED & VERIFIED  
**Version:** 1.0.0  
**Target Systems:** Android Room Persistence, DTN Mesh Routing, Cloud Gateway Wire Protocol  

---

## 1. Principle of Identity Immutability

In distributed, multi-transport, delay-tolerant networks like NEXA, an encrypted message traverses untrusted intermediate nodes, heterogeneous wireless transports, and varying network topologies. To ensure cryptographic security, idempotency, and data integrity:

> **The logical identity and cryptographic payload of a message are strictly immutable from the moment of generation on the originating device.**
> **All transport-dependent, relay-specific, and hop-dependent values are strictly external routing metadata.**

Under no circumstances may a relay node, gateway, or local transport pipeline modify immutable message fields.

---

## 2. Field Classification Matrix

| Field Name | Category | Originator / Modifiers | Description |
| :--- | :--- | :--- | :--- |
| `packet_id` | **Immutable Identity** | Origin Device Only | Unique 128-bit identifier (UUIDv4 or 16-byte cryptographically secure random nonce). |
| `sender_nx_id` | **Immutable Identity** | Origin Device Only | Canonical NX-ID of the authoring device's user account. |
| `recipient_nx_id` | **Immutable Identity** | Origin Device Only | Canonical NX-ID of the designated final recipient. |
| `conversation_id` | **Immutable Identity** | Deterministic Formula | Canonical pairwise conversation hash: `SHA-256(min(A, B) : max(A, B))`. |
| `timestamp` | **Immutable Identity** | Origin Device Only | Milliseconds since Unix epoch at authoring time. |
| `payload_sha256` | **Immutable Identity** | Origin Device Only | Hex-encoded SHA-256 digest of the encrypted payload bytes. |
| `ciphertext` | **Immutable Identity** | Origin Device Only | Double Ratchet encrypted envelope ciphertext. |
| `auth_signature` | **Immutable Identity** | Origin Device Only | Ed25519 sender signature over immutable envelope headers. |
| `hop_count` | *Mutable Routing* | Intermediate Relays | Decremented at each mesh forwarder; excluded from sender signature. |
| `ttl` | *Mutable Routing* | Intermediate Relays | Expiration time window for store-and-forward caching. |
| `next_hop` | *Mutable Routing* | Intermediate Relays | MAC address, IP address, or Nearby endpoint ID of downstream peer. |
| `transport_type` | *Mutable Routing* | Local Transport Worker | Physical interface that delivered the packet (`WEBSOCKET`, `BLUETOOTH_MESH`, `WIFI_DIRECT`, `SMS`). |
| `rssi` | *Mutable Routing* | Physical Radio Driver | Received Signal Strength Indicator of the wireless frame. |
| `relay_node_id` | *Mutable Routing* | Intermediate Relays | Node identifier of the immediate peer providing the hop. |

---

## 3. Cryptographic Collision & Conflict Resolution Rules

When an incoming packet arrives at the ingestion gateway (`NexaUniversalTransportIngest.kt` on Android or `hub.go` on the backend), it is verified against the authoritative deduplication index using the following state-transition rules:

```
                          Inbound Packet with packet_id
                                       │
                                       ▼
                         Query message_dedup_index
                                       │
                    ┌──────────────────┴──────────────────┐
                    │                                     │
                 NOT FOUND                              FOUND
                    │                                     │
                    ▼                                     ▼
        Insert STATUS_RESERVED                 Compare (senderNxId, payloadSha256)
                    │                                     │
                    │                    ┌────────────────┴────────────────┐
                    │                    │                                 │
                    │                 MATCH                             MISMATCH
                    │                    │                                 │
                    │                    ▼                                 ▼
                    │           Examine status:                    [CONFLICT DETECTED]
                    │           ├── PROCESSED                      1. Mark STATUS_CONFLICT
                    │           │   ► Duplicate: drop payload,     2. Reject & drop packet
                    │           │     record transport observation, 3. DO NOT decrypt
                    │           │     dispatch ACK                 4. Log security alert
                    │           ├── RESERVED
                    │           │   ► In-flight: wait/suppress
                    │           └── CONFLICT
                    │               ► Reject & drop
                    ▼
         Proceed to Decryption
                    │
         ┌──────────┴──────────┐
         │                     │
      SUCCESS                FAILURE
         │                     │
         ▼                     ▼
  Update PROCESSED       Update FAILED
  Insert Room message    Rollback reservation
```

### Detailed Resolution Scenarios:

#### Scenario A: Legitimate Multi-Transport Retransmission (Match)
- **Condition:** An identical packet arrives over Wi-Fi Direct after already being received over WebSocket (`existing.senderNxId == incoming.senderNxId && existing.payloadSha256 == incoming.payloadSha256`).
- **Action:**
  1. Payload is not decrypted again; Double Ratchet state remains untouched.
  2. Transport observation is recorded in `message_transport_observations`:
     `{packet_id: "...", transport_type: "WIFI_DIRECT", observed_at: now, rssi: -45}`.
  3. Transport delivery ACK is returned to the Wi-Fi Direct peer so it ceases retransmission.

#### Scenario B: Cryptographic Collision / Malicious Packet Tampering (Conflict)
- **Condition:** An incoming packet arrives with a previously observed `packet_id`, but `senderNxId` differs, or `payload_sha256` differs.
- **Action:**
  1. Atomic transition of `message_dedup_index` to `STATUS_CONFLICT`.
  2. **Double Ratchet Pre-Decryption Firewall:** Decryption is strictly aborted. The cryptographic ratchet engine is never invoked, preventing adversary-induced ratchet state corruption or denial of service.
  3. Security event is logged with severity `CRITICAL`:
     ```
     [TransportIngest] SECURITY CONFLICT: packet_id 9f8a... reused with differing payload or sender. Original sender: NX-ALICE, new sender: NX-MALLORY. Rejected.
     ```

#### Scenario C: Transient Ingestion Failure (Rollback)
- **Condition:** Packet reserved as `STATUS_RESERVED`, but network drops or local storage encounters an unrecoverable disk error before Room insertion.
- **Action:**
  1. `status` is transitioned to `STATUS_FAILED`.
  2. If the peer retransmits the packet later, the system permits a retry rather than permanently dropping the message.

---

## 4. Message ID Generation Standards

To guarantee global uniqueness without centralized coordination across millions of offline devices:

1. **Entropy Source:** Cryptographically secure pseudorandom number generator (`java.security.SecureRandom` on Android, `crypto/rand` in Go).
2. **Format:** Standard 128-bit UUID Version 4 formatted as 36 lowercase characters with hyphens (e.g., `f47ac10b-58cc-4372-a567-0e02b2c3d479`), or a 16-byte hexadecimal string.
3. **Collision Probability:** For $N$ messages generated independently, the probability of collision is:
   $$P \approx 1 - e^{-\frac{N^2}{2 \times 2^{128}}}$$
   Generating 1 billion messages per day across 100 million devices results in a collision probability of less than $10^{-18}$, guaranteeing mathematical uniqueness across the lifetime of the network.
