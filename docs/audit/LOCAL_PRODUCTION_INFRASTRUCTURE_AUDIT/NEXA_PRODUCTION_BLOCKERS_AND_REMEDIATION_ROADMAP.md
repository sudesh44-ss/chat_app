# NEXA — Production Blockers & Remediation Roadmap

**Audit Mode:** STRICT READ-ONLY INSPECTION  
**Target:** Android 15 Client & Render Go Backend  
**Date:** November 2026  

---

## 1. Prioritized Blocker Classification

### Priority P0: Critical Blockers (Hard Stop on Onboarding & State Persistence)

#### Blocker P0-1: Inbound Reverse SMS Telecom Receiver & Webhook Not Connected
- **Evidence:** `backend/internal/auth/reverse_sms.go:142`, `backend/internal/auth/webhook_auth.go:32`.
- **Root Cause:**
  1. `INBOUND_SMS_RECEIVER_NUMBER` is missing on Render, causing backend to issue dummy placeholder `+919800000000`.
  2. Carrier inbound SMS gateway is not configured to forward messages to `https://nexa-relay.onrender.com/api/v1/auth/inbound-sms`.
  3. `GATEWAY_WEBHOOK_SECRET` is missing in Render, causing `VerifyGatewaySignature` to fail closed (401).
- **User Impact:** Phone number verification times out after 90 seconds. The user can NEVER complete onboarding.
- **Remediation Plan:**
  1. Provision a real telecom Virtual Mobile Number (VMN) or GSM modem with Twilio, MSG91, or custom gateway.
  2. In Render, set `INBOUND_SMS_RECEIVER_NUMBER` to that real number.
  3. Generate a 64-char hex secret and set `GATEWAY_WEBHOOK_SECRET` in Render.
  4. Configure the carrier's webhook dispatch to send `POST https://nexa-relay.onrender.com/api/v1/auth/inbound-sms` with `X-Gateway-Signature: sha256=<hmac>` header.

#### Blocker P0-2: Ephemeral Disk Account Deletion on Render Restart
- **Evidence:** `backend/internal/identity/store.go:114`, `backend/Dockerfile:24`.
- **Root Cause:** All accounts, devices, and sessions are written to `/root/data/identity_store.json` on Render's ephemeral filesystem. Every code deployment, container restart, or free sleep cycle wipes this file.
- **User Impact:** All users are logged out and their server identity is deleted on every restart.
- **Remediation Plan:**
  1. Attach a Render Persistent Disk (e.g., 1GB mounted at `/var/data`).
  2. Set environment variable `IDENTITY_STORE_PATH=/var/data/identity_store.json`.
  3. *(Long-term)*: Implement real PostgreSQL database connectivity in the Go backend.

#### Blocker P0-3: Redis Connection Failure & Queue Fallback to RAM
- **Evidence:** `backend/cmd/server/main.go:41`, `backend/internal/queue/redis_buffer.go:54`.
- **Root Cause:** The Go server only checks `REDIS_ADDR` (defaulting to `localhost:6379`) and ignores `REDIS_URL`. Inside Render container, `localhost:6379` is unreachable.
- **User Impact:** Offline messages and authentication challenges are stored in volatile RAM. All offline messages are permanently lost on server restart.
- **Remediation Plan:**
  1. Update `backend/cmd/server/main.go` and `internal/queue/redis_buffer.go` to inspect `REDIS_URL` and use `redis.ParseURL(redisURL)` with TLS (`rediss://`).
  2. Set `REDIS_URL` in Render.

#### Blocker P0-4: WebSocket Ed25519 Public Key Mismatch
- **Evidence:** `CryptoKeystoreManager.kt:128`, `NexaApplication.kt:244`, `backend/internal/connection/client.go:173`.
- **Root Cause:** `CryptoKeystoreManager` generates an identity public key via SHA-256 clamping during onboarding, while `WebSocketManager` signs authentication challenges using an Ed25519 key from `nexa_handshake_keys`. The backend compares them and rejects the WebSocket connection.
- **User Impact:** The app displays **"Relay Disconnected"** on the Chat List screen. Real-time message exchange fails.
- **Remediation Plan:**
  1. Unify the identity key derivation in Android to use standard Ed25519 point derivation across both onboarding registration and WebSocket challenge signing.

---

### Priority P1: Core Functional Gaps

#### Blocker P1-1: FCM OAuth2 Bearer Token Exchange Missing
- **Evidence:** `backend/internal/fcm/fcm_service.go:82, 111`.
- **Root Cause:** `s.accessToken` is never populated. No Google Service Account OAuth2 exchange is implemented.
- **User Impact:** Background push wake-up notifications cannot reach sleeping Android devices.
- **Remediation Plan:**
  1. Integrate `google.FindDefaultCredentials` or parse `FIREBASE_SERVICE_ACCOUNT_JSON` to generate bearer tokens with `https://www.googleapis.com/auth/firebase.messaging` scope.
  2. Set `FCM_PROJECT_ID` and `FIREBASE_SERVICE_ACCOUNT_JSON` on Render.

---

### Priority P2: Production Hardening & Reliability

1. **PostgreSQL Database Implementation:**
   - Replace JSON file store with real PostgreSQL database tables using `database/sql` or `pgx` to execute `database/migrations/`.
2. **Deep Health Check:**
   - Update `/health` endpoint to actively ping Redis and the database before reporting `UP`.

---

## 2. The Single Most Important Blocker & Next Implementation Phase

### The Single Most Important Blocker
**Blocker P0-1 (Inbound Reverse SMS Telecom Loop Not Connected):**
The Android user cannot reach the Chat List or complete onboarding because the application sends a verification beacon to dummy number `+919800000000` with no carrier gateway or webhook secret configured.

### The Exact Next Implementation Phase
**Phase 1: Gateway Configuration & Ephemeral State Remediation**
1. Configure `INBOUND_SMS_RECEIVER_NUMBER` and `GATEWAY_WEBHOOK_SECRET` in Render.
2. Update `redis_buffer.go` to parse `REDIS_URL` with TLS support.
3. Attach Render Persistent Disk at `/var/data/identity_store.json`.
4. Fix public key derivation in `CryptoKeystoreManager.kt` so the registered key matches `WebSocketManager`'s Ed25519 key.
