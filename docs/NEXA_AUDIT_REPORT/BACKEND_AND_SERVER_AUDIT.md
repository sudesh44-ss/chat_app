# NEXA — Backend & Server Architecture Audit

**Audit Date:** November 4, 2026  
**Audited Location:** `H:\chat_app\backend`  
**Deployment Infrastructure:** Docker Compose, Alpine Linux 3.20, Go 1.22, In-Memory Ephemeral Redis 7.

---

## 1. Executive Summary: Real Backend vs BaaS

NEXA does **not** rely on Supabase, Firebase Firestore, or any third-party Backend-as-a-Service for its core messaging and signaling pipelines.
The project contains an autonomous, self-hosted, lightweight Go (Golang) microservice suite designed around the **Blind-Postman Principle**:
- **Zero Disk Storage:** No chat messages, attachments, or user social graphs are ever written to the server's disk.
- **Ephemeral In-Memory Buffering:** Undelivered messages are held in Redis with a strict 24-hour TTL (`volatile-ttl`), after which they evaporate if uncollected.
- **RAM-Only Routing:** Online messages are piped directly from the sender's WebSocket read-pump to the recipient's WebSocket write-pump via Go channels.

---

## 2. Microservices Inventory (7 Services)

| Service Name | Entry Point | Port | Primary Responsibilities | Deployment Status |
|---|---|:---:|---|:---:|
| **`nexa-relay` (Server)** | `backend/cmd/server/main.go` | `8080` | Client WebSocket sessions, Blind-Postman forwarding, Server ACKs, Redis queue buffering | CONFIGURED & COMPILED |
| **`nexa-swarm`** | `backend/cmd/swarm/main.go` | `8081` | 1-to-many P2P BitTorrent-style swarm cross-seeding coordination | CONFIGURED & COMPILED |
| **`nexa-blind-relay`** | `backend/cmd/relay/main.go` | `8082` | DTN Data Mule opportunistic dump ingestion (`/api/v1/relay/opportunistic-dump`) | CONFIGURED & COMPILED |
| **`nexa-signaling`** | `backend/cmd/signaling/main.go` | `8083` | WebRTC P2P direct SCTP DataChannel SDP Offer/Answer & ICE candidate relay | CONFIGURED & COMPILED |
| **`nexa-handshake`** | `backend/cmd/handshake/main.go` | `8084` | Ephemeral prekey directory, X3DH handshake mailbox, Hashcash micro-PoW verifier | CONFIGURED & COMPILED |
| **`nexa-qr-sync`** | `backend/cmd/qr-sync/main.go` | `8085` | Day-0 QR cryptographic handshake verification & cross-device sync | CONFIGURED & COMPILED |
| **`nexa-security-rules`**| `backend/cmd/security-rules/main.go`| `8086` | OTA Differential YARA rules, malware SHA-256 hashes, Bloom filter distribution | CONFIGURED & COMPILED |

---

## 3. Deep Service Breakdown

### A. Core Messaging Relay Server (`nexa-relay`)
- **Location:** `backend/cmd/server/main.go`, `backend/internal/connection/hub.go`
- **Protocol:** HTTP/1.1 upgraded to WebSocket (`github.com/gorilla/websocket`)
- **Endpoint:** `GET /ws?nx_id={nx_id}&blind_token={optional}`
- **Routing Lifecycle:**
  1. Client connects with query param `nx_id`.
  2. `Hub.Register()` adds session to concurrent in-memory map `sessions[session.NxId]`.
  3. Hub triggers `flushPendingQueue()`, popping buffered messages from Redis and streaming them to the client.
  4. On incoming packet:
     - Dispatches immediate `ACK_SERVER_RECEIVED` to sender.
     - Resolves `packet.RecipientNxId`.
     - If recipient is online, writes bytes into recipient's `Send` channel (RAM-to-RAM).
     - If offline, pushes packet to Redis with 24h TTL and triggers FCM log stub.

### B. Handshake & Prekey Service (`nexa-handshake`)
- **Location:** `backend/cmd/handshake/main.go`, `backend/internal/handshake/handler.go`
- **Endpoints:**
  - `POST /api/v1/users/prekey`: Uploads signed prekey bundle.
  - `GET /api/v1/users/{nx_id}/prekey`: Downloads target user's public prekey bundle.
  - `POST /api/v1/requests/dispatch`: Dispatches encrypted connection request.
  - `GET /api/v1/requests/pending?nx_id={nx_id}`: Retrieves sealed requests.
- **Security Features:**
  - **Rate Limiting:** Enforces maximum 15 connection requests per hour per IP.
  - **Micro-PoW Verification:** Validates Hashcash nonce (`pow_nonce` + `pow_timestamp`) to neutralize automated spam bots.

### C. WebRTC Signaling Hub (`nexa-signaling`)
- **Location:** `backend/cmd/signaling/main.go`, `backend/internal/signaling/hub.go`
- **Endpoints:** `GET /ws/signaling?nx_id={nx_id}`, `GET /health`
- **Traffic Governed:**
  - SDP Offers & Answers.
  - ICE Candidates (`candidate`, `sdpMid`, `sdpMLineIndex`).
  - **Circuit Breaker:** Rate-limits TURN relaying to 1.5 MB/s and rejects file payloads > 100 MB.
  - **Push Debouncer:** Aggregates multi-file arrivals into a 5-second sliding debounce window.

---

## 4. Two-Device End-to-End Communication Trace

### Can Two Physically Separate NEXA Installations Communicate?

Let us trace a message sent from **Device A (`NX-1001-A1A`)** to **Device B (`NX-2002-B2B`)**:

```
[ Device A (Sender) ]
         │
    1. User types message & taps Send (INTERNET transport).
    2. Inserts into local Room DB: status = PENDING.
    3. Serializes to Protobuf NexaWirePacket (type = PACKET_MESSAGE).
    4. Encrypts with keystoreManager.encryptWithMasterKey().  <-- [CRITICAL DEFECT]
    5. Transmits over WebSocket ws://server:8080/ws?nx_id=NX-1001-A1A.
         │
         ▼
[ Go Backend (nexa-relay on Port 8080) ]
    6. gorilla/websocket receives binary frame.
    7. Decodes wire packet: Sender = NX-1001-A1A, Recipient = NX-2002-B2B.
    8. Dispatches ACK_SERVER_RECEIVED packet back to Device A (Device A sets status = SENT).
    9. Hub checks: Is NX-2002-B2B in sessions map?
         │
         ├─[ YES: Device B is Online ]
         │     Pipes binary frame to Device B's WebSocket channel.
         │
         └─[ NO: Device B is Offline ]
               Pushes binary frame to Redis queue (key = "pending:NX-2002-B2B", TTL = 24h).
               Device B connects 2 hours later -> Hub flushes Redis queue to Device B.
         │
         ▼
[ Device B (Receiver) ]
    10. WebSocketListener.onMessage() decodes NexaWirePacket.
    11. Hands packet to ChatRepository.handleIncomingDataSmsPacket() / handleIncomingPacket().
    12. Attempts decryption: keystoreManager.decryptWithMasterKey().
         │
         ▼
    [ DECRYPTION FAILURE ]: Device B attempts to decrypt with its own local KeyStore Master Key.
    Because Device B does not possess Device A's Android KeyStore AES key,
    the decryption throws AEADBadTagException!
    ChatRepository catches the exception and displays: "[Encrypted Message]".
```

### Audit Finding on Two-Device Communication:
1. **Network Transport & Server Routing:** **100% OPERATIONAL & VERIFIED.** The Go WebSocket relay correctly routes binary packets between separate clients, handles offline buffering in Redis, and dispatches server ACKs.
2. **Payload Cryptography Disconnect:** Because `ChatRepositoryImpl.kt` lines 142 & 183 call the device-local `keystoreManager.encryptWithMasterKey` rather than invoking `doubleRatchetEngine.ratchetEncrypt()`, cross-device plaintext decryption currently fails. Wiring `DoubleRatchetEngine` resolves this completely.
