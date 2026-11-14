# NEXA — Prioritized Implementation Roadmap

**Audit Date:** November 4, 2026  
**Audited Location:** `H:\chat_app`  
**Objective:** Actionable, prioritized engineering plan to transition NEXA from its current Functional Prototype status to a Production-Grade Release.

---

## Phase 1: Critical Protocol & Messaging Fixes (Immediate Priority)

### 1.1 Wire `DoubleRatchetEngine` into `ChatRepositoryImpl` [P0 - Blocker]
- **Issue:** `ChatRepositoryImpl.sendMessage()` currently encrypts outgoing text payloads using the local device's `keystoreManager.encryptWithMasterKey()` instead of calling `doubleRatchetEngine.ratchetEncrypt()`.
- **Action Items:**
  - In `ChatRepositoryImpl.sendMessage()`, retrieve active bilateral ratchet session via `doubleRatchetEngine.getOrCreateSession(recipientNxId)`.
  - Encrypt plaintext using `doubleRatchetEngine.ratchetEncrypt(recipientNxId, rawPlainBytes)`.
  - Pack resulting ciphertext, DH ephemeral public key, sequence number, and Poly1305 MAC tag into `EncryptedMessagePayload`.
  - In `handleIncomingPacket()` / `handleIncomingDataSmsPacket()`, invoke `doubleRatchetEngine.ratchetDecrypt()`.
- **Target Files:** `app/src/main/java/com/nexa/app/data/repository/ChatRepositoryImpl.kt`.

### 1.2 Enforce Cryptographic Challenge on WebSocket Handshake [P0 - Security]
- **Issue:** Go relay server currently accepts arbitrary `?nx_id={nx_id}` query params without verifying ownership.
- **Action Items:**
  - In `backend/cmd/server/main.go`, generate a random 32-byte nonce challenge during WebSocket upgrade.
  - Require Android client to sign the challenge using `Ed25519Signer` and return the signature in a handshake frame.
  - Reject WebSocket connection if signature fails verification against the user's public identity key.
- **Target Files:** `backend/internal/connection/hub.go`, `app/src/main/java/com/nexa/app/core/network/WebSocketManager.kt`.

---

## Phase 2: Production Authentication & Identity Hardening (High Priority)

### 2.1 Server-Side Ed25519 Key-Derived NX-ID Allocation [P1]
- **Issue:** NX-IDs are currently generated on client side via pseudo-random number generation (`SecureRandom`), risking collisions.
- **Action Items:**
  - Derive NX-IDs deterministically on the server from `Base32(SHA256(publicKey)[:5])` with prefix `NX-`.
  - Implement a central unique constraint in `backend/internal/handshake/memory_store.go`.
- **Target Files:** `backend/cmd/handshake/main.go`, `app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt`.

### 2.2 Production SMS Gateway & Real OTP Verification [P1]
- **Issue:** `AuthApiServiceImpl` is an in-memory mock that accepts any 6-digit numeric OTP.
- **Action Items:**
  - Integrate an external SMS Gateway (Twilio, Msg91, or Firebase Phone Auth) into `backend/cmd/handshake`.
  - Create secure endpoints: `POST /api/v1/auth/request-otp` and `POST /api/v1/auth/verify-otp`.
  - Replace `AuthApiServiceImpl` with a production Ktor/Retrofit API service.
- **Target Files:** `app/src/main/java/com/nexa/app/data/remote/api/AuthApiService.kt`.

### 2.3 Firebase Cloud Messaging (FCM) Integration [P1]
- **Issue:** FCM wake-up pings are currently stubs in the Go relay; no FCM SDK in Android client.
- **Action Items:**
  - Add `google-services.json` and `com.google.firebase:firebase-messaging` to Android app.
  - Implement `NexaFirebaseMessagingService` with background wake-up intent filters.
  - In `backend/internal/connection/hub.go`, implement live FCM HTTP v1 push notifications using service account credentials.
- **Target Files:** `app/src/main/AndroidManifest.xml`, `backend/internal/connection/hub.go`.

---

## Phase 3: Database & Multi-Device Resilience (Medium Priority)

### 3.1 Explicit Room Migrations [P2]
- **Issue:** `NexaDatabase` is at version 10 with `exportSchema = false` and no explicit `Migration` steps.
- **Action Items:**
  - Set `exportSchema = true` in `app/build.gradle.kts`.
  - Define explicit `Migration(1, 2)` through `Migration(9, 10)` classes handling table additions (`TransferSessionEntity`, `MeshEnvelopeEntity`, `QuarantineEntity`).
- **Target Files:** `app/src/main/java/com/nexa/app/data/local/NexaDatabase.kt`.

### 3.2 Dynamic Bandwidth Rate Limiter for Large WebRTC Transfers [P2]
- **Issue:** Need fine-grained user bandwidth settings (e.g., limit transfer speed to 2 MB/s on cellular data).
- **Action Items:**
  - Connect `MediaPrefs` transfer rate limiter into `ResilientDataStreamer.kt`.
- **Target Files:** `app/src/main/java/com/nexa/app/core/webrtc/ResilientDataStreamer.kt`.

---

## Phase 4: UI Polishing & Production Deployment (Refinement)

### 4.1 Real-Time Typing Indicators [P3]
- **Action Items:**
  - Add `PACKET_TYPING` to `proto/nexa_wire.proto`.
  - Emit typing events over WebSocket when user inputs text in `ChatScreen.kt`.
- **Target Files:** `proto/nexa_wire.proto`, `app/src/main/java/com/nexa/app/presentation/chat/ChatViewModel.kt`.

### 4.2 Production Docker & Reverse Proxy Deployment [P3]
- **Action Items:**
  - Configure Nginx or Caddy reverse proxy with Let's Encrypt automated TLS certificates for ports 8080–8086.
  - Deploy Go relay containers on a hardened Linux VPS.
- **Target Files:** `backend/docker-compose.yml`.
