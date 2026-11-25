# NEXA — Master System Architecture Blueprint
## 03. Backend Architecture & Go Relay Services

---

### 1. Architectural Model & Zero-Disk Philosophy

The NEXA backend is engineered as a high-concurrency, horizontally scalable **Go (Golang 1.25) Modular Relay Server**. Adhering strictly to the primary architectural mandate (`1architecture.md`), the server operates as a **Blind Post Office**:

```
+---------------------------------------------------------------------------------------------------+
|                                 THE BLIND POST OFFICE BACKEND CORE                                |
|                                                                                                   |
|  - Zero-Disk Storage: Chat messages, attachments, and encryption keys NEVER touch server disks.   |
|  - Ephemeral RAM Buffering: Packets awaiting offline recipient delivery are held strictly in       |
|    Redis RAM buffers with a mandatory 24-hour TTL and purged immediately upon receipt of ACK.     |
|  - Blind Routing: Forwarding is performed strictly using public destination handles (NX-ID) or    |
|    blind pairwise rotating tokens. The relay has zero cryptographic ability to decrypt payloads.   |
+---------------------------------------------------------------------------------------------------+
```

---

### 2. Backend Package Structure & Microservices

The Go backend located in `backend/` is organized into distinct domain packages within `internal/` and entry points within `cmd/`:

```
backend/
├── cmd/
│   ├── server/main.go          # Master Relay Server (Unified Monolith hosting all primary ports)
│   ├── handshake/main.go       # Isolated Prekey Directory & Hashcash PoW Verifier Sidecar (Port 8084)
│   ├── qr-sync/main.go         # Day-0 Out-of-Band Cryptographic Sync Sidecar (Port 8085)
│   ├── relay/main.go           # Dedicated High-Throughput WebSocket Forwarder Sidecar (Port 8080)
│   ├── security-rules/main.go  # OTA YARA Rule & Malware Hash Distribution Sidecar (Port 8086)
│   ├── signaling/main.go       # Isolated WebRTC P2P Signaling Sidecar (Port 8083)
│   └── swarm/main.go           # P2P Swarm Cross-Seeding Coordinator Sidecar (Port 8081)
│
├── internal/
│   ├── api/                    # HTTP REST handlers (DTN opportunistic cloud bridge, health checks)
│   ├── auth/                   # Identity verification (Google, Firebase, MSG91, Reverse SMS, Truecaller)
│   ├── connection/             # Real-time WebSocket Hub, Client Sessions, Redis Pub/Sub clustering
│   ├── connections/            # Contact relationships, friend requests, user discovery, blocking
│   ├── database/               # PostgreSQL connection pool (`pgxpool`), schema migrations, repository
│   ├── dtn/                    # Delay-Tolerant Networking envelope parser & vector routing
│   ├── fcm/                    # Firebase Cloud Messaging background wake-up dispatcher
│   ├── handshake/              # Proof-of-Work challenge verification and prekey exchange
│   ├── identity/               # NX-ID generator, phone crypto (HMAC + AES-GCM), session migrator
│   ├── prekey/                 # Signal-style Signed Prekeys & One-Time Prekey (OPK) management
│   ├── queue/                  # Ephemeral Redis queue buffer with degraded in-memory fallback
│   ├── ratelimit/              # Distributed sliding-window token bucket rate limiter
│   ├── relay/                  # Relay routing tables and blind packet forwarders
│   ├── rules/                  # OTA security rules storage and distribution
│   ├── signaling/              # WebRTC signaling hub, SDP/ICE candidate relay, circuit breaker
│   ├── swarm/                  # Swarm catalog, chunk bitfield tracker, and peer cross-seeder
│   ├── sync/                   # Multi-device synchronization hub
│   └── wire/                   # Protobuf wire protocol codecs and packet builders
│
├── Dockerfile                  # Multi-stage Alpine container build (CGO_ENABLED=0 static binary)
└── docker-compose.yml          # Production container composition (Server + PostgreSQL 15 + Redis 7)
```

---

### 3. Server Startup Lifecycle & Configuration Validation

The master server entry point (`backend/cmd/server/main.go`) enforces a strict, fail-fast initialization sequence:

1. **Environment & Security Gates Validation (lines 54–65):**
   - Calls `identity.ValidatePhoneCryptoConfig()`: Validates that `PHONE_HMAC_KEY` (32 bytes) and `PHONE_ENC_KEY` (32 bytes) are present. In production (`APP_ENV=production`), missing keys trigger immediate panic (`log.Fatalf`), preventing unencrypted phone storage.
   - Calls `auth.ValidateOtpConfig(isProduction)`: Validates that `OTP_HMAC_PEPPER` is configured. Rejects default development peppers in production.
2. **Ephemeral Redis Queue Initialization (lines 79–93):**
   - Establishes connection to Redis (`REDIS_ADDR`, default `localhost:6379`).
   - If Redis is unreachable in production, server aborts (`log.Fatalf`). In development, it initializes a degraded in-memory RAM fallback.
3. **PostgreSQL Database Connection & Auto-Migration (lines 96–139):**
   - Initializes `pgxpool.Pool` using `DATABASE_URL`.
   - Executes `database.RunMigrations()`, applying sequential migrations 001 through 018.
   - Executes `database.BackfillPhoneProtection()`, ensuring legacy phone records are encrypted.
   - If legacy `identity_store.json` exists, runs `identity.NewMigrator()` to migrate accounts into PostgreSQL.
4. **Auth & Identity Handlers Registration (lines 161–172):**
   - Initializes `auth.OtpService` with MSG91 SMS provider and distributed Redis state.
   - Initializes `auth.GoogleVerifier`, `auth.FirebaseVerifier`, `auth.TruecallerVerifier`.
   - Mounts authentication endpoints under `/api/v1/auth/`.
5. **Session Hub & Clustering Initialization (lines 176–187):**
   - Creates the concurrent `connection.Hub` backed by the Redis buffer and FCM service.
   - Starts `hub.StartClusterSubscriber()`, subscribing to the `nexa:cluster:broadcast` Redis channel for multi-instance horizontal scaling.
6. **Subsystem Wiring & Endpoint Binding (lines 189–340):**
   - Mounts Connection Handlers, Prekey Handlers, Messaging WebSocket (`/ws`), WebRTC Signaling WebSocket (`/ws/signaling`), DTN Ingestion (`/api/v1/relay/opportunistic-dump`), Media Fallback Tickets (`/api/v1/media/upload-ticket`), and Telemetry APIs (`/api/v1/telemetry/metrics`).

---

### 4. Real-Time Messaging Hub & Session Lifecycle (`internal/connection/`)

#### A. Client Session Management (`client.go`)
When an Android client connects to `ws://host:8080/ws?nx_id=NX-XXXXXXXX&device_id=DEVICE-UUID`:
1. **WebSocket Upgrade:** The connection is upgraded to RFC 6455 WebSocket via `gorilla/websocket` with 64 KB read/write buffers.
2. **Ed25519 Cryptographic Challenge Dispatch:**
   - The server generates a 32-byte secure random nonce (`challenge_nonce`).
   - Sends a `PACKET_AUTH_CHALLENGE` packet with a strict 60-second TTL.
   - Spawns two independent goroutines: `ReadPump()` and `WritePump()`.
3. **Challenge-Response Verification:**
   - The client must respond with a `PACKET_AUTH_RESPONSE` containing its 32-byte Ed25519 public key and 64-byte cryptographic signature over the nonce.
   - The server verifies the signature using `crypto/ed25519.Verify()`.
   - If valid, the session transitions to `Authenticated = true`, registers with `Hub`, and dispatches `PACKET_AUTH_SUCCESS`.
   - If invalid or timed out, the socket is immediately terminated (`CloseWithUnauthorized`).

```
  Client (Android)                                               Server (Go Relay)
         |                                                               |
         | --- HTTP GET /ws?nx_id=NX-12345678 (Upgrade Request) -------> |
         | <--- HTTP 101 Switching Protocols (WebSocket Established) --- |
         |                                                               |
         | <--- PACKET_AUTH_CHALLENGE (32-byte nonce, 60s TTL) --------- |
         |                                                               |
         | [Signs nonce using Keystore-backed Ed25519 Private Key]       |
         |                                                               |
         | --- PACKET_AUTH_RESPONSE (Ed25519 Signature + Public Key) ---> |
         |                                                               |
         |                                  [Verifies Signature via]     |
         |                                  [crypto/ed25519.Verify]      |
         |                                                               |
         | <--- PACKET_AUTH_SUCCESS ------------------------------------ |
         |                                                               |
         | === Authenticated Full-Duplex Blind Messaging Active ======== |
```

#### B. Blind-Postman Routing & Redis Buffering (`hub.go` & `queue/redis_buffer.go`)
- **Online Recipient:** When User A sends a `PACKET_MESSAGE` to User B:
  1. The server extracts `recipient_nx_id` from the wire header (the payload remains opaque ciphertext).
  2. The server checks `Hub.sessions[recipient_nx_id]`.
  3. If User B has an active WebSocket session, the packet is placed directly on User B's outbound write channel (`sendCh`), delivering it in sub-10 milliseconds.
  4. The server receives a delivery `PACKET_ACK` from User B and routes it back to User A.
- **Offline Recipient:**
  1. If User B is not connected, the server stores the packet in Redis:
     `RPUSH nexa:queue:NX-RECIPIENT <serialized_protobuf_bytes>`
     `EXPIRE nexa:queue:NX-RECIPIENT 86400` (24-hour strict TTL).
  2. The server triggers `fcmService.SendWakeupPing(recipient_nx_id)` via Firebase Cloud Messaging, waking User B's device in the background.
  3. When User B reconnects and completes the Ed25519 handshake, `Hub` flushes all pending packets from Redis into User B's write channel via FIFO queue ordering.

#### C. Horizontal Scaling via Redis Pub/Sub (`pubsub.go`)
In a multi-node cluster deployment:
- When a packet arrives for a recipient connected to a *different* relay node, the local node publishes the packet to Redis channel `nexa:cluster:broadcast`.
- All cluster nodes listen to this channel via `StartClusterSubscriber()`.
- The specific node hosting the recipient's active WebSocket delivers the packet locally, achieving seamless horizontal scaling without sticky load-balancer sessions.

---

### 5. Authentication, Identity & Onboarding Subsystem (`internal/auth/`)

The backend provides four independent phone verification pipelines and one identity verification engine:

```
                                  AUTH SUBSYSTEM PIPELINES
  +----------------------------------------------------------------------------------------+
  | 1. Truecaller SDK Verification    -> One-tap server-side token validation via Truecaller |
  | 2. Firebase Phone OTP             -> Server-side Firebase Admin ID token verification     |
  | 3. MSG91 Cellular SMS OTP         -> 6-digit OTP delivery with HMAC-SHA256 peppered state  |
  | 4. Zero-Permission Reverse SMS    -> Inbound carrier webhook matching client challenge    |
  | 5. Google Sign-In (OAuth 2.0)     -> Cryptographic Google ID Token signature verification |
  +----------------------------------------------------------------------------------------+
```

1. **Deterministic Phone Number Protection (`identity/phonecrypto.go`):**
   - Phone numbers are never stored in plaintext in the database.
   - `ComputePhoneHash(phone)`: Computes an HMAC-SHA256 hash using `PHONE_HMAC_KEY`. This deterministic hash enables $O(1)$ indexing and contact discovery without exposing raw numbers.
   - `EncryptPhoneNumber(phone)`: Encrypts the raw phone number using AES-256-GCM with a random 12-byte IV using `PHONE_ENC_KEY`.
2. **Zero-Permission Reverse SMS Flow (`auth/reverse_sms.go`):**
   - Solves the problem of requiring intrusive SMS read permissions on Android.
   - The Android client requests a verification token via `/api/v1/auth/initiate-reverse-sms`.
   - The server generates a unique challenge code (e.g., `NX-VERIFY-8X92K`) and returns a carrier inbound gateway number.
   - The client launches the default SMS app with the pre-filled challenge code via standard Android intent.
   - The carrier webhook (`/api/v1/webhooks/sms-inbound`) receives the SMS, matches the sender phone number to the challenge code, and marks the phone number as verified.

---

### 6. Prekey & Cryptographic Claim Directory (`internal/prekey/`)

Implements the server-side directory for Signal-style **X3DH (Extended Triple Diffie-Hellman)** key agreements:
1. **Signed Prekey Storage:** Users publish their Ed25519-signed X25519 Signed Prekey (`SPK`) and digital signature via `/api/v1/users/prekeys/signed`.
2. **One-Time Prekeys (OPKs) Pool:** Users upload a batch of 100 ephemeral X25519 One-Time Prekeys via `/api/v1/users/prekeys/one-time`.
3. **Atomic Claim Protocol (`/api/v1/prekeys/claim`):**
   - When User A initiates a new chat with User B, User A requests User B's prekey bundle.
   - The server retrieves User B's Identity Key (`IK`), Signed Prekey (`SPK`), and atomically pops *one* One-Time Prekey (`OPK`) from PostgreSQL using a transaction with `SELECT ... FOR UPDATE SKIP LOCKED`.
   - Once claimed, the OPK is deleted from the server to guarantee it is never reused across sessions.

---

### 7. Ephemeral Media Relay & WebRTC Signaling (`internal/signaling/` & `cmd/server/main.go`)

1. **WebRTC P2P Signaling Hub (`internal/signaling/hub.go`):**
   - Endpoint: `/ws/signaling` (requires valid Bearer session token).
   - Relays SDP Offers, SDP Answers, and ICE Candidates between peers establishing direct P2P DataChannels.
   - Includes a **Circuit Breaker** and **Push Debouncer** to prevent signaling denial-of-service (DoS) attacks.
2. **Ephemeral Media Cloud Relay (`/api/v1/media/upload-ticket`):**
   - For environments where direct P2P connections cannot traverse strict Symmetric NATs.
   - Generates pre-signed upload URLs to Cloudflare R2 object storage.
   - Enforces a strict 100 MB maximum size ceiling.
   - **7-Day Auto-Purge Policy:** All uploaded blobs carry a 7-day TTL lifecycle rule.
   - **Immediate ACK Purge:** When the recipient receives the file, it dispatches `/api/v1/media/purge-ack`, prompting the server to delete the object immediately ($₹0$ permanent storage cost).
