# NEXA — Messaging Security & Recipient Routing Audit

**Audit Date:** November 4, 2026  
**Auditor:** Network Security Engineer & Protocol Specialist  
**Pipeline:** Sender Authentication → Encryption → Relay Routing → Decryption  

---

## 1. End-to-End Messaging Pipeline Verification

```text
[ User A (Android) ] 
       │
       ├─ 1. Verify Active Cryptographic Session (DoubleRatchetEngine)
       ├─ 2. Encrypt Plaintext -> EncryptedEnvelope (ChaCha20-Poly1305)
       ├─ 3. Wire Framing (49-Byte Overhead)
       ├─ 4. Stage in SQLCipher Outbox (status = 'PENDING')
       ▼
[ Transport Selection: WebSocket / DATA SMS / DTN / WebRTC ]
       │
       ▼
[ Go Backend (Blind-Postman Relay) ]
       │
       ├─ 1. Authenticate Sender Connection (Ed25519 Challenge)
       ├─ 2. Validate Sender Device Authorization
       ├─ 3. Resolve Target Recipient NX-ID & Authorized Active Devices
       ├─ 4. Inspect Blind Header (NO Plaintext Inspection, NO Plaintext Storage)
       ├─ 5. Route Ciphertext to Active Connection or Buffer in Redis (24h TTL)
       ▼
[ User B (Android) ]
       │
       ├─ 1. Validate Expected Sender Identity & Recipient NX-ID
       ├─ 2. Replay Protection Check (Packet ID & Monotonic Counter)
       ├─ 3. Decrypt Payload via DoubleRatchetEngine (Recover Skipped Keys if Out-of-Order)
       ├─ 4. Stage Plaintext in SQLCipher Inbox (status = 'DELIVERED' -> 'READ')
```

---

## 2. Recipient Isolation & Cross-User Security

### A. Routing Security Checks
- **Server Blind Routing**: The Go relay server routes ciphertext strictly using `packet.RecipientNxId`. It never decrypts, inspects, or logs the message content.
- **Cross-User Leak Prevention**:
  A client connecting to `/ws?nx_id=NX-USER-A` is barred from subscribing to or receiving frames addressed to `NX-USER-B`. The WebSocket hub enforces strict session-to-identity binding.
- **Impersonation Defense**:
  A sender cannot spoof `senderNxId` because the initial WebSocket handshake requires signing a server-issued cryptographic nonce with the Ed25519 identity private key.

### B. Outbox Ratchet Key Reuse Optimization
In `ChatRepositoryImpl.kt`, retrying failed or pending messages reuses the already-encrypted wire packet rather than advancing the Double Ratchet key chain again. This completely eliminates key-desynchronization bugs on flaky mobile networks.

### C. Multi-Transport Resiliency
1. **Internet WebSocket**: Real-time delivery with exponential backoff jitter (0–500ms).
2. **DATA SMS (Port 8901)**: Fully functional off-grid fallback using binary SMS fragmentation (`[MsgID: 2B][Seq: 1B][Total: 1B][CRC8: 1B]`).
3. **DTN Opportunistic Mesh**: Store-and-forward bundles passed peer-to-peer over BLE/Wi-Fi Direct with cryptographic TTL pruning.
4. **WebRTC DataChannels**: Zero-cloud direct transfer for high-throughput media.
