# NEXA — Messaging & Transport Subsystem Audit

**Audit Date:** November 4, 2026  
**Audited Location:** `H:\chat_app`  
**Transports Audited:** Internet WebSocket, Port 8901 Binary Data SMS, BLE L2CAP CoC, Wi-Fi Aware, WebRTC SCTP DataChannel, LAN mDNS, DTN Custody Transfer.

---

## 1. Transport Architecture Diagram

```
                              [ OUTGOING MESSAGE / FILE PAYLOAD ]
                                              │
                                              ▼
                                 [ TransportRouter / Dispatcher ]
                                              │
                   ┌──────────────────────────┼──────────────────────────┐
                   │                          │                          │
                   ▼                          ▼                          ▼
        [ Light Text / Status ]      [ Heavy Media / Files ]    [ Zero Internet Off-Grid ]
                   │                          │                          │
        ┌──────────┴──────────┐               │                          │
        │                     │               │                          │
        ▼                     ▼               ▼                          ▼
 [ 1. WebSocket Relay ] [ 2. Data SMS ] [ 3. WebRTC DataChannel ] [ 4. MANET / DTN Mesh ]
  - Port 8080            - Port 8901     - SCTP Streaming          - BLE L2CAP (<=64KB)
  - Protobuf Wire Frames - 140B Binary   - 64KB Sparse Chunks      - Wi-Fi Aware (>64KB)
  - Ephemeral Redis 24h  - Zero Internet - Bitfield Resumption     - Store-Carry-Forward
  - Server ACKs          - Carrier SIM   - LAN mDNS (30-50 MB/s)   - 7-Hop Limit Ceiling
```

---

## 2. Comprehensive Transport Comparison Matrix

| Transport Method | Implementation Status | Media / Payload Types | Max Payload | Auth / Security | Offline Recipient Handling | Fallback Transport | Evidence File |
|---|:---:|---|:---:|---|---|---|---|
| **Internet WebSocket** | **COMPLETE (92%)** | Text messages, Delivery ACKs, Pings | Unlimited (streamed) | TLS + Ephemeral NX-ID Query | Buffered in Redis (24h TTL) | Failover to Data SMS or DTN Mesh | `core/network/WebSocketManager.kt` |
| **Port 8901 Data SMS** | **COMPLETE (92%)** | Text messages, Fallback pings | 133B single, multipart | Port 8901 Bound + App Magic Bytes | Delivered when SMS received | Fallover to DTN Mesh | `data/remote/sms/NexaDataSmsSender.kt` |
| **BLE L2CAP CoC** | **COMPLETE (90%)** | Short mesh packets, Route RREQ/RREP | 64 KB (512B MTU) | Curve25519 Blind Tokens | Held in `dtn_outbox` (48h TTL) | Wi-Fi Aware or Cloud Mule Dump | `core/mesh/transport/MeshDrivers.kt` |
| **Wi-Fi Aware (NAN)** | **COMPLETE (90%)** | High-speed offline media, Bulk DTN | Streamed (64KB chunks) | Zero-Knowledge Blind Tokens | Held in `dtn_outbox` (48h TTL) | BLE L2CAP or Internet Cloud Bridge | `core/mesh/transport/MeshDrivers.kt` |
| **WebRTC SCTP DataChannel** | **COMPLETE (95%)** | P2P Files, Images, Videos | Unlimited (tested 2GB+) | DTLS-SRTP + ChaCha20-Poly1305 | Session enters `PENDING_PEER_ONLINE` | TURN Relay fallback | `core/webrtc/ResilientDataStreamer.kt` |
| **LAN Auto-Discovery (mDNS)** | **COMPLETE (92%)** | High-speed local transfers | Network line rate | Local subnet isolation | N/A (LAN only) | WebRTC STUN/TURN | `core/webrtc/LanDiscoveryBridge.kt` |
| **Firebase Cloud Messaging (FCM)**| **STUB ONLY (10%)** | Background wake-up pings | 4 KB | Server Auth Key | N/A (FCM queues up to 28 days) | Foreground Services / WorkManager | `backend/internal/connection/hub.go:206` |
| **Supabase Realtime** | **ABSENT (0%)** | None | N/A | N/A | N/A | N/A | None (Not used by design) |

---

## 3. Deep Transport Mechanics

### A. Port-Bound Binary Data SMS (Port 8901)
- **Why Port 8901?** Standard text SMS appears in the user's messaging app (Google Messages, Truecaller), exposing encrypted ciphertext and cluttering the inbox. Port 8901 binary SMS is delivered by Android Telephony strictly to the application registered for that port (`NexaDataSmsReceiver`), completely invisible to the device's default SMS app.
- **Header Structure:** 1-byte version (`0x01`) + 8-byte message ID + 1-byte chunk index + 1-byte total chunks.
- **Payload Splitting:** Payloads $\le 133\text{ bytes}$ are dispatched in a single binary SMS. Larger payloads are sliced into multipart data SMS chunks.

### B. WebRTC Direct P2P SCTP DataChannel
- **Direct Streaming:** Files are sliced from `ParcelFileDescriptor` into 64 KB chunks directly on background threads.
- **Backpressure Monitoring:** `dataChannel.bufferedAmount()` is checked before every chunk. If buffer > 1 MB, streaming loop yields for 15ms.
- **Sparse `.nxdl` Bitfield Resumption:** If connection drops mid-transfer, neither sender nor receiver discards progress. `BitfieldTracker` swaps received `BitSet` bytes on reconnect and resumes only missing chunks.
- **Zero Heap Churn:** Employs `MemoryBufferPool` (16 pre-allocated 64 KB byte arrays), eliminating GC pauses during multi-gigabyte transfers.

### C. MANET Mesh Transport Router
- **Dynamic Handoff:**
  - Payloads $\le 64\text{ KB}$ route over BLE L2CAP Connection-Oriented Channels (low power).
  - Payloads $> 64\text{ KB}$ dynamically pivot to Wi-Fi Aware (high throughput).
- **Hop Bound:** Strict 7-hop limit ceiling prevents infinite looping.
- **Custody Transfer:** Messages hold in `OUTBOX_HOLD` until intermediate Data Mule confirms `CUSTODY_ACCEPTED`.
