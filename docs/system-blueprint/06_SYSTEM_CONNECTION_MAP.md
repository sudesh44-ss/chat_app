# NEXA — Master System Architecture Blueprint
## 06. System Connection Map & Network Interconnections

---

### 1. Connection Topology & Architecture Overview

The NEXA ecosystem operates across a heterogeneous network matrix consisting of:
1. **Client-to-Relay Network:** Encrypted WebSocket and REST pipelines connecting Android devices to the Go backend.
2. **Peer-to-Peer Mesh & Streaming Network:** Direct device-to-device DataChannels, BLE L2CAP sockets, and Wi-Fi Aware links.
3. **Internal Backend Infrastructure Network:** High-speed localhost / Docker internal network connecting Go microservices, PostgreSQL, and Redis.
4. **External Cloud & Carrier Integrations:** Google Drive, Firebase Cloud Messaging, SMS gateways, and cellular networks.

```
+---------------------------------------------------------------------------------------------------+
|                                 NEXA MASTER NETWORK CONNECTION TOPOLOGY                           |
|                                                                                                   |
|  [ Android Client ]                                                                               |
|         |                                                                                         |
|         +--- (TLS 1.3 / WSS / Port 8080) -------> [ Go Relay: /ws ]                               |
|         +--- (TLS 1.3 / WSS / Port 8083) -------> [ Go Signaling: /ws/signaling ]                 |
|         +--- (TLS 1.3 / HTTPS / Port 8080) -----> [ Go REST APIs: /api/v1/... ]                   |
|         +--- (Cellular Telephony / Port 8901) --> [ Cellular SMS Network ]                        |
|         +--- (BLE L2CAP / Wi-Fi Aware) ---------> [ Peer Android Client (DTN Mesh) ]              |
|         +--- (WebRTC SCTP/DTLS / UDP) ----------> [ Peer Android Client (Direct P2P) ]            |
|         +--- (HTTPS REST / TLS 1.3) ------------> [ Google Drive API: appDataFolder ]             |
|         +--- (HTTPS REST / TLS 1.3) ------------> [ Cloudflare R2: S3 Media Mailbox ]             |
|                                                                                                   |
|  [ Go Relay Backend ]                                                                             |
|         |                                                                                         |
|         +--- (TCP / Port 5432) -----------------> [ PostgreSQL Database (pgxpool) ]               |
|         +--- (TCP / Port 6379) -----------------> [ Redis Ephemeral RAM Buffer (go-redis) ]       |
|         +--- (Redis Pub/Sub / Port 6379) -------> [ Relay Cluster Peer Nodes ]                    |
|         +--- (HTTPS / TLS 1.3) -----------------> [ Firebase Cloud Messaging (FCM HTTP v1) ]     |
|         +--- (HTTPS / TLS 1.3) -----------------> [ MSG91 / Twilio SMS Gateways ]                 |
+---------------------------------------------------------------------------------------------------+
```

---

### 2. Comprehensive System Connection Matrix

| Source Component | Target Component | Transport & Protocol | Port / Path | Serialization / Payload Format | Authentication & Security | Sync / Async | Lifecycle & Reconnect Policy |
|---|---|---|---|---|---|---|---|
| **Android Client** (`WebSocketManager`) | **Go Relay** (`Hub`) | TCP / WSS (WebSocket over TLS 1.3) | `8080` / `/ws` | Protocol Buffers v3 (`NexaWirePacket`) | Mutual auth via Ed25519 signature over server 32-byte challenge nonce (60s TTL) | Async Full-Duplex | Keepalive ping every 30s. Auto-reconnect with exponential backoff (1s to 30s). |
| **Android Client** (`SignalingClient`) | **Go Signaling** (`SignalingHub`)| TCP / WSS (WebSocket over TLS 1.3) | `8083` (or `8080`) / `/ws/signaling` | JSON (`action`, `sdp`, `candidate`) | Bearer session token verified against `public.device_sessions` table | Async Full-Duplex | Active during file transfer negotiations; disconnects upon session completion. |
| **Android Client** (`AuthApiService`) | **Go Backend** (`AuthHandler`) | TCP / HTTPS (REST) | `8080` / `/api/v1/auth/*` | JSON (request / response bodies) | Google ID token / Firebase token / SMS verification challenge | Sync Request/Response | Standard HTTP/2 connection pooling via Square OkHttp. |
| **Android Client** (`ConnectionsRepo`) | **Go Backend** (`ConnHandler`) | TCP / HTTPS (REST) | `8080` / `/api/v1/connections/*` | JSON (NX-IDs, request statuses) | Bearer session token header (`Authorization: Bearer <token>`) | Sync Request/Response | Standard HTTP/2 connection pooling; idempotent retry logic. |
| **Android Client** (`PrekeyManager`) | **Go Backend** (`PrekeyHandler`) | TCP / HTTPS (REST) | `8080` / `/api/v1/prekeys/*` | JSON (Base64 keys, Ed25519 signatures) | Bearer session token header; atomic OPK claim with `SKIP LOCKED` | Sync Request/Response | Fetched on first conversation initiation; cached locally in Room DB. |
| **Android Client** (`WebRtcClient`) | **Peer Client** (`WebRtcClient`) | UDP / SCTP over DTLS (WebRTC) | Dynamic UDP (STUN/TURN negotiated) | Binary (64 KB encrypted chunks) | End-to-end encrypted via DTLS 1.2 + ChaCha20-Poly1305 per chunk | Async Streaming | Bounded by token-bucket rate limiter and thermal throttling monitor. |
| **Android Client** (`NexaDataSmsSender`)| **Peer Client** (Telephony) | Cellular Radio / Binary SMS | Port `8901` (Telephony SMS) | Compact Binary Wire Frame | Double Ratchet ChaCha20-Poly1305 E2EE payload | Async Store-and-Forward | Cellular carrier delivery; multi-part PDU reassembly via Android SMS stack. |
| **Android Client** (`SmsFallbackRepo`) | **Peer Client** (Default SMS App)| Cellular GSM SMS (Text) | Carrier SMS Network | Base91 Encoded Text (`NX1...`) | Symmetric 256-bit AES session key derived out-of-band + Keystore | Async Multi-Part | Fragmented into 133-char segments; reassembled upon receipt of all parts. |
| **Android Client** (`BleTransport`) | **Peer Client** (`BleTransport`)| Bluetooth Low Energy (BLE) | Dynamic L2CAP CoC Channel | Protobuf Binary / Authenticated DTN | Ed25519 signed `DtnEnvelope` + blind pairwise routing tokens | Async Hop-by-Hop | Opportunistic discovery via BLE scanning; capped at 64 KB bundles. |
| **Android Client** (`WifiAware`) | **Peer Client** (`WifiAware`) | Wi-Fi Aware (NAN) | Dynamic Wi-Fi Aware Port | Direct TCP Stream / Chunks | Mutual Ed25519 identity handshake before socket transfer | Async High-Speed | Active during physical proximity; automatically tears down when out of range. |
| **Android Client** (`DriveService`) | **Google Drive Cloud** | TCP / HTTPS (REST) | `443` / `appDataFolder` | Binary Stream (`.vault` bundle) | OAuth 2.0 User Token (`drive.appdata` scope) + BIP-39 AES-GCM envelope | Async Resumable Chunked | 2 MB chunked upload with automatic byte-offset resume on network drop. |
| **Go Backend** (`database.Pool`) | **PostgreSQL DB** | TCP / Native PostgreSQL Wire Protocol | `5432` | Binary PostgreSQL Wire Format | Password / TLS certificate; connection pooled via `pgxpool` (max 50 conns) | Sync Connection Pool | Persistent pool with health checks; re-establishes dropped connections automatically. |
| **Go Backend** (`queue.RedisBuffer`) | **Redis Service** | TCP / RESP Protocol | `6379` | Binary Redis Protocol | Password authentication; connection pooled via `go-redis` | Sync / Pipeline | Automatic retry with in-memory RAM fallback if Redis crashes. |
| **Go Relay Node A** (`Hub`) | **Go Relay Node B** (`Hub`) | TCP / Redis Pub/Sub | `6379` / `nexa:cluster:broadcast`| Serialized Protobuf bytes | Internal cluster trust (secured within private VPC / container network) | Async Broadcast | Standing subscriber goroutine listening on pub/sub channel. |
| **Go Backend** (`fcm.FcmService`) | **Firebase Cloud Messaging** | TCP / HTTPS (REST) | `443` / `fcm.googleapis.com` | JSON (FCM HTTP v1 payload) | Google Service Account OAuth 2.0 JWT bearer token | Async Background | Dispatches data-only wake-up pings when recipient is offline in Redis. |
| **Android Client** (`R2Service`) | **Cloudflare R2** | TCP / HTTPS (S3 API) | `443` / Pre-signed Object URLs | Binary Stream (Encrypted Media Blob)| Pre-signed URL ticket with 15-minute expiration; AES-256 encrypted payload | Async Upload / Download | 7-day auto-purge rule; immediate deletion via `/api/v1/media/purge-ack`. |

---

### 3. Port Allocation & Network Service Map

```
+---------------------------------------------------------------------------------------------------+
| PORT NUMBER | SERVICE NAME          | PROTOCOL        | ACCESSIBILITY       | ROLE                |
|-------------+-----------------------+-----------------+---------------------+---------------------|
| 8080        | nexa-relay (Core)     | HTTP/1.1 & WSS  | Public Internet     | WebSocket / REST    |
| 8081        | nexa-swarm            | HTTP / REST     | Public / Semi-Priv  | Swarm Seeding       |
| 8082        | nexa-blind-relay      | HTTP / REST     | Public Internet     | DTN Cloud Dump      |
| 8083        | nexa-signaling        | HTTP/1.1 & WSS  | Public Internet     | WebRTC Signaling    |
| 8084        | nexa-handshake        | HTTP / REST     | Public Internet     | Prekey & PoW Auth   |
| 8085        | nexa-qr-sync          | HTTP / REST     | Public Internet     | Day-0 QR Sync       |
| 8086        | nexa-security-rules   | HTTP / REST     | Public Internet     | OTA YARA Rules      |
| 5432        | PostgreSQL 15+        | PostgreSQL Wire | Internal VPC Only   | Metadata DB         |
| 6379        | Redis 7.x             | RESP            | Internal VPC Only   | Ephemeral RAM Queue |
| 8901        | Nexa Data SMS         | Cellular Radio  | Carrier Network     | Port-bound SMS      |
+---------------------------------------------------------------------------------------------------+
```

---

### 4. Heartbeat, Timeout & Reconnection Rules

1. **WebSocket Keepalive Ping/Pong:**
   - Client sends `PACKET_PING` every 30 seconds.
   - Server must respond with `PACKET_PING` (pong) within 10 seconds.
   - If two consecutive pings fail, the client marks the transport `DISCONNECTED` and initiates reconnection.
2. **Exponential Backoff Reconnect:**
   - Attempt 1: Immediate (0 ms delay).
   - Attempt 2: 1,000 ms.
   - Attempt 3: 2,000 ms.
   - Attempt 4: 4,000 ms.
   - Maximum cap: 30,000 ms with $\pm 20\%$ jitter to prevent thundering herd spikes on the relay server.
3. **Session Authentication Timeout:**
   - After WebSocket upgrade, the client must complete the Ed25519 challenge-response within 60 seconds.
   - Unauthenticated sessions exceeding 60 seconds are forcibly killed by `ClientSession.WritePump()`.
